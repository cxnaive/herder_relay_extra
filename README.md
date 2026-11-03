# Herdr Relay Extra + Herdr Pocket

当前可配套使用的 **Relay 源码 + 原生 Android App 源码**。APK 及编译好的 Linux Relay/Gateway 通过本仓库的 **GitHub Releases** 发布，不存放在 Git 仓库目录中。

| 组件 | 版本 | 位置 |
| --- | --- | --- |
| 电脑 Relay | `0.23.5+codexfix.8` | 仓库根目录，`cmd/herdr-mobile-relay` |
| 原生 TCP Gateway | `0.23.5+codexfix.6` | `cmd/herdr-gateway`，`deploy/native-tcp/` |
| Herdr Pocket Android | `0.2.14` / versionCode `17` | `herdr-android/` |

本次按当前可用源码快照替换此前根目录的 0.24.0 文件扩展实现，不把两条不同基线混为同一版本。组件对应关系及源码来源见 [VERSION.json](VERSION.json)。旧内容仍可从 Git 历史查看。

## 下载 Android APK

0.2.14 修复图片读取中断后不补载的问题：重连或回到前台后自动恢复可见预览，合并重复请求，保留已读历史和图片缓存。

0.2.13 包含完成提醒声音检查/测试入口及此前 0.2.12 本地构建的工作空间标题修复。

进入仓库 **Releases → Herdr Pocket 0.2.14 + Relay codexfix.8**，下载 `Herdr-Pocket-0.2.14-debug.apk`。

- Android 9 / API 28 及以上。
- 与此前同签名的 Pocket APK 可直接覆盖安装，**不需要重新配对**。
- 发布包使用调试签名，不是 Play 商店版本；签名私钥不随源码发布。自己构建的 debug APK 通常使用不同签名，不要为了覆盖安装而直接卸载含配对/草稿的旧 App。
- Release 同时提供 SHA-256 校验文件，源码由对应 Git tag 的 Source code 下载项提供。

## 下载 Relay / Gateway 与部署指南

**[完整部署指南：从零安装 → systemd → 配对 → 升级/回滚](docs/native-deployment.md)**

- 公网服务器装 **Gateway**；运行 Herdr/Agent 的电脑以同一用户装 **Relay**；手机装 APK。
- 服务端 Release **`native-v0.23.5-codexfix.8`** 提供 Linux x86_64 / amd64：
  - `herdr-mobile-relay_0.23.5-codexfix.8_linux_amd64.tar.gz`
  - `herdr-gateway_0.23.5-codexfix.6_linux_amd64.tar.gz`
  - `SHA256SUMS.txt`
- 包含二进制、配置/systemd 模板、初始化工具、部署说明和构建来源；无需在目标机器安装 Go。
- 公网业务只需 **8377/TCP**，原生 App 不要求域名/HTTPS；应用数据使用现有 E2EE。健康端口仅 loopback。
- 只更新二进制时保留原密钥和设备库，**不重启 Herdr/Agent**。已有安装先读指南的升级章节，不重复初始化。
- 此服务端 Release 独立于 Android `android-v0.2.14`；Android 保持 Latest。本次未提供 ARM64/macOS/Windows 二进制。

## 已实现

- 工作空间 / Tab 分组与可靠会话定位，修复恢复后的 Codex 历史读取。
- 原生对话、连续工具调用的两层折叠、历史分页/缓存/加载反馈。
- 手机适配终端字体和滚动历史。
- 图片缩放、PDF/Markdown 全屏查看与 Codex 文件引用。
- 指定工作空间新建 Agent、重命名；Codex 模型/思考强度按会话切换，下一轮生效。
- 对话顶部运行状态及真实本轮计时，不以打开页面或最后输出时间代替开始时间。
- **App 内常驻监控**：开启后默认关注全部 Agent，新 Agent 自动纳入，可单独关闭完成提醒。原生通知、按轮次去重、点击回到确切会话；可测试完成提醒并直接进入声音设置。
- **纯 IP + TCP** 或既有 HTTPS/WSS：应用消息沿用设备鉴权和 E2EE，原生模式不需要域名/证书。

## 构建电脑 Relay / Gateway

需要 `go.mod` 指定的 **Go 1.27.3**。首次构建需要下载依赖。
完整 Linux 归档使用 `scripts/package-native-release.sh 新输出目录`，然后 `python3 scripts/check-native-release.py 输出目录`；以下命令只构建单个可执行文件。

```bash
mkdir -p bin
CGO_ENABLED=0 go build -trimpath \
  -ldflags '-s -w -X main.version=0.23.5+codexfix.8 -X main.revision=source-build' \
  -o bin/herdr-mobile-relay ./cmd/herdr-mobile-relay
CGO_ENABLED=0 go build -trimpath \
  -ldflags '-s -w -X main.version=0.23.5+codexfix.6 -X main.revision=source-build' \
  -o bin/herdr-gateway ./cmd/herdr-gateway

go test -p 1 ./...
go vet ./...
```

Android 构建需要 JDK 21、Android SDK 35 / Build Tools 35.0.0：

```bash
cd herdr-android
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
./gradlew --no-daemon testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
```

APK 输出：`herdr-android/app/build/outputs/apk/debug/app-debug.apk`。

## 部署与配对

- 电脑 Relay 安装在运行 Herdr、保存项目/会话的电脑上；公网服务器只部署 Gateway。
- 原生 TCP 业务仅需 **一个公网 TCP 端口 8377**，SSH 另作维护；Gateway HTTP 健康检查绑定 loopback，STUN 可关闭。
- 示例地址只用于文档，必须换成自己的服务器。不要把运行时 `relay.env`、注册秘密、设备凭据、完整邀请链接或二维码提交到仓库。
- `herdr-mobile-relay tcp-pair [设备名]` 通过正在运行的 Relay 的私有 Unix socket 创建限时设备邀请；调用时加载该 Relay 的环境配置。不要用另一进程直接写活跃的设备库。
- 仅更新本地扩展二进制时，不要重启正在工作的 Herdr/Agent；先备份配置，只重启 Relay。

[原生 TCP 配置与安全边界](docs/native-tcp.md) · [Agent 管理与模型设置](docs/agent-management.md) · [状态及计时](docs/agent-run-state.md) · [后台监控说明](herdr-android/BACKGROUND-MONITORING.md)

## 通知与安全边界

常驻监控需要用户开启、允许通知，通知栏提供停止按钮；可增加耗电。Android Doze、厂商省电、强行停止或断网仍可能延迟/漏报。**这是手机常驻连接，不是 FCM/厂商云推送。**目前未进行用户真机锁屏验收。

TCP 本身不加密，应用数据由现有 ECDH/HMAC/AES-GCM E2EE 保护；路由/握手元数据、地址、大小和时序仍可见。未关闭原 HTTPS/WSS 证书校验，也未做独立安全审计。

验证记录：178 个 Android JVM/Robolectric 测试通过，真实本地 TCP+E2EE fixture 覆盖后台监控/静音/重连；未改动的 Relay 沿用前次 Go 全套 tests/vet 和相关 race 验证结果。详细范围与未验证项见 [Android VALIDATION.md](herdr-android/VALIDATION.md)。

## 许可证与历史代码

遵循仓库 [LICENSE](LICENSE) 和第三方各自许可证；Android 依赖/引用来源见 [THIRD_PARTY.md](herdr-android/THIRD_PARTY.md)。保留上游 Go module 路径用于内部导入，不代表本分支由上游发布。

`web/` 是当前 Relay 配套的原始生成快照，未手工改写 JS/压缩产物；手机 App 不使用此网页作为 Agent UI。`QUICKSTART.md`、上游 CI 和部分网页文档仍保留上游工作流；本地扩展发布以本首页和 VERSION.json 为准，Android release tag 使用 `android-v...`，不触发上游 `v*` 插件发布流水线。
