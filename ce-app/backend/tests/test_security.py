"""The local API must not be drivable by an arbitrary website.

The backend listens on 127.0.0.1, but a malicious page opened in the user's
browser can still ask that browser to call it. CORS is the only fence, so it must
be an allowlist, not a wildcard with credentials.
"""
from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_a_third_party_origin_gets_no_cors_allowance():
    response = client.get("/api/system/info", headers={"Origin": "https://evil.example"})

    assert response.headers.get("access-control-allow-origin") is None, (
        "a foreign origin was granted access to the local API"
    )


def test_the_packaged_opaque_origin_is_allowed():
    response = client.get("/api/system/info", headers={"Origin": "null"})

    assert response.status_code == 200
    assert response.headers.get("access-control-allow-origin") == "null"


def test_the_dev_origin_is_allowed_without_credentials():
    response = client.get(
        "/api/system/info", headers={"Origin": "http://localhost:5173"}
    )

    assert response.status_code == 200
    assert response.headers.get("access-control-allow-origin") == "http://localhost:5173"
    assert response.headers.get("access-control-allow-credentials") is None


def test_the_media_endpoint_refuses_injected_paths():
    # A relative path or a null byte never comes from the file picker.
    assert client.get("/api/media/file", params={"path": "relative/file.mp4"}).status_code == 400
    assert client.get("/api/media/file", params={"path": "/a\0b.mp4"}).status_code == 400


def test_the_media_endpoint_still_404s_a_missing_absolute_file():
    assert client.get("/api/media/file", params={"path": "/nonexistent/x.mp4"}).status_code == 404
