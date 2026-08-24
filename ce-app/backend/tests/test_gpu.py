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
