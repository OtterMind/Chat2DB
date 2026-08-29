"""The local n8n-flavoured automation layer."""
from __future__ import annotations

import pytest

from core import workflows


class _Reporter:
    def stage(self, stage, fraction, label=""):
        pass


def test_list_presets_exposes_node_chains():
    presets = workflows.list_presets()
    ids = {p["id"] for p in presets}
    assert {"volleyball", "talking", "shorts"} <= ids
    vb = next(p for p in presets if p["id"] == "volleyball")
    assert [n["id"] for n in vb["nodes_meta"]][:3] == ["ingest", "measure", "template"]


def test_run_chain_builds_and_saves_a_project(tmp_path, monkeypatch, media):
    from app.config import settings
    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))

    out = workflows.run("volleyball", str(media["clip_a"]), _Reporter())
    nodes = [n["id"] for n in out["nodes"]]
    assert "ingest" in nodes and "measure" in nodes and "build" in nodes and "save" in nodes
    assert out["timeline"]["clips"], "the chain produced a real timeline"
    assert out["project"].startswith("wf-volleyball")


def test_run_unknown_preset_raises():
    with pytest.raises(ValueError):
        workflows.run("nope", "/tmp/x.mp4", _Reporter())


def test_watch_start_stop_toggles(tmp_path):
    workflows.stop_watch()
    st = workflows.start_watch(str(tmp_path), "shorts")
    assert st["active"] is True
    assert workflows.stop_watch()["active"] is False
