# Herdr Pocket · Android

与本仓库电脑 Relay 配套的原生 Android 客户端，不使用 WebView 运行 Agent 界面。

当前源码 `0.2.26` / versionCode `29` 配合 Relay codexfix.14，按 [Codex 终端主会话策略](../docs/primary-session-binding.md) 展示 runtime 唯一主线程，或以匹配的 Herdr 上报消除多主线程歧义；无法确认时提示可尝试 `--no-daemon`，其他 agent 不改变流程；同会话缓存会刷新，换会话撤销旧请求并切换缓存。下载版本及构建来源以下方已发布记录为准。

会话识别完全由 Relay 完成；手机只跟随最终会话 ID。菜单中的“会话绑定诊断”使用[只读诊断接口](../docs/session-binding-diagnostics.md)，展示可扩展 JSON，不查询可操作候选、不分析子代理或确认主会话。

<!-- android-downloads:start -->
## 安装

**[0.2.28 / versionCode 31](https://github.com/cxnaive/herder_relay_extra/releases/tag/android-v0.2.28)**。

| 设备架构 | 下载 |
| --- | --- |
| ARM64 手机 / 平板 | [Release · arm64-v8a](https://github.com/cxnaive/herder_relay_extra/releases/download/android-v0.2.28/Herdr-Pocket-0.2.28-release-arm64-v8a.apk) |
| 32 位 ARM | [Release · armeabi-v7a](https://github.com/cxnaive/herder_relay_extra/releases/download/android-v0.2.28/Herdr-Pocket-0.2.28-release-armeabi-v7a.apk) |
| x86_64 模拟器 / 设备 | [Release · x86_64](https://github.com/cxnaive/herder_relay_extra/releases/download/android-v0.2.28/Herdr-Pocket-0.2.28-release-x86_64.apk) |

附件还包含三种 ABI 的 Debug APK、`BUILD-INFO.json` 和 `SHA256SUMS.txt`。
所有 APK 直接复用 CI 固定签名产物，不重新编译或签名。**直接覆盖安装，不要卸载旧版**，以保留配对和草稿。
Android 9 / API 28 及以上；仍使用原有调试证书，不是 Play 商店版本。自行构建的签名可能不同。

发布来源：[CI run 41909289840](https://github.com/cxnaive/herder_relay_extra/actions/runs/41909289840)，源码 `07d8f53cbbe82005a84b3aa5044a2195a6341a72`。
构建成功不等于完整 check 或真机验收全绿；验证范围和已知缺口见 Release 说明及 `herdr-android/VALIDATION.md`。
历史版本和更新内容保留在 Releases / CHANGELOG。
<!-- android-downloads:end -->

## 电脑名称

0.2.24 已包含配对时确认电脑名称，以及设备卡片上的本机重命名。仅改显示备注，不改 Relay、连接或配对；离线也可改名。详见[电脑名称与分组说明](AGENT-GROUPS.md)。

## 功能

工作区按当前目录读取，文件数量较多时点“加载更多目录项”；搜索只筛选已加载条目，不宣称全项目搜索。默认隐藏点号开头的条目，可用“显示隐藏文件/目录”开关查看；符号链接/特殊文件不会被跟随。

- 多电脑、工作空间和 Herdr Tab 分组；重命名、在指定工作空间创建 Agent。
- 原生对话、工具调用两层折叠、历史分页与缓存、加载进度。
- 手机适配终端字体、滚动历史、快捷键与原生输入。
- 图片放大及 PDF/Markdown 全屏查看，工作区路径和文件校验。图像预览断线等待、恢复补载；可见时按需读取，重复请求合并和有限重试。
- Codex 会话级模型/思考强度选择，来自运行时目录，下一轮生效。
- 固定运行状态条：运行、审批/输入、完成、空闲、中断/失败；基于真实本轮开始时间计时，不用页面打开时间估计。
- **常驻监控**：用户开启并允许通知后，默认关注全部 Agent，包括新建者；卡片/对话 ⋯ 可单独关闭完成提醒。前台与服务共享 E2EE 连接，轮次去重，点通知进入确切会话。

完成提醒默认请求系统通知铃声和振动；在“常驻监控 / 完成提醒”中可发送测试通知，并直接打开该通道的声音/弹出设置。常驻监控状态静音；覆盖安装不会重置用户原先的静音或铃声选择。

常驻通知可一键停止。请允许后台运行并按需关闭电池优化；这不是云推送，强停/省电/断网期间无法保证实时送达。锁屏不显示对话正文。

图片预览从当前 Agent 的工作区文件或消息内嵌数据读取，不是历史截图永久归档。若文件已删除、被覆盖或不在该工作区，重连无法恢复原始历史图像；不会为补图绕过路径权限。

详见 [常驻监控](BACKGROUND-MONITORING.md)、[功能与边界](NATIVE-PARITY.md)、[验证](VALIDATION.md)、[更新记录](CHANGELOG.md)。

## 构建

要求 JDK 21、Android SDK Platform 35、Build Tools 35.0.0。Gradle Wrapper（8.14.6）及依赖已固定；首次构建需要网络下载依赖。

```bash
cd herdr-android
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
./gradlew --no-daemon testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。如依赖已缓存，可添加 `--offline`。
不要提交 `local.properties`、`.gradle/`、`build/`、签名私钥或设备状态。

## 许可与上游

许可证及依赖说明见 [LICENSE](LICENSE)、[THIRD_PARTY.md](THIRD_PARTY.md)。Android 早期参考源基于 upstream 0.24.0，保留在 `UPSTREAM.json` 和 `third_party/`；本次配套 Relay 则以根目录及 `../VERSION.json` 标注为准。

`relay/` 下是接口说明，不要求再打旧增量补丁。根目录已经包含当前 Relay 改动。私有部署记录、用户会话日志、配对密钥和旧诊断截图不随源码发布。

## 文件预览、下载与电脑文件访问

0.2.17 / Relay codexfix.11 将预览和下载分开：图片/PDF 预览最多 12 MiB，文本预览最多 1 MiB；超限仍可点开文件信息、下载或分享原文件。下载显示进度并支持取消，使用私有缓存，需要足够空间；中断后重试从头开始。

对话及 Markdown 的本地文件链接不限扩展名。默认只访问当前 Agent 工作区；在“我的设备 → 对应电脑 → 工作区外文件访问”可由控制者明确开启更大只读范围。该授权按当前手机/电脑分别保存在 Relay，开启后文件页提供根目录、Agent 工作目录和绝对路径跳转。可能接触密钥等私密文件，请仅在信任设备时开启。详见 [文件访问说明](../docs/file-access.md)。
