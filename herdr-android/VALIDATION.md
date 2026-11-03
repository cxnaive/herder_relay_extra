# 验证记录 · Android 0.2.11 / Relay codexfix.8

验证日期：2026-11-03。

## Android

- 129 个 JVM/Robolectric 测试通过；`testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` 成功。
- Lint：0 errors / 79 warnings。Debug APK 的 versionCode 为 14、versionName 为 0.2.11。
- 原生 TCP fixture 验证 gateway 分帧、ECDH/HMAC/AES-GCM E2EE、后台监控、单个静音、重连不重复通知及新轮次提醒。fixture 不接触用户真实会话。
- 前台 Activity 与 Service 共用连接；前台退场后监控继续，停止服务释放 wake lock；权限拒绝/撤销、Android 15 specialUse 声明和通知跳转已覆盖。
- 回归覆盖工作空间/Tab 隔离、Codex 会话匹配、分页缓存、工具双层折叠、图片/PDF/Markdown 查看、模型与思考强度、运行状态与真实轮次计时。
- `screenshots/qa-0.2.11` 中两张图片由原生 UI 测试生成，使用虚构数据，不是真机截图。

## Relay

- 当前 Relay 为 `0.23.5+codexfix.8`，基于 upstream 0.23.5 的本地扩展；不是仓库旧版 0.24.0 文件扩展的简单版本升级。
- Go 全套测试和 vet 通过；codexruntime/protocol/app 以及涉及 TCP、鉴权、路由和并发的相关 race 测试通过。
- 私有部署的只读验收确认多 Agent 的运行状态、模型目录、历史分页和文件传输可用。未把真实会话内容、部署地址、配对信息或原始日志纳入仓库。

## 边界

- 当前无物理 Android 设备连接。没有把 Robolectric 或 TCP fixture 当作真实厂商锁屏/Doze/后台保活验收。
- 常驻监控不是 FCM/厂商推送，强行停止、省电、清理和断网仍可延迟通知；详见 BACKGROUND-MONITORING.md。
- APK 为调试签名构建，不是 Play 商店发布。仓库不含签名私钥；自行构建的 debug 包通常不能覆盖这里的签名包。
- 没有独立密码学或安全审计；native TCP 本身不提供 TLS，应用数据通过 E2EE v2 加密，路由/握手元数据仍可见。
