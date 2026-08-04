"""Run an incremental AMQP load test and stop on sustained queue backlog."""

import base64
import json
import os
import threading
import time
import urllib.parse
import urllib.request
import uuid

import pika
from pika.exceptions import NackError, UnroutableError

from amqp_load_test import env_float, env_int, login, read_accepted_count, watermark_headers


DEFAULT_RATES = (200, 400, 500, 600, 800, 1000, 2000, 3000, 4000, 5000)


def configured_rates() -> list[int]:
    raw = os.getenv("RATES", ",".join(str(rate) for rate in DEFAULT_RATES))
    rates = [int(value.strip()) for value in raw.split(",") if value.strip()]
    if not rates or any(rate <= 0 for rate in rates):
        raise ValueError("RATES must contain positive comma-separated integers")
    return rates


def configured_sources() -> list[str]:
    raw = os.getenv("RABBITMQ_SOURCES", "billing,orders,subscriptions,shipping")
    sources = [value.strip() for value in raw.split(",") if value.strip()]
    if not sources or any(len(source) > 100 for source in sources):
        raise ValueError("RABBITMQ_SOURCES must contain non-empty values no longer than 100 characters")
    return sources


def configured_event_types() -> list[str]:
    raw = os.getenv("RABBITMQ_EVENT_TYPES", "payment,refund,invoice,shipment")
    event_types = [value.strip() for value in raw.split(",") if value.strip()]
    if not event_types or any(len(event_type) > 100 for event_type in event_types):
        raise ValueError(
            "RABBITMQ_EVENT_TYPES must contain non-empty values no longer than 100 characters"
        )
    return event_types


def configured_watermark_headers() -> list[dict[str, bool] | None]:
    """Return the per-message header pattern used by the ramp test.

    With no overrides, messages alternate between the default behavior and an
    explicit watermark opt-out. RABBITMQ_WATERMARK remains available when a
    stage should use one setting for every message.
    """
    pattern = os.getenv("RABBITMQ_WATERMARK_PATTERN")
    if pattern is None and os.getenv("RABBITMQ_WATERMARK") is not None:
        single = watermark_headers()
        return [single]

    values = [
        value.strip().lower()
        for value in (pattern or "absent,false").split(",")
        if value.strip()
    ]
    if not values or any(value not in {"absent", "false", "true"} for value in values):
        raise ValueError(
            "RABBITMQ_WATERMARK_PATTERN must contain absent, false, or true "
            "as comma-separated values"
        )
    return [
        None if value == "absent" else {"x-tengen-watermark": value == "true"}
        for value in values
    ]


def watermark_header_label(headers: dict[str, bool] | None) -> str:
    if headers is None:
        return "absent"
    return "true" if headers["x-tengen-watermark"] else "false"


def read_ready_messages(management_url: str, username: str, password: str, queue: str) -> int:
    vhost = urllib.parse.quote(os.getenv("RABBITMQ_VHOST", "/"), safe="")
    queue_name = urllib.parse.quote(queue, safe="")
    credentials = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    request = urllib.request.Request(
        f"{management_url}/api/queues/{vhost}/{queue_name}",
        headers={"Authorization": f"Basic {credentials}"},
    )
    with urllib.request.urlopen(request, timeout=5) as response:
        data = json.loads(response.read().decode("utf-8"))
    return int(data.get("messages_ready", 0))


def monitor_stage(
    management_url: str,
    rabbit_user: str,
    rabbit_password: str,
    queue: str,
    http_base_url: str,
    access_token: str,
    poll_interval: float,
    backlog_threshold: int,
    sustained_seconds: float,
    stop_event: threading.Event,
    abort_event: threading.Event,
    samples: list[tuple[float, int, int]],
    reason: list[str],
) -> None:
    backlog_started: float | None = None
    while not stop_event.is_set():
        try:
            now = time.monotonic()
            ready = read_ready_messages(management_url, rabbit_user, rabbit_password, queue)
            accepted = read_accepted_count(http_base_url, access_token)
            samples.append((now, ready, accepted))
        except Exception as error:
            reason.append(f"monitor error: {error}")
            abort_event.set()
            return

        if ready >= backlog_threshold:
            if backlog_started is None:
                backlog_started = now
            elif now - backlog_started >= sustained_seconds:
                reason.append(
                    f"ready messages stayed at or above {backlog_threshold} "
                    f"for {sustained_seconds:.0f}s"
                )
                abort_event.set()
                return
        else:
            backlog_started = None

        stop_event.wait(poll_interval)


def publish_stage(
    channel: pika.adapters.blocking_connection.BlockingChannel,
    rate: int,
    duration: int,
    run_id: str,
    stage_number: int,
    event_types: list[str],
    sources: list[str],
    management_url: str,
    rabbit_user: str,
    rabbit_password: str,
    queue: str,
    http_base_url: str,
    access_token: str,
    watermark_header_options: list[dict[str, bool] | None],
    poll_interval: float,
    backlog_threshold: int,
    sustained_seconds: float,
    drain_timeout: float,
) -> dict[str, object]:
    target = rate * duration
    accepted_before = read_accepted_count(http_base_url, access_token)
    samples: list[tuple[float, int, int]] = []
    reason: list[str] = []
    stop_monitor = threading.Event()
    abort_stage = threading.Event()
    monitor_thread = threading.Thread(
        target=monitor_stage,
        args=(
            management_url,
            rabbit_user,
            rabbit_password,
            queue,
            http_base_url,
            access_token,
            poll_interval,
            backlog_threshold,
            sustained_seconds,
            stop_monitor,
            abort_stage,
            samples,
            reason,
        ),
        daemon=True,
    )
    monitor_thread.start()

    confirmed = 0
    failed = 0
    watermark_header_counts = {"absent": 0, "false": 0, "true": 0}
    started = time.monotonic()
    next_publish_at = started
    try:
        for index in range(target):
            if abort_stage.is_set():
                break
            delay = next_publish_at - time.monotonic()
            if delay > 0 and abort_stage.wait(delay):
                break
            next_publish_at += 1 / rate

            event_type = event_types[index % len(event_types)]
            source = sources[(index // len(event_types)) % len(sources)]
            event = {
                "type": event_type,
                "source": source,
                "data": {
                    "amount": 100 + index,
                    "orderId": f"{run_id}-stage-{stage_number}-{index}",
                },
            }
            message_headers = watermark_header_options[index % len(watermark_header_options)]
            properties = pika.BasicProperties(
                content_type="application/json",
                delivery_mode=2,
                message_id=f"amqp-ramp-{run_id}-{stage_number}-{index}",
                headers=message_headers,
            )
            try:
                channel.basic_publish(
                    exchange=os.getenv("RABBITMQ_EXCHANGE", "tengen.input"),
                    routing_key=os.getenv("RABBITMQ_ROUTING_KEY", "events"),
                    body=json.dumps(event, separators=(",", ":")).encode("utf-8"),
                    properties=properties,
                    mandatory=True,
                )
                confirmed += 1
                watermark_header_counts[watermark_header_label(message_headers)] += 1
            except (NackError, UnroutableError):
                failed += 1
    finally:
        publishing_finished = time.monotonic()
        stop_monitor.set()
        monitor_thread.join(timeout=poll_interval + 1)

    if abort_stage.is_set():
        accepted_after = read_accepted_count(http_base_url, access_token)
        elapsed = publishing_finished - started
        return {
            "status": "STOPPED",
            "rate": rate,
            "target": target,
            "confirmed": confirmed,
                "failed": failed,
                "accepted": max(0, accepted_after - accepted_before),
                "elapsed": elapsed,
                "watermark_headers": watermark_header_counts,
                "reason": reason[-1] if reason else "stage aborted",
            }

    drain_started = time.monotonic()
    accepted_after = accepted_before
    ready = 0
    drained = False
    while time.monotonic() - drain_started < drain_timeout:
        ready = read_ready_messages(management_url, rabbit_user, rabbit_password, queue)
        accepted_after = read_accepted_count(http_base_url, access_token)
        if ready == 0 and accepted_after >= accepted_before + confirmed:
            drained = True
            break
        time.sleep(poll_interval)

    completed_at = time.monotonic()
    elapsed = completed_at - started
    accepted = max(0, accepted_after - accepted_before)
    return {
        "status": "PASS" if drained else "STOPPED",
        "rate": rate,
        "target": target,
        "confirmed": confirmed,
        "failed": failed,
        "accepted": accepted,
        "elapsed": elapsed,
        "watermark_headers": watermark_header_counts,
        "reason": "stage did not drain" if not drained else "",
    }


def main() -> None:
    rates = configured_rates()
    event_types = configured_event_types()
    sources = configured_sources()
    watermark_header_options = configured_watermark_headers()
    stage_duration = env_int("STAGE_DURATION_SECONDS", 60)
    poll_interval = env_float("QUEUE_POLL_INTERVAL_SECONDS", 1)
    sustained_seconds = env_float("SUSTAINED_BACKLOG_SECONDS", 10)
    drain_timeout = env_float("DRAIN_TIMEOUT_SECONDS", 60)
    backlog_threshold = env_int("BACKLOG_THRESHOLD_MESSAGES", 500)

    http_base_url = os.getenv("TENGEN_HTTP_BASE_URL", "http://localhost:8080").rstrip("/")
    access_token = login(
        http_base_url,
        os.getenv("ADMIN_USER", "admin"),
        os.getenv("ADMIN_PASSWORD", "admin"),
    )
    rabbit_user = os.getenv("RABBITMQ_USER", "tengen")
    rabbit_password = os.getenv("RABBITMQ_PASSWORD", "tengen")
    management_url = os.getenv("RABBITMQ_MANAGEMENT_URL", "http://localhost:15672").rstrip("/")
    queue = os.getenv("RABBITMQ_QUEUE", "tengen.events")
    port = env_int("RABBITMQ_PORT", 5672)
    run_id = uuid.uuid4().hex[:12]

    credentials = pika.PlainCredentials(rabbit_user, rabbit_password)
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(
            host=os.getenv("RABBITMQ_HOST", "localhost"),
            port=port,
            virtual_host=os.getenv("RABBITMQ_VHOST", "/"),
            credentials=credentials,
            heartbeat=30,
            blocked_connection_timeout=30,
        )
    )
    channel = connection.channel()
    channel.confirm_delivery()

    print(
        f"rates={','.join(str(rate) for rate in rates)} "
        f"event_types={','.join(event_types)} "
        f"sources={','.join(sources)} "
        f"watermark_pattern={','.join(watermark_header_label(headers) for headers in watermark_header_options)} "
        f"stage_duration={stage_duration}s "
        f"backlog_threshold={backlog_threshold} "
        f"sustained_for={sustained_seconds:.0f}s"
    )

    results: list[dict[str, object]] = []
    try:
        for stage_number, rate in enumerate(rates, start=1):
            result = publish_stage(
                channel,
                rate,
                stage_duration,
                run_id,
                stage_number,
                event_types,
                sources,
                management_url,
                rabbit_user,
                rabbit_password,
                queue,
                http_base_url,
                access_token,
                watermark_header_options,
                poll_interval,
                backlog_threshold,
                sustained_seconds,
                drain_timeout,
            )
            results.append(result)
            print(
                f"stage={stage_number} rate={result['rate']}/s "
                f"status={result['status']} confirmed={result['confirmed']} "
                f"accepted={result['accepted']} elapsed={result['elapsed']:.1f}s "
                f"watermark_headers={result['watermark_headers']}"
            )
            if result["status"] != "PASS":
                print(f"stop_reason={result['reason']}")
                break
    finally:
        connection.close()

    print("summary:")
    for result in results:
        print(
            f"  {result['rate']}/s: {result['status']} "
            f"confirmed={result['confirmed']} accepted={result['accepted']} "
            f"watermark_headers={result['watermark_headers']}"
        )


if __name__ == "__main__":
    main()
