# 验证记录 · Android 0.2.13 / Relay codexfix.8

验证日期：2026-11-03。

## Android

- 0.2.13 在 Android API 28 / 35 上验证新通道的通知铃声/振动设置、常驻状态静音、旧通道的静音/自选铃声保留、权限拒绝、默认无铃声/音量/勿扰提示，以及测试通知不启动监控、不伪造完成、不修改轮次去重。
- 声音测试与真实完成提醒共用通道和 Builder；UI 测试验证直达正确通知通道的设置页。`screenshots/qa-0.2.13/monitor-settings.png` 使用合成状态，已目视核对，不代表用户手机的实际音量。
- **没有接入用户手机或进行真机试听**。测试证明通知配置/路由和状态检测符合预期，不证明厂商系统在锁屏、勿扰或限流条件下一定发声。升级保留旧通道选择；用户可点击测试并在系统中选择有声铃声。

- 0.2.12 的新增回归在修复前复现对话标题/完成通知串工作空间，修复后通过；覆盖电脑/服务会话隔离、同目录不同工作空间、缺失元数据回退、空工作空间保留及工作空间改名。
- `WorkspaceLabelUiTest` 使用虚构数据生成 `screenshots/qa-0.2.12/workspace-header.png`，已目视核对；同时验证标题更新不改变选中会话的目标身份。

- 162 个 JVM/Robolectric 测试通过；`testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` 成功。
- Lint：0 errors / 61 warnings。Debug APK 的 versionCode 为 16、versionName 为 0.2.13。
- 原生 TCP fixture 验证 gateway 分帧、ECDH/HMAC/AES-GCM E2EE、后台监控、单个静音、重连不重复通知及新轮次提醒。fixture 不接触用户真实会话。
- 前台 Activity 与 Service 共用连接；前台退场后监控继续，停止服务释放 wake lock；权限拒绝/撤销、Android 15 specialUse 声明和通知跳转已覆盖。
- 回归覆盖工作空间/Tab 隔离、Codex 会话匹配、分页缓存、工具双层折叠、图片/PDF/Markdown 查看、模型与思考强度、运行状态与真实轮次计时。
- `screenshots/qa-0.2.11` 中两张图片由原生 UI 测试生成，使用虚构数据，不是真机截图。

## Relay

本次只修改 Android 显示与通知设置；Relay/Gateway 版本、部署和正在运行的 Agent 均未改变。以下为前次验证记录，本次未重跑 Go 测试。

- 当前 Relay 为 `0.23.5+codexfix.8`，基于 upstream 0.23.5 的本地扩展；不是仓库旧版 0.24.0 文件扩展的简单版本升级。
- Go 全套测试和 vet 通过；codexruntime/protocol/app 以及涉及 TCP、鉴权、路由和并发的相关 race 测试通过。
- 私有部署的只读验收确认多 Agent 的运行状态、模型目录、历史分页和文件传输可用。未把真实会话内容、部署地址、配对信息或原始日志纳入仓库。

## 边界

- 当前无物理 Android 设备连接。没有把 Robolectric 或 TCP fixture 当作真实厂商锁屏/Doze/后台保活验收。
- 常驻监控不是 FCM/厂商推送，强行停止、省电、清理和断网仍可延迟通知；详见 BACKGROUND-MONITORING.md。
- APK 为调试签名构建，不是 Play 商店发布。仓库不含签名私钥；自行构建的 debug 包通常不能覆盖这里的签名包。
- 没有独立密码学或安全审计；native TCP 本身不提供 TLS，应用数据通过 E2EE v2 加密，路由/握手元数据仍可见。
