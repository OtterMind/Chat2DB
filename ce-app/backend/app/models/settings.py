from app.database import db

class SettingsModel:
    @staticmethod
    def get(key: str) -> str | None:
        conn = db.connect()
        row = conn.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
        return row["value"] if row else None
    @staticmethod
    def set(key: str, value: str) -> None:
        conn = db.connect()
        conn.execute("INSERT OR REPLACE INTO settings (key, value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)", (key, value))
        conn.commit()
    @staticmethod
    def get_all() -> dict:
        conn = db.connect()
        rows = conn.execute("SELECT key, value FROM settings").fetchall()
        return {row["key"]: row["value"] for row in rows}