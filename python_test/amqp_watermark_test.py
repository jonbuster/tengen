"""Verify RabbitMQ watermark behavior with absent, false, and true headers."""

import json
import os
import re
import time
import urllib.parse
import urllib.request
import uuid

import pika
from pika.exceptions import NackError, UnroutableError

from amqp_load_test import env_float, env_int, login, read_accepted_count


WATERMARK_METRIC = re.compile(
    r'^tengen_rabbitmq_watermark_total\{(?P<labels>[^}]*)\}\s+'
    r'(?P<value>[-+0-9.eE]+)',
    re.MULTILINE,
)


def read_watermark_counts(http_base_url: str, access_token: str) -> dict[str, int]:
    request = urllib.request.Request(
        f"{http_base_url}/actuator/prometheus",
        headers={
            "Accept": "text/plain",
            "Authorization": f"Bearer {access_token}",
        },
    )
    with urllib.request.urlopen(request, timeout=5) as response:
        metrics = response.read().decode("utf-8")

    counts = {"applied": 0, "skipped": 0}
    for match in WATERMARK_METRIC.finditer(metrics):
        labels = match.group("labels")
        result = re.search(r'result="(applied|skipped)"', labels)
        if result:
            counts[result.group(1)] = int(float(match.group("value")))
    return counts


def read_json(url: str, access_token: str) -> dict:
    request = urllib.request.Request(
        url,
        headers={"Accept": "application/json", "Authorization": f"Bearer {access_token}"},
    )
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.loads(response.read().decode("utf-8"))


def matching_event_details(
    http_base_url: str,
    access_token: str,
    event_type: str,
    source: str,
    run_id: str,
) -> list[dict]:
    query = urllib.parse.urlencode({
        "type": event_type,
        "source": source,
        "size": 100,
    })
    page = read_json(f"{http_base_url}/api/event-history?{query}", access_token)
    matches = []
    for summary in page.get("content", []):
        event_id = summary.get("id")
        if event_id is None:
            continue
        detail = read_json(f"{http_base_url}/api/event-history/{event_id}", access_token)
        data = detail.get("data") or {}
        if data.get("testRun") == run_id:
            matches.append(detail)
    return matches


def wait_for_metrics(
    http_base_url: str,
    access_token: str,
    accepted_before: int,
    watermark_before: dict[str, int],
    timeout_seconds: float,
) -> dict[str, int]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        accepted = read_accepted_count(http_base_url, access_token)
        watermark = read_watermark_counts(http_base_url, access_token)
        if (
            accepted >= accepted_before + 3
            and watermark["applied"] >= watermark_before["applied"] + 2
            and watermark["skipped"] >= watermark_before["skipped"] + 1
        ):
            return watermark
        time.sleep(0.5)
    raise RuntimeError(
        "Timed out waiting for RabbitMQ metrics: "
        f"accepted_before={accepted_before}, watermark_before={watermark_before}"
    )


def wait_for_history(
    http_base_url: str,
    access_token: str,
    event_type: str,
    source: str,
    run_id: str,
    timeout_seconds: float,
) -> list[dict]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        matches = matching_event_details(http_base_url, access_token, event_type, source, run_id)
        if len(matches) >= 3:
            return matches
        time.sleep(0.5)
    raise RuntimeError(f"Timed out waiting for Event Explorer records for testRun={run_id}")


def main() -> None:
    http_base_url = os.getenv("TENGEN_HTTP_BASE_URL", "http://localhost:8080").rstrip("/")
    access_token = login(
        http_base_url,
        os.getenv("ADMIN_USER", "admin"),
        os.getenv("ADMIN_PASSWORD", "admin"),
    )

    rabbit_user = os.getenv("RABBITMQ_USER", "tengen")
    rabbit_password = os.getenv("RABBITMQ_PASSWORD", "tengen")
    event_type = os.getenv("RABBITMQ_TEST_TYPE", "payment")
    source = os.getenv("RABBITMQ_TEST_SOURCE", "billing")
    run_id = uuid.uuid4().hex[:12]
    timeout_seconds = env_float("WATERMARK_TEST_TIMEOUT_SECONDS", 30)
    port = env_int("RABBITMQ_PORT", 5672)

    accepted_before = read_accepted_count(http_base_url, access_token)
    watermark_before = read_watermark_counts(http_base_url, access_token)
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(
            host=os.getenv("RABBITMQ_HOST", "localhost"),
            port=port,
            virtual_host=os.getenv("RABBITMQ_VHOST", "/"),
            credentials=pika.PlainCredentials(rabbit_user, rabbit_password),
            heartbeat=30,
            blocked_connection_timeout=30,
        )
    )
    channel = connection.channel()
    channel.confirm_delivery()

    exchange = os.getenv("RABBITMQ_EXCHANGE", "tengen.input")
    routing_key = os.getenv("RABBITMQ_ROUTING_KEY", "events")
    cases = (
        ("header-absent", None),
        ("header-false", {"x-tengen-watermark": False}),
        ("header-true", {"x-tengen-watermark": True}),
    )
    confirmed = 0
    try:
        for case, headers in cases:
            event = {
                "type": event_type,
                "source": source,
                "data": {
                    "testRun": run_id,
                    "testCase": case,
                    "amount": 2500,
                },
            }
            properties = pika.BasicProperties(
                content_type="application/json",
                delivery_mode=2,
                message_id=f"amqp-watermark-{run_id}-{case}",
                headers=headers,
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
                print(f"published={case} headers={headers or 'absent'}")
            except (NackError, UnroutableError) as error:
                raise RuntimeError(f"RabbitMQ did not confirm {case}") from error
    finally:
        connection.close()

    if confirmed != len(cases):
        raise RuntimeError(f"Expected {len(cases)} confirmed messages, got {confirmed}")

    watermark_after = wait_for_metrics(
        http_base_url,
        access_token,
        accepted_before,
        watermark_before,
        timeout_seconds,
    )
    details = wait_for_history(
        http_base_url,
        access_token,
        event_type,
        source,
        run_id,
        timeout_seconds,
    )
    results = {
        (detail.get("data") or {}).get("testCase"): (detail.get("event") or {}).get("watermarkApplied")
        for detail in details
        if detail.get("event")
    }
    expected = {"header-absent": True, "header-false": False, "header-true": True}
    if any(results.get(case) != value for case, value in expected.items()):
        raise RuntimeError(f"Unexpected watermark results: expected={expected}, actual={results}")

    print(f"verified_run={run_id} source={source}")
    print(f"watermark_metrics_before={watermark_before} after={watermark_after}")
    print(f"event_history_watermark_applied={results}")


if __name__ == "__main__":
    main()
