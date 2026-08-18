# Chat2DB Codex、Hermes 外部 Agent Runtime 实施计划

> 状态：MVP Implemented（可观测性增强项继续迭代）
> 适用范围：Chat2DB Community
> 目标分支：`feature/task-agent-runtime`
> 最后更新：2026-08-15
> 上位规划：[`agent-runtime-task-artifact-plan.md`](./agent-runtime-task-artifact-plan.md)

## 0. 实施进度

截至 2026-08-15，Phase 3.1 已完成控制面、Daemon 调度、事件接收和运行终态收敛的纵向切片：

- [x] 新增 `EMBEDDED`、`EXTERNAL_DAEMON` Runtime Transport；
- [x] 新增 `SPRING_AI`、`CODEX`、`HERMES` Runtime Provider；
- [x] 建立 Runtime Profile 与 Runtime Instance 领域模型及独立存储契约；
- [x] 增加 H2 V10 migration，持久化 Profile、实例、能力、并发和心跳；
- [x] 建立 Profile 创建、更新、查询及乐观锁校验；
- [x] 建立 Daemon 幂等注册、Heartbeat、容量校验和超时离线判定；
- [x] Runtime Instance 槽位由控制面 Claim/终态事务维护，Heartbeat 不覆盖 `activeRuns`；
- [x] 增加用户侧 Profile/Instance API；
- [x] 增加使用独立 Bearer Token 保护的 Daemon 注册与心跳 API；
- [x] AgentRun 固化 Runtime Profile、Provider 与配置快照，避免执行期间配置漂移；
- [x] 建立原子 claim、实例并发槽位占用、lease token 哈希存储和续租；
- [x] 建立独立 lease attempt fencing，不复用现有 Run 轮次 `attempt`；
- [x] Daemon claim 后进入 `DISPATCHED`，Provider 启动 ACK 后才进入 `RUNNING`；
- [x] 建立外部 Runtime Event 的幂等写入、attempt fencing 和单调 sequence 校验；
- [x] 建立 complete、fail、cancel-ack 终态回传及重复 ACK 幂等；
- [x] 终态提交原子更新 Run、Lease、终态 Event，并释放 Runtime Instance 槽位；
- [x] 外部 Run 取消改为控制面请求、Daemon 轮询感知并 ACK，不再提前伪造终态；
- [x] 定时回收过期 Lease：未启动 Run 安全重排队，已启动 Run 收敛为 `UNKNOWN`；
- [x] 对 Authorization、Run Lease Header 和响应 token 字段启用日志脱敏；
- [x] Task-scoped token 已在服务端绑定 Run、Task、Agent、任务创建者、Runtime Instance、lease attempt 与过期时间；仅允许已启动且仍在运行的当前 Lease 使用，终态或过期后立即失效；
- [x] 增加 Runtime 专用 SQL Tool Gateway HTTP 入口，丢弃调用方传入的 Agent 上下文，并由服务端授权结果注入 `runId` 后复用现有 DataScope、Capability、Proposal、Approval 和 ToolAttempt 链路；
- [x] 以 Fake Runtime 覆盖 claim、start、task token、event、complete、Artifact 和槽位释放的完整领域协议生命周期；
- [x] 覆盖领域服务、H2 重开持久化、状态时机、事件顺序、Controller 身份边界和 Daemon Token focused tests。
- [x] 新增独立 `chat2db-community-agent-runtime` 模块和可执行 Codex Daemon，支持注册、心跳、领取、续租、取消轮询、事件回传和终态收敛；
- [x] 按本机 Codex CLI 0.147.0 生成的 v2 schema 实现 app-server JSON-RPC：`initialize`、`thread/start|resume`、`turn/start|interrupt`；
- [x] 转换 Codex 消息、Tool Call/Result、Usage、Session/Turn ID，并分类协议错误、进程退出、无活动超时和取消；
- [x] 增加每个 Run/lease attempt 独立工作目录、最小进程环境、可执行文件解析和安全清理；
- [x] 修复 Runtime Event 响应缺少最新 Lease revision、导致真实 Daemon 无法安全提交后续终态的问题。
- [x] 增加 Task-scoped Streamable HTTP MCP JSON-RPC Endpoint，支持握手、工具发现、调用和 Ping；每次调用复用短期 Token 授权并由服务端注入 Run/DataScope；
- [x] Claim 返回 MCP Endpoint 描述，Daemon 只将 Task Token 注入当前 Codex 子进程环境，app-server 通过 `mcp_servers.*.bearer_token_env_var` 使用；
- [x] Provider 进程启动后先 ACK `RUNNING` 再初始化 MCP，Codex Thread ID 通过 `SESSION_UPDATED` 事件独立持久化，避免放宽启动前 Token 边界。
- [x] Codex Thread ID 已作为 `AgentRun.providerSessionId` 通过 H2 V14 持久化；兼容的后续 Run 在 Profile 开启 Session Resume 时通过 Claim 恢复父 Run Thread；Profile、Provider、Agent 或 Snapshot 变化时不会错误继承；
- [x] Daemon 续租支持响应丢失后的 revision 重同步，并在最后确认的 Lease 到期前容忍 Chat2DB 短暂重启；硬过期后仍由 fencing 与 reconciliation 收敛。
- [x] Daemon 执行器已抽象为 Provider 无关的 Lease 执行层，可通过 `CHAT2DB_AGENT_RUNTIME_PROVIDER=CODEX|HERMES` 注册对应实例；
- [x] 基于 Silieco 当前 Hermes Backend 与本机 Hermes 0.18.2 核对 ACP 链路，实现 `initialize`、`session/new|resume`、`session/prompt|cancel`；
- [x] Hermes Adapter 已转换消息、推理、Tool Call/Result、Usage、Session ID 和 Task-scoped HTTP MCP，并覆盖 Fake ACP 协议测试；
- [x] Hermes 子进程强制移除 YOLO/Hook 自动批准环境并拒绝相关参数；ACP 权限请求通过持久化 Approval Bridge 暂停原 Session，由用户批准或拒绝后再恢复；
- [x] 增加独立 `AgentRuntimeApproval`、H2 V15 migration、用户决策 API 和 Daemon request/status/ack API；Run 在 Provider 消费决策前保持 `WAITING_APPROVAL`，租约续期与 fencing 持续有效；
- [x] Approval Bridge 覆盖请求幂等、内容冲突、用户身份绑定、乐观锁、重启后读取、Fake Control Plane 和同一 Hermes ACP Session 恢复测试。
- [x] 定义外部 Runtime Artifact Manifest 与完成前上传 API；校验类型、MIME、5 MiB 大小、SHA-256、固定工作区 sidecar、文件名和 lease attempt；
- [x] 文件型 Artifact 仅接受内联 Base64 并保存到 Chat2DB H2 Artifact Version，不接收或暴露 Runtime 本地路径；
- [x] 显式 Manifest 按同一 Run/类型幂等并优先于中央提取；冲突内容拒绝，全部上传成功后 Daemon 才提交 Run 完成；
- [x] Manifest Evidence 只接受当前 Run 的成功 ToolAttempt 引用，并按 Task DataScope 校验；范围收紧后历史 Evidence 保留但标记失效；归档保留只读产出物，永久删除级联清理。
- [x] 增加 H2 V16 Gateway Transport Bridge：Channel、外部 Conversation 绑定、InboundMessage、DeliveryCommand、一次性 Gateway Token、入站幂等和 Delivery Outbox；
- [x] 飞书、钉钉复用同一标准化传输契约；Gateway 只负责验签/解密后的消息转发与回复投递，不直接启动第二个 Hermes Turn；
- [x] Delivery Outbox 支持领取租约、线程回复、幂等回执、指数退避、最多 5 次尝试和死信；任务/会话归档或删除后返回明确链接状态；
- [x] Daemon 持久化本机 Provider 进程登记；重启时以 PID、启动时间和可执行文件名三重核验，清理确认属于自己的孤儿进程，并保留身份不一致的隔离记录以避免 PID 复用误杀。
- [x] Community Desktop 启动时自动发现本机 Codex/Hermes，使用仅驻留当前进程的内部 Daemon Token 启动多 Provider Runtime，不要求用户配置 Profile ID；
- [x] 新增用户侧 Runtime Option 聚合接口，自动建立幂等默认 Profile，并返回安装、在线、版本和容量状态；
- [x] Agent 编辑器改为 Silieco 风格 Runtime 卡片，使用 Codex/Hermes 标识并覆盖加载、空态、错误态与自动刷新。

MVP 不尝试跨 Daemon 重新附着旧 Provider 的 stdin/stdout；进程所有权无法安全证明时不会接管。
已启动 Run 在 Lease 过期后仍按结果不确定语义收敛为 `UNKNOWN`，用户重试产生新 Run 时才使用已持久化的 Provider Session ID 恢复会话。
尚未完成的工作集中在指标聚合、Session Resume 拒绝后的显式降级策略和更严格的版本兼容矩阵。
当前 Task-scoped MCP Endpoint 已复用 Runtime Task Token 授权服务，没有回退到通用桌面
MCP 身份。现有 `AgentRuntimeTypeEnum.EXTERNAL_AGENT` 暂时作为前端与
AgentDefinition 的兼容字段保留；调度层已经改用独立的 Transport、Provider 和不可变 Run Snapshot。

## 1. 背景与目标

当前 Chat2DB 已建立 Agent、Task、Run、Event、Context、Approval、ToolAttempt 和 Artifact 控制面，并以 Spring AI 作为内置 Runtime。下一阶段需要接入 Codex 和 Hermes，使其成为与 Spring AI 并列的 Agent 执行能力，同时保持现有数据权限、审批、任务状态和产出物语义。

本计划参考 Silieco 的统一 Agent Backend、Runtime Daemon、任务领取与 Channel 分层，但不复制其完整协作平台。

目标包括：

- Spring AI 继续作为进程内 Runtime；
- Codex 通过结构化 app-server 协议执行 AgentRun；
- Hermes 通过 ACP 协议执行 AgentRun；
- 外部进程由独立 Runtime Daemon 管理，而不是由 Spring Web 请求线程长期持有；
- Chat2DB 始终是 Task、Run、审批、上下文、工具执行和 Artifact 的唯一事实来源；
- 后续可利用 Hermes Gateway 接入飞书、钉钉等 IM，但 Gateway 只承担消息传输职责；
- 保持 Community 离线优先、loopback、本地存储和最小权限约束。

## 2. 非目标

本阶段不实现：

- 多节点分布式调度；
- 通用 Workflow Designer；
- 多 Agent Squad 或 Autopilot；
- Enterprise Gateway、云账户或商业授权；
- 让外部 Runtime 直接读取 Chat2DB 数据库或数据源凭证；
- 同一条 IM 消息同时由 Hermes Gateway 和 Chat2DB Tasker 独立执行；
- 将 Hermes 的自动批准模式作为生产默认行为。

## 3. 核心设计原则

### 3.1 控制面只有一个

Chat2DB 控制面负责：

- AgentDefinition 和 RuntimeProfile；
- AgentTask、AgentRun 和合法状态转换；
- Context Snapshot；
- DataScope、Capability 和 Tool Policy；
- Approval、ToolAttempt 和审计记录；
- Artifact、Artifact Version 和 Evidence；
- Conversation、IM 路由绑定和消息投递状态；
- 失败恢复、重试、取消和最终结果收敛。

Runtime 只能报告执行事实，不能直接更新 Task、Run、Approval 或 Artifact 数据表。

### 3.2 执行协议与 Provider 分离

避免为每个 CLI Agent 扩展一个顶层 Runtime 类型。建议拆分为：

```text
Runtime Transport
|- EMBEDDED
`- EXTERNAL_DAEMON

Runtime Provider
|- SPRING_AI
|- CODEX
`- HERMES
```

`RuntimeTransport` 决定任务如何调度，`RuntimeProvider` 决定如何调用具体 Agent。

### 3.3 IM Gateway 与 Agent Runtime 分离

Hermes 的两个能力必须分开：

- `hermes acp`：Agent 执行 Runtime；
- `hermes gateway`：飞书、钉钉等消息平台连接器。

Gateway 不应绕过 Tasker 直接执行同一条任务，否则会产生两份会话、重复回复、审批失效和状态不一致。

## 4. 目标架构

```text
Chat / Task Board / External IM
              |
              v
       Chat2DB Control Plane
       |- Conversation
       |- AgentTask / AgentRun
       |- Context / Approval
       |- Tool Gateway
       |- Artifact Store
       `- Delivery Outbox
              |
              +--------------------------+
              |                          |
              v                          v
      Embedded Spring AI          Runtime Daemon
                                  |- Codex Adapter
                                  `- Hermes ACP Adapter
                                           |
                                           v
                                 Task-scoped MCP Tools

External IM inbound/outbound:

Feishu / DingTalk
       |
       v
Hermes Gateway Transport Bridge
       |
       v
Chat2DB Conversation / Delivery Outbox
```

## 5. Runtime Profile 与实例模型

### 5.1 Runtime Profile

Runtime Profile 是可配置的执行模板，建议包含：

```text
id
name
transport
provider
executable
model
workingDirectoryPolicy
customArguments
environmentReferences
mcpConfiguration
timeoutSeconds
maxConcurrency
thinkingMode
serviceTier
sessionResumeEnabled
approvalBridgeEnabled
enabled
createdAt
updatedAt
revision
```

约束：

- 不在 Profile 中保存明文凭证；
- `customArguments` 经过 Provider allowlist/denylist 校验；
- executable 必须来自明确配置或受信任路径；
- 环境变量使用安全引用，不在日志和事件中展开；
- Runtime Profile Snapshot 写入 AgentRun，避免执行期间配置变化影响历史语义。

### 5.2 Runtime Instance

每个活跃 Daemon 注册为 Runtime Instance：

```text
id
daemonId
provider
providerVersion
protocolVersion
capabilities
maxConcurrency
activeRuns
status
lastHeartbeatAt
registeredAt
```

状态建议：

```text
ONLINE
DEGRADED
OFFLINE
DISABLED
```

## 6. AgentRun 执行协议

### 6.1 最小接口

```text
POST /api/agent/runtime/daemon/instances/register
POST /api/agent/runtime/daemon/instances/{instanceId}/heartbeat
POST /api/agent/runtime/daemon/instances/{instanceId}/runs/claim
POST /api/agent/runtime/daemon/runs/{runId}/started
POST /api/agent/runtime/daemon/runs/{runId}/events
POST /api/agent/runtime/daemon/runs/{runId}/artifacts
POST /api/agent/runtime/daemon/runs/{runId}/approvals/request
POST /api/agent/runtime/daemon/runs/{runId}/approvals/status
POST /api/agent/runtime/daemon/runs/{runId}/approvals/ack
POST /api/agent/runtime/daemon/runs/{runId}/complete
POST /api/agent/runtime/daemon/runs/{runId}/fail
POST /api/agent/runtime/daemon/runs/{runId}/cancel-ack
POST /api/agent/runtime/daemon/runs/{runId}/lease/renew

POST /api/agent/gateway/channels
GET  /api/agent/gateway/channels
POST /api/agent/gateway/channels/{channelId}/inbound
POST /api/agent/gateway/channels/{channelId}/deliveries/claim
POST /api/agent/gateway/channels/{channelId}/deliveries/{deliveryId}/receipt
```

接口路径是实施建议，最终应遵循现有 Web API 命名规范。

### 6.2 Claim 响应

Daemon 领取任务后获得：

```json
{
  "runId": "run-id",
  "taskId": "task-id",
  "attempt": 1,
  "leaseToken": "opaque-token",
  "leaseExpiresAt": "2026-08-15T10:00:00Z",
  "taskScopedToken": "opaque-token",
  "runtimeProfileSnapshot": {},
  "contextSnapshot": {},
  "outputContract": {},
  "mcpEndpoints": []
}
```

### 6.3 租约与 fencing

- Daemon 领取 Run 时生成新的 `attempt` 和 `leaseToken`；
- 只有当前 attempt 可以续租和上报事件；
- lease 过期后，旧进程上报的事件必须被拒绝；
- Run 是否重新排队取决于错误类型和工具执行状态；
- 写操作结果为 `UNKNOWN` 时禁止自动重试；
- Runtime Instance 失联不直接等同于任务失败，应先进行 lease/orphan reconciliation。

当前单机实现每 15 秒扫描一批过期 Lease。Provider 尚未 ACK `started` 的 Run 会释放槽位并
安全返回 `QUEUED`，下一次 claim 使用递增的 lease attempt；已经 ACK 启动的 Run 因执行结果
可能不确定而进入 `UNKNOWN`，不会自动重试。已经收到取消请求的过期 Lease 收敛为 `CANCELLED`。

### 6.4 状态转换

```text
QUEUED
  | claim 成功
  v
DISPATCHED
  | Runtime 进程启动并 ACK
  v
RUNNING
  |- WAITING_APPROVAL
  |- COMPLETED
  |- FAILED
  |- CANCELLED
  `- UNKNOWN
```

不得在 Daemon 尚未启动 Provider 进程前将 Run 标记为 `RUNNING`。

Task 状态继续由控制面根据 Run 状态、输出契约和人工操作进行收敛：

- Run 开始后 Task 可进入 `IN_PROGRESS`；
- Approval 未处理时 Task 可保持 `IN_PROGRESS` 或显式展示等待审批；
- Run 完成且满足输出契约后 Task 进入 `IN_REVIEW`；
- 只有人工确认或明确策略才能将 Task 置为 `DONE`。

## 7. Runtime Event

事件建议包含：

```text
RUN_STARTED
MESSAGE_STARTED
MESSAGE_DELTA
MESSAGE_COMPLETED
TOOL_CALL_REQUESTED
TOOL_CALL_STARTED
TOOL_CALL_COMPLETED
TOOL_CALL_FAILED
APPROVAL_REQUIRED
SESSION_UPDATED
USAGE_UPDATED
ARTIFACT_PRODUCED
RUN_COMPLETED
RUN_FAILED
RUN_CANCELLED
```

每个事件至少携带：

```text
eventId
runId
attempt
sequence
eventType
occurredAt
payload
```

幂等规则：

- `(runId, attempt, eventId)` 唯一；
- 同一 attempt 的 `sequence` 单调递增；
- 重复事件返回成功但不重复产生副作用；
- 旧 attempt 事件返回明确的 stale lease 错误；
- SSE 只是事件展示通道，持久化 Event Store 才是恢复来源。

## 8. Runtime Daemon

Daemon 的职责：

- 发现本机 Codex/Hermes 可执行文件和版本；
- 注册能力并定期心跳；
- 按可用并发槽领取 Run；
- 创建任务隔离工作目录；
- 启动、监控和终止 Provider 进程；
- 将 Provider 协议转换为统一 Runtime Event；
- 保存必要的进程引用和 Session ID；
- 续租并处理取消请求；
- 上报 Usage、Artifact Manifest 和最终结果；
- 启动时扫描并协调孤儿执行。

Daemon 不负责：

- 直接修改 Task 状态；
- 绕过 Tool Gateway 访问数据库；
- 判断用户是否拥有数据权限；
- 将任意本地文件路径直接暴露给前端；
- 保存 Chat2DB 主数据库凭证。

Community 默认只允许 loopback 或本地 IPC 访问 Daemon。Task-scoped token 应短期有效，并绑定 `taskId`、`runId`、`attempt`、工具范围和 DataScope。

## 9. Codex Adapter

Codex Adapter 使用：

```text
codex app-server --listen stdio://
```

而不是解析终端自然语言文本。

首版能力：

- JSON-RPC 初始化和协议版本校验；
- 创建或恢复 Thread/Session；
- 发送不可变 Context Snapshot；
- 转换流式消息、工具调用和 Usage；
- 保存 Codex Thread ID；
- 支持 Cancel；
- 进程退出、协议错误和无活动超时分类；
- 清理子进程和任务工作目录中的临时资源。

上下文输入可以包含工作目录范围内的 `AGENTS.md`，但必须明确其来源和优先级。Chat2DB 的 DataScope、Approval 和 Tool Policy 不能被工作目录指令覆盖。

## 10. Hermes ACP Adapter

Hermes Adapter 使用：

```text
hermes acp
```

首版能力：

- ACP JSON-RPC 初始化；
- `session/new` 和 Session 恢复；
- MCP Server 配置转换；
- 消息、工具调用、Tool Result 和 Token Usage 转换；
- Provider 错误和进程退出分类；
- Cancel 和超时处理；
- 每任务独立工作目录及必要的 Hermes 状态隔离。

禁止默认设置：

```text
HERMES_YOLO_MODE=1
```

Hermes 工具权限请求必须进入 Chat2DB Approval Bridge：

```text
Hermes permission request
        |
        v
AgentRuntimeApproval / WAITING_APPROVAL
        |
        v
用户批准或拒绝
        |
        v
恢复原 Hermes Session
```

`AgentRuntimeApproval` 与 SQL Proposal 使用的 `AgentApproval` 分表保存，避免把 Provider
权限选择伪装为 SQL Proposal。Daemon 等待期间继续续租；用户决策后 Run 仍保持
`WAITING_APPROVAL`，只有 Daemon ACK 已将选项回复给 ACP 请求后才回到 `RUNNING`。
当前租约终结或过期时，未决 Runtime Approval 会变为 `EXPIRED`。

如果当前 ACP 版本无法安全暂停和恢复某类工具请求，该能力应标记为不支持，而不是自动批准。

## 11. Tool Gateway 与 MCP

MCP 只承担工具调用协议，不承担任务领取和 Run 调度。

外部 Runtime 通过 Task-scoped MCP Endpoint 调用工具。每次调用必须重新校验：

```text
当前用户权限
INTERSECT Agent Capability
INTERSECT Task DataScope Snapshot
INTERSECT Runtime Token Scope
INTERSECT Tool Safety Policy
```

安全要求：

- 不向 Runtime 传递数据源密码；
- SQL 仍通过现有 AgentToolGateway；
- ToolAttempt 使用幂等键；
- 写操作继续执行 Proposal 和 Approval 流程；
- Tool Result 对行数、字段和敏感内容进行限制；
- Task-scoped token 不可用于其他 Run、Task 或用户；
- MCP 审计事件和 Runtime Event 建立关联。

## 12. Artifact 协议

### 12.1 保留中央提取

继续保留现有逻辑：

- 最终 Markdown 生成 `REPORT` Artifact；
- 支持的 Chart Spec 生成 `CHART`；
- 查询数据生成 `DATA_TABLE`；
- Artifact 必须具备 Evidence；
- Output Contract 决定是否可以进入 `IN_REVIEW`。

### 12.2 显式 Artifact Manifest

外部 Runtime 可以额外上报：

```json
{
  "artifactId": "client-generated-id",
  "type": "REPORT",
  "title": "本月渠道类型分析",
  "mimeType": "text/markdown",
  "size": 1234,
  "sha256": "...",
  "content": "...",
  "evidence": []
}
```

文件型 Artifact 必须先上传或复制到 Chat2DB 管理的存储，再确认 Run 完成。禁止将任意 Runtime 本地绝对路径作为用户可访问链接。

当前实现约定 Provider 在固定任务工作目录写入 `.chat2db-artifacts.json` JSON 数组，最多
5 个 Manifest。Daemon 读取时拒绝符号链接和非普通文件，并逐个调用 lease-fenced Artifact
上传 API。结构化 Artifact 使用 UTF-8 `content`；`FILE` 使用 `contentBase64`，解码后最多
5 MiB，并直接保存到 Chat2DB 管理的 H2 Artifact Version。全部 Manifest 入库成功后才允许
提交 Run 完成。

校验包括：

- 类型、大小和 MIME allowlist；
- 哈希完整性；
- 文件名净化；
- Artifact 与当前 runId/attempt 绑定；
- Evidence 对象仍在 Task DataScope 内；
- 删除、归档和权限变化后的访问行为明确。

同一 Run 每种 Artifact 类型只保留一个结果：显式 Manifest 优先；之后的中央 Markdown/Chart
提取只补充缺失类型。相同客户端 Artifact ID 与哈希可安全重试，复用类型但内容不同会被拒绝。
任务归档后 Artifact 对任务所有者继续只读可见；永久删除任务时级联删除 Artifact；权限范围
变化不会篡改历史 Evidence，但读取时会返回 `valid=false` 和失效原因。

## 13. Hermes Gateway 与外部 IM

### 13.1 推荐模式：Transport Bridge

Hermes Gateway 负责：

- 飞书、钉钉等平台连接；
- 平台事件验签和解密；
- 标准化入站消息；
- 根据 Delivery Command 发送回复；
- 返回平台 messageId 和投递结果；
- 平台级重连、限流和错误处理。

Chat2DB 负责：

- Conversation 和消息历史；
- 结构化 `@Agent` 或默认 Agent 路由；
- AgentTask/AgentRun 创建；
- Runtime 选择；
- 最终回复内容；
- Delivery Outbox、幂等和重试状态；
- Task、Run 和 Artifact 快捷链接。

标准化入站消息建议包含：

```text
platform
installationRef
chatId
threadId
messageId
eventId
senderId
senderDisplayName
text
mentions
attachments
receivedAt
idempotencyKey
```

当前实现由用户创建 Channel，并只在创建响应中返回一次 Gateway Token；后续 Gateway 请求通过
`X-Chat2DB-Agent-Gateway-Token` 认证，数据库只保存 SHA-256。飞书和钉钉都提交上述标准化消息，
平台验签、解密、凭证和网络重连留在 Hermes Gateway 本地 Profile。Chat2DB 根据
`(channelId, chatId, threadId)` 绑定 Conversation，根据 `(channelId, idempotencyKey)` 去重，
再复用 `AgentChatTaskService` 创建唯一 Task/Run。

Gateway 领取 DeliveryCommand 时，Chat2DB 才将已终态 Run 转为最终回复；同一 InboundMessage
最多产生一条 Outbox 记录。领取使用 60 秒投递租约，失败按退避时间重试，达到 5 次进入
`DEAD_LETTER`。平台回执保存 messageId；重复回执不会产生第二条回复。

Delivery Command 建议包含：

```text
deliveryId
platform
installationRef
chatId
threadId
replyToMessageId
content
attachments
idempotencyKey
```

### 13.2 禁止双重执行

以下链路禁止作为 Tasker 托管模式：

```text
IM -> Hermes Gateway -> Hermes 自主执行并回复
                    `-> Chat2DB 同时创建 Task
```

它会造成：

- 重复回复；
- Hermes Session 与 Chat2DB Conversation 分叉；
- Tasker 无法可靠取消任务；
- Approval 和 DataScope 被绕过；
- Artifact 未进入 Chat2DB；
- 两边状态无法确定谁是最终结果。

### 13.3 可选的非托管模式

如果产品确实需要 Hermes 完全自主处理 IM，应将其标记为独立的 `UNMANAGED` 集成模式，并在 UI 中明确：

- Tasker 不拥有运行状态；
- 不支持统一审批和可靠取消；
- Hermes 会话不等于 Chat2DB Conversation；
- Artifact 和 Usage 可能不可用；
- 不提供受 Tasker 管理的 SLA。

该模式不属于本计划首版范围。

## 14. Conversation、Task 与 IM 绑定

建议增加可追溯关系：

```text
Conversation
|- sourceType: CHAT2DB | FEISHU | DINGTALK | ...
|- externalInstallationRef
|- externalChatId
`- externalThreadId

ChatMessage
|- messageType
|- taskId
|- agentId
|- agentName
`- externalMessageId

AgentTask
|- originSessionId
|- originMessageId
`- sourceContextSnapshot
```

归档和删除行为：

- Conversation 归档后，Task 仍保留上下文快照；
- Task 中的来源链接显示“对话已归档”，由用户确认后再打开；
- Conversation 删除后不再跳转，显示“来源对话已删除”；
- Task 删除或归档后，聊天中的任务卡片保留文字记录，但禁止进入无效页面；
- 外部 IM 消息删除不影响已经固化的 Task Context Snapshot。

## 15. 安全与 Community 边界

- Runtime Daemon 默认绑定 `127.0.0.1` 或使用本地 IPC；
- 不通过 artifact name、端口或路径推断 Community Runtime；
- Community 后端继续使用 `chat2db.runtime.mode=community`；
- 核心离线功能不依赖外部 Gateway；
- 启用飞书/钉钉意味着用户明确配置外部网络连接；
- IM 凭证优先保留在本地 Gateway Profile 中，Chat2DB 只保存不透明引用和健康状态；
- 所有日志对 Token、密钥、Cookie、Prompt 敏感内容和数据库结果进行脱敏；
- Provider 子进程使用最小环境变量和任务隔离目录；
- 禁止 Runtime 任意读取用户主目录或其他项目目录；
- 取消任务时只终止当前 Tasker 启动并记录的进程树。

## 16. 分阶段实施

### Phase 3.1：外部 Runtime 控制面

- [x] 将 transport 与 provider 概念分离；
- [x] 增加 RuntimeProfile 和 RuntimeInstance；
- [x] 增加注册、心跳和能力协商；
- [x] 增加 claim、lease、attempt fencing 和续租；
- [x] 调整 `DISPATCHED`、`RUNNING` 状态时机；
- [x] 增加事件幂等和 sequence 校验；
- [x] 增加 complete、fail、cancel-ack 和终态幂等；
- [x] 增加取消请求下发、槽位释放和过期 Lease 协调；
- [x] 增加 task-scoped token；
- [x] 增加 Runtime 健康状态 API；
- [x] 以 Fake Runtime 验证完整生命周期（领域协议；独立进程 HTTP E2E 留待 Daemon 实现）。

### Phase 3.2：Codex MVP

- [x] 实现 Codex app-server Adapter；
- [x] 支持消息流、Session ID、Usage 和 Cancel；
- [x] 支持任务工作目录和上下文输入；
- [x] 接入 Task-scoped MCP；
- [x] 接入最终 Report Artifact；
- [x] 验证进程异常、超时和取消；
- [x] 验证 Chat2DB 重启后的 Session/孤儿 Run 恢复。

### Phase 3.3：Hermes ACP MVP

- [x] 实现 Hermes ACP Adapter；
- [x] 支持 Session、消息、Tool Call 和 Usage；
- [x] 转换 MCP 配置；
- [x] 实现 Approval Bridge；
- [x] 确认未启用 YOLO；
- [x] 支持取消、超时和 Provider 错误分类；
- [x] 验证任务级工作目录隔离；Hermes Profile 状态由专用 `HERMES_HOME` 引用隔离。

### Phase 3.4：Artifact 增强

- [x] 定义 Artifact Manifest；
- [x] 增加文件上传和哈希校验；
- [x] 建立显式 Artifact 与中央提取结果的去重规则；
- [x] 确保上传完成后才能终结 Run；
- [x] 覆盖删除、归档、权限变化和 Evidence 失效行为。

### Phase 3.5：Hermes Gateway IM Bridge

- [x] 定义 Channel、InboundMessage 和 DeliveryCommand 契约；
- [x] 增加 Conversation 与外部聊天绑定；
- [x] 增加入站事件幂等；
- [x] 增加 Delivery Outbox 和投递回执；
- [x] 以 Fake Feishu Transport 完成控制面端到端验证；
- [x] 飞书和钉钉复用标准化 Transport Bridge 契约；
- [x] 验证归档、删除、重试、重复事件和线程回复。

### Phase 3.6：可靠性与可观测性

- [x] Daemon 崩溃与孤儿 Run 恢复；旧进程清理后由 Lease reconciliation 收敛，不跨进程接管 stdio；
- [x] Provider 进程泄漏检测；使用 PID、启动时间和可执行文件三重核验；
- [x] Runtime 容量、版本和健康度查询 API；
- [ ] Run 事件延迟、租约过期和失败分类指标；
- [x] Delivery Outbox 重试和死信处理；
- [ ] Session 恢复失败的降级策略；
- [ ] Runtime/Profile 兼容版本检查。

## 17. MVP 范围

建议首个可交付版本只包含：

- 单机单 Daemon；
- Codex 和 Hermes 本地 Runtime Profile；
- claim、start、event、complete、fail、cancel；
- lease 和 attempt fencing；
- Session ID 持久化；
- Task-scoped MCP；
- 最终 Markdown Report Artifact；
- Runtime 健康状态展示。

首个 MVP 不包含 IM Gateway。应先证明外部 Runtime 的任务、状态、审批和产出物闭环，再接入飞书或钉钉。

## 18. 验收标准

### 18.1 生命周期

- Daemon 未在线时 Run 保持 `QUEUED`，前端展示 Runtime 离线；
- Daemon claim 后 Run 进入 `DISPATCHED`；
- Provider 启动 ACK 后进入 `RUNNING`；
- 页面刷新和 SSE 重连后可从 Event Store 恢复进度；
- Cancel 能终止对应进程并收敛为 `CANCELLED`；
- Daemon 失联后旧 attempt 不能继续写入事件；
- 可安全重试的 Run 能被重新领取；
- 结果不确定的写操作不自动重试。

### 18.2 Codex/Hermes

- 两种 Provider 都能消费同一份 AgentRunRequest；
- 消息、Tool Call、Usage 和 Session ID 均转换为统一事件；
- Provider 不获得数据源凭证；
- SQL 工具调用经过现有 Scope 和 Approval 校验；
- Hermes 未开启自动审批；
- Runtime 退出后不遗留无主进程。

### 18.3 Artifact

- 最终回答可生成 Report Artifact；
- 文件上传前后哈希一致；
- Artifact 与 Run、Evidence 可追溯；
- 缺少 Output Contract 要求的 Artifact 时 Task 不进入 `IN_REVIEW`；
- 无效本地路径不会暴露给前端。

### 18.4 IM Bridge

- 同一平台事件重复投递只创建一次消息和一次 Task/Run；
- 一个 Run 只产生一条最终 IM 回复；
- Chat2DB 是 Conversation 和 Task 状态事实源；
- Gateway 失败时 Delivery Outbox 可以安全重试；
- Task/Conversation 删除或归档后链接显示明确状态；
- Gateway 不对 Tasker 托管消息自主启动第二个 Hermes Turn。

## 19. 验证计划

### 后端 focused tests

- Runtime Registry 和 Profile 兼容性；
- Run claim 并发唯一性；
- lease 续期、过期和 attempt fencing；
- Event 幂等、顺序和旧 attempt 拒绝；
- Run/Task 状态机；
- task-scoped token 的 Task、Run、Tool 和 DataScope 限制；
- Approval 暂停与恢复；
- Artifact Manifest、哈希和 Evidence；
- IM 入站幂等和 Delivery Outbox。

### Adapter tests

- 使用 fake JSON-RPC/ACP process 测试 Codex/Hermes 协议；
- 握手超时、无活动超时、异常退出和 stderr 错误；
- Session 恢复成功与拒绝后的降级；
- Cancel 和进程树清理；
- 不依赖真实账户或外部 IM 凭证。

### Community 验证

- 执行相关 focused Maven tests，并确认非零测试数、零失败和零错误；
- 执行前端 focused test、lint 和 Community build；
- 构建 Community 后端；
- 以 OFFLINE、Community mode 和 `127.0.0.1:10825` 做本地 smoke；
- 检查 Daemon 和 Backend 均未暴露非 loopback listener；
- 执行 `git diff --check` 并审查最终 diff。

真实飞书、钉钉联调属于需要明确凭证和外部系统授权的单独验证，不作为无凭证本地测试的一部分。

## 20. 主要风险与缓解

| 风险 | 影响 | 缓解措施 |
| --- | --- | --- |
| Runtime 失联后旧进程继续运行 | 重复执行和重复回复 | lease、attempt fencing、孤儿进程协调 |
| Hermes 自动审批工具 | 绕过 SQL 和数据权限 | 禁止 YOLO，建立 Approval Bridge |
| Gateway 与 Tasker 同时执行 | 双会话、双回复 | Gateway 限定为 Transport Bridge |
| 外部进程获取过多权限 | 本地文件或数据泄露 | 任务目录、最小环境、task-scoped MCP |
| Artifact 引用本地路径 | 前端不可访问或越权 | 上传到受控存储、哈希和 MIME 校验 |
| IM 重复事件 | 重复任务和回复 | 平台 eventId/messageId 幂等 |
| Provider 协议升级 | Adapter 不兼容 | 协议版本协商和能力声明 |
| 写操作重试 | 重复数据变更 | UNKNOWN 终态和禁止自动重试 |

## 21. 实施前需要确认的决策

1. Runtime Daemon 使用 Java、Go 或其他独立实现；
2. Backend 与 Daemon 采用 loopback HTTP、WebSocket 还是本地 IPC；
3. Runtime Profile 凭证引用和本地配置的保存位置；
4. Codex/Hermes Session 状态的持久化和清理周期；
5. Artifact 文件大小、类型和保留策略；
6. 首个 IM 平台选择飞书还是钉钉；
7. Hermes Gateway 采用插件、Webhook 还是独立 Relay 接入 Chat2DB；
8. Conversation、Task 和外部消息删除后的保留策略；
9. Runtime 自动重试范围和最大 attempt；
10. Daemon 是否随 JCEF Community 桌面包一同分发。

## 22. 推荐决策

建议按以下顺序推进：

1. 先建立 Runtime Daemon 协议和 Fake Runtime；
2. 首个真实 Provider 接入 Codex；
3. 再接入 Hermes ACP 和 Approval Bridge；
4. 稳定任务状态、Session 和 Artifact 后，再接 Hermes Gateway；
5. Hermes Gateway 首版只作为 IM Transport Bridge；
6. 首版保持单机、loopback、单控制面，不引入分布式调度。

最终边界为：

> Spring AI、Codex 和 Hermes 是可替换的执行 Runtime；Chat2DB Tasker 是唯一控制面；Hermes Gateway 是可选的外部消息传输层。
