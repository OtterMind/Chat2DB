from fastapi import WebSocket

class ConnectionManager:
    def __init__(self): self._connections: list[WebSocket] = []
    async def connect(self, ws): await ws.accept(); self._connections.append(ws)
    def disconnect(self, ws):
        if ws in self._connections: self._connections.remove(ws)
    async def broadcast(self, message: dict):
        for c in list(self._connections):
            try: await c.send_json(message)
            except Exception: self.disconnect(c)

ws_manager = ConnectionManager()