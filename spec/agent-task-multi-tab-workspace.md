# Agent Task 多页签工作区设计与实施计划

## 背景

Chat2DB 的数据库工作台使用稳定的页签模型承载 SQL Console、表结构和数据编辑等长生命周期页面。当前 Agent Task 模块仍以单个大型页面配合多个 Modal 承载任务看板、任务详情、定时任务和 Agent 配置，存在以下问题：

- 创建任务和 Agent 配置表单受 Modal 宽高限制，在 JCEF 或不同屏幕尺寸下容易重排、闪动。
- 打开任务详情、定时任务或 Agent 管理时会替换当前页面，用户无法保留多个工作上下文。
- Agent 的 Runtime、数据范围、能力、审批模式和产出契约需要更大的稳定展示区域。
- 页面状态由多个互斥布尔值和手工 URL 判断共同维护，扩展成本较高。
- 如果多个详情页面同时挂载并轮询，会产生不必要的请求和页面更新。

## 目标

在 Task 一级入口内增加与数据库工作台视觉和操作习惯一致的二级页签工作区：

1. 任务看板作为固定、不可关闭的首页签。
2. 任务详情可以打开为多个去重页签。
3. 新建任务、定时任务、Agent 管理、Agent 新建和 Agent 编辑使用全尺寸页面页签。
4. 页签切换与 `/tasks` 路由保持同步，支持直接链接和浏览器前进、后退。
5. 只有 active 页签允许执行自动刷新或轮询；隐藏页签不得持续请求详情和运行事件。
6. 长表单离开或关闭时提供未保存确认，避免由 Modal 改为页签后产生数据丢失。

## 非目标

- 不复用数据库工作台的 `workspaceStore`，避免引入 SQL Console、数据源上下文和分屏布局耦合。
- 第一阶段不支持 Task 页签分屏、固定/取消固定、关闭后恢复等高级工作台能力。
- 不修改现有 Agent Task 后端接口和数据模型。
- 不持久化 System Prompt、数据范围等完整表单内容到 Local Storage。

## 页签模型

| 页面 | 页签 Key | 路由 | 关闭规则 |
| --- | --- | --- | --- |
| 任务看板 | `board` | `/tasks` | 固定，不可关闭 |
| 归档任务 | `archive` | `/tasks/archive` | 可关闭 |
| 任务详情 | `task:{taskId}` | `/tasks/{taskId}` | 可关闭，同一任务去重 |
| 新建任务 | `task:new` | `/tasks/new` | 可关闭，有修改时确认 |
| 定时任务 | `schedules` | `/tasks/schedules/new` | 可关闭 |
| 定时任务详情 | `schedule:{scheduleId}` | `/tasks/schedules/{scheduleId}` | 可关闭，同一定时任务去重 |
| Agent 管理 | `agents` | `/tasks/agents` | 可关闭 |
| 新建 Agent | `agent:new` | `/tasks/agents/new` | 可关闭，有修改时确认 |
| 编辑 Agent | `agent:{agentId}` | `/tasks/agents/{agentId}/edit` | 可关闭，同一 Agent 去重，有修改时确认 |

## 页面布局

Task 工作区由两层组成：

1. 顶部 `TaskWorkspaceTabs`：复用通用 `CustomTabs` 的选中、关闭、拖动和溢出菜单交互。
2. 下方 active 页面容器：始终占满剩余区域，只渲染当前页签对应页面。

Agent 管理页保留左侧 Agent 列表和右侧详情。新建或编辑 Agent 时打开独立页签，使用完整双栏表单：

- 主栏：头像、身份、Runtime、模型、描述、System Prompt 和数据范围。
- 侧栏：能力权限和产出契约。
- 页面操作栏：取消和保存；保存期间显示 loading 并禁止重复提交。

小尺寸窗口下双栏布局降级为单栏，页签栏保持横向滚动或溢出菜单，不回退为 Modal。

## 状态与刷新规则

- Task Tab 列表和 active key 由独立的 Task 工作区状态维护。
- URL 是当前 active 页签的可分享表达，不承载全部已打开页签。
- 从看板、聊天记录或定时任务执行记录打开实体时，已存在页签则激活，否则新增。
- 任务详情进入 active 状态时立即加载一次；仅当 active 任务仍有运行中的 Run 时每 2 秒刷新。
- 任务详情离开 active 状态时立即清理轮询 Timer。
- 定时任务列表和详情仅在定时任务页签成为 active 时加载；隐藏时不轮询。
- Agent Runtime 检测和数据源列表仅在 Agent 管理或编辑页签 active 时加载。
- 手动刷新只作用于当前 active 页面。

## 路由兼容

保留现有 `/tasks`、`/tasks/archive`、`/tasks/{taskId}` 和 `/tasks/schedules/*` 链接。新增 `/tasks/new` 和 `/tasks/agents/*`。

路由解析顺序必须先识别 `archive`、`new`、`schedules` 和 `agents` 等保留路径，再把 `/tasks/{value}` 解释为任务 ID。`popstate` 和 hash history 变化都需要重新激活对应页签。

## 未保存保护

- 新建任务、Agent 新建和 Agent 编辑页通过 Form 变更事件维护 dirty 状态。
- 关闭 dirty 页签时显示确认框。
- 保存成功后清除 dirty，并跳转到创建后的任务详情或 Agent 编辑/管理页面。
- 页签切换不销毁表单草稿；关闭页签才释放组件状态。

## 验收标准

1. Task 顶部页签样式与数据库工作台一致，任务看板始终可返回。
2. 可以同时打开多个任务详情，并通过页签切换。
3. 创建任务、Agent 管理、新建 Agent、编辑 Agent 不再使用 Modal。
4. 直接打开或刷新 Task 子路由能恢复对应 active 页签。
5. 浏览器/JCEF 返回操作能够回到上一个 Task 页签。
6. inactive 任务详情不发起 2 秒轮询，切回 active 后恢复。
7. inactive Agent/定时任务页不执行自动加载或检测。
8. dirty 表单关闭前需要用户确认。
9. 720px 以下宽度不出现页面级横向溢出，主要操作仍可访问。

## 验证计划

- 为页签去重、路由解析、关闭后的 active 页签选择和 active 刷新判断增加纯函数测试。
- 执行 Task 相关前端聚焦测试。
- 执行 `yarn run lint` 和 i18n 校验。
- 执行 `yarn run build:web:community --app_version=0.0.0`。
- 执行 `git diff --check` 并检查最终工作区状态。
