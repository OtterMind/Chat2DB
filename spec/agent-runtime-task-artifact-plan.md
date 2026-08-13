# Chat2DB Agent Runtime、Task 与 Artifact 设计规划

> 状态：Approved / In Progress
> 适用范围：Chat2DB Community
> 目标分支：`feature/task-agent-runtime`
> 最后更新：2026-08-12

## 0. 实施进度

截至 2026-08-12，已完成三个后端纵向切片，并开始 Phase 2 安全执行链路：

- [x] 建立 `AgentDefinition`、`AgentTask`、`AgentRun` 领域模型和服务/存储契约；
- [x] 建立 Agent Capability、DataScope、Runtime、Task/Run 状态枚举；
- [x] 创建 Task 时生成不可变 DataScope 快照，并禁止降低 Agent 的行数、超时、生产访问和审批约束；
- [x] 建立独立 H2 Agent Store 与 Flyway V1 migration；
- [x] 以单事务创建 Task 和首个 Run，并建立 Agent/Task/Run 外键和查询索引；
- [x] 覆盖领域权限边界、原子回滚、重启后读取和唯一名称约束的 focused tests；
- [x] 建立 Runtime SPI、能力描述、Runtime Registry 和 Spring AI Runtime 流式事件适配；
- [x] 建立 Run 合法状态机、时间戳语义和基于 revision 的 CAS 更新；
- [x] 将 Task DataScope 传入现有 AI Tool 调用链，并在数据源、库、Schema、表和 SQL 执行边界重新校验；
- [x] 建立 Runtime 调度协调器、Task/Run 独立状态收敛和取消入口；
- [x] 建立幂等、有序的 Run Event 持久化与重启读取，并提供基础 Context Assembler；
- [x] 建立绑定当前用户身份的 Agent、Task、Run Event 基础 Web API；
- [x] 建立 Artifact、不可变 Version、Evidence、SNAPSHOT/LIVE 领域模型和 H2 V3 migration；
- [x] 将 Run 最终消息归档为结构化 Report Artifact，并在 Task Detail 返回完整 Results；
- [x] 校验 Agent Output Contract，缺少必需 Artifact 或报告章节时不自动进入 `IN_REVIEW`；
- [x] 建立不可变 SQL Proposal、风险分类、Approval、ToolAttempt 状态与 H2 V4 migration；
- [x] 建立 AgentToolGateway，统一执行前 Scope/Capability/Approval 校验和 ToolAttempt 幂等；
- [x] 将 Agent `execute_sql` 接入 ToolGateway；写操作结果不确定时标记 `UNKNOWN` 并禁止自动重试；
- [x] 审批后重新组装控制面快照并恢复 Spring AI Run，Task Detail 同步返回 Proposal、Approval 和 ToolAttempt；
- [x] 从最终消息中的受支持 Chart Spec 自动提取有 SQL Evidence 的 `CHART` 和 `DATA_TABLE` Snapshot Artifact；
- [x] 建立 Artifact 到 Dashboard 的 Snapshot/Live 发布、副本来源引用和 H2 V5 migration；
- [x] Live Chart 刷新时重新校验当前用户、Task DataScope、数据源可见性和只读 SELECT；
- [x] 增加 Agent 创建与数据访问配置 UI，并接入 Agent 定义 API；
- [x] 增加 Chat 结构化 `@Agent`，按当前数据上下文收窄权限快照并创建 Task/Run；
- [x] 增加 Tasks 一级入口、四列 Board/List、Task Detail、Results、Approval、Runs 和 Activity；
- [x] 增加追加式 Pinned/Comment/Attachment Task Context、V6 migration、详情管理和 Runtime 上下文组装；
- [x] 以终态 Run 的 CAS 状态转换固化不可变 Run Summary，并限制 Context 组装为全部 Pinned、最近协作上下文和历史摘要；
- [x] 完成 Community 前端构建；
- [x] 完成包含 V6 的 Community 后端构建，以及 OFFLINE、`127.0.0.1:10825` 启动和 Agent API smoke。

本节仅记录已经由代码和测试验证的实现，不表示 Phase 1 或 Phase 2 已完成。当前
Spring AI 适配器、Agent/Task Web API、控制面 Run 生命周期、审批恢复、结构化产出、
Dashboard 发布、Agent 配置、Chat `@Agent`、Tasks UI 和追加式 Task Context 已打通。
Community 前端构建已通过；基线中不存在于锁定 Lucide 版本的 `SquareSquare` 与
`Columns3Cog` 已分别替换为语义相近且可用的 `PanelsTopLeft` 与 `Columns3`。

## 1. 背景

Chat2DB 当前已经具备基于 Spring AI 的模型调用、流式对话、Tool Callback、数据库上下文、AI SQL 查询工具、MCP Server、聊天历史和 Dashboard/Chart 等能力。

下一阶段需要把当前即时对话能力扩展为完整的 Agent 协作能力：

- 可以创建和配置不同角色的 Agent；
- Agent 只能访问被授权的数据集和工具；
- 用户可以在 Chat 中通过 `@Agent` 创建并执行任务；
- Task 拥有独立生命周期、看板、上下文、Run、审批和审计记录；
- 数据分析类 Task 能产生报告、指标、图表、数据表和文件等结构化产出物；
- Task 产出可以选择性发布到现有 Dashboard；
- 后续可以接入外部 Agent Runtime，而不替换现有 Spring AI 能力。

本设计借鉴 Silieco 的 Task/Run 分离、持久化状态、Agent Mention 和执行历史等思想，但不直接复制其完整协作平台、Daemon、Squad、Autopilot 和通用 Workflow。

## 2. 设计目标

### 2.1 产品目标

形成四个清晰的产品空间：

| 产品空间 | 核心职责 |
| --- | --- |
| Chat | 即时交流、选择数据上下文、`@Agent`、创建 Task、补充指令 |
| Tasks | Task 看板、详情、审批、进度、Run、上下文和产出管理 |
| Workspace | 数据库浏览、SQL 编辑和人工数据库操作 |
| Dashboard | 图表与指标的发布、组合和持续观察 |

### 2.2 技术目标

- Chat2DB 持有 Agent、权限、Task、Run、审批、工具执行和 Artifact 的最终状态；
- Spring AI 和外部 Agent 都通过统一 Runtime SPI 执行；
- 数据库是 Task 状态的事实来源，SSE/WebSocket 只用于降低界面更新延迟；
- 所有工具调用都经过统一的权限、风险、审批、幂等和审计入口；
- 页面刷新、SSE 断开和 Chat2DB 重启后仍能恢复 Task；
- 写操作在不确定执行结果时不得自动重试；
- Community 桌面运行继续保持离线优先、loopback 和本地存储约束。

### 2.3 非目标

第一阶段不实现：

- 通用项目管理平台；
- Gantt、Swimlane 和复杂项目计划；
- Squad 和多 Agent 自动编排；
- Autopilot 和外部事件调度；
- 通用 Workflow Designer；
- 多节点分布式 Runtime 调度；
- Enterprise Gateway、云账户、订阅或商业权限模型。

## 3. 核心设计决策

### 3.1 采用 Hybrid Runtime

不在 Spring AI 和外部 Agent 之间二选一。

Spring AI 继续作为首个内置 Runtime，未来增加外部 Runtime：

```text
Agent Control Plane
        |
        v
Agent Runtime SPI
  |- EMBEDDED_SPRING_AI
  `- EXTERNAL_AGENT
        |
        v
Chat2DB Tool Gateway
        |
        v
Datasource / Database / Schema / Table
```

Runtime 只负责执行一次 Run，不拥有 Task、权限和审批的最终状态。

### 3.2 Task 与 Run 分离

- `AgentTask` 表示持续存在的工作目标；
- `AgentRun` 表示 Agent 针对 Task 的一次具体执行；
- 一个 Task 可以有多个 Run；
- Run 完成不代表 Task 已完成；
- Agent 交付结果后，Task 通常进入 `IN_REVIEW`；
- 用户确认后，Task 才进入 `DONE`。

### 3.3 Chat、Task、Artifact 分工

```text
Chat     = 交互入口
Task     = 工作生命周期
Artifact = 可交付结果
Dashboard = 结果的发布与持续观察空间
```

普通聊天不应自动污染 Task 看板；结构化 `@Agent` 委派或显式“创建任务”才创建 Task。

### 3.4 新 AgentTask 不复用旧 Task

当前仓库中的 `Task` 用于导入、导出等后台传输任务，存储和生命周期均不满足 Agent Task 要求。

后端新模型使用明确命名：

- `AgentTask`；
- `AgentRun`；
- `AgentArtifact`；
- `AgentApproval`；
- `AgentToolAttempt`。

用户界面仍可显示为“Task / 任务”。

### 3.5 Artifact 是一等领域对象

数据分析 Task 不能只保存最终文本回复。报告、指标、图表、数据表和文件都应成为可版本化、可追溯和可发布的 Artifact。

## 4. 总体架构

```text
Frontend
  |- Chat / @Agent
  |- Task Board / Task Detail
  |- Approval UI
  |- Artifact Renderer
  `- Dashboard
          |
          v
Web Adapters
  |- Agent API
  |- Task API
  |- Runtime API
  |- SSE Events
  `- MCP Adapter
          |
          v
Domain Services
  |- AgentDefinitionService
  |- AgentAccessService
  |- AgentTaskService
  |- AgentRunService
  |- AgentContextAssembler
  |- AgentApprovalService
  |- AgentToolGateway
  `- AgentArtifactService
          |
          +------------------------+
          |                        |
          v                        v
Agent Runtime SPI             Storage Contracts
  |- SpringAiRuntime            |- Agent Store
  `- ExternalRuntime            |- Event Store
                                `- Artifact Store
          |
          v
Database Plugins / SQL Execution
```

模块边界遵循现有 Chat2DB 约束：

- `domain-api`：模型、请求、枚举、服务接口和存储接口；
- `domain-core`：权限、Task、Run、审批、Artifact 和工具业务编排；
- `storage`：内部 Agent Store 的持久化实现；
- `web`：HTTP、SSE、MCP 和 Runtime 接入适配；
- `client`：Chat、Task 看板、详情和 Artifact 展示；
- `jcef`：只提供桌面桥接，不承载 Agent 业务逻辑。

## 5. Agent Runtime

### 5.1 Runtime SPI

建议定义：

```java
public interface AgentRuntime {
    RuntimeType type();

    RuntimeCapabilities capabilities();

    AgentRunHandle start(
        AgentRunRequest request,
        AgentEventSink eventSink
    );

    void resume(
        AgentRunResumeRequest request,
        AgentEventSink eventSink
    );

    void cancel(String runId);
}
```

Runtime 能力描述至少包含：

- 是否支持流式消息；
- 是否支持工具调用；
- 是否支持中断；
- 是否支持审批后恢复；
- 是否支持会话恢复；
- 是否为外部进程；
- Runtime 版本。

### 5.2 Spring AI Runtime

第一阶段把现有 Spring AI 调用链封装为 `SpringAiAgentRuntime`：

- 保留当前模型提供商适配；
- 保留 ChatClient、流式响应和 Tool Callback；
- 将 SSE 直接输出改造成 Run Event；
- 将会话历史输入改造成 `AgentContextAssembler` 的结果；
- 所有工具调用改走 `AgentToolGateway`；
- Runtime 不直接更新 Task 最终状态。

### 5.3 外部 Agent Runtime

外部 Runtime 在后续阶段提供：

- Runtime 注册；
- Heartbeat；
- Run claim/dispatch；
- Task-scoped token；
- 消息和 Tool Call 上报；
- 取消和失联恢复；
- Runtime 能力协商。

MCP 负责工具协议，不同时承担 Run 调度协议。

外部 Agent 不获得数据源密码，不直接连接 Chat2DB 内部存储；它只能使用短期、Task 绑定、权限受限的能力 Token 调用 Tool Gateway。

## 6. Agent 定义

Agent 由五部分组成：

```text
Agent
|- Identity
|- Runtime Profile
|- Instructions
|- Tool Policy
`- Data Access Policy
```

### 6.1 基础字段

```text
id
name
avatar
description
status
runtimeType
runtimeProfileId
modelConfigId
systemPrompt
createdBy
createdAt
updatedAt
```

### 6.2 工具权限

初始能力建议包括：

```text
METADATA_READ
DATA_READ
DATA_WRITE
DDL
EXPORT
IMPORT
```

工具可见性和工具执行权限必须分开：Agent 看不到未授权工具，也不能通过构造请求绕过后端执行检查。

### 6.3 数据访问范围

数据范围支持层级授权：

```text
Datasource
  `- Database
      `- Schema
          `- Table
```

允许：

- 授权整个数据源；
- 限制到 database/schema/table；
- 显式排除敏感对象；
- 配置只读、最大结果行数和执行超时；
- 针对生产环境强制审批。

最终有效权限：

```text
当前用户权限
INTERSECT Agent 配置权限
INTERSECT Task 数据范围
INTERSECT Tool 安全策略
```

创建 Task 时检查一次，执行每次 Tool Call 时再次检查。Agent 配置不能扩大当前用户权限。

## 7. Chat 与 @Agent

### 7.1 一级入口

建议保留独立一级入口，但将用户可见名称从 `Stream` 调整为 `Chat` 或 `Agent Chat`。

内部 key 和 `/stream/:sessionId` 路由可暂时保留，以兼容现有历史链接。

一级导航建议为：

```text
Chat
Tasks
Workspace
Dashboard
```

### 7.2 交互模式

Chat 输入支持两种明确模式：

```text
ASK  = 仅询问
TASK = 委派任务
```

默认规则：

- 没有 Agent Mention：默认 `ASK`；
- 选择 `@Agent`：默认切换为 `TASK`；
- 用户可手动切回“仅询问”；
- 用户也可以把普通 Chat 结果“转为任务”。

### 7.3 结构化 Mention

不能通过正则解析显示文本来触发 Agent。

请求应携带：

```json
{
  "interactionMode": "TASK",
  "mentions": [
    {
      "type": "AGENT",
      "id": "agent-123",
      "label": "数据分析师"
    }
  ]
}
```

统一 Mention 模型：

```ts
interface ChatMention {
  type: 'AGENT' | 'TABLE';
  id: string;
  label: string;
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
}
```

输入下拉分组展示 Agents 和 Data Objects。

### 7.4 创建与继续规则

```text
新 Chat 中 @Agent
  -> 新建 AgentTask
  -> 新建 AgentRun

已有 Task 上下文中 @Agent
  -> 保持同一 AgentTask
  -> 新建 AgentRun
```

第一阶段一个 Task 只允许一个主执行 Agent。多个 Agent Mention 应提示用户选择主 Agent或拆分 Task。

### 7.5 防止自动触发循环

- 只有用户发送的结构化 Mention 可以直接创建 Task/Run；
- Assistant 文本中的 `@Agent` 只渲染，不触发；
- Agent 委派其他 Agent 必须调用正式的 `delegate_task` 工具；
- 委派经过权限、深度和去重检查；
- 第一阶段默认关闭 Agent-to-Agent 自动委派；
- Agent 委派不能扩大原 Task 的 DataScope。

## 8. Task 领域模型

### 8.1 AgentTask

建议字段：

```text
id
title
description
acceptanceCriteria
status
priority
assigneeAgentId
createdBy
originType
originSessionId
originMessageId
dataScopeSnapshot
currentRunId
createdAt
updatedAt
completedAt
revision
```

`originType`：

```text
CHAT
BOARD
CONSOLE
API
```

Task 状态：

```text
BACKLOG
TODO
IN_PROGRESS
IN_REVIEW
BLOCKED
DONE
CANCELLED
```

### 8.2 AgentRun

建议字段：

```text
id
taskId
agentId
runtimeType
runtimeProfileSnapshot
triggerType
status
attempt
parentRunId
startedAt
completedAt
failureReason
resultSummary
```

Run 状态：

```text
QUEUED
DISPATCHED
RUNNING
WAITING_APPROVAL
COMPLETED
FAILED
CANCELLED
UNKNOWN
```

### 8.3 独立状态维度

不得用一个状态字段表达全部生命周期：

| 状态维度 | 示例 |
| --- | --- |
| TaskStatus | `IN_PROGRESS`、`IN_REVIEW`、`DONE` |
| RunStatus | `RUNNING`、`WAITING_APPROVAL`、`FAILED` |
| ApprovalStatus | `PENDING`、`APPROVED`、`REJECTED` |
| ToolAttemptStatus | `PREPARED`、`EXECUTING`、`SUCCEEDED`、`UNKNOWN` |

## 9. Task 上下文

Task 上下文分为：

### 9.1 任务定义

- 标题；
- 目标；
- 背景；
- 验收标准；
- 优先级和状态。

### 9.2 数据上下文

- DataScope；
- 数据源、database、schema；
- 关联表；
- 保存的 SQL；
- Console 引用；
- 附件。

### 9.3 协作上下文

- 用户评论；
- 用户补充；
- Agent 提问；
- 审批决策。

### 9.4 执行上下文

- Run 历史；
- Tool Call；
- SQL Proposal；
- Tool Attempt；
- 结果、错误和审计。

### 9.5 上下文组装

`AgentContextAssembler` 不应把全部历史原样塞给模型，而应组装：

```text
任务定义
+ 当前数据范围快照
+ Pinned Context
+ 最近交互
+ 历史 Run 摘要
+ 当前待处理审批
```

长 Task 应生成不可变的 Run Summary，减少上下文无限增长和语义漂移。

## 10. 工具执行、审批与幂等

### 10.1 统一 Tool Gateway

所有 Runtime 必须通过：

```java
AgentToolGateway.invoke(
    AgentPrincipal principal,
    AgentTaskContext taskContext,
    ToolInvocation invocation
)
```

执行顺序：

```text
身份验证
-> Agent 权限
-> Task DataScope
-> Tool Policy
-> SQL 风险分类
-> Approval Policy
-> 幂等 ToolAttempt
-> 实际工具执行
-> 审计与结果持久化
```

### 10.2 SQL Proposal

审批针对不可变 Proposal，而不是允许 Agent 随意继续：

```text
proposalVersion
sqlSnapshot
sqlHash
dataSourceId
databaseName
schemaName
operationClass
riskLevel
estimatedImpact
```

SQL 变化后必须创建新版本并使旧审批失效。

### 10.3 Approval

建议保存：

```text
runId
proposalVersion
requestedBy
requestedAt
decidedBy
decidedAt
decision
reason
```

批准后执行前重新验证权限、数据目标、Proposal Hash 和 ToolAttempt 状态。

### 10.4 ToolAttempt

建议唯一键：

```text
(runId, proposalVersion, toolCallId)
```

只有一个请求能从 `PREPARED` 原子转换为 `EXECUTING`。

写 SQL 发出后连接中断时进入 `UNKNOWN`，不得自动再次执行。只读操作和明确未发出的写操作可按策略重试。

## 11. Task Artifact

### 11.1 定义

Artifact 是 Task 的正式交付结果，不等同于 Chat 消息或 Run 日志。

```text
Task
|- Context   输入
|- Activity  过程
`- Artifacts 产出
```

第一阶段支持：

```text
REPORT
METRIC
CHART
DATA_TABLE
FILE
```

SQL 默认作为 Evidence；只有任务目标本身是生成 SQL 时，才将 SQL 作为主要产出。

### 11.2 分析报告结构

数据分析类 Task 的标准报告：

```text
Analysis Report
|- Executive Summary
|- Key Metrics
|- Findings
|- Charts
|- Data Tables
|- Recommendations
|- Methodology
`- Evidence
```

报告使用结构化 Block，而不是一整段不可解析 Markdown：

```json
{
  "artifactType": "REPORT",
  "blocks": [
    {
      "type": "SUMMARY",
      "content": "过去 30 天退款率显著上升。"
    },
    {
      "type": "METRIC_GROUP",
      "artifactIds": ["metric-1", "metric-2"]
    },
    {
      "type": "FINDINGS",
      "items": ["华东区域异常", "渠道 A 突增"]
    },
    {
      "type": "CHART",
      "artifactId": "chart-1"
    },
    {
      "type": "DATA_TABLE",
      "artifactId": "table-1"
    },
    {
      "type": "RECOMMENDATIONS",
      "items": ["检查渠道 A 的发布记录"]
    }
  ]
}
```

Markdown 可作为文本 Block 的内容格式，但不承担整个 Artifact 协议。

### 11.3 Artifact 版本

```text
AgentArtifact
|- id
|- taskId
|- type
|- currentVersion
`- status

AgentArtifactVersion
|- artifactId
|- version
|- content
|- createdByRunId
|- createdAt
`- supersedesVersion
```

用户补充条件或 SQL 变化后创建新版本，不覆盖历史结果。

### 11.4 Evidence

每个结论、指标、图表和数据表应能追溯：

```text
runId
toolAttemptId
dataSourceId
databaseName
schemaName
sqlSnapshot
sqlHash
executedAt
rowCount
resultSnapshotId
```

Artifact 不能包含超出当前查看者权限的数据。打开 Evidence、刷新 Live Artifact 和下载文件时均需重新授权。

### 11.5 Snapshot 与 Live

`SNAPSHOT`：

- 保存 Agent 分析当时的数据依据；
- 不随源数据库变化；
- 用于复核历史结论；
- 保存必要聚合结果或有限明细；
- 默认用于 Task Artifact。

`LIVE`：

- 保存查询定义；
- 刷新时重新执行并重新授权；
- 显示最近更新时间；
- 更适合 Dashboard；
- 数据变化后原报告结论可能失效。

发布到 Dashboard 时由用户明确选择“数据快照”或“可刷新图表”。

### 11.6 输出契约

Agent 定义可配置：

```json
{
  "requiredArtifacts": [
    { "type": "REPORT", "min": 1 }
  ],
  "requiredSections": [
    "summary",
    "findings",
    "recommendations",
    "methodology"
  ]
}
```

Run 完成但未满足输出契约时，Task 不得自动进入 `IN_REVIEW`。

## 12. Artifact 与 Dashboard

关系为：

```text
Task Artifact
      |
      | publish
      v
Dashboard Chart / Metric
```

采用“发布副本 + 来源引用”：

- Task Artifact 保持历史不可变；
- Dashboard Chart 可以调整布局、颜色和刷新周期；
- Dashboard 修改不反向篡改已完成 Task；
- 保存 `sourceTaskId`、`sourceArtifactId` 和 `sourceVersion`；
- Task 详情展示已发布到哪些 Dashboard。

现有 `ChartSchema + metaData + databaseInfo` 和“钉到 Dashboard”流程应尽量复用。

## 13. 前端信息架构

### 13.1 Chat

Chat 中展示结构化 Task 卡片：

```text
+------------------------------------------------+
| 退款异常分析                       IN PROGRESS |
| Agent: 数据分析师                              |
| Scope: Production / sales                     |
| 正在检查退款率和异常时间段...                  |
| [查看任务] [停止] [补充上下文]                 |
+------------------------------------------------+
```

等待审批时在 Chat 和 Task 详情中展示同一 Approval 状态。

### 13.2 Task Board

第一阶段提供：

- Board；
- List；
- All Tasks；
- 按状态、Agent、数据源和创建人过滤；
- 快速创建 Task；
- 从 Chat/Console 跳转到 Task。

### 13.3 Task Detail

推荐布局：

```text
+-----------------------------------------------------+
| 退款异常分析                         IN REVIEW      |
| 数据分析师 | Production/sales | 运行于 10:32       |
+-----------------------------------------------------+
| Overview | Results | Activity | Runs                |
+-----------------------------------+-----------------+
| Results                           | Task Properties |
| 结论摘要                          | Agent           |
| 核心指标卡                        | Data Scope      |
| 图表                              | Priority        |
| 关键发现                          | Status          |
| 异常明细表                        | Created by      |
| 建议                              | Updated at      |
| [发布到 Dashboard] [导出] [复核] |                 |
+-----------------------------------+-----------------+
```

Tab 职责：

| Tab | 内容 |
| --- | --- |
| Overview | 目标、验收标准、状态和产出摘要 |
| Results | 完整 Artifact 报告 |
| Activity | 评论、补充、审批和状态变化 |
| Runs | 模型调用、Tool Call、SQL、错误和重试 |

默认 Tab：

- 执行中：Activity；
- 等待审批：定位审批卡；
- `IN_REVIEW` 或 `DONE`：Results。

### 13.4 设计语言

继续复用：

- Ant Design；
- `antd-style` 和现有 Theme Token；
- Lucide 和现有 Chat2DB 图标；
- `SQLPreview`；
- ChartCard/ChartCardBox；
- 现有 Workspace/Console 跳转能力；
- 现有左右面板、导航和卡片层级。

不新增与 Chat2DB 无关的独立设计系统。

## 14. 事件与恢复

持久化完成后再发送事件：

```text
事务提交状态
-> 发送 SSE/WebSocket 事件
-> 前端刷新 Task/Run/Artifact 快照
```

事件只作为通知，不作为状态真相。

建议事件：

```text
task.created
task.updated
run.queued
run.started
run.message
run.waiting_approval
run.completed
run.failed
approval.requested
approval.decided
artifact.created
artifact.updated
```

Run Event 使用稳定 `eventId` 或 `(runId, sequence)` 唯一约束，防止断线重传产生重复消息。

## 15. 持久化建议

Agent Task 需要事务、唯一约束、版本和 CAS，不能继续使用有数量淘汰的 JSON `LargeDataStorage`。

建议使用 Chat2DB 自己的内部 H2 文件库，并提供独立迁移和 Repository：

- 只保存 Chat2DB 控制面数据；
- 不使用用户连接的数据源；
- 路径遵循 Community 存储路径；
- 支持原子状态转换和唯一约束；
- 支持未来迁移；
- 不改变 Community 离线和本地运行合同。

候选逻辑表：

```text
agent_definition
agent_access_policy
agent_data_scope
agent_task
agent_run
agent_run_event
agent_approval
agent_tool_attempt
agent_artifact
agent_artifact_version
agent_artifact_evidence
agent_artifact_dashboard_ref
```

具体表结构应在实现前形成单独 Schema 设计，并验证 H2 与未来可迁移性。

## 16. 分阶段实施

### Phase 1：领域基础与内置 Runtime

- 定义 Agent、Task、Run、Event、Context 和 Artifact API；
- 建立内部 Agent Store；
- 定义 Runtime SPI；
- 封装 Spring AI Runtime；
- 实现 Agent 创建和基础配置；
- 实现 DataScope 和只读 Tool Policy；
- 实现 Task List 和 Task Detail 基础页；
- 实现 Chat 结构化 `@Agent`；
- 支持从 Chat 创建 Task 和第一个 Run。

### Phase 2：安全执行与分析产出

- AgentToolGateway；
- SQL Proposal；
- Approval；
- ToolAttempt 幂等；
- 重启恢复；
- Task Board；
- `REPORT`、`CHART`、`DATA_TABLE`、`FILE`；
- Artifact Version 和 Evidence；
- Task Results 页面；
- 发布 Chart 到 Dashboard；
- Snapshot/Live 区分。

### Phase 3：外部 Agent Runtime

- Runtime 注册和 Heartbeat；
- Run claim/dispatch；
- Task-scoped token；
- MCP 权限收口；
- 外部消息和 Tool Call 回传；
- 取消、离线、失联和恢复；
- Runtime 能力和版本兼容。

### Phase 4：高级协作

- Task 模板；
- 子任务；
- Agent-to-Agent 委派；
- Workflow Stage；
- Human/Agent Gate；
- 调度和自动化。

## 17. 第一阶段验收场景

### 17.1 Chat 创建分析任务

```text
用户进入 Chat
-> 选择数据上下文
-> @数据分析师 并输入分析目标
-> 前端以 TASK 模式发送结构化 Mention
-> 后端验证用户和 Agent 权限
-> 创建 AgentTask 和 AgentRun
-> Chat 展示 Task 卡片
-> Tasks 看板出现新任务
```

### 17.2 数据分析结果

```text
Agent 执行只读查询
-> 保存 ToolAttempt 和 Evidence
-> 生成 Report、Metric、Chart、DataTable
-> Task Results 展示结构化报告
-> Task 进入 IN_REVIEW
-> 用户打开 SQL 和结果快照复核
-> 用户标记 DONE
```

### 17.3 写操作审批

```text
Agent 生成写 SQL Proposal
-> Task/Run 进入等待审批
-> Chat 和 Task 详情显示审批卡
-> 用户批准
-> 后端重新验证 Proposal、权限和数据目标
-> 唯一 ToolAttempt 执行一次
-> 结果记录为 SUCCEEDED / FAILED / UNKNOWN
```

### 17.4 发布到 Dashboard

```text
用户在 Task Results 选择一个 Chart Artifact
-> 选择目标 Dashboard
-> 选择 SNAPSHOT 或 LIVE
-> 创建 Dashboard Chart 副本
-> 保存来源引用
-> Dashboard 可独立调整布局
-> Task 历史结果保持不变
```

### 17.5 重启恢复

```text
Run 等待审批
-> Chat2DB 重启
-> Task、Run、Approval 和 Artifact 状态恢复
-> 前端重新查询后显示一致状态
-> 不重复执行 SQL
```

## 18. 验证要求

每个阶段至少覆盖：

- 状态转换单元测试；
- Task/Run 分离测试；
- Agent 与用户权限交集测试；
- DataScope 越权测试；
- Approval 版本失效测试；
- ToolAttempt 幂等和并发测试；
- 写操作 `UNKNOWN` 不重试测试；
- Event 重传去重测试；
- 重启恢复测试；
- Artifact Version 和 Evidence 测试；
- Dashboard 发布不反向修改 Task Artifact 测试；
- Community Runtime Mode、离线和 loopback 边界检查；
- 前端 `@Agent`、Task 卡片、Board 和 Results 的 focused test；
- 前端 lint 和 Community build；
- 后端 focused Maven tests，并确认非零测试数和零失败。

## 19. 待进一步决策

以下问题在进入具体实现前需要形成 ADR 或专项设计：

1. 内部 Agent H2 Store 的路径、连接池和迁移策略；
2. Agent DataScope 的表级排除和通配规则；
3. Community 单用户模式下 Agent 权限配置的默认值；
4. Agent 输出契约的配置粒度；
5. Snapshot 数据保留行数、文件大小和清理策略；
6. LIVE Artifact 刷新时的权限变化和失效表现；
7. 外部 Runtime 的注册协议和 Task-scoped token 格式；
8. Chat Session 删除后关联 Task 的保留与跳转策略；
9. 多 Agent 与子任务在 Phase 4 的状态汇总规则；
10. Artifact 导出 PDF/Excel 的首版范围。

## 20. 结论

本规划采用以下产品和技术主线：

> Chat 是 Agent 的交互入口，Task 是工作的生命周期，Artifact 是可交付结果，Dashboard 是结果的发布与持续观察空间。

Chat2DB 保留 Spring AI 作为内置 Runtime，同时建立可插拔 Runtime SPI。Agent、权限、Task、审批、工具执行和 Artifact 都由 Chat2DB 控制面统一管理。第一阶段优先打通内置 Runtime、结构化 `@Agent`、Task 看板、数据权限和分析结果；在安全执行闭环稳定后，再增加外部 Agent Runtime。
