"""The pip-free installer's sdist path: pure sources in, compiled sources refused."""
from __future__ import annotations

import io
import tarfile

import pytest

from core.engine import _pypi


def _make_sdist(path, members: dict[str, bytes]):
    with tarfile.open(path, "w:gz") as archive:
        for name, data in members.items():
            info = tarfile.TarInfo(f"pkg-1.0/{name}")
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))


def test_a_pure_sdist_is_recognised_and_unpacked(tmp_path):
    sdist = tmp_path / "pkg-1.0.tar.gz"
    _make_sdist(sdist, {
        "pkg/__init__.py": b"VALUE = 1\n",
        "pkg/core.py": b"def f():\n    return VALUE\n",
        "setup.py": b"from setuptools import setup; setup()\n",
        "tests/test_x.py": b"raise SystemExit(1)\n",  # must not be copied
    })

    assert _pypi.sdist_is_pure(sdist)

    _pypi.extract_sdist(sdist, tmp_path / "out")

    assert (tmp_path / "out" / "pkg" / "__init__.py").read_bytes() == b"VALUE = 1\n"
    assert (tmp_path / "out" / "pkg" / "core.py").exists()
    assert not (tmp_path / "out" / "tests").exists()


def test_a_sdist_with_compiled_code_is_refused_not_unpacked(tmp_path):
    sdist = tmp_path / "pkg-1.0.tar.gz"
    _make_sdist(sdist, {
        "pkg/__init__.py": b"",
        "pkg/native.cpp": b"int main(){}\n",
    })

    assert not _pypi.sdist_is_pure(sdist)
    with pytest.raises(Exception):
        _pypi.extract_sdist(sdist, tmp_path / "nope")


def test_classify_reads_the_registry_honestly(monkeypatch):
    import json

    def fake(name):
        return {
            "wheel": {"urls": [{"filename": "x-1-py3-none-any.whl"}]},
            "sdist": {"urls": [{"filename": "x-1.tar.gz"}]},
            "none": {"urls": []},
        }[name]

    monkeypatch.setattr(_pypi, "_release", fake)

    assert _pypi.classify("wheel") == "wheel"
    assert _pypi.classify("sdist") == "sdist"
    assert _pypi.classify("none") == "none"
