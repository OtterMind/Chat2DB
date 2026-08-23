"""Cancellation that reaches the child process.

A cancel button that only stops the *reporting* is a lie: the FFmpeg process keeps
decoding, the fan keeps spinning, and the next job queues behind it. Work started
from `core.tasks` binds its cancel event to the worker thread, and every FFmpeg
call in the engine goes through `run()` here, which polls the child and kills it —
process group and all — the moment the flag is set.

Thread-local on purpose: the alternative is threading a `cancel` parameter through
every helper in the engine, which is a lot of signature churn for a flag that is
already unique per worker thread.
"""
from __future__ import annotations

import subprocess
import threading

_local = threading.local()

#: How often a running child is checked against the flag.
POLL = 0.1


class Cancelled(Exception):
    """The user pressed Cancel; the child has been killed."""


def bind(event: threading.Event | None) -> None:
    """Attach a cancel flag to *this* thread. Called by the task runner."""
    _local.event = event


def current() -> threading.Event | None:
    return getattr(_local, "event", None)


def cancelled() -> bool:
    event = current()
    return bool(event and event.is_set())


def check() -> None:
    if cancelled():
        raise Cancelled()


def run(command: list[str], **kwargs) -> subprocess.CompletedProcess:
    """`subprocess.run`, except it dies when the task is cancelled.

    Without a cancel flag bound this is exactly `subprocess.run`, so nothing
    changes for the synchronous endpoints and the tests that use them.
    """
    event = current()
    if event is None:
        return subprocess.run(command, **kwargs)

    check()
    capture = kwargs.pop("capture_output", False)
    if capture:
        kwargs.setdefault("stdout", subprocess.PIPE)
        kwargs.setdefault("stderr", subprocess.PIPE)

    with subprocess.Popen(command, **kwargs) as child:
        while True:
            try:
                out, err = child.communicate(timeout=POLL)
                return subprocess.CompletedProcess(command, child.returncode, out, err)
            except subprocess.TimeoutExpired:
                if event.is_set():
                    child.kill()
                    child.communicate()
                    raise Cancelled()
