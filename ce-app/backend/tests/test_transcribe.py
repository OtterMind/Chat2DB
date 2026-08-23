"""Speech recognition must start on a machine without the CUDA runtime.

A user with an NVIDIA card but no CUDA toolkit saw
`Library cublas64_12.dll is not found or cannot be loaded` — faster-whisper
reaching for the GPU and finding half of it. That machine is normal, so the CPU
path is a fallback, not a failure.
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
            if device in fails_on:
                raise RuntimeError("Library cublas64_12.dll is not found or cannot be loaded")
            self.size = size
            self.device = device

    module = types.ModuleType("faster_whisper")
    module.WhisperModel = FakeModel  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "faster_whisper", module)
    return calls


def test_it_falls_back_to_the_cpu_when_cuda_is_half_installed(monkeypatch):
    calls = _fake_faster_whisper(monkeypatch, fails_on={"auto"})
    model = transcribe._load("base")

    assert calls == ["auto", "cpu"], "the CPU fallback was not attempted"
    assert model.device == "cpu"
    assert "cpu" in (transcribe._MODEL_NAME or "")


def test_the_gpu_is_still_preferred_when_it_works(monkeypatch):
    calls = _fake_faster_whisper(monkeypatch, fails_on=set())
    model = transcribe._load("base")

    assert calls == ["auto"], "the CPU was used even though the GPU loaded"
    assert model.device == "auto"


def test_a_machine_where_nothing_loads_says_so(monkeypatch):
    _fake_faster_whisper(monkeypatch, fails_on={"auto", "cpu"})
    with pytest.raises(transcribe.TranscriberUnavailable) as failure:
        transcribe._load("base")
    assert "could not start" in str(failure.value)
