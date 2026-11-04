# Herdr Relay Extra + Herdr Pocket

<!-- pocket-preview:start -->
<table>
  <tr>
    <td align="center" valign="top" width="25%">
      <a href="images/pocket/agents.png"><img src="images/pocket/agents.png" width="200" alt="Herdr Pocket：电脑与工作空间分组" /></a>
      <br /><sub>电脑与工作空间分组</sub>
    </td>
    <td align="center" valign="top" width="25%">
      <a href="images/pocket/conversation.png"><img src="images/pocket/conversation.png" width="200" alt="Herdr Pocket：原生对话与工具折叠" /></a>
      <br /><sub>原生对话与工具折叠</sub>
    </td>
    <td align="center" valign="top" width="25%">
      <a href="images/pocket/terminal.png"><img src="images/pocket/terminal.png" width="200" alt="Herdr Pocket：终端与附加按键" /></a>
      <br /><sub>终端与附加按键</sub>
    </td>
    <td align="center" valign="top" width="25%">
      <a href="images/pocket/workspace.png"><img src="images/pocket/workspace.png" width="200" alt="Herdr Pocket：工作区文件浏览" /></a>
      <br /><sub>工作区文件浏览</sub>
    </td>
  </tr>
</table>

<sub>0.2.23 界面预览 · 使用演示数据的原生控件渲染，点击查看大图。</sub>
<!-- pocket-preview:end -->

当前可配套使用的 **Relay 源码 + 原生 Android App 源码**。APK 及编译好的 Linux Relay/Gateway 通过本仓库的 **GitHub Releases** 发布，不存放在 Git 仓库目录中。

| 组件 | 当前源码版本 | 位置 |
| --- | --- | --- |
| 电脑 Relay | `0.23.5+codexfix.14` | 仓库根目录，`cmd/herdr-mobile-relay` |
| 原生 TCP Gateway | `0.23.5+codexfix.6` | `cmd/herdr-gateway`，`deploy/native-tcp/` |
| Herdr Pocket Android | `0.2.27` / versionCode `30` | `herdr-android/` |

本次按当前可用源码快照替换此前根目录的 0.24.0 文件扩展实现，不把两条不同基线混为同一版本。组件对应关系及源码来源见 [VERSION.json](VERSION.json)。旧内容仍可从 Git 历史查看。

自 **Android 0.2.24 / Relay codexfix.12** 起引入主会话验证：验证终端主进程、独占 runtime 与会话存储，不再信任错误的 Herdr session ID；暂时失去证据时保留原会话缓存和草稿，但禁止不确定的输入。旧 APK 不支持新的绑定租约，需配套更新；Gateway codexfix.6 不变。[发现机制与边界](docs/primary-session-binding.md)。

当前源码仅对 **Codex** 使用终端范围内的 runtime 主会话发现：先排除有明确父子关系的子代理，主线程唯一时直接采用；共享模式仍有多个主线程时，只用匹配候选的 Herdr 上报 ID 消除歧义。无法确认时不猜选，提示可尝试 `codex --no-daemon` 并检查 hook（不保证修复）。其他 agent 保持原有 Herdr Relay 流程；不执行 `/status`、动态 hook 或修改 Codex。相同 ID 刷新缓存，不同 ID 撤销旧请求并隔离历史，详见[会话身份与缓存边界](docs/primary-session-binding.md)。

**`--no-daemon` 已有独立关联路径**：只读验证该 TUI 进程实际持有的会话写锁及精确 ID 元数据，不查询共享后台。主线程唯一即可绑定，不依赖 hook；新会话可能要等首轮输入落下元数据。若 `/new`、`/resume` 后仍持有多个主线程，旧 hook 上报不作消歧依据，保持未确认。独立模式模型设置请在电脑端操作，运行计时不冒用共享后台；其他 agent 不受影响。

Pocket `0.2.27` 修复终端输出刷新打断手机输入法编辑：普通快照/增量不再重设输入框类型，保留拼音编辑状态、光标与草稿；密码输入仍单独处理。本次只更新 App，Relay codexfix.14 / Gateway codexfix.6 不变。

“会话绑定诊断”通过[独立只读接口](docs/session-binding-diagnostics.md)提供带版本的可扩展 JSON；手机不查询可操作候选、不分析子代理或确认主会话，也不会自动上传诊断。

下方链接指向已发布版本；`VERSION.json` 的 `android_release` / `native_release` 记录精确的产物来源和源码提交，独立于后续文档更新。

<!-- android-downloads:start -->
## 下载 Android APK

**[0.2.27 / versionCode 30](https://github.com/cxnaive/herder_relay_extra/releases/tag/android-v0.2.27)**。

| 设备架构 | 下载 |
| --- | --- |
| ARM64 手机 / 平板 | [Release · arm64-v8a](https://github.com/cxnaive/herder_relay_extra/releases/download/android-v0.2.27/Herdr-Pocket-0.2.27-release-arm64-v8a.apk) |
| 32 位 ARM | [Release · armeabi-v7a](https://github.com/cxnaive/herder_relay_extra/releases/download/android-v0.2.27/Herdr-Pocket-0.2.27-release-armeabi-v7a.apk) |
| x86_64 模拟器 / 设备 | [Release · x86_64](https://github.com/cxnaive/herder_relay_extra/releases/download/android-v0.2.27/Herdr-Pocket-0.2.27-release-x86_64.apk) |

附件还包含三种 ABI 的 Debug APK、`BUILD-INFO.json` 和 `SHA256SUMS.txt`。
所有 APK 直接复用 CI 固定签名产物，不重新编译或签名。**直接覆盖安装，不要卸载旧版**，以保留配对和草稿。
Android 9 / API 28 及以上；仍使用原有调试证书，不是 Play 商店版本。自行构建的签名可能不同。

发布来源：[CI run 41902832600](https://github.com/cxnaive/herder_relay_extra/actions/runs/41902832600)，源码 `9c0d8e264216f5fe8c52a9bf129cfad05e10cca3`。
构建成功不等于完整 check 或真机验收全绿；验证范围和已知缺口见 Release 说明及 `herdr-android/VALIDATION.md`。
历史版本和更新内容保留在 Releases / CHANGELOG。
<!-- android-downloads:end -->

## CI 构建、固定签名与发布

**Release 全部复用 CI 构建产物，发布也在 GitHub Actions 完成，不经本机中转。**

- `build-artifacts`：构建 Linux/macOS 四架构 Relay/Gateway、Linux amd64 完整部署包，以及三种 ABI 的 Android APK；可信分支使用原 Pocket 证书签名。
- `publish-components`：指定成功构建的 run ID，先只读验证，再明确发布；自动更新 README 下载链接、VERSION 发布来源和部署指南。不会在每次 push 时自动发版。
- 已发布的同名版本不覆盖、不换包；冲突先升版本号。源标签始终指向产物的精确 commit，README 后续文档提交不移动标签。
- CI 产物统一保留一天；GitHub Release 附件不受此期限影响。发布流程不重启服务器。

操作入口：**Actions → publish-components → Run workflow**。详见[发布操作、签名与安全边界](docs/native-ci.md)。

<!-- native-downloads:start -->
## 下载 Relay / Gateway 与部署指南

**[完整部署指南](docs/native-deployment.md)** · **[native-v0.23.5-codexfix.14](https://github.com/cxnaive/herder_relay_extra/releases/tag/native-v0.23.5-codexfix.14)**

Linux x86_64 / amd64 完整部署包：

- [`herdr-mobile-relay_0.23.5-codexfix.14_linux_amd64.tar.gz`](https://github.com/cxnaive/herder_relay_extra/releases/download/native-v0.23.5-codexfix.14/herdr-mobile-relay_0.23.5-codexfix.14_linux_amd64.tar.gz)
- [`herdr-gateway_0.23.5-codexfix.6_linux_amd64.tar.gz`](https://github.com/cxnaive/herder_relay_extra/releases/download/native-v0.23.5-codexfix.14/herdr-gateway_0.23.5-codexfix.6_linux_amd64.tar.gz)
- [`SHA256SUMS.txt`](https://github.com/cxnaive/herder_relay_extra/releases/download/native-v0.23.5-codexfix.14/SHA256SUMS.txt)

包含二进制、Relay Web、配置/systemd 模板、初始化工具、部署说明及 BUILD-INFO。
其他架构的 `native-binaries-*` 仅是 CI 可执行文件，不是完整部署包。
公网服务器装 Gateway，运行 Herdr/Agent 的电脑装 Relay；已运行同版 Gateway 无需因 Relay 更新而重启。
升级保留现有配置、密钥和设备库，不重复初始化，不重启 Herdr/Agent；发布流程不部署服务器。

发布来源：[CI run 41895522095](https://github.com/cxnaive/herder_relay_extra/actions/runs/41895522095)，源码 `3d343b1d5021d2fb089fc19342dbceb234ee70f4`。
Android 为 Latest；服务端版本和 Android 版本分别编号。
<!-- native-downloads:end -->

## 已实现

- 工作空间 / Tab 分组与可靠会话定位，修复恢复后的 Codex 历史读取。
- 原生对话、连续工具调用的两层折叠、历史分页/缓存/加载反馈。
- 手机适配终端字体和滚动历史。
- 图片缩放、PDF/Markdown 全屏查看与通用本地文件引用；超限文件仍可下载/分享，支持进度与取消。
- 可选的工作区外只读浏览/预览/下载：默认关闭，按手机凭据在 Relay 端授权，设备页可随时关闭。
- 大图/工具结果挤出近期日志窗口时补回中间文字和看图调用，保持在工具双层折叠外。
- 指定工作空间新建 Agent、重命名；Codex 模型/思考强度按会话切换，下一轮生效。
- 对话顶部运行状态及真实本轮计时，不以打开页面或最后输出时间代替开始时间。
- **App 内常驻监控**：开启后默认关注全部 Agent，新 Agent 自动纳入，可单独关闭完成提醒。原生通知、按轮次去重、点击回到确切会话；可测试完成提醒并直接进入声音设置。
- **纯 IP + TCP** 或既有 HTTPS/WSS：应用消息沿用设备鉴权和 E2EE，原生模式不需要域名/证书。

## 本地开发构建（不用于 Release 发布）

需要 `go.mod` 指定的 **Go 1.27.3**。首次构建需要下载依赖。
完整 Linux 归档使用 `scripts/package-native-release.sh 新输出目录`，然后 `python3 scripts/check-native-release.py 输出目录`；以下命令只构建单个可执行文件。

```bash
mkdir -p bin
CGO_ENABLED=0 go build -trimpath \
  -ldflags '-s -w -X main.version=0.23.5+codexfix.14 -X main.revision=source-build' \
  -o bin/herdr-mobile-relay ./cmd/herdr-mobile-relay
CGO_ENABLED=0 go build -trimpath \
  -ldflags '-s -w -X main.version=0.23.5+codexfix.6 -X main.revision=source-build' \
  -o bin/herdr-gateway ./cmd/herdr-gateway

go test -p 1 ./...
go vet ./...
```

Android 构建需要 JDK 21、Android SDK 35 / Build Tools 35.0.0；Gradle Wrapper 固定为 8.14.6：

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

验证记录：226 个 Android JVM/Robolectric 测试通过，真实本地 TCP+E2EE fixture 覆盖后台监控/静音/重连、超限文件下载/分享、工作区外权限切换、文件链接与系统保存后恢复原名；Relay codexfix.11 的 Go 全套 tests/vet 和 workspace/deviceauth/app/protocol race 检查通过。详细范围与未验证项见 [Android VALIDATION.md](herdr-android/VALIDATION.md)。

## 许可证与历史代码

遵循仓库 [LICENSE](LICENSE) 和第三方各自许可证；Android 依赖/引用来源见 [THIRD_PARTY.md](herdr-android/THIRD_PARTY.md)。保留上游 Go module 路径用于内部导入，不代表本分支由上游发布。

`web/` 是当前 Relay 配套的原始生成快照，未手工改写 JS/压缩产物；手机 App 不使用此网页作为 Agent UI。`QUICKSTART.md`、上游 CI 和部分网页文档仍保留上游工作流；本地扩展发布以本首页和 VERSION.json 为准，Android release tag 使用 `android-v...`，不触发上游 `v*` 插件发布流水线。
