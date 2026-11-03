# 验证记录 · Android 0.2.22 / Relay codexfix.11

## 0.2.22 · 终端历史与附加按键

验证完成于 2026-11-04（Asia/Shanghai），包含此前未单独发布的 0.2.21 UI 改进；发布标签为 `android-v0.2.22`。

- **248 个 JVM/Robolectric 测试通过，0 failures / 0 errors / 0 skipped**；`testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` 成功，lint **0 errors / 62 warnings**。versionCode **25** / versionName **0.2.22**；与此前 APK 相同的调试签名。
- 真实本地 TCP + E2EE fixture 返回 `pane_content` 原始帧（不是模拟 `command_result` 替代），覆盖 500→1,500 行加载、等待动画及输入禁用、prepend 位置、刷新后不拉回底部、失败保留旧画面和恢复实时订阅、重复/迟到响应丢弃。
- 远端 Backspace/Enter 不动手机草稿；PgUp 发送固定 VT 控制序列，Ctrl-W 发送 Herdr 接受的组合键名。fixture 核对完整 Agent/session/terminal/cwd，测试旧菜单、重建页面与未确认投递禁用，不向真实 Agent 发测试按键。
- API 28/35 验证嵌套横纵滚动的触摸读取、先排队刷新后拖动的顺序、补载位置及顶部只读加载。Ctrl/Alt 是显式组合菜单，非锁定修饰键；紧凑布局保留退格并在测量前释放第二排空间，菜单仍提供方向/翻页。
- 对实际部署只读检查了两个 Codex 终端，请求 500 和 1,500 行均返回同一屏 **47 行**，不能靠增加 App 行数还原。没有启用会滚动电脑真实窗格的 text/recent 历史采集；无更多行时提示并提供明确的远端翻页/结构化对话入口。远端 PgUp/PgDn 是否翻历史取决于目标程序，不宣称可以恢复所有历史。
- 合成终端截图 `app/build/reports/terminal-ui/` 已目视检查：400dp、360dp/1.3 倍字体、360dp/短视口，真实启用的按键和历史加载状态。没有连接 Android 真机或完整模拟器；本次不宣称所有输入法/远端程序键位已验收。
- 全套已有配对、下载、恢复原名、图片/文档、通知和对话历史回归保留。**仅更新 App，不更改或重启 Relay/Gateway/Herdr/Agent，不改用户的配对和文件权限**。用法见 `TERMINAL.md`。

## 0.2.21 · Impeccable 原生 UI 打磨（本地构建）

- **235 个 JVM/Robolectric 测试通过**，Debug/AndroidTest APK 构建与 lint 成功；lint **0 errors / 63 warnings**。versionCode **24** / versionName **0.2.21**，保持原调试签名，尚未提交/发布。
- 依据用户提供的 `pbakaus/impeccable` 固定 revision 的 Android、Operate/Read、quieter/typeset/layout/polish 指南进行本地评审和打磨，详见 `DESIGN.md`。未安装/运行其 launcher，也未用网页 detector 的空结果冒充 Android 验收；无新增运行时依赖。
- 新原生场景覆盖 Agent 列表、对话、两层工具、终端、设备页、文件目录与文档查看；400×850dp、360×800dp/1.3 倍字体、800×600dp，另有 400×500dp 键盘高度模拟。图像均为 Views/Canvas 渲染，使用虚构 Agent、路径和内容；对照基线取自上次发布 commit 的隔离源码副本。
- 检查文字/次要文字/链接颜色组合 ≥4.5:1；共享操作与输入动作至少 48dp；运行中的真实计时保持可见并减少固定状态区高度。测试约束消息区仍有可用高度、输入草稿/发送回调不变、侧栏/底栏适配及当前 Agent 身份不变。
- 全套现有回归覆盖 TCP/E2EE、通知/监控、历史恢复、图片/PDF/Markdown、下载/保存后恢复原名；本次没有改变这些协议或权限逻辑。
- **没有连接真机或安装模拟器**：截图证据为 Robolectric 原生绘制，不是浏览器，也不宣称硬件触摸/帧率/TalkBack 已验收。现有原生 Back、system bar/IME insets 保留。仅设计深色方案，本次不宣称浅色/动态取色支持。
- 新合成图放在 `app/build/reports/ui-polish/after/`；本地交付的 UI 预览和对比图由这些截图拼接。旧版本已提交截图保持原样。
- 仅更新 Android；Relay、Gateway、Herdr/Agent 和已配对设备权限不变。

## 先前验证记录

## 0.2.20 · 保存后恢复原名

- **226 个 JVM/Robolectric 测试通过**，`testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` 成功；lint **0 errors / 64 warnings**。versionCode **23** / versionName **0.2.20**，保持原调试签名，发布标签 `android-v0.2.20`；包含此前未单独发布的 0.2.18/0.2.19 本地修复。
- API 28/35 的模拟文档提供程序覆盖：原名已正确、不支持/拒绝改名、返回 null、继续强制后缀、改名改变 URI、原名碰撞、查询失败、仅支持名称投影、跨提供程序 URI 拒绝。未知后缀、多个后缀、中文/大小写、无后缀、隐藏文件和原本为 `.bin` 的名称均按完整原名校验。
- 实际系统保存回调测试覆盖完整分片下载后的自定义文件名恢复，以及旧 Relay/草稿导出的小文件保存入口；取消不写入或改名。写入失败不会进入改名阶段，已存在的同名测试文件保持原字节。所有测试内容均为合成数据。
- 改名返回新 URI 时，只用返回位置复核实际名称，旧 URI 不能继续读取也能成功；未确认恢复时显示持续的部分成功说明、原名/实际名和复制原名按钮。`build/reports/restore-name/partial-success.png` 为合成原生界面，已目视核对。
- **未接入物理 Android 手机或厂商文件管理器验收**。测试验证标准文档接口和回调链路，不保证每种文件提供程序支持改名或接受任意文件名；不删除或强制覆盖冲突文件。
- 仅改 Android；不更新/重启 Relay、Gateway、Herdr 或 Agent，不修改电脑端原文件和既有读取权限。

## 先前验证记录

## 0.2.19 · 系统保存后缀（本地构建）

- **210 个 JVM/Robolectric 测试通过**，`testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` 成功；lint **0 errors / 64 warnings**。versionCode **22** / versionName **0.2.19**，原调试签名，尚未提交/发布；包含 0.2.18 本地修复。
- 失败前回归证明 APK 保存 Intent 的类型仍是 `application/octet-stream`；修复后传 `application/vnd.android.package-archive`，保留原 `EXTRA_TITLE`。API 28/35 上覆盖大小写、中文空格、PDF、ZIP、文本、CSV、图片、Office、无后缀及未知后缀，明确保留通用类型回退边界。
- 保存和分享共用后缀匹配策略；APK 不再采用 ZIP 内容嗅探类型。网络分片、校验和进度逻辑不变，不修改原文件内容。
- 原生 TCP+E2EE 对话下载用实际 Activity `pause/stop → 系统选择位置 → restart/start/resume` 回调验证保存；完整校验后的本地副本不再依赖已关闭的查看器。字节一致性、取消和仍在进行的读取权限隔离测试保留。
- 未接入用户手机/厂商系统文件管理器真机验收；验证 Intent 文件名/MIME 和保存流程，不宣称控制任意第三方文件提供程序命名。未知格式仍使用通用二进制回退。
- 仅改 Android；Relay/Gateway/Herdr/Agent 未更新或重启，已有文件读取授权不变。

## 先前验证记录

## 0.2.18 · 文件链接误识别（本地构建）

- **205 个 JVM/Robolectric 测试通过**，`testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` 成功；lint **0 errors / 64 warnings**。versionCode **21** / versionName **0.2.18**，保持原调试签名，尚未提交/发布。
- 修复前回归复现：发布说明中的仓库 slug、两个 Release 标签、仅提及的 APK/校验文件名被错误转为当前 cwd 下的文件卡片。修复后这些代码文本不再推断为文件，明确 Markdown/Codex 文件引用仍保持任意文件类型支持；假候选不会耗尽八个文件卡片名额。
- 新增真实 TCP+E2EE 原生对话 fixture：服务器推送 `filesystem_read:true`，App 从三个对话链接/卡片打开工作区外 APK、源码包和校验文件；校验确切 Agent target、cwd、filesystem scope 和根目录相对路径。通过实际 Android 保存位置选择器保存校验文件，核对原始字节一致。
- 对话渲染不会为疑似名称发起文件探测。显式引用不存在时只尝试一次原路径，中文错误包含实际绝对路径；不跨目录搜索或降级权限。合成截图 `build/reports/file-links/` 已目视核对。
- 现有配对权限下，公网 TCP+E2EE 只读核对真实 APK/源码包/校验文件的元数据均成功；把仓库名/发布标签拼在工作目录后的三个伪路径均准确复现 `Workspace file was not found`。未把私有凭据/生产会话日志放入测试或源码。
- **仅修改 Android，不更新或重启 Relay/Gateway/Herdr/Agent**；已开启的工作区外只读授权保留。没有连接物理 Android 手机验收。

## 先前版本验证记录

## 0.2.17 / Relay codexfix.11 · 文件下载与可选扩展读取

- **200 个 JVM/Robolectric 测试通过**；`testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` 成功，lint **0 errors / 64 warnings**。APK versionCode **20** / versionName **0.2.17**，保持原调试签名；发布标签为 `android-v0.2.17` / `native-v0.23.5-codexfix.11`。
- 原生 UI 验证超限文件仍打开详情，下载/分享均可点击；不支持预览的链接也能打开。验证 Android 系统保存位置选择器、FileProvider 分享、分片进度、取消/权限范围变化清理临时文件、迟到响应丢弃。
- TCP + E2EE fixture 从文件列表打开 13 MiB 以上的文件，不请求预览；分片完成后实际读回分享 URI，逐字节核对。验证开启前的风险确认、开启后 `/` 根目录与 Agent 工作目录跳转、关闭授权，以及每个请求的确切 Agent 身份和显式 scope。
- Go 全套 tests/vet 通过，`workspace/deviceauth/app/protocol` race 通过；覆盖默认拒绝、设备隔离/持久化、撤销/版本变化、只读动作限制、路径逃逸、分片偏移/散列/大小和同大小文件变化。
- 仅更新电脑 Relay 至 **codexfix.11**；公网 TCP+E2EE 验证工作区外合成文件 **13,631,505 bytes / 27 chunks** 完整下载且 SHA-256 相符，并验证目录读取、旧 Agent 身份拒绝、workspace scope 未被隐式扩展。Herdr PID 和 Agent terminal/session 清单保持不变，Gateway 未变。
- 合成文件详情截图已目视检查；**没有连接物理 Android 设备验收**。权限需由用户显式开启，新设备默认关闭；不在源码或测试报告中记录生产设备凭据、真实文件或对话内容。

## 先前版本验证记录

验证日期：2026-11-03。

## Android

- 0.2.16 针对近期原始日志被大图片/工具结果挤出窗口导致的中途缺口，覆盖同版本近期补读、保留已读旧页/游标、插入顺序、版本变化/停滞游标拒绝及补读边界。
- 真实本地 TCP+E2EE UI fixture 预置“旧用户消息 + 旧工具缓存”，新尾页只有后续命令；验证客户端继续回读中间说明和 view_image，顺序正确且两层工具保持折叠，图片/文字在折叠外。
- `screenshots/qa-0.2.16/recovered-commentary.png` 是虚构会话和合成图片，已目视核对。没有连接真实 Android 手机。此本地修复随 0.2.17 一并发布。

- 0.2.15 新增按目录分页读取；真实本地 TCP+E2EE UI fixture 验证根目录补页、进入子目录和返回上层分别请求正确 path，目录全路径/Tab 名称及只读能力文案正确，空目录独立显示；默认隐藏点号条目，开关可显示并恢复隐藏。
- 分页模型验证根目录/路径/版本匹配、重复页/越界条目/不完整页拒绝；旧响应不会被接受为另一个目录的内容。截图 `screenshots/qa-0.2.15/workspace-directory.png` 使用虚构目录，已目视核对。
- 0.2.15 / codexfix.9 分别按 `android-v0.2.15` / `native-v0.23.5-codexfix.9` 发布；Gateway 仍为 codexfix.6。无物理 Android 设备连接。

- 0.2.14 修复前的回归测试复现了“失败缓存不重试”和“历史重绘重复请求”；修复后覆盖离线打开、断线重连、前后台切换、同图请求合并、最多两个并发读取、有限重试/手动重试、旧会话迟到回调丢弃及已读图片缓存保留。
- 真实本地 TCP + E2EE fixture 在 `workspace_file` 读取中途断开连接，验证同一图片重连恢复；关闭前台期间加入新的 `view_image` 历史，回到对话同步后可见范围内的图片正常读取。没有发送 Agent 任务或修改生产会话。
- `screenshots/qa-0.2.14/reconnected-image.png` 是原生测试界面与合成图像，已目视核对。图片在工具折叠外显示，全屏查看入口不因缩略图读取失败被取消。没有接入用户手机进行真机验收。
- 0.2.14 按 `android-v0.2.14` 发布同签名 Debug APK 和 SHA-256 校验文件。服务端代码/版本/部署未修改；图片源文件删除、覆盖或越过工作区边界不属于可自动恢复的网络失败。

- 0.2.13 在 Android API 28 / 35 上验证新通道的通知铃声/振动设置、常驻状态静音、旧通道的静音/自选铃声保留、权限拒绝、默认无铃声/音量/勿扰提示，以及测试通知不启动监控、不伪造完成、不修改轮次去重。
- 声音测试与真实完成提醒共用通道和 Builder；UI 测试验证直达正确通知通道的设置页。`screenshots/qa-0.2.13/monitor-settings.png` 使用合成状态，已目视核对，不代表用户手机的实际音量。
- **没有接入用户手机或进行真机试听**。测试证明通知配置/路由和状态检测符合预期，不证明厂商系统在锁屏、勿扰或限流条件下一定发声。升级保留旧通道选择；用户可点击测试并在系统中选择有声铃声。

- 0.2.12 的新增回归在修复前复现对话标题/完成通知串工作空间，修复后通过；覆盖电脑/服务会话隔离、同目录不同工作空间、缺失元数据回退、空工作空间保留及工作空间改名。
- `WorkspaceLabelUiTest` 使用虚构数据生成 `screenshots/qa-0.2.12/workspace-header.png`，已目视核对；同时验证标题更新不改变选中会话的目标身份。

- 188 个 JVM/Robolectric 测试通过；`testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` 成功。
- Lint：0 errors / 60 warnings。Debug APK 的 versionCode 为 19、versionName 为 0.2.16。
- 原生 TCP fixture 验证 gateway 分帧、ECDH/HMAC/AES-GCM E2EE、后台监控、单个静音、重连不重复通知及新轮次提醒。fixture 不接触用户真实会话。
- 前台 Activity 与 Service 共用连接；前台退场后监控继续，停止服务释放 wake lock；权限拒绝/撤销、Android 15 specialUse 声明和通知跳转已覆盖。
- 回归覆盖工作空间/Tab 隔离、Codex 会话匹配、分页缓存、工具双层折叠、图片/PDF/Markdown 查看、模型与思考强度、运行状态与真实轮次计时。
- `screenshots/qa-0.2.11` 中两张图片由原生 UI 测试生成，使用虚构数据，不是真机截图。

## Relay

- codexfix.10 的失败前回归复现了大图片结果之后错误 reset / 中途说明和图像调用缺失；修复后追加回读找到已有记录连接点，保留中间工具和文字。正常窗口移动的说明/SourceTruncated 不再引起不必要的重置。
- Codex 结构化 image/input_image/output_image 块不再变成巨量 Base64 工具文本；原有文字和 view_image 路径关联保留。普通字符串和非图片工具输出保持原样。
- 回读使用原有确切会话 scope 和分页校验，覆盖上下文取消、跨源 revision、游标停滞及最多 32 步的边界。不会读取其他会话或修改原始日志。
- Go 全套 tests/vet 及 app/conversation/protocol/transport race 均通过。私有公网 TCP 回读核对受影响轮次的 14 条说明/看图记录完整，结构化图片结果为短标记，不再包含 Base64 工具文本；测试日志和原文未纳入源码。
- 仅重启电脑 Relay 切到 codexfix.10，Herdr PID 和 Agent terminal/session 清单保持不变，Gateway 未更新。该本地修复随 0.2.17 / codexfix.11 一并发布。

- 新增只读 `workspace_list` / `workspace_directory_v1`：只列当前目录，最多 200 项/页，响应条目预算 192 KiB，最多扫描当前目录 100,000 项，不递归子目录；使用上下文超时和两个并发槽。
- 沿用 descriptor-rooted 文件系统访问，拒绝符号链接目录、路径逃逸及无效/过期游标；绑定已鉴权设备、确切 Agent/服务会话/terminal/generation 和工作目录，读取后再次检查授权与身份。不增加文件写入权限。
- Go 回归复现旧树 4,000 项被一个早期子树占满，验证新根目录完整、分层读取、无重复分页、版本变化、取消读取、长路径消息预算和符号链接拒绝。
- Go 全套测试与 vet、workspace/app/protocol/transport/gateway/nativeconn 的 race 测试通过。
- 电脑 Relay 已仅替换至 codexfix.9；Gateway 不变，未重启 Herdr/Agent。部署前后 Agent terminal/session 清单及 Herdr PID 一致。
- 私有本机加密连接与正式公网 TCP 链路均验证两个同目录 Agent 的根目录和子目录分页与磁盘一致，跨会话伪造 target 和路径逃逸被拒绝。用户目录名/真实会话标识/配置及凭据未纳入公开源码或测试数据。

## 边界

- 当前无物理 Android 设备连接。没有把 Robolectric 或 TCP fixture 当作真实厂商锁屏/Doze/后台保活验收。
- 常驻监控不是 FCM/厂商推送，强行停止、省电、清理和断网仍可延迟通知；详见 BACKGROUND-MONITORING.md。
- APK 为调试签名构建，不是 Play 商店发布。仓库不含签名私钥；自行构建的 debug 包通常不能覆盖这里的签名包。
- 没有独立密码学或安全审计；native TCP 本身不提供 TLS，应用数据通过 E2EE v2 加密，路由/握手元数据仍可见。
