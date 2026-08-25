"""The credit screen's data must match what actually ships.

The 1.0 criterion is "every shipped package listed with its licence". This test
is the ratchet for the Python half: every pinned backend dependency must appear,
and none may be listed without a licence — an unlicensed row on a credit screen
is the same lie as a missing row.
"""
from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app
from core.engine import attribution

client = TestClient(app)


def test_every_installed_backend_dependency_is_listed_with_a_licence():
    """Every pinned dep that is present must be credited with a licence.

    The development venv is deliberately light, so a pin that is not installed
    here is skipped; in the packaged runtime every pin is installed, and there
    this test therefore covers all of them.
    """
    import importlib.util

    from core.engine import attribution

    names = {entry["name"] for entry in attribution.backend_attribution()}
    for requirement in attribution._requirements():
        module = attribution.MODULE_NAMES.get(requirement, requirement.replace("-", "_"))
        if importlib.util.find_spec(module) is None and importlib.util.find_spec(
            requirement.replace("-", "_")
        ) is None:
            continue  # not installed in this dev venv; present in the packaged runtime
        assert requirement in names, f"{requirement} ships here but is not credited"

    body = client.get("/api/system/attribution").json()
    missing = [e["name"] for e in body["backend"] if not e["licence"]]
    assert not missing, f"credited without a licence: {missing}"


def test_bundled_tools_are_named():
    body = client.get("/api/system/attribution").json()

    bundled = {entry["name"] for entry in body["bundled"]}
    assert {"FFmpeg", "Electron"} <= bundled, "the big borrowed pieces must be credited"
