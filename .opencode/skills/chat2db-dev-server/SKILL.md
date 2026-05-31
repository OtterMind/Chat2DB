---
name: chat2db-dev-server
description: Start, restart, stop, and inspect the local Chat2DB development servers in E:\workspace\Chat2DB. Use when the user asks to 启动/重启/打开/检查 Chat2DB 前端后端, restart backend after Java changes, restart frontend after React changes, verify ports/logs, or fix dev-server startup issues for this workspace.
---

# Chat2DB Dev Server

## Quick Start

Use `scripts/manage-chat2db-dev.ps1` for repeatable server operations.

```powershell
# Start both frontend and backend.
& "E:\workspace\Chat2DB\.codex\skills\chat2db-dev-server\scripts\manage-chat2db-dev.ps1" -Action start

# Restart both. Install backend dependency modules first when Java backend modules changed.
& "E:\workspace\Chat2DB\.codex\skills\chat2db-dev-server\scripts\manage-chat2db-dev.ps1" -Action restart -InstallBackendDeps

# Check listening ports and log tails.
& "E:\workspace\Chat2DB\.codex\skills\chat2db-dev-server\scripts\manage-chat2db-dev.ps1" -Action status
```

This is a project-local skill. Keep it under `E:\workspace\Chat2DB\.codex\skills`, not under the global user skills directory.

## Workspace Defaults

- Workspace: `E:\workspace\Chat2DB`
- Frontend directory: `chat2db-client`
- Backend directory: `chat2db-server`
- Frontend URL: `http://localhost:8000`
- Backend URL: `http://localhost:10821`
- Logs: `E:\workspace\Chat2DB\logs\frontend.log` and `E:\workspace\Chat2DB\logs\backend.log`
- Java: set `$env:JAVA_HOME = "D:\tool\Java\jdk-17"` before Maven commands.
- Frontend package manager: use `D:\nvm4w\nodejs\yarn.cmd`; do not use npm.

## Backend Restart Rule

When backend code changed outside `chat2db-server-start`, run the script with `-InstallBackendDeps` before restart. This installs the modules that `chat2db-server-start` consumes so `spring-boot:run -pl chat2db-server-start` does not load stale local Maven artifacts.

This is required after changes in:

- `chat2db-server-domain-api`
- `chat2db-server-domain-core`
- `chat2db-server-domain-repository`
- `chat2db-server-web-api`

## Verification

After starting or restarting:

1. Confirm ports `8000` and `10821` are listening.
2. Check backend log for `Started Chat2dbLiteApplication` and `[Startup] Chat2dbLiteApplication started successfully`.
3. Check frontend log for `App listening at` and `Local: http://localhost:8000`.
4. If an endpoint was added, verify it without mutating data when possible, for example:

```powershell
Invoke-WebRequest -Uri 'http://localhost:10821/api/task/cleanup' -Method Options -UseBasicParsing
```

## Notes

- Early frontend proxy `ECONNREFUSED` entries can appear while the backend is still starting; treat them as transient if backend later starts and `/api` calls succeed.
- If a requested endpoint returns `NoHandlerFoundException`, install backend dependencies with `-InstallBackendDeps` and restart backend again.
