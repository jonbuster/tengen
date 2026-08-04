"""Publish valid Tengen events over one persistent AMQP connection."""

import json
import os
import re
import threading
import time
import urllib.error
import urllib.request
import uuid

import pika
from pika.exceptions import NackError, UnroutableError


def env_int(name: str, default: int) -> int:
    value = int(os.getenv(name, str(default)))
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


def env_float(name: str, default: float) -> float:
    value = float(os.getenv(name, str(default)))
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


def watermark_headers() -> dict[str, bool] | None:
    raw = os.getenv("RABBITMQ_WATERMARK")
    if raw is None:
        return None
    normalized = raw.strip().lower()
    if normalized not in {"true", "false"}:
        raise ValueError("RABBITMQ_WATERMARK must be true or false when set")
    return {"x-tengen-watermark": normalized == "true"}


def http_json(url: str, payload: dict) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"HTTP {error.code} from {url}") from error


def login(http_base_url: str, username: str, password: str) -> str:
    response = http_json(
        f"{http_base_url}/api/auth/login",
        {"username": username, "password": password},
    )
    token = response.get("accessToken")
    if not token:
        raise RuntimeError("Login response did not contain an access token")
    return token


def read_accepted_count(http_base_url: str, access_token: str) -> int:
    request = urllib.request.Request(
        f"{http_base_url}/actuator/prometheus",
        headers={
            "Accept": "text/plain",
            "Authorization": f"Bearer {access_token}",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            metrics = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"HTTP {error.code} from {http_base_url}/actuator/prometheus") from error

    pattern = re.compile(
        r'^tengen_rabbitmq_messages_total\{(?P<labels>[^}]*)\}\s+'
        r'(?P<value>[-+0-9.eE]+)',
        re.MULTILINE,
    )
    for match in pattern.finditer(metrics):
        if 'result="accepted"' in match.group("labels"):
            return int(float(match.group("value")))
    raise RuntimeError("Accepted RabbitMQ metric was not found")


def monitor_accepted_events(
    http_base_url: str,
    access_token: str,
    interval: float,
    stop_event: threading.Event,
    samples: list[tuple[float, int]],
    errors: list[str],
) -> None:
    while not stop_event.is_set():
        try:
            samples.append((time.monotonic(), read_accepted_count(http_base_url, access_token)))
        except RuntimeError as error:
            errors.append(str(error))
        stop_event.wait(interval)


def main() -> None:
    rate = env_int("RATE", 200)
    duration = env_int("DURATION", 20)
    port = env_int("RABBITMQ_PORT", 5672)
    drain_timeout = env_float("DRAIN_TIMEOUT_SECONDS", 120)
    metrics_interval = env_float("METRICS_INTERVAL_SECONDS", 1)
    message_headers = watermark_headers()
    total = rate * duration
    run_id = uuid.uuid4().hex[:12]

    http_base_url = os.getenv("TENGEN_HTTP_BASE_URL", "http://localhost:8080").rstrip("/")
    access_token = login(
        http_base_url,
        os.getenv("ADMIN_USER", "admin"),
        os.getenv("ADMIN_PASSWORD", "admin"),
    )
    accepted_before = read_accepted_count(http_base_url, access_token)
    samples = [(time.monotonic(), accepted_before)]
    metric_errors: list[str] = []
    stop_monitor = threading.Event()
    monitor_thread = threading.Thread(
        target=monitor_accepted_events,
        args=(http_base_url, access_token, metrics_interval, stop_monitor, samples, metric_errors),
        daemon=True,
    )
    monitor_thread.start()

    credentials = pika.PlainCredentials(
        os.getenv("RABBITMQ_USER", "tengen"),
        os.getenv("RABBITMQ_PASSWORD", "tengen"),
    )
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

    exchange = os.getenv("RABBITMQ_EXCHANGE", "tengen.input")
    routing_key = os.getenv("RABBITMQ_ROUTING_KEY", "events")
    confirmed = 0
    failed = 0
    started = time.monotonic()
    next_publish_at = started

    try:
        for index in range(total):
            delay = next_publish_at - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            next_publish_at += 1 / rate

            event = {
                "type": "payment",
                "source": "billing",
                "data": {
                    "amount": 100 + index,
                    "orderId": f"{run_id}-{index}",
                },
            }
            properties = pika.BasicProperties(
                content_type="application/json",
                delivery_mode=2,
                message_id=f"amqp-load-{run_id}-{index}",
                headers=message_headers,
            )

            try:
                channel.basic_publish(
                    exchange=exchange,
                    routing_key=routing_key,
                    body=json.dumps(event, separators=(",", ":")).encode("utf-8"),
                    properties=properties,
                    mandatory=True,
                )
                confirmed += 1
            except (NackError, UnroutableError):
                failed += 1
    finally:
        connection.close()

    publishing_finished = time.monotonic()
    target_accepted = accepted_before + confirmed
    processing_finished = publishing_finished
    accepted_after = accepted_before
    drain_deadline = publishing_finished + drain_timeout
    while confirmed and time.monotonic() < drain_deadline:
        try:
            accepted_after = read_accepted_count(http_base_url, access_token)
        except RuntimeError as error:
            metric_errors.append(str(error))
        if accepted_after >= target_accepted:
            processing_finished = time.monotonic()
            break
        time.sleep(min(metrics_interval, 0.25))
    else:
        try:
            accepted_after = read_accepted_count(http_base_url, access_token)
        except RuntimeError as error:
            metric_errors.append(str(error))
        processing_finished = time.monotonic()

    stop_monitor.set()
    monitor_thread.join(timeout=metrics_interval + 1)

    publish_elapsed = publishing_finished - started
    end_to_end_elapsed = processing_finished - started
    accepted_delta = max(0, accepted_after - accepted_before)
    publisher_rate = confirmed / publish_elapsed if publish_elapsed else 0
    accepted_rate = accepted_delta / end_to_end_elapsed if end_to_end_elapsed else 0
    print(f"target={total} confirmed={confirmed} failed={failed}")
    print(f"publisher_elapsed={publish_elapsed:.2f}s publisher_rate={publisher_rate:.1f} messages/sec")
    print(
        f"tengen_accepted={accepted_delta} "
        f"end_to_end_elapsed={end_to_end_elapsed:.2f}s "
        f"processing_rate={accepted_rate:.1f} messages/sec"
    )
    if accepted_delta < confirmed:
        print(f"warning=processing_incomplete missing={confirmed - accepted_delta}")
    if metric_errors:
        print(f"metric_poll_errors={len(metric_errors)} last_error={metric_errors[-1]}")


if __name__ == "__main__":
    main()
