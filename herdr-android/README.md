# Herdr Pocket · Android 0.2.20

与本仓库根目录的 **Relay 0.23.5+codexfix.11** 配套的原生 Android 客户端，不使用 WebView 运行 Agent 界面。

## 安装

0.2.20 发布于 **`android-v0.2.20`**，附件为 `Herdr-Pocket-0.2.20-debug.apk` 与 SHA-256 校验文件。保存后查询实际名称，若被改后缀则通过系统文档接口尝试恢复原名并再次核对；拒绝改名、同名冲突或无法确认时显示原名/实际名称并可复制原名，不删除或强制覆盖同名文件。

包含此前 0.2.18/0.2.19 本地修复：正确匹配保存/分享的 MIME；发布标签、仓库 slug 和未带路径的附件名不再生成假文件卡片。可覆盖旧同签名 App，**无需重新配对、升级或重启 Relay/Gateway**。若旧 App 下载此 APK 仍为 `.bin`，这一次先改回 `Herdr-Pocket-0.2.20-debug.apk` 再安装。

0.2.17 发布于 `android-v0.2.17`，包含文件访问/下载改进及此前 0.2.16 的对话漏补修复。配套 Relay 安装包见 `native-v0.23.5-codexfix.11`，Gateway codexfix.6 无需更新。

0.2.15 工作区逐层分页修复发布于 `android-v0.2.15`，配套 Relay 安装包见 `native-v0.23.5-codexfix.9`。目录分页需要 Relay codexfix.9，旧 Relay 继续使用有限的工作区树并提示升级。

预编译 APK 从本仓库 **GitHub Releases** 下载；APK 不提交到 Git 历史。
Android 9（API 28）及以上。发布 APK 使用原有调试签名，可覆盖此前同签名的 Pocket 包，且不需要重新配对。自行构建会使用你本机的 debug 签名，未必能覆盖发布包；不要为解决签名冲突直接卸载有配对/草稿的旧包。

配对：启动电脑 Relay，生成设备邀请，在“我的设备”扫码/粘贴。支持 HTTPS/WSS 和 `herdr-tcp://IP:端口/#…` 的限时设备邀请。邀请和二维码包含秘密，请勿公开。

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

要求 JDK 21、Android SDK Platform 35、Build Tools 35.0.0。Gradle Wrapper（8.13）及依赖已固定；首次构建需要网络下载依赖。

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
