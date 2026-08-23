"""Speech recognition must start on a machine without the CUDA runtime — and
must use the best engine that machine actually has.

A user with an NVIDIA card but no CUDA toolkit saw
`Library cublas64_12.dll is not found or cannot be loaded` — faster-whisper
reaching for the GPU and finding half of it. That machine is normal, so the CPU
path is a fallback, not a failure.

The ladder is `cuda/float16` → `auto/int8` → `cpu/int8`: float16 on a working
CUDA runtime is both faster and more accurate, and int8 is where we land, not
where we aim. The model is chosen the same way — the most accurate one already
downloaded, never a fixed "base".
"""
from __future__ import annotations

import sys
import types

import pytest

from core.engine import transcribe


@pytest.fixture(autouse=True)
def _reset_model():
    transcribe._MODEL = None
    transcribe._MODEL_NAME = None
    yield
    transcribe._MODEL = None
    transcribe._MODEL_NAME = None


def _fake_faster_whisper(monkeypatch, fails_on: set[str]):
    """A stand-in whose constructor refuses the devices we name."""
    calls: list[str] = []

    class FakeModel:
        def __init__(self, size, device="auto", compute_type="int8"):
            calls.append(device)
            self.compute_type = compute_type
            if device in fails_on:
                raise RuntimeError("Library cublas64_12.dll is not found or cannot be loaded")
            self.size = size
            self.device = device

    module = types.ModuleType("faster_whisper")
    module.WhisperModel = FakeModel  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "faster_whisper", module)
    return calls


def test_it_falls_back_to_the_cpu_when_cuda_is_half_installed(monkeypatch):
    calls = _fake_faster_whisper(monkeypatch, fails_on={"cuda", "auto"})
    model = transcribe._load("base")

    assert calls == ["cuda", "auto", "cpu"], "the CPU fallback was not attempted"
    assert model.device == "cpu"
    assert "cpu" in (transcribe._MODEL_NAME or "")


def test_the_gpu_is_still_preferred_when_it_works(monkeypatch):
    calls = _fake_faster_whisper(monkeypatch, fails_on=set())
    model = transcribe._load("base")

    assert calls == ["cuda"], "a working GPU was not used first"
    assert model.device == "cuda"
    # Accuracy, not just speed: int8 on the GPU would be the wrong default.
    assert model.compute_type == "float16"


def test_a_machine_where_nothing_loads_says_so(monkeypatch):
    _fake_faster_whisper(monkeypatch, fails_on={"cuda", "auto", "cpu"})
    with pytest.raises(transcribe.TranscriberUnavailable) as failure:
        transcribe._load("base")
    assert "could not start" in str(failure.value)


def test_the_best_downloaded_model_is_used_not_the_smallest(monkeypatch):
    """A machine with `base` and `small` transcribes with `small`."""
    monkeypatch.setattr(transcribe, "local_models", lambda: ["base", "small"])
    assert transcribe.best_local_model() == "small"

    monkeypatch.setattr(transcribe, "local_models", lambda: ["base", "medium", "small"])
    assert transcribe.best_local_model() == "medium"

    # Nothing downloaded: the caller still gets a name it can fetch.
    monkeypatch.setattr(transcribe, "local_models", lambda: [])
    assert transcribe.best_local_model() == "base"


def test_the_chosen_model_is_the_one_that_loads(monkeypatch):
    calls = _fake_faster_whisper(monkeypatch, fails_on=set())
    monkeypatch.setattr(transcribe, "local_models", lambda: ["base", "small"])
    model = transcribe._load()

    assert calls == ["cuda"]
    assert model.size == "small", "the engine loaded a different model than the card reports"
