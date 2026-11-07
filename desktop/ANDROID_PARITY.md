# Android 与桌面端功能核对

核对日期：2026-11-07。以当前仓库 Kotlin 客户端的实际入口、请求与能力检查为依据，对比当前桌面源码。这里的“已实现”表示已有桌面实现和对应本地验证，不表示已完成真实部署联调或 macOS 实机验收。

## 本轮补齐

| 功能 | 桌面端结果 | Android 依据 |
| --- | --- | --- |
| 电脑 → 项目 → Agent | 电脑、项目两级独立折叠并保存；搜索临时展开匹配项，清空搜索后恢复 | `AgentListPresentation.kt`、`AgentGroupHeader.kt` |
| 项目名称与顺序 | 读取独立的 `workspaces` 列表，包含空项目，按服务端顺序显示 | `AgentPresentation.kt` |
| Agent 名称 | 优先 `tab_label`、`tab_name`，再使用名称等后备字段；提供方单独显示 | `AgentPresentation.title()` |
| 消息加载反馈 | 转圈、整理历史的实际进度、加载更早消息和取消等待；保留当前内容 | `HistoryLoadView.kt`、`ConversationHistoryRequest` |
| Agent 状态和用时 | 选中 Agent 每 5 秒读取状态；只使用可靠的本轮用时，断线、替换或超过 15 秒未确认时停止推算 | `AgentRunPresentation.kt` |
| 两级工具折叠 | 连续的工具事件合并为组；组内独立展开每次调用的输入、输出，展开状态按会话保留 | `ConversationTimeline.kt`、`NativeToolGroup.kt` |
| Codex 文件引用 | 将 `:codex-file-citation{path="…"}` 显示成可点击文件引用；代码块和转义示例保持原样 | `CodexFileCitations.kt` |
| 文件链接 | 当前远端工作区的绝对路径、相对路径、`file:///` 链接及 Markdown 内相对链接 | `ReviewReference.kt` |
| Markdown 预览 | 渲染正文、表格、代码、图片和链接；可展开阅读区域，支持标题锚点 | `DocumentReviewer.kt` |
| PDF 预览 | 应用内嵌系统阅读器，翻页、缩放；Windows 已在隐藏窗口实际渲染验证，Mac 待实机验证 | `DocumentReviewer.kt`、`ReviewViewport.kt` |
| 对话图片 | 识别 Markdown、图片字段和工具中的图片路径；通过当前 Agent 的授权连接读取，支持放大查看 | `ConversationPresentation.kt`、`ConversationImages.kt` |

## 仍有差距

优先级 P0 表示会影响日常操作闭环，P1 表示常用配套功能，P2 表示后续体验增强。

| 领域 | Android 已有 | 桌面现状与差距 | 建议 |
| --- | --- | --- | --- |
| 审批与问答 | 审批按钮、单选/多选/自定义回答、问题导航，携带事件指纹 | 尚无对应卡片；当前只能进入终端手动处理 | P0 |
| 新建 Agent | 按工作区选择 Agent 配置、初始提示、额外启动参数，保留不确定创建状态 | 尚无新建入口 | P0 |
| Agent 管理 | 重命名、停止、重启、清空、标签排序 | 只有展示名称，没有管理操作 | P0 |
| 模型配置 | 读取动态模型目录，修改模型和思考强度，验证回读结果 | 尚无设置入口 | P0 |
| 附件上传 | 选择文件/图片，分块上传、校验、取消，再随提示发送 | 只支持纯文本提示；无上传、拖入和粘贴图片发送 | P0 |
| 增量对话 | 订阅更新、补丁、失效恢复、临时内容、历史缓存 | 当前为每 3 秒读取选中对话；未接增量订阅，重启后不恢复正文缓存 | P0 |
| 可靠发送 | 状态跟踪、明确拒绝与未知结果区分、草稿保留 | 不自动重发并保存不确定状态；仍将部分明确失败保守地当作未知结果 | P1 |
| 状态监控 | 后台监控多会话、通知分类、静音、完成/等待提醒 | 只轮询当前选中 Agent；无后台监控或完成通知 | P1 |
| 指令与查找 | Agent 斜杠指令列表，对话/终端文本搜索，复制最新回复 | 无斜杠菜单、对话搜索和复制最新回复按钮；正文与工具文本可选择复制 | P1 |
| 会话诊断 | 主会话绑定说明、只读诊断证据、活动记录 | 有未确认状态提示；尚无详细诊断和活动列表 | P1 |
| 文件访问范围 | 控制者为当前设备显式开启工作区外只读访问；根目录和绝对路径跳转 | 固定为选中 Agent 工作区；不自动提升范围，工作区外引用会提示无法打开 | P1 |
| 文件列表 | 隐藏文件切换、目录分页、筛选、路径跳转 | 已有分页、筛选、上下级导航；无隐藏文件开关和手填路径入口 | P1 |
| 精确文档定位 | PDF 指定页、源码行号、标题锚点 | 已有 Markdown 锚点；引用里的 PDF 页码和源码行号尚未接入 | P1 |
| 文件引用发现 | 还会从裸绝对路径、代码片段及工具输出中提取文件卡片 | 当前处理正文/文档中的 Markdown 链接和 Codex 引用；裸路径和工具输出的自动文件卡片尚未补齐 | P1 |
| 文档阅读 | 全屏、缩放、阅读位置恢复、历史跳转、系统分享 | 可展开预览、图像缩放和下载；缺少跨文件前进/后退、长期阅读位置与系统分享 | P2 |
| 下载 | 独立原文件下载、进度、取消、版本校验 | 已有；两端均不提供断点续传 | 已有 |
| 编辑文件 | 源码保留编辑 UI，但由 `canEdit()` 和服务端能力控制 | 当前配套 Relay 未公开 `workspace_file_write`，不能把 Android 的旧入口算成可用编辑功能；桌面保持只读 | 待服务端支持 |
| Git | 工作区 Git 状态和逐文件 diff | 尚无 Git 面板 | P1 |
| 项目/Worktree 管理 | 创建、重命名、关闭、排序、列出/创建/打开/移除 Worktree | 仅展示已有项目和 Agent | P1 |
| 已配对电脑 | 本地改名、删除/忘记电脑、恢复连接 | 支持添加和重连；缺少改名、移除入口 | P1 |
| 配对设备管理 | 已配对设备列表、角色、重命名、撤销、邀请新设备 | 仅能使用已有邀请配对并遵守角色权限 | P1 |
| 连接方式 | HTTPS 邀请/WSS、多个网关、TCP，支持直连协商 | 当前只支持 `herdr-tcp://` 单网关 | P1 |
| Relay 运维 | 检查更新、版本能力、Pi 集成开关 | 无这些管理页面 | P2 |
| 语音 | 本机朗读最新回复、停止朗读 | 未实现 | P2 |
| 扫码与分享 | 相机扫码、Android 分享入口 | 桌面已支持粘贴邀请；可后续增加二维码图片识别和系统分享 | P2 |

## 桌面应保持不同的部分

- 原生终端直接接收键盘、鼠标、中文输入和尺寸变化，不搬运手机的独立输入框、补充按键栏、快照分页和换行补偿。
- 通知应使用桌面通知/托盘与按会话静音，不照搬 Android 前台服务和省电设置。
- 文件入口使用系统保存对话框；后续上传应支持拖入与粘贴图片。
- PDF 使用操作系统内嵌阅读引擎；菜单、对话和终端继续使用 MyGo 原生 UI。

## 建议下一批实现顺序

1. 结构化审批/问答、Agent 新建/管理、模型与思考强度。
2. 附件上传、增量消息订阅和可靠的历史恢复。
3. 完成/等待通知、诊断与活动、Git diff。
4. 设备/项目/Worktree 管理，再扩展 WSS 和多网关。

## 核对源码

Android 主要入口在 [`NativeAgents.kt`](../herdr-android/app/src/main/java/dev/herdr/companion/NativeAgents.kt)、[`MainActivity.kt`](../herdr-android/app/src/main/java/dev/herdr/companion/MainActivity.kt)。分组、计时、引用、图片分别由 [`AgentPresentation.kt`](../herdr-android/app/src/main/java/dev/herdr/companion/AgentPresentation.kt)、[`AgentRunPresentation.kt`](../herdr-android/app/src/main/java/dev/herdr/companion/AgentRunPresentation.kt)、[`ReviewReference.kt`](../herdr-android/app/src/main/java/dev/herdr/companion/ReviewReference.kt)、[`ConversationImages.kt`](../herdr-android/app/src/main/java/dev/herdr/companion/ConversationImages.kt) 实现。监控入口在 [`AgentMonitor.kt`](../herdr-android/app/src/main/java/dev/herdr/companion/AgentMonitor.kt)，配对入口在 [`PairingLink.kt`](../herdr-android/app/src/main/java/dev/herdr/companion/PairingLink.kt)。

桌面当前实现对应 `sidebar.go`、`timeline.go`、`media.go`、`citations.go`、`run_state.go`、`files.go` 和 `internal/pdfview/`。所有写操作扩展都应继续携带精确目标，不通过标题推断会话身份。
