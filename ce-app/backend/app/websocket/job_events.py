"""Realtime fan-out, hardened (0.9.35, advisors' A1).

The old loop awaited each client in sequence: one slow socket stalled every
other client, and a connection that died without raising stayed subscribed.
Now sends run concurrently with a per-client timeout, dead sockets are pruned
in one pass, and the container is a set guarded by a lock.
"""
import asyncio

from fastapi import WebSocket

_SEND_TIMEOUT = 2.0


class ConnectionManager:
    def __init__(self) -> None:
        self._connections: set[WebSocket] = set()
        self._lock = asyncio.Lock()

    async def connect(self, ws: WebSocket) -> None:
        await ws.accept()
        async with self._lock:
            self._connections.add(ws)

    async def disconnect(self, ws: WebSocket) -> None:
        async with self._lock:
            self._connections.discard(ws)

    async def broadcast(self, message: dict) -> None:
        async with self._lock:
            targets = list(self._connections)
        if not targets:
            return

        async def one(ws: WebSocket) -> bool:
            try:
                await asyncio.wait_for(ws.send_json(message), timeout=_SEND_TIMEOUT)
                return True
            except Exception:  # noqa: BLE001 — dead or slow client: prune it
                return False

        alive = await asyncio.gather(*(one(ws) for ws in targets),
                                    return_exceptions=True)
        dead = [ws for ws, ok in zip(targets, alive) if ok is not True]
        if dead:
            async with self._lock:
                self._connections.difference_update(dead)


ws_manager = ConnectionManager()
