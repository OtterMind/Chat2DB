#!/usr/bin/env python3
"""Create local-only NapCat and relay configuration without printing secrets."""

from __future__ import annotations

import json
import os
import secrets
from pathlib import Path


ROOT = Path(__file__).resolve().parent
ENV_PATH = ROOT / ".env"
NAPCAT_DIR = ROOT / "napcat"


def _read_environment() -> dict[str, str]:
    values: dict[str, str] = {}
    if not ENV_PATH.exists():
        return values
    for raw_line in ENV_PATH.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key] = value
    return values


def _secret(values: dict[str, str], name: str) -> str:
    current = values.get(name, "")
    if len(current) >= 32 and not current.startswith("replace-"):
        return current
    return secrets.token_urlsafe(48)


def main() -> None:
    values = _read_environment()
    values.update(
        {
            "NAPCAT_UID": values.get("NAPCAT_UID", str(os.getuid())),
            "NAPCAT_GID": values.get("NAPCAT_GID", str(os.getgid())),
            "NAPCAT_WEBUI_TOKEN": _secret(values, "NAPCAT_WEBUI_TOKEN"),
            "ONEBOT_TOKEN": _secret(values, "ONEBOT_TOKEN"),
            "RELAY_TOKEN": _secret(values, "RELAY_TOKEN"),
            "QQ_GROUP_ID": values.get("QQ_GROUP_ID", "1080856850"),
            "CLOUDFLARE_TUNNEL_TOKEN": values.get("CLOUDFLARE_TUNNEL_TOKEN", ""),
        }
    )
    ENV_PATH.write_text(
        "".join(f"{name}={value}\n" for name, value in values.items()), encoding="utf-8"
    )
    ENV_PATH.chmod(0o600)

    NAPCAT_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    (NAPCAT_DIR / "config").mkdir(mode=0o700, exist_ok=True)
    (NAPCAT_DIR / "qq").mkdir(mode=0o700, exist_ok=True)

    onebot_config = {
        "network": {
            "httpServers": [
                {
                    "enable": True,
                    "name": "chat2db-relay",
                    "host": "0.0.0.0",
                    "port": 3000,
                    "enableCors": False,
                    "enableWebsocket": False,
                    "messagePostFormat": "array",
                    "token": values["ONEBOT_TOKEN"],
                    "debug": False,
                }
            ],
            "httpSseServers": [],
            "httpClients": [],
            "websocketServers": [],
            "websocketClients": [],
            "plugins": [],
        },
        "musicSignUrl": "",
        "enableLocalFile2Url": False,
        "parseMultMsg": False,
        "imageDownloadProxy": "",
    }
    onebot_path = NAPCAT_DIR / "onebot11.json"
    onebot_path.write_text(
        json.dumps(onebot_config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    onebot_path.chmod(0o600)

    webui_config = {
        "host": "0.0.0.0",
        "prefix": "/webui",
        "port": 6099,
        "token": values["NAPCAT_WEBUI_TOKEN"],
        "loginRate": 3,
    }
    webui_path = NAPCAT_DIR / "config" / "webui.json"
    webui_path.write_text(
        json.dumps(webui_config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    webui_path.chmod(0o600)

    print("Configuration generated. Secret values were written locally and were not printed.")


if __name__ == "__main__":
    main()
