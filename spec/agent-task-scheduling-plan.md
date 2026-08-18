# Chat2DB Agent Task 定时计划设计

> 状态：Implemented
> 适用范围：Chat2DB Community
> 目标分支：`feature/task-agent-runtime`
> 最后更新：2026-08-17
> 上位规划：[`agent-runtime-task-artifact-plan.md`](./agent-runtime-task-artifact-plan.md)
> 参考实现：Silieco Autopilot 与数据库执行记录调度器

## 1. 背景

Chat2DB 当前已经建立完整的 Agent Task 执行链路：

```text
AgentTask
  -> AgentRun
  -> AgentRunCoordinator
  -> Spring AI / Codex / Hermes / DSH
  -> Run Event / Approval / Tool Attempt
  -> Artifact
  -> Task IN_REVIEW
```

现有任务只能由看板、Chat `@Agent`、Console 或 API 即时创建。数据日报、定期巡检、渠道分析和周期性报告仍需要用户重复操作，因此需要增加由时间触发的 Agent Task。

本设计参考 Silieco 的以下原则：

- 自动化定义、触发配置和单次执行记录相互分离；
- Cron 与时区共同决定标准 UTC `plannedAt`；
- 使用数据库唯一键保证同一计划时间只执行一次；
- 调度状态、业务 Task 和 Agent Run 各自保持清晰职责；
- 应用重启后从持久化状态恢复，而不是依赖内存 Timer；
- Agent 或 Runtime 不可用时记录明确的跳过原因，不无限堆积任务。

Chat2DB 第一阶段不复制 Silieco 的完整 Autopilot、Webhook、API Trigger、Squad 或通用 Workflow，只实现 Agent Task 的一次性与周期性时间计划。

## 2. 目标与非目标

### 2.1 目标

- 用户可以为指定 Agent 创建一次性或周期性任务计划；
- 支持标准五段式 Cron、IANA 时区和未来执行时间预览；
- 每次计划触发生成一个独立的 `AgentTask` 和初始 `AgentRun`；
- Spring AI、Codex、Hermes 和 DSH 继续复用统一 Runtime 分发链路；
- 同一 `scheduleId + plannedAt` 在重复扫描、重启和异常重试下最多创建一个 Task；
- 计划创建者、数据权限快照、任务来源和执行记录可以审计；
- 计划暂停、归档或引用任务删除后仍能正确展示历史状态；
- Community Web 与 JCEF 保持离线优先、loopback 和本地 H2 存储约束。

### 2.2 非目标

第一阶段不实现：

- 通用 Workflow Designer；
- Webhook、消息事件或数据库事件触发；
- 多 Agent 编排和 Squad；
- 秒级 Cron 和分布式集群调度；
- `REPLACE` 正在运行任务的抢占式并发策略；
- Chat2DB 完全退出后仍由操作系统后台服务持续执行；
- Enterprise Gateway、云调度或远程托管计划。

## 3. 核心设计决策

### 3.1 计划不是 Task 上的时间字段

定时计划是可重复使用的任务模板，不直接给现有 `AgentTask` 增加 `cronExpression`。

每次触发创建新的 Task：

```text
AgentTaskSchedule
  |- 2026-08-18 09:00 -> TASK-A
  |- 2026-08-19 09:00 -> TASK-B
  `- 2026-08-20 09:00 -> TASK-C
```

这样可以保证：

- 每次执行有独立的 Task 状态、Run、审批和 Artifact；
- 一次失败不会污染其他周期的执行历史；
- Task 归档、删除和人工继续对话不会改变计划模板；
- 报告、图表和数据表不会混合在同一个 Task 中；
- 计划页面可以聚合展示各次执行，同时 Task 页面仍保持现有语义。

不采用“同一个 Task 周期性追加 Run”的方案。该方案会导致已完成任务重新进入执行中、多期 Artifact 混合、并发判断困难，以及归档和删除语义不清晰。

### 3.2 调度器只创建任务，不直接调用 Provider

计划调度器不识别 Codex、Hermes 或 DSH 协议。它只负责创建普通 `AgentTask + AgentRun`，随后调用现有 `AgentRunCoordinator.dispatch(runId)`。

```text
Schedule Dispatcher
       |
       v
AgentTaskService.createScheduledTask(...)
       |
       v
AgentRunCoordinator.dispatch(runId)
       |
       +-- Embedded Spring AI
       `-- External Runtime Daemon
             |- Codex
             |- Hermes
             `- DSH
```

Runtime Provider、Session Resume、Tool Gateway、Approval、Artifact 和 Task 状态收敛继续由现有控制面负责。

### 3.3 使用固定扫描器，不动态注册内存 Cron

后端使用一个固定频率的 Spring `@Scheduled` Job，例如每 15 秒扫描一次到期计划。

不为每条用户计划动态创建内存 `ScheduledFuture`，原因包括：

- 后端重启后需要重新恢复全部 Timer；
- 更新、暂停和删除计划容易残留旧 Timer；
- 多线程重复触发缺少可靠幂等；
- 无法形成统一执行审计和失败恢复入口。

数据库中的 `nextRunAt`、执行记录和唯一约束是事实来源，内存扫描器只是唤醒机制。

### 3.4 业务执行状态不复制 Run 状态

`AgentTaskScheduleExecution` 只记录“调度是否成功产生并交付 Task”。Task 和 Run 的实际执行状态继续从现有表读取。

例如 execution 为 `DISPATCHED` 时，对应 Run 仍可能是：

- `QUEUED`；
- `RUNNING`；
- `WAITING_APPROVAL`；
- `COMPLETED`；
- `FAILED`。

计划详情 API 通过 `taskId/runId` 返回当前关联状态，避免在 Schedule Execution 中维护第二份容易漂移的 Run 状态。

## 4. 领域模型

### 4.1 AgentTaskSchedule

建议新增模型：

```text
AgentTaskSchedule
|- id
|- name
|- taskTitle
|- taskDescription
|- acceptanceCriteria
|- assigneeAgentId
|- priority
|- dataScopeSnapshot
|- scheduleType             ONCE | CRON
|- scheduledAt              ONCE 使用，UTC 时间
|- cronExpression           CRON 使用，标准五段式
|- timezone                 IANA timezone，例如 Asia/Shanghai
|- status                   ACTIVE | PAUSED | ARCHIVED
|- concurrencyPolicy        SKIP
|- catchUpPolicy            LATEST_ONLY
|- nextRunAt
|- lastRunAt
|- createdBy
|- createdAt
|- updatedAt
`- revision
```

约束：

- `ONCE` 必须提供 `scheduledAt`，且不得提供 `cronExpression`；
- `CRON` 必须提供 `cronExpression` 和合法时区；
- 第一阶段 `concurrencyPolicy` 只接受 `SKIP`；
- 第一阶段 `catchUpPolicy` 只接受 `LATEST_ONLY`；
- `ARCHIVED` 计划只读，不再参与扫描；
- 更新计划使用 revision CAS，避免页面上的旧数据覆盖新配置。

### 4.2 AgentTaskScheduleExecution

建议新增模型：

```text
AgentTaskScheduleExecution
|- id
|- scheduleId
|- source                   SCHEDULE | MANUAL
|- plannedAt                标准 UTC 计划时间
|- status                   CLAIMED | TASK_CREATED | DISPATCHED | SKIPPED | FAILED
|- taskId
|- runId
|- attempt
|- leaseToken
|- leaseExpiresAt
|- reasonCode
|- failureReason
|- createdAt
`- updatedAt
```

数据库必须建立唯一约束：

```text
UNIQUE(schedule_id, planned_at, source)
```

自动触发使用标准 Cron occurrence 作为 `plannedAt`。手动“立即执行”使用请求时生成的 UTC 时间，并以 `source=MANUAL` 区分。

执行记录不依赖 Task 外键级联删除。即使对应 Task 被永久删除，计划历史仍保留 `taskId` 和执行结果，并向前端返回 `taskLinkState=DELETED`。

### 4.3 AgentTask 来源扩展

扩展 `AgentTaskOriginTypeEnum`：

```text
SCHEDULE
```

`AgentTask` 增加：

```text
originScheduleId
originScheduleExecutionId
plannedAt
```

扩展 `AgentRunTriggerTypeEnum`：

```text
SCHEDULED
```

Task Detail 使用这些字段展示计划来源、计划时间和返回计划详情的快捷入口。

## 5. Cron 与时间语义

### 5.1 Cron 格式

对用户只接受标准五段式 Cron：

```text
minute hour day-of-month month day-of-week
```

示例：

```text
0 9 * * *       每天 09:00
0 9 * * 1-5     每个工作日 09:00
30 18 1 * *     每月 1 日 18:30
```

不接受秒字段、`@daily`、`L`、`W` 等第一阶段 UI 无法稳定表达的扩展语法。

Spring `CronExpression` 使用六段式。领域层应提供统一适配器，在解析时为五段式表达式补充固定秒字段 `0`，不能让 Controller、Storage 或 UI 各自转换。

### 5.2 时区

- 保存 IANA timezone，例如 `Asia/Shanghai`；
- 不保存固定 `UTC+8`，因为固定偏移无法正确处理夏令时；
- `plannedAt` 和 `nextRunAt` 按 UTC epoch millis 持久化；
- UI 按计划时区和用户本地时区展示；
- 修改时区视为计划的实质性修改，需要重新计算 `nextRunAt`。

### 5.3 预览

后端提供纯计算接口，返回未来三次执行时间：

```text
GET /api/agent/task-schedules/cron-preview
    ?expression=0%209%20*%20*%201-5
    &timezone=Asia/Shanghai
```

响应中的时间使用 UTC epoch millis，与 Schedule 持久化模型保持一致：

```json
{
  "nextRuns": [
    1787014800000,
    1787101200000,
    1787187600000
  ]
}
```

Cron 错误与时区错误使用不同的稳定错误码：`agent.schedule.invalidCron` 和
`agent.schedule.invalidTimezone`。

### 5.4 补偿与迟到窗口

第一阶段采用：

```text
catchUpPolicy = LATEST_ONLY
maxLateness   = 5 minutes
```

行为：

- 正常扫描只执行最新到期 occurrence；
- 应用短暂重启，最新 occurrence 迟到不超过 5 分钟时允许执行；
- 离线数小时或数天后，不重放所有历史 occurrence；
- 最新 occurrence 超过迟到窗口时记录一次 `SKIPPED/MISSED_WINDOW`，然后推进到未来时间；
- `ONCE` 计划超过迟到窗口后记录 `SKIPPED/MISSED_WINDOW` 并自动结束，不在每次扫描时重复尝试。

## 6. 调度与幂等流程

### 6.1 正常执行

```text
1. Fixed-delay scanner 读取数据库当前时间
2. 查询 ACTIVE 且 nextRunAt <= now 的计划
3. 计算该计划的标准 plannedAt
4. INSERT execution(scheduleId, plannedAt, source=SCHEDULE)
5. 唯一键冲突则本次扫描直接结束
6. 执行准入校验
7. 原子创建 AgentTask + AgentRun，并绑定 execution
8. 推进 schedule.nextRunAt / lastRunAt
9. 调用 AgentRunCoordinator.dispatch(runId)
10. execution 更新为 DISPATCHED
```

扫描应设置批量上限，避免启动时一次处理过多计划。建议第一阶段每 Tick 最多处理 50 条。

### 6.2 原子创建

存储层新增一个明确的事务方法，一次完成：

- 确认当前 execution lease；
- 插入 `agent_task`；
- 插入初始 `agent_run`；
- 回写 execution 的 `taskId/runId/status=TASK_CREATED`；
- 推进 Schedule 的 `lastRunAt/nextRunAt/revision`。

不能先调用普通 `taskService.create()`，再单独回写 execution。否则进程可能在两次写入之间退出，重试时无法判断 Task 是否已经创建。

该事务仍通过 Storage API 暴露，Web 和 Domain Core 不直接拼接 H2 SQL。

### 6.3 三个异常窗口

必须覆盖：

#### A. 抢占成功，创建 Task 前退出

execution 停留在 `CLAIMED`。Lease 到期后允许新的扫描器增加 attempt 并重新领取。

#### B. Task 已创建，dispatch 前退出

execution 已保存 `taskId/runId`。恢复任务扫描 `TASK_CREATED` execution：

- Run 仍为 `QUEUED`：重新调用 dispatch；
- Run 已进入其他状态：只把 execution 收敛为 `DISPATCHED`；
- 不创建第二个 Task。

#### C. dispatch 已开始，execution 更新前退出

恢复时读取 Run 状态。只有 `QUEUED` Run 才能再次调用 dispatch，其他状态直接视为已交付。外部 Runtime 的 Claim/Lease 和事件 fencing 继续负责 Provider 执行幂等。

### 6.4 Lease

即使 Community 主要是单机 H2，也需要短 Lease，防止：

- 同一 JVM 中一次扫描尚未结束，下一次扫描再次进入；
- JCEF 与手工启动的后端错误地指向同一存储；
- 进程退出后 execution 永久停留在 `CLAIMED`。

所有 Lease 续期和终态写入必须匹配 `executionId + leaseToken + status`，旧执行者失去 Lease 后不能覆盖新 attempt。

第一阶段不需要抽象成全局通用 Scheduler Framework；实现 feature-scoped 的 `AgentTaskScheduleDispatcher` 和 `agent_task_schedule_execution` 即可。

## 7. 准入、安全与权限

### 7.1 身份归属

定时执行没有当前 HTTP 请求身份。Task 的 `createdBy` 必须来自 Schedule 创建者，不允许使用系统默认用户或当前线程残留身份。

Controller 只负责在创建/更新 Schedule 时注入当前用户；Dispatcher 从持久化 Schedule 读取 `createdBy`。

### 7.2 数据范围

计划创建时保存不可变 `dataScopeSnapshot`。每次触发时：

1. 使用该快照作为本次 Task 请求的最大范围；
2. 与 Agent 当前授权范围重新校验；
3. 权限被收紧时禁止使用旧权限；
4. Agent 后续获得更大权限时，旧 Schedule 不自动扩大；
5. 用户需要显式编辑并重新授权 Schedule 才能扩大范围。

校验失败时 execution 记录稳定 reason code，不创建 Task。

### 7.3 Agent 与 Runtime 准入

触发时重新检查：

- Schedule 创建者仍可访问该 Agent；
- Agent 状态为 `ACTIVE`；
- Runtime Profile 仍存在且启用；
- Spring AI 模型配置可用；
- 外部 Provider 类型与 Profile Snapshot 合法；
- 定时任务需要的 DataScope 和 Capability 未被撤销。

对于 Codex、Hermes、DSH 等外部 Runtime，若当前没有在线且具备容量的 Runtime Instance，第一阶段记录 `SKIPPED/RUNTIME_OFFLINE`，不创建长期排队的计划任务。手工创建 Task 的现有排队语义不受影响。

### 7.4 并发策略

第一阶段固定：

```text
concurrencyPolicy = SKIP
```

如果同一 Schedule 之前生成的 Task 仍存在活跃 Run：

```text
QUEUED | DISPATCHED | RUNNING | WAITING_APPROVAL
```

本次 execution 记录 `SKIPPED/PREVIOUS_EXECUTION_ACTIVE`，并正常推进下次时间。

不实现 `REPLACE`，因为它涉及取消 Spring AI 调用、外部 Runtime Session、审批等待和结果不确定状态；也不在第一阶段实现无限 `QUEUE`，避免离线后积压周期任务。

## 8. 状态与引用语义

### 8.1 Schedule 状态

```text
ACTIVE <-> PAUSED -> ARCHIVED
```

- `ACTIVE`：参与扫描；
- `PAUSED`：保留配置和历史，不产生执行；
- `ARCHIVED`：只读，不参与扫描；
- 第一阶段不提供永久删除 Schedule，避免破坏执行审计。

### 8.2 Execution 状态

```text
CLAIMED -> TASK_CREATED -> DISPATCHED
    |             |
    +-> SKIPPED   +-> FAILED
    `-> FAILED
```

- `DISPATCHED` 是调度层终态，表示 Task/Run 已交给现有执行链路；
- Run 后续成功或失败不反向改写 execution；
- 计划详情从关联 Task/Run 展示最终业务结果。

### 8.3 Task 链接

计划执行历史返回：

```text
taskLinkState = AVAILABLE | ARCHIVED | DELETED
```

- `AVAILABLE`：允许跳转 `/tasks/{taskId}`；
- `ARCHIVED`：提示任务已归档，可跳转归档详情；
- `DELETED`：不跳转，提示“对应任务已被删除”；
- execution 自身始终保留计划时间、状态和失败原因。

Task 详情中的 Schedule 链接采用相同规则：Schedule 已归档时提示已归档，不把归档误判为不存在。

## 9. API 设计

建议独立使用 Schedule Controller，保持现有 `AgentControlController` 不继续膨胀：

```text
POST /api/agent/task-schedules
GET  /api/agent/task-schedules
GET  /api/agent/task-schedules/{scheduleId}
POST /api/agent/task-schedules/{scheduleId}
POST /api/agent/task-schedules/{scheduleId}/pause
POST /api/agent/task-schedules/{scheduleId}/resume
POST /api/agent/task-schedules/{scheduleId}/archive
POST /api/agent/task-schedules/{scheduleId}/run-now
GET  /api/agent/task-schedules/{scheduleId}/executions
GET  /api/agent/task-schedules/cron-preview
```

要求：

- 创建、更新和生命周期接口绑定当前用户；
- 更新、暂停、恢复、归档使用 expected revision；
- `run-now` 仍执行完整准入检查；
- 列表只返回当前用户创建的计划；
- Detail 返回 Schedule、最近 execution、关联 Task/Run 摘要；
- Controller 只做身份、参数和响应转换，Cron、权限、幂等和状态行为放在 Domain Service。

## 10. 前端设计

### 10.1 入口

Tasks 页面工具栏增加“定时任务”入口，进入独立的计划列表或右侧抽屉。不要把定时计划混入四列 Task Board，因为计划不是待执行 Task。

### 10.2 创建与编辑

复用现有 Task 创建表单中的：

- Task 标题和描述；
- 验收标准；
- Agent 选择；
- Runtime 标识；
- 数据范围；
- 优先级。

增加调度区：

- 一次执行；
- 每天；
- 每周及星期选择；
- 自定义 Cron；
- 时区；
- 后端返回的未来三次执行预览；
- “上一次任务未结束时跳过本次”的明确说明。

可视化控件最终转换为五段式 Cron。自定义 Cron 无法由可视化控件完整表达时，保留原表达式并进入高级编辑状态，不能静默改写。

### 10.3 计划列表

建议展示：

```text
每日渠道分析
@AnalysisAgent · 每天 09:00 · Asia/Shanghai
下次执行：2026-08-18 09:00
最近执行：TASK-A12B3C · 执行中
```

操作包括：

- 查看详情；
- 编辑；
- 暂停/恢复；
- 立即执行；
- 归档。

### 10.4 Task 详情

Inspector 增加：

```text
来源：定时计划
计划：每日渠道分析
计划时间：2026-08-18 09:00 Asia/Shanghai
查看计划
```

Task Board 中的来源筛选增加 `SCHEDULE`，但不改变现有状态列和 Run 展示方式。

## 11. 模块归属

建议按现有模块边界实施：

| 模块 | 职责 |
| --- | --- |
| `domain-api` | Schedule/Execution 模型、枚举、请求、服务和 Storage 接口 |
| `domain-core` | Cron 计算、准入、Schedule 生命周期、Dispatcher 和恢复逻辑 |
| `storage` | H2 Flyway migration、CAS、唯一约束、Lease 和原子 Task/Run 创建 |
| `web` | 独立 Schedule Controller、身份绑定和 Cron Preview API |
| `community-client` | Schedule Service、列表、编辑器、执行历史和 Task 来源展示 |
| `agent-runtime` | 不增加定时逻辑；继续消费普通 AgentRun |
| `jcef` | 不增加业务逻辑；继续启动 Community 后端和现有 Runtime Supervisor |

当前 Agent Store migration 到 V16。实施时可使用下一可用版本，例如：

```text
V17__agent_task_schedule.sql
```

实际开发前必须再次检查 migration 序号，避免与并行改动冲突。

## 12. 测试与验证

### 12.1 Domain focused tests

至少覆盖：

- ONCE/CRON 参数互斥；
- 五段式 Cron 校验；
- 非法 timezone；
- DST 前进和回拨时间；
- `LATEST_ONLY` 和 5 分钟迟到窗口；
- 暂停、恢复、归档状态转换；
- Agent 归档、Runtime 离线、权限收紧时跳过；
- 前一任务仍活跃时 `SKIP`；
- createdBy 和 DataScope Snapshot 不漂移。

### 12.2 Storage focused tests

至少覆盖：

- `(scheduleId, plannedAt, source)` 唯一性；
- Task、Run、execution、nextRunAt 原子提交；
- 中途异常全部回滚；
- Lease Token fencing；
- Lease 超时后重领；
- H2 关闭并重开后恢复计划和执行；
- Task 归档和永久删除后的 `taskLinkState`；
- revision CAS 冲突。

### 12.3 Dispatcher tests

使用可注入 Clock 和 Fake Runtime，不依赖真实 Codex、Hermes 或 DSH：

- 同一 Tick 重复执行不重复创建 Task；
- 两个 Dispatcher 并发时只有一个成功；
- 在 `CLAIMED`、`TASK_CREATED` 和 dispatch 后分别模拟退出并恢复；
- `QUEUED` Run 可恢复交付，已运行 Run 不重复 dispatch；
- 批量上限和单条失败隔离。

### 12.4 Web 与前端

- 当前用户只能查看和修改自己的 Schedule；
- Cron Preview 的错误码和返回格式稳定；
- 创建、编辑、暂停、恢复、立即执行和归档；
- 后端响应漂移和未知枚举有安全回退；
- Task/计划已归档或删除时链接提示正确；
- 前端 lint、focused test 和 Community production build。

### 12.5 Community smoke

- 使用 `-Dchat2db.runtime.mode=community`；
- 使用 `-Dchat2db.network.status=OFFLINE`；
- 后端绑定 `127.0.0.1:10825`；
- 前端通过 `127.0.0.1:8889` 访问；
- 分别验证 Spring AI 和至少一个 Fake External Runtime 的计划触发；
- 验证重启后不会重复创建同一 `plannedAt` Task。

真实 Provider smoke 只有在明确授权时执行，不能在默认测试中调用用户机器上的 Codex、Hermes 或 DSH，也不能消耗用户账号额度。

## 13. 实施阶段

### Phase 1：可靠后端纵向切片

1. Schedule/Execution 模型、枚举和 Storage 契约；
2. H2 migration、索引、唯一约束和 CAS；
3. Cron/Timezone 校验及未来三次预览；
4. Schedule CRUD、暂停、恢复和归档；
5. Dispatcher、Lease、幂等创建和恢复；
6. Runtime/DataScope 准入；
7. focused tests 和 Community-mode smoke。

### Phase 2：前端完整体验

1. 定时任务入口和计划列表；
2. 创建/编辑 Schedule；
3. 一次、每天、每周和自定义 Cron 编辑器；
4. 执行预览、历史记录和 Task 跳转；
5. Task Detail 计划来源；
6. i18n、focused test、lint 和 Community build。

### Phase 3：增强能力

在第一、二阶段稳定后再评估：

- `QUEUE` 并发策略；
- 失败通知和连续失败自动暂停；
- 更丰富的节假日与工作日规则；
- Webhook/API Trigger；
- 独立后台服务或系统启动项；
- 抽取通用数据库执行记录调度器。

## 14. Community/JCEF 运行边界

Community Desktop 当前是本地离线优先架构。用户彻底退出 Chat2DB 后，Community 后端和本地 Runtime Supervisor 也会停止，因此定时计划无法在应用完全关闭时准点执行。

第一阶段 UI 必须明确提示：

> 定时任务仅在 Chat2DB 后端运行期间触发。应用关闭期间错过的执行将按照最近一次补偿和迟到窗口策略处理。

如果产品要求“关闭桌面窗口甚至用户退出后仍持续执行”，需要把 Community 后端或 Runtime Daemon 安装为受操作系统管理的独立后台服务，并单独设计：

- 开机启动和退出语义；
- 本地数据库的单进程所有权；
- JCEF 与后台服务的连接发现；
- 升级、日志、崩溃恢复和卸载；
- macOS、Windows 和 Linux 平台权限。

该能力不属于本次定时计划 MVP，不能通过增加一个 `@Scheduled` 方法替代。

## 15. 最终结论

Chat2DB 定时任务应采用：

```text
持久化 Schedule 模板
  + 标准 plannedAt
  + Execution 唯一键与 Lease
  + 每次生成独立 AgentTask
  + 复用现有 AgentRun/Runtime/Artifact 控制面
```

第一阶段优先保证不重复、不越权、可恢复和状态可解释；复杂并发策略、外部事件和后台常驻运行放在后续阶段。

## 16. 实施结果（2026-08-17）

本设计的 Phase 1 和 Phase 2 已在 `feature/task-agent-runtime` 分支完成，实际实现保持了本文定义的模块边界：

- `domain-api` 增加 Schedule、Execution、Claim、生命周期请求、调度枚举、服务与 Storage 契约，并为 Task/Run 增加计划来源字段；
- `domain-core` 增加严格五段式 Cron/IANA 时区校验、可注入时钟的调度服务、15 秒固定扫描、五分钟迟到窗口、`LATEST_ONLY`、`SKIP`、Lease 恢复和统一 `AgentRunCoordinator` 分发；
- `storage` 通过 `V17__agent_task_schedule.sql` 增加计划表、执行表、索引与唯一约束，并在 H2 Storage 内实现 Task、Run、Execution、Schedule 推进的单事务提交和 Lease fencing；
- `web` 增加独立 `/api/agent/task-schedules` Controller，覆盖 CRUD、生命周期、立即执行、执行历史和 Cron 预览，并绑定当前用户身份与 Owner 校验；
- `community-client` 在 Tasks 工具栏增加定时任务入口，提供计划列表、创建/编辑、预设与自定义 Cron、时区预览、暂停/恢复/归档/立即执行、执行历史、Task 链接状态和 Task 详情来源展示；
- Spring AI、Codex、Hermes 和 DSH 未增加定时任务专用分支，计划触发仍生成普通 Task/Run 并进入既有 Provider 分发、审批、事件、Artifact 与状态收敛链路。

已执行的自动验证包括：

- Domain focused tests：11 个测试，覆盖 Cron、DST、迟到窗口、状态转换、准入与恢复；
- Storage focused tests：4 个测试，覆盖迁移重开、原子回滚、并发唯一领取、Lease reclaim/fencing、链接状态和 revision CAS；
- Web focused tests：4 个测试，覆盖身份绑定、Owner 拒绝、Cron Preview 和 Lease Token 不序列化；
- Frontend focused test、i18n 校验、lint 和 Community production build；
- Community backend reactor package（测试由命令显式跳过，测试结果以上述 focused tests 为准）；
- 隔离 Community smoke：后端以 Community/OFFLINE/loopback 配置启动在 `127.0.0.1:10826`，前端启动在 `127.0.0.1:8890` 并代理至该后端；Schedule 列表、合法 Cron Preview、非法六段 Cron 稳定错误码、页面 HTML、前端 bundle 和代理链路均验证通过。

当前环境没有可连接的应用内浏览器实例，因此未执行点击级 UI 回归。隔离数据目录中没有 Agent Definition，也未调用真实 Spring AI、Codex、Hermes 或 DSH；真实 Provider 触发仍遵守本文授权边界，留给人工回归或显式授权后的 smoke。
