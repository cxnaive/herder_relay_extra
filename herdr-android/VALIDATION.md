# 验证记录 · Android 0.2.15 / Relay codexfix.9

验证日期：2026-11-03。

## Android

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

- 183 个 JVM/Robolectric 测试通过；`testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` 成功。
- Lint：0 errors / 58 warnings。Debug APK 的 versionCode 为 18、versionName 为 0.2.15。
- 原生 TCP fixture 验证 gateway 分帧、ECDH/HMAC/AES-GCM E2EE、后台监控、单个静音、重连不重复通知及新轮次提醒。fixture 不接触用户真实会话。
- 前台 Activity 与 Service 共用连接；前台退场后监控继续，停止服务释放 wake lock；权限拒绝/撤销、Android 15 specialUse 声明和通知跳转已覆盖。
- 回归覆盖工作空间/Tab 隔离、Codex 会话匹配、分页缓存、工具双层折叠、图片/PDF/Markdown 查看、模型与思考强度、运行状态与真实轮次计时。
- `screenshots/qa-0.2.11` 中两张图片由原生 UI 测试生成，使用虚构数据，不是真机截图。

## Relay

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
