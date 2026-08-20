<div align="center">
  <img src="./icon.png" alt="Chat2DB" width="100">
  <div align="center">
  Powered by  <a href="https://ottermind.ai">OtterMind</a>
</div>
  <br/>
  <p><strong>An AI-powered database client and SQL workspace for developers, DBAs, analysts, and data teams.</strong></p>
</div>

<div align="center">
  <a href="./README.md"><img alt="README in English" src="https://img.shields.io/badge/English-d9d9d9"></a>
  <a href="./README_CN.md"><img alt="简体中文版自述文件" src="https://img.shields.io/badge/简体中文-d9d9d9"></a>
  <a href="./README_JA.md"><img alt="日本語のREADME" src="https://img.shields.io/badge/日本語-d9d9d9"></a>
  <a href="./README_ES.md"><img alt="README en español" src="https://img.shields.io/badge/Español-d9d9d9"></a>
  <a href="./README_KO.md"><img alt="한국어 README" src="https://img.shields.io/badge/한국어-d9d9d9"></a>
</div>

## What is Chat2DB?

Chat2DB Community is a free, cross-platform database client for Windows, macOS, and Linux. It runs entirely on your machine and combines a full-featured SQL workspace with an AI assistant that you connect to your own model.

- **40+ databases** — MySQL, PostgreSQL, Oracle, SQL Server, ClickHouse, MongoDB, Redis, SQLite, MariaDB, TiDB, Hive, DB2, Snowflake, BigQuery, Elasticsearch, Trino, TimescaleDB, Greenplum, YugabyteDB, CrateDB, QuestDB, Apache IoTDB, Firebird, HSQLDB, Apache Derby, and more via plugins — new JDBC databases can be added through configuration only, without code changes.
- **SQL workspace** — editing, completion, formatting, execution, saved SQL, and execution history.
- **AI assistant** — bring your own AI model to generate, explain, and optimize SQL in natural language.
- **Database management** — browse metadata, manage tables and objects (DDL/DML), and edit data in place.
- **Data import and export**, **dashboards and charts**, and an **[open-source CLI with MCP support](https://github.com/OtterMind/Chat2DB-CLI)**.

<div align="center">

[![Chat2DB workspace with SQL editor and AI assistant — click to watch the intro video](https://cdn.chat2db-ai.com/website/img/first_video_cover.webp)](https://cdn.chat2db-ai.com/website/video/first_sceen_en.mp4)

</div>

### Screenshots

| Dashboards and charts | ER diagrams |
| --- | --- |
| ![Dashboards and charts](https://cdn.chat2db-ai.com/website/img/bi_dashboard.png) | ![ER diagram](https://cdn.chat2db-ai.com/website/img/er_diagrams.png) |

| Visual data management | Data import and export |
| --- | --- |
| ![Visual data management](https://cdn.chat2db-ai.com/website/img/visual_data_mnagement_en.png) | ![Data import and export](https://cdn.chat2db-ai.com/website/img/import_export_data_en.png) |

## Quick Start

### Option 1: Desktop App

Download the installer for your platform from [GitHub Releases](https://github.com/OtterMind/Chat2DB/releases), install it, and start connecting to your databases. No further setup is required.

### Option 2: Docker

Requirements: Docker 19.03.0+, Docker Compose 2.0.0+ (Compose V2, only for the Compose variant), 2+ CPU cores, 4+ GiB RAM.

First create the encryption key (see [Encryption Key](#encryption-key) for why it matters), then start the container:

```bash
# Run once from a repository checkout. Re-running reuses the same valid key.
git clone https://github.com/OtterMind/Chat2DB.git && cd Chat2DB
./script/security/init-community-encryption-key.sh

docker run --detach \
  --name chat2db-community \
  --restart unless-stopped \
  --publish 127.0.0.1:10825:10825 \
  --volume "$HOME/.chat2db-community-docker:/root/.chat2db-community" \
  --env CHAT2DB_COMMUNITY_ENCRYPTION_KEY_FILE=/run/secrets/chat2db-community-encryption.key \
  --volume "$HOME/.config/chat2db-community/encryption.key:/run/secrets/chat2db-community-encryption.key:ro" \
  chat2db/chat2db:latest
```

Then open `http://localhost:10825` in your browser.

Alternatively, use the bundled Compose definition:

```bash
./script/security/init-community-encryption-key.sh
docker compose --file docker/docker-compose.yml up --detach
```

Notes:

- To update, pull the new image, remove the old container, and run the start command again. Keep `~/.config/chat2db-community/encryption.key` across rebuilds.
- The `docker run` example stores application data in `$HOME/.chat2db-community-docker`; the Compose definition uses the `chat2db-community-data` named volume. These locations do not share data.
- Chat2DB Community 5.3.0 uses the independent `/root/.chat2db-community` directory and does not automatically migrate data from earlier images that used `/root/.chat2db`.

## Security Notes

Chat2DB Community is a single-user, local-first application. It has no user
accounts or authorization boundaries between users. Keep the HTTP service
bound to `127.0.0.1` or `::1` and do not expose it to other users or
untrusted networks.

Custom JDBC drivers are executable Java code — install them only from
sources you trust. Imported configuration files, archives, SQL files,
database contents, and AI responses remain untrusted data. See the
[Security Policy](SECURITY.md) for the complete trust boundary and
vulnerability reporting process.

If you find this project useful, please give us a Star ⭐️ — it really helps!

<div align="center">
  <a href="https://github.com/OtterMind/Chat2DB"><img src="https://cdn.chat2db.ai/g/Area.gif" alt="Star Chat2DB on GitHub" width="600"></a>
</div>

## Encryption Key

Chat2DB Community encrypts stored datasource passwords and AI model API keys with AES-256-GCM using a per-installation key. Create it once from a repository checkout (requires `openssl`):

```bash
./script/security/init-community-encryption-key.sh
```

The key is written to `~/.config/chat2db-community/encryption.key`. **Back this file up separately and keep it across upgrades and container rebuilds** — replacing or losing it makes previously stored datasource passwords and AI model API keys unreadable. Web/headless startup fails when no valid key is provided; only Desktop mode creates a missing key automatically.

<details>
<summary>Key configuration reference (custom paths, resolution order, validation)</summary>

The key must be valid Base64 that decodes to exactly 32 bytes. The bundled initializer generates the standard padded form: 44 Base64 characters ending in `=`. It is cryptographic key material, not a human-readable password. Datasource passwords and AI API keys use the same key with separate authenticated AAD values, so ciphertext from one purpose cannot be decrypted as the other.

To use a custom path, pass it to the script and configure the same path when starting Chat2DB:

```bash
./script/security/init-community-encryption-key.sh /secure/path/chat2db-community.key

java -Dloader.path=chat2db-community-server/chat2db-community-start/target/lib \
    -Dchat2db.runtime.mode=community \
    -Dchat2db.mode=WEB \
    -Dchat2db.gui=false \
    -Dchat2db.network.status=OFFLINE \
    -Dchat2db.community.encryption-key-file=/secure/path/chat2db-community.key \
    -Dserver.address=127.0.0.1 \
    -Dserver.port=10825 \
    -jar chat2db-community-server/chat2db-community-start/target/chat2db-community.jar
```

The script's key-file path priority is the positional argument, `CHAT2DB_COMMUNITY_ENCRYPTION_KEY_FILE`, then the default path. It reuses a valid regular file, rejects symbolic links and non-regular files, and refuses to overwrite an invalid file. Keep the key readable only by the Chat2DB process owner.

Key configuration is resolved in this order:

1. JVM property `chat2db.community.encryption-key` containing the Base64 key.
2. Environment variable `CHAT2DB_COMMUNITY_ENCRYPTION_KEY` containing the Base64 key.
3. JVM property `chat2db.community.encryption-key-file` containing a key-file path.
4. Environment variable `CHAT2DB_COMMUNITY_ENCRYPTION_KEY_FILE` containing a key-file path.
5. Default file `~/.config/chat2db-community/encryption.key`.

The first configured value is authoritative. A blank value, malformed Base64, a key that does not decode to 32 bytes, or an invalid key file fails startup instead of falling through to the next source. File-based configuration is recommended because it avoids placing the key value directly in process arguments or environment variables.

Automatic key-file creation depends on `chat2db.mode`, not `chat2db.gui`. Community Desktop mode (`chat2db.runtime.mode=community` with `chat2db.mode=DESKTOP`) creates the selected key file when no inline key is configured and the file is missing. Any non-Desktop mode, including normal Web/headless startup, never creates a missing key and fails until a valid key is provided or initialized. The resolved key is cached for the process lifetime, so changing key configuration requires an application restart.

</details>

## External Agent Runtime Daemon Authentication

External Agent Runtime daemons such as Claude Code, Codex, OpenCode, Pi, Hermes,
and DeepSeek Harness must send a dedicated
Bearer token when calling `/api/agent/runtime/daemon/**`. Configure it before
starting Chat2DB with the `CHAT2DB_AGENT_RUNTIME_TOKEN` environment variable or
the equivalent `-Dchat2db.agent.runtime.token=...` JVM property. The environment
variable takes precedence. An unset token makes daemon endpoints return 503,
while an invalid token returns 401.

This token authenticates Runtime Daemon APIs only. It is not the Community data
encryption key and must not be stored in Runtime Profiles, logs, or the source
repository.

After a daemon claims and starts a Run, Chat2DB returns a separate short-lived
task token. The task-scoped Streamable HTTP MCP endpoint is
`/api/agent/runtime/mcp/runs/{runId}` and implements `initialize`, `tools/list`,
`tools/call`, and `ping`. Every request sends the task token as
`Authorization: Bearer <task-token>`. It is bound to the current Run and lease
attempt, expires with the lease or terminal Run state, and cannot authenticate
daemon control-plane calls.

The daemon never writes this token into a Runtime Profile. It injects
`CHAT2DB_AGENT_TASK_TOKEN` only into the current child process and references it
through the provider's managed MCP configuration or approval bridge. Claude Code,
Codex, OpenCode, Hermes, and DeepSeek Harness receive the task-scoped MCP endpoint;
Pi currently has no equivalent native managed-MCP configuration surface and runs
with the immutable task context only. MCP tools expose
only datasource, database, schema, table metadata, and SQL operations within
the Task DataScope. SQL still passes through Capability, Proposal, Approval,
and ToolAttempt controls. The older SQL HTTP tool endpoint continues to use
`X-Chat2DB-Agent-Task-Token` only as a daemon compatibility API.

When Session Resume is enabled on the Runtime Profile, provider session IDs are
persisted with `SESSION_UPDATED` events. A later Run resumes its parent Thread
only when the Agent, Provider, Runtime Profile, and full Profile snapshot all
match. During a short Chat2DB restart, the Daemon retries renewal until the last
acknowledged Lease expires; hard expiry still stops local execution and preserves
attempt fencing instead of replaying an uncertain operation.

After Community Desktop starts, it scans the process `PATH`, the login shell,
and common installation locations for Claude Code, Codex, OpenCode, Pi, Hermes,
and DeepSeek Harness. It creates internal
Runtime Profiles for the current user, so the Agent editor can show detected
Runtime cards without asking for a Runtime Profile ID. Use
`CHAT2DB_CLAUDE_CODE_PATH`, `CHAT2DB_CODEX_PATH`, `CHAT2DB_OPENCODE_PATH`,
`CHAT2DB_PI_PATH`, `CHAT2DB_HERMES_PATH`, or `CHAT2DB_DSH_PATH` for custom
executable locations.

The Runtime Daemon is also built as one separate local executable. Standalone
mode defaults to `AUTO` and detects every supported provider. Set
`CHAT2DB_AGENT_RUNTIME_PROVIDER` to one provider enum, such as `CLAUDE_CODE`,
`CODEX`, `OPENCODE`, `PI`, `HERMES`, or `DSH`, to restrict discovery:

```bash
mvn -B -f chat2db-community-server/pom.xml \
  -pl :chat2db-community-agent-runtime -am \
  -Dmaven.test.skip=true package

CHAT2DB_AGENT_RUNTIME_TOKEN='<same daemon token configured for Chat2DB>' \
java -jar chat2db-community-server/chat2db-community-agent-runtime/target/chat2db-agent-runtime-exec.jar

CHAT2DB_AGENT_RUNTIME_PROVIDER=HERMES \
CHAT2DB_AGENT_RUNTIME_TOKEN='<same daemon token configured for Chat2DB>' \
java -jar chat2db-community-server/chat2db-community-agent-runtime/target/chat2db-agent-runtime-exec.jar
```

It connects only to `http://127.0.0.1:10825` by default. Override the local
origin, stable daemon id, isolated workspace root, or concurrency with
`CHAT2DB_AGENT_RUNTIME_URL`, `CHAT2DB_AGENT_RUNTIME_PROVIDER`, `CHAT2DB_AGENT_RUNTIME_DAEMON_ID`,
`CHAT2DB_AGENT_RUNTIME_WORKSPACE`, and `CHAT2DB_AGENT_RUNTIME_CONCURRENCY`.
Runtime Profile environment entries are references to daemon environment
variable names; use a dedicated `CODEX_HOME` or `HERMES_HOME` reference instead
of forwarding the user's full home directory or process environment. The Hermes
adapter strips YOLO/hook auto-approval settings and rejects their launch flags.
ACP permission requests use the persistent Chat2DB Approval Bridge: the Run
stays in `WAITING_APPROVAL` while the daemon keeps its lease alive, and the
original Hermes session resumes only after a user decision is acknowledged.

Codex and Hermes can publish explicit artifacts by writing a JSON array to the
fixed `.chat2db-artifacts.json` file in the task workspace. The daemon uploads
every manifest before completing the Run. Chat2DB verifies type, MIME, decoded
size (up to 5 MiB), SHA-256, safe file name, lease attempt, and evidence scope.
File artifacts use inline Base64 and are copied into Chat2DB-managed H2 storage;
runtime-local paths and symlinked manifest files are rejected.

The daemon keeps process identity metadata in
`.chat2db-runtime-processes.json` under the workspace root. After an abnormal
exit, a new daemon reaps only entries whose PID, process start time, and
executable name all match. Identity mismatches remain quarantined instead of
risking a PID-reuse kill. A started Run with an uncertain outcome still
converges to `UNKNOWN` through lease reconciliation and is never blindly
replayed.

Hermes Gateway connects Feishu and DingTalk through the transport-only
`/api/agent/gateway/channels/**` API. Channel creation returns a Gateway token
once; subsequent calls use `X-Chat2DB-Agent-Gateway-Token`, while Chat2DB stores
only its hash. The Gateway owns platform verification, decryption, and sending;
Chat2DB owns conversation/thread binding, inbound idempotency, Task/Run state,
the final reply, and the Delivery Outbox. Failed deliveries back off and become
dead letters after five attempts. The Gateway must not start a second Hermes
turn for the same managed message.

## Build from Source

### Prerequisites

- Java runtime: <a href="https://adoptium.net/temurin/releases/?version=17" target="_blank">Eclipse Temurin 17</a>
- Node.js 18.17.0 or later
- Maven 3.8 or later

### Clone the Repository

```bash
git clone https://github.com/OtterMind/Chat2DB.git
```

### Frontend

Use Yarn with the checked-in lockfile.

```bash
cd Chat2DB/chat2db-community-client
yarn install --frozen-lockfile
yarn run start:community:hot
```

### Backend

```bash
cd Chat2DB
mvn -B clean package -Dmaven.test.skip=true -Dchat2db.finalName=chat2db-community \
    -f chat2db-community-server/pom.xml \
    -pl chat2db-community-start -am
./script/security/init-community-encryption-key.sh
java -Dloader.path=chat2db-community-server/chat2db-community-start/target/lib \
    -Dchat2db.gui=false \
    -Dchat2db.runtime.mode=community \
    -Dchat2db.mode=WEB \
    -Dchat2db.network.status=OFFLINE \
    -Dchat2db.community.encryption-key-file="$HOME/.config/chat2db-community/encryption.key" \
    -Dserver.address=127.0.0.1 \
    -Dserver.port=10825 \
    -Dspring.profiles.active=dev \
    -jar chat2db-community-server/chat2db-community-start/target/chat2db-community.jar
```

### Build a Local Docker Image

```bash
./docker/docker-build.sh 5.3.0 chat2db/chat2db:5.3.0
```

## Database guides

Step-by-step guides for connecting Chat2DB Community to specific databases:

- [BigQuery](./docs/guides/bigquery.md) — Google BigQuery via a Google Cloud service account.

## Community vs Commercial Editions

The Community edition contains the full local database client described above, including custom AI model support. The commercial Pro and Enterprise editions build on the same core and add hosted AI services, user accounts, cloud storage and multi-device sync, and team collaboration and governance features. See [chat2db.ai](https://chat2db.ai) for details.

## Contributing

We welcome bug reports, feature requests, documentation improvements, testing feedback, and pull requests from the community.

Before opening an issue or submitting a pull request, please read our [Contributing Guide](./CONTRIBUTING.md). It explains how to report bugs, suggest improvements, and make contributions easier for maintainers to review.

- For bugs and feature requests, please use [GitHub Issues](https://github.com/OtterMind/Chat2DB/issues).
- For questions, setup help, and open-ended discussions, please use [GitHub Discussions](https://github.com/OtterMind/Chat2DB/discussions).
- If your pull request is related to an issue, please link it in the PR description.

## Community and Support

- GitHub Issues: [report a bug or request a feature](https://github.com/OtterMind/Chat2DB/issues)
- GitHub Discussions: [ask questions and share ideas](https://github.com/OtterMind/Chat2DB/discussions)
- Discord: [join our Discord server](https://discord.gg/uNjb3n5JVN)
- Email: Chat2DB@ch2db.com

## Acknowledgments

Thanks to everyone who has contributed to Chat2DB.

<a href="https://github.com/OtterMind/Chat2DB/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=OtterMind/Chat2DB" alt="Chat2DB contributors" />
</a>

## License

Chat2DB Community version 5.3.0 and later is available under the
[license terms in this repository](./LICENSE). This is a source-available
license based on the Apache License 2.0 with additional conditions. Chat2DB
releases published before version 5.3.0, including version 0.3.7 and the
earlier historical tags, remain under the Apache License 2.0.
