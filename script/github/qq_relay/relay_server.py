#!/usr/bin/env python3
"""Authenticated, fixed-destination HTTP relay for NapCat OneBot 11."""

from __future__ import annotations

import hmac
import json
import os
import re
import threading
import time
from collections import OrderedDict, deque
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Mapping
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin
from urllib.request import Request, urlopen


MAX_REQUEST_BYTES = 4096
DELIVERY_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,160}$")
CONTROL_CHARACTER_PATTERN = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
URL_PATTERN = re.compile(r"(?i)\b(?:https?://|www\.)\S+")


class RequestError(RuntimeError):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


class OneBotRejected(RuntimeError):
    """Raised when OneBot responds successfully but rejects the message."""


def _remove_urls(message: str) -> str:
    return URL_PATTERN.sub("[链接已省略]", message)


@dataclass(frozen=True)
class RelayConfig:
    relay_token: str
    onebot_token: str
    onebot_url: str
    repository: str
    group_id: int
    max_message_length: int = 900
    rate_limit: int = 30
    rate_window_seconds: int = 60

    @classmethod
    def from_environment(cls) -> "RelayConfig":
        relay_token = os.environ.get("RELAY_TOKEN", "")
        onebot_token = os.environ.get("ONEBOT_TOKEN", "")
        group_id = os.environ.get("QQ_GROUP_ID", "")
        if len(relay_token) < 32:
            raise RuntimeError("RELAY_TOKEN must contain at least 32 characters")
        if len(onebot_token) < 32:
            raise RuntimeError("ONEBOT_TOKEN must contain at least 32 characters")
        if not group_id.isdigit():
            raise RuntimeError("QQ_GROUP_ID must be a numeric QQ group number")
        return cls(
            relay_token=relay_token,
            onebot_token=onebot_token,
            onebot_url=os.environ.get("ONEBOT_URL", "http://napcat:3000"),
            repository=os.environ.get("RELAY_REPOSITORY", "OtterMind/Chat2DB"),
            group_id=int(group_id),
            max_message_length=int(os.environ.get("RELAY_MAX_MESSAGE_LENGTH", "900")),
            rate_limit=int(os.environ.get("RELAY_RATE_LIMIT", "30")),
        )


class RateLimiter:
    def __init__(self, limit: int, window_seconds: int):
        self.limit = limit
        self.window_seconds = window_seconds
        self._timestamps: deque[float] = deque()
        self._lock = threading.Lock()

    def acquire(self, now: float | None = None) -> bool:
        current = time.monotonic() if now is None else now
        with self._lock:
            cutoff = current - self.window_seconds
            while self._timestamps and self._timestamps[0] <= cutoff:
                self._timestamps.popleft()
            if len(self._timestamps) >= self.limit:
                return False
            self._timestamps.append(current)
            return True


class DeliveryStore:
    def __init__(self, ttl_seconds: int = 86400, max_entries: int = 5000):
        self.ttl_seconds = ttl_seconds
        self.max_entries = max_entries
        self._deliveries: OrderedDict[str, tuple[float, str]] = OrderedDict()
        self._lock = threading.Lock()

    def reserve(self, delivery_id: str, now: float | None = None) -> str | None:
        current = time.monotonic() if now is None else now
        with self._lock:
            cutoff = current - self.ttl_seconds
            while self._deliveries:
                _, (created_at, _) = next(iter(self._deliveries.items()))
                if created_at > cutoff:
                    break
                self._deliveries.popitem(last=False)
            existing = self._deliveries.get(delivery_id)
            if existing is not None:
                return existing[1]
            self._deliveries[delivery_id] = (current, "pending")
            while len(self._deliveries) > self.max_entries:
                self._deliveries.popitem(last=False)
            return None

    def complete(self, delivery_id: str, message_id: str) -> None:
        with self._lock:
            created_at, _ = self._deliveries[delivery_id]
            self._deliveries[delivery_id] = (created_at, message_id)

    def release(self, delivery_id: str) -> None:
        with self._lock:
            self._deliveries.pop(delivery_id, None)


class OneBotClient:
    def __init__(self, base_url: str, token: str, group_id: int):
        self.base_url = base_url.rstrip("/") + "/"
        self.token = token
        self.group_id = group_id

    def send_group_message(self, message: str) -> str:
        body = json.dumps(
            {"group_id": self.group_id, "message": message}, ensure_ascii=False
        ).encode("utf-8")
        request = Request(
            urljoin(self.base_url, "send_group_msg"),
            data=body,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urlopen(request, timeout=15) as response:
                response_body = response.read()
        except HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:300]
            raise RuntimeError(f"OneBot returned HTTP {error.code}: {detail}") from error
        except URLError as error:
            raise RuntimeError(f"OneBot is unavailable: {error.reason}") from error

        try:
            decoded = json.loads(response_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise RuntimeError("OneBot returned invalid JSON") from error
        if not isinstance(decoded, Mapping):
            raise RuntimeError("OneBot returned an invalid response")
        if decoded.get("retcode") != 0:
            raise OneBotRejected("OneBot rejected the group message")
        if not isinstance(decoded.get("data"), Mapping):
            raise RuntimeError("OneBot rejected the group message")
        data = decoded["data"]
        return str(data.get("message_id") or "unknown")


class RelayState:
    def __init__(self, config: RelayConfig):
        self.config = config
        self.rate_limiter = RateLimiter(config.rate_limit, config.rate_window_seconds)
        self.deliveries = DeliveryStore()
        self.onebot = OneBotClient(config.onebot_url, config.onebot_token, config.group_id)


class RelayHandler(BaseHTTPRequestHandler):
    relay_state: RelayState
    server_version = "Chat2DBQQRelay/1.0"

    def _send_json(self, status: int, payload: Mapping[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def _authorize(self) -> None:
        expected = f"Bearer {self.relay_state.config.relay_token}"
        supplied = self.headers.get("Authorization", "")
        if not hmac.compare_digest(supplied, expected):
            raise RequestError(HTTPStatus.UNAUTHORIZED, "unauthorized")

    def _read_payload(self) -> Mapping[str, Any]:
        content_type = self.headers.get("Content-Type", "")
        if not content_type.lower().startswith("application/json"):
            raise RequestError(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be JSON")
        try:
            content_length = int(self.headers.get("Content-Length", ""))
        except ValueError as error:
            raise RequestError(HTTPStatus.LENGTH_REQUIRED, "Content-Length is required") from error
        if content_length < 1 or content_length > MAX_REQUEST_BYTES:
            raise RequestError(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "request body is too large")
        try:
            payload = json.loads(self.rfile.read(content_length).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise RequestError(HTTPStatus.BAD_REQUEST, "request body is not valid JSON") from error
        if not isinstance(payload, Mapping):
            raise RequestError(HTTPStatus.BAD_REQUEST, "request body must be a JSON object")
        return payload

    def _validate_payload(self, payload: Mapping[str, Any]) -> tuple[str, str]:
        config = self.relay_state.config
        if payload.get("repository") != config.repository:
            raise RequestError(HTTPStatus.FORBIDDEN, "repository is not allowed")
        delivery_id = payload.get("delivery_id")
        if not isinstance(delivery_id, str) or not DELIVERY_ID_PATTERN.fullmatch(delivery_id):
            raise RequestError(HTTPStatus.BAD_REQUEST, "delivery_id is invalid")
        message = payload.get("message")
        if not isinstance(message, str) or not message.strip():
            raise RequestError(HTTPStatus.BAD_REQUEST, "message must be non-empty text")
        if len(message) > config.max_message_length:
            raise RequestError(HTTPStatus.BAD_REQUEST, "message is too long")
        if CONTROL_CHARACTER_PATTERN.search(message):
            raise RequestError(HTTPStatus.BAD_REQUEST, "message contains control characters")
        return delivery_id, message

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/healthz":
            self._send_json(HTTPStatus.OK, {"ok": True})
            return
        self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        delivery_id = ""
        reserved = False
        try:
            if self.path != "/v1/qq/github":
                raise RequestError(HTTPStatus.NOT_FOUND, "not found")
            self._authorize()
            delivery_id, message = self._validate_payload(self._read_payload())
            existing = self.relay_state.deliveries.reserve(delivery_id)
            if existing is not None:
                if existing == "pending":
                    raise RequestError(
                        HTTPStatus.SERVICE_UNAVAILABLE, "delivery is still in progress"
                    )
                self._send_json(
                    HTTPStatus.OK,
                    {
                        "ok": True,
                        "duplicate": True,
                        "message_id": existing,
                    },
                )
                return
            reserved = True
            if not self.relay_state.rate_limiter.acquire():
                raise RequestError(HTTPStatus.TOO_MANY_REQUESTS, "rate limit exceeded")
            url_removed = False
            try:
                message_id = self.relay_state.onebot.send_group_message(message)
            except OneBotRejected:
                fallback_message = _remove_urls(message)
                if fallback_message == message:
                    raise
                message_id = self.relay_state.onebot.send_group_message(fallback_message)
                url_removed = True
            self.relay_state.deliveries.complete(delivery_id, message_id)
            reserved = False
            self._send_json(
                HTTPStatus.OK,
                {
                    "ok": True,
                    "duplicate": False,
                    "message_id": message_id,
                    "url_removed": url_removed,
                },
            )
        except RequestError as error:
            if reserved:
                self.relay_state.deliveries.release(delivery_id)
            self._send_json(error.status, {"error": error.message})
        except RuntimeError:
            if reserved:
                self.relay_state.deliveries.release(delivery_id)
            self._send_json(HTTPStatus.BAD_GATEWAY, {"error": "QQ delivery failed"})

    def log_message(self, format_string: str, *args: Any) -> None:
        super().log_message(format_string, *args)


def create_handler(state: RelayState) -> type[RelayHandler]:
    class ConfiguredRelayHandler(RelayHandler):
        relay_state = state

    return ConfiguredRelayHandler


def main() -> None:
    config = RelayConfig.from_environment()
    port = int(os.environ.get("RELAY_PORT", "8080"))
    server = ThreadingHTTPServer(("0.0.0.0", port), create_handler(RelayState(config)))
    server.serve_forever()


if __name__ == "__main__":
    main()
