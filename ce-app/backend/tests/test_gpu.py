"""The graphics card: probed, never assumed — and never a crash when absent.

Two failures this file exists for. The compositor decided NVENC was available by
grepping FFmpeg's encoder list, which lists `h264_nvenc` on machines whose
driver refuses it — wrong in both directions. And `/api/system/doctor` returned
`"cuda": {"available": false}` as a hard-coded literal, so a user with a working
card was told they had none.

The sandbox this runs in has no NVIDIA card, which makes it the important case:
everything must come back as an honest "no" and nothing may raise.
"""
from __future__ import annotations

import sys

from fastapi.testclient import TestClient

from app.main import app
from core.engine import gpu

client = TestClient(app)


def test_probing_a_machine_without_a_card_is_not_an_error():
    caps = gpu.capabilities()

    assert isinstance(caps.nvenc, bool) and isinstance(caps.nvdec, bool)
    assert caps.as_dict()["used"] == [] or caps.nvenc or caps.nvdec
    if not caps.name and not caps.nvenc and not caps.nvdec:
        assert any("hardware acceleration" in note.lower() for note in caps.notes)


def test_the_encoder_choice_follows_the_probe(monkeypatch):
    monkeypatch.setattr(
        gpu, "best_encoder",
        lambda: {"name": "h264_nvenc", "vendor": "NVIDIA", "codec": "H.264", "ok": True, "reason": ""},
    )
    assert "h264_nvenc" in gpu.encode_args({"nvenc_cq": 21})

    monkeypatch.setattr(gpu, "best_encoder", lambda: None)
    args = gpu.encode_args({"preset": "superfast", "crf": 20})
    assert "libx264" in args and "20" in args and "h264_nvenc" not in args


def test_decoding_arguments_are_empty_without_a_card(monkeypatch):
    monkeypatch.setattr(gpu, "best_decoder", lambda: None)
    assert gpu.decode_args() == []

    monkeypatch.setattr(gpu, "best_decoder", lambda: "cuda")
    assert gpu.decode_args() == ["-hwaccel", "cuda"]


def test_the_proxy_command_uses_the_card_when_there_is_one(monkeypatch, tmp_path):
    from pathlib import Path

    from core.engine import proxy

    monkeypatch.setattr(gpu, "best_decoder", lambda: "cuda")
    monkeypatch.setattr(
        gpu, "best_encoder",
        lambda: {"name": "h264_nvenc", "vendor": "NVIDIA", "codec": "H.264", "ok": True, "reason": ""},
    )
    command = proxy.build_command(Path("in.mp4"), Path("out.mp4"))

    assert command[command.index("-hwaccel") + 1] == "cuda"
    assert "h264_nvenc" in command
    # And the decode flag must come before the input, or FFmpeg ignores it.
    assert command.index("-hwaccel") < command.index("-i")


def test_the_proxy_command_falls_back_cleanly(monkeypatch, tmp_path):
    from pathlib import Path

    from core.engine import proxy

    monkeypatch.setattr(gpu, "best_decoder", lambda: None)
    monkeypatch.setattr(gpu, "best_encoder", lambda: None)
    command = proxy.build_command(Path("in.mp4"), Path("out.mp4"))

    assert "-hwaccel" not in command
    assert "libx264" in command


def test_the_doctor_reports_what_was_probed():
    body = client.get("/api/system/doctor").json()

    assert set(body["cuda"]) >= {"available", "name", "encode", "decode"}
    assert isinstance(body["cuda"]["available"], bool)
    # The literal it used to be would have made this pass by accident, so pin
    # the shape as well: a machine with a card must be able to say so.
    assert body["cuda"]["available"] is (bool(gpu.capabilities().name) or gpu.capabilities().nvenc)


def test_the_status_endpoint_lists_what_the_card_is_used_for():
    body = client.get("/api/gpu/status").json()

    assert set(body) >= {"encode", "decode", "whisperDevice", "used", "notes"}
    assert isinstance(body["used"], list)


def test_the_benchmark_measures_the_processor_even_with_no_card():
    body = client.post("/api/gpu/benchmark", json={"seconds": 1, "width": 320, "height": 240}).json()

    assert body["cpu"] is not None and body["cpu"] > 0
    assert "gpu" in body  # None here, a number on a machine with a card


def test_offering_cuda_libraries_is_refused_without_a_card(monkeypatch):
    if gpu.capabilities().name:
        return  # a machine with a card would really install 1.3 GB
    response = client.post("/api/ai/cuda/install")
    assert response.status_code == 409
    assert "nvidia" in response.json()["detail"].lower()


def test_the_cuda_status_explains_itself():
    body = client.get("/api/ai/cuda/status").json()

    assert set(body) >= {"device", "detail", "canInstall", "downloadMb"}
    assert body["device"] in ("cpu", "cuda")
    if body["device"] == "cpu":
        assert body["detail"], "a fallback to the processor must say why"


# ---------------------------------------------------- every machine, not one


def test_the_probe_reports_a_reason_for_every_encoder_that_failed():
    """A bare "no" is what sent the owner back to ask what the answer meant."""
    entries = gpu.probe_encoders()

    assert len(entries) >= 6, "only NVIDIA was tried"
    vendors = {entry["vendor"] for entry in entries}
    assert {"NVIDIA", "Intel Quick Sync", "AMD"} <= vendors, vendors
    for entry in entries:
        assert entry["ok"] or entry["reason"], f"{entry['name']} failed without saying why"


def test_the_encoder_flags_match_the_vendor(monkeypatch):
    """NVIDIA, Intel and AMD each want different words for "constant quality"."""
    for name, expected in (
        ("h264_nvenc", "-cq"),
        ("h264_qsv", "-global_quality"),
        ("h264_amf", "-qp_i"),
        ("h264_vaapi", "-qp"),
    ):
        monkeypatch.setattr(
            gpu, "best_encoder",
            lambda name=name: {"name": name, "vendor": "x", "codec": "H.264", "ok": True, "reason": ""},
        )
        args = gpu.encode_args({"nvenc_cq": 21})
        assert name in args and expected in args, args


def test_the_decoder_is_whatever_answered_first(monkeypatch):
    monkeypatch.setattr(gpu, "best_decoder", lambda: "qsv")
    assert gpu.decode_args() == ["-hwaccel", "qsv"]

    monkeypatch.setattr(gpu, "best_decoder", lambda: None)
    assert gpu.decode_args() == []


def test_the_memory_advice_scales_with_the_card(monkeypatch):
    """The note used to be written for a 4 GB card and said so in every case."""
    monkeypatch.setattr(gpu, "nvidia_smi", lambda: {"name": "Test", "memory_mb": 4096, "driver": "1"})
    small = " ".join(gpu.capabilities().notes)
    monkeypatch.setattr(gpu, "nvidia_smi", lambda: {"name": "Test", "memory_mb": 24576, "driver": "1"})
    large = " ".join(gpu.capabilities().notes)

    assert "3B" in small and "30B" in large, (small, large)


def test_a_machine_with_no_hardware_still_gets_a_working_command():
    """The fallback is not an error state; it is most machines."""
    args = gpu.encode_args({"crf": 20, "preset": "veryfast"})
    assert "-c:v" in args
    assert args[args.index("-c:v") + 1] in {"libx264", *(e["name"] for e in gpu.probe_encoders())}


def test_the_encoder_probe_gives_the_encoder_time_to_flush():
    """The probe that told a GTX 1650 owner their card could not encode.

    It asked for three frames into `-f null -`. NVENC buffers several frames and
    flushes at end of stream, so the run ended before a single packet came out
    and FFmpeg said "Nothing was written into output file". x264 emits packets in
    those same three frames, which is why nobody questioned the shape.
    """
    import inspect

    source = inspect.getsource(gpu.probe_encoders)

    assert "-frames:v" not in source, "a frame limit can end the run before the encoder flushes"
    assert "duration=1.5" in source, "the probe clip is too short to prove anything"
    assert "getsize" in source, "the probe must check that something was actually written"


def test_a_model_is_recommended_against_the_card_in_this_machine(monkeypatch):
    from fastapi.testclient import TestClient

    from app.main import app as application
    from app.routers import ai

    monkeypatch.setattr(gpu, "nvidia_smi", lambda: {"name": "T", "memory_mb": 4096, "driver": "1"})
    monkeypatch.setattr(ai, "_ollama_state", lambda: {"models": [], "running": True})
    body = TestClient(application).get("/api/ai/models").json()

    assert body["vramGb"] == 4.0
    fits = {m["name"]: m["fits"] for m in body["models"]}
    assert fits["qwen2.5vl:3b"] is True, "a 3B vision model fits a 4 GB card"
    assert fits["llama3.2-vision:11b"] is False, "an 11B model does not"
    assert all(m["why"] and m["note"] for m in body["models"]), "every row must say why it is there"


def test_the_catalogue_offers_a_model_that_can_see():
    from app.routers.ai import CATALOGUE

    vision = [m for m in CATALOGUE if m["job"] == "vision"]
    assert len(vision) >= 3, "the whole point is that a model can look at the frames"
    assert any(m["vramGb"] <= 4 for m in vision), "nothing here fits a 4 GB card"


def test_the_probe_does_not_hide_the_encoder_s_own_words():
    """`-loglevel error` hid the reason and the user saw only the symptom.

    FFmpeg's closing line — "Nothing was written into output file, because at
    least one of its streams received no packets" — is what happens *after* the
    encoder refuses. The encoder's own line, which says why, is a warning.
    """
    import inspect

    source = inspect.getsource(gpu.probe_encoders)

    assert '"warning"' in source, "the probe still runs at error level and hides the cause"
    assert "first_stderr" in source, "a rescue attempt's failure would bury the real reason"
    assert "constqp" in source, "no second attempt is made before giving up"


def test_a_card_that_cannot_encode_gets_something_to_try(monkeypatch):
    """Naming the three usual Windows causes is more use than a shrug."""
    monkeypatch.setattr(gpu, "nvidia_smi", lambda: {"name": "GeForce", "memory_mb": 4096, "driver": "591"})
    monkeypatch.setattr(gpu, "best_encoder", lambda: None)
    monkeypatch.setattr(gpu, "can_decode", lambda: True)

    notes = " ".join(gpu.capabilities().notes).lower()

    assert "high performance" in notes, "the Optimus case is the common one and is not mentioned"
    assert "driver" in notes
    assert "cannot damage" in notes, "the user asked whether turning it on is risky"


# --------------------------------------------- asking Windows for the card


def test_the_preference_covers_the_process_that_actually_encodes(monkeypatch):
    """Windows' own Settings page can only reach the app. FFmpeg does the work."""
    import os

    monkeypatch.setenv("CE_APP_EXE", os.__file__)  # any real file will do
    paths = gpu._executables()

    assert any(p.endswith(("python", "python.exe", "python3", "python3.11")) or "python" in p
               for p in paths), paths
    assert len(set(paths)) == len(paths), "the same executable was listed twice"
    assert all(os.path.isabs(p) for p in paths), "the registry needs full paths"


def test_the_preference_value_is_the_one_windows_expects():
    assert gpu.HIGH_PERFORMANCE == "GpuPreference=2;"
    assert gpu.GPU_PREFERENCE_KEY.endswith("UserGpuPreferences")


def test_it_says_so_politely_on_a_system_that_has_no_such_setting():
    """The sandbox is Linux; this must answer, not raise."""
    from fastapi.testclient import TestClient

    from app.main import app as application

    client = TestClient(application)
    read = client.get("/api/gpu/preference").json()
    write = client.post("/api/gpu/preference").json()

    if sys.platform != "win32":
        assert read["supported"] is False and "Windows" in read["reason"]
        assert write["supported"] is False and write["changed"] == []
    else:  # pragma: no cover - only on the machines that matter
        assert "entries" in read


def test_setting_the_preference_clears_the_cached_probes():
    """The probes are cached for the process; after a change they are stale."""
    import inspect

    from app.routers import gpu as router

    source = inspect.getsource(router.set_preference)
    for name in ("can_encode", "probe_encoders", "best_decoder"):
        assert f"{name}.cache_clear()" in source, f"{name} would keep answering with the old result"
