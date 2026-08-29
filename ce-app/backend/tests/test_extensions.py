"""The eight novel extensions, each tested."""
from __future__ import annotations

import sys
from types import SimpleNamespace

from core import extensions
from core.assistant import providers


def test_webhook_off_by_default(monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "publish_webhook_url", "")
    assert extensions.send_webhook("x", {})["sent"] is False


def test_webhook_posts_to_user_endpoint(monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "publish_webhook_url", "http://example/hook")
    seen = {}
    class FakeResp: ok = True; status_code = 200
    fake = SimpleNamespace(post=lambda url, json, timeout: (seen.update(json) or FakeResp()))
    monkeypatch.setitem(sys.modules, "requests", fake)
    out = extensions.send_webhook("render_done", {"file": "a.mp4"})
    assert out["sent"] is True and seen["event"] == "render_done"


def test_provenance_lists_components_and_licences():
    edit = {"summary": {"captions": 3, "brain": {"winner": "rules",
        "scoreboard": [{"name": "ensemble"}]}}}
    prov = extensions.build_provenance(edit)
    names = [c["component"] for c in prov["components"]]
    assert "faster-whisper" in names and "ffmpeg" in names and "ensemble" in names
    assert all(c["licence"] for c in prov["components"])


def test_dna_save_and_match(tmp_path, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))
    extensions.save_dna("old", {"avg_shot": 2, "cut_rate": 30, "bpm": 120, "talk": .5, "motion": .5, "name": "old"})
    got = extensions.match_dna({"avg_shot": 2, "cut_rate": 30, "bpm": 120, "talk": .5, "motion": .5})
    assert got and got["name"] == "old" and got["distance"] == 0.0


def test_autotag_and_language_search():
    assert "sport" in extensions.autotag({"action": .6, "speech": .1, "emotion": .3})
    tagged = [{"tags": ["sport"]}, {"tags": ["talking"]}]
    assert extensions.search_tags("اسپایک", tagged)[0]["tags"] == ["sport"]
    assert len(extensions.search_tags("", tagged)) == 2


def test_fanout_writes_per_platform_metadata(tmp_path, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))
    out = extensions.fanout("clip", ["tiktok", "square"])
    assert len(out["specs"]) == 2
    assert (tmp_path / "exports" / "clip-fanout" / "tiktok.md").exists()


def test_vault_roundtrip_and_tamper(tmp_path, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))
    extensions.vault_set("youtube", "secret-key")
    assert extensions.vault_get("youtube") == "secret-key"
    assert "youtube" in extensions.vault_list()
    # tamper -> integrity fails -> None
    import json, pathlib
    p = pathlib.Path(tmp_path) / ".vault.json"
    data = json.loads(p.read_text()); data["youtube"]["data"] = "00" * 8
    p.write_text(json.dumps(data))
    assert extensions.vault_get("youtube") is None


def test_chain_runs_three_steps(monkeypatch):
    monkeypatch.setattr(providers, "chat",
                        lambda messages, **k: SimpleNamespace(text="out", label="t:m"))
    out = providers.run_chain("a volleyball rally")
    assert [s["step"] for s in out["steps"]] == ["summarise", "title", "hook"]
    assert out["complete"] is True


def test_chain_without_provider_still_returns_shape(monkeypatch):
    monkeypatch.setattr(providers, "chat", lambda messages, **k: None)
    out = providers.run_chain("x")
    assert out["complete"] is False and len(out["steps"]) == 3


def test_batch_processes_folder(tmp_path):
    (tmp_path / "a.mp4").write_bytes(b"x")
    (tmp_path / "b.txt").write_bytes(b"y")
    out = extensions and __import__("core.workflows", fromlist=["run_batch"]).run_batch(str(tmp_path))
    assert out["processed"] == 1
