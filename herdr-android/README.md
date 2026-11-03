# Herdr Pocket · Android 0.2.11

与本仓库根目录的 **Relay 0.23.5+codexfix.8** 配套的原生 Android 客户端，不使用 WebView 运行 Agent 界面。

## 安装

预编译 APK 从本仓库 **GitHub Releases** 下载；APK 不提交到 Git 历史。
Android 9（API 28）及以上。发布 APK 使用原有调试签名，可覆盖此前同签名的 Pocket 包，且不需要重新配对。自行构建会使用你本机的 debug 签名，未必能覆盖发布包；不要为解决签名冲突直接卸载有配对/草稿的旧包。

配对：启动电脑 Relay，生成设备邀请，在“我的设备”扫码/粘贴。支持 HTTPS/WSS 和 `herdr-tcp://IP:端口/#…` 的限时设备邀请。邀请和二维码包含秘密，请勿公开。

## 功能

- 多电脑、工作空间和 Herdr Tab 分组；重命名、在指定工作空间创建 Agent。
- 原生对话、工具调用两层折叠、历史分页与缓存、加载进度。
- 手机适配终端字体、滚动历史、快捷键与原生输入。
- 图片放大及 PDF/Markdown 全屏查看，工作区路径和文件校验。
- Codex 会话级模型/思考强度选择，来自运行时目录，下一轮生效。
- 固定运行状态条：运行、审批/输入、完成、空闲、中断/失败；基于真实本轮开始时间计时，不用页面打开时间估计。
- **常驻监控**：用户开启并允许通知后，默认关注全部 Agent，包括新建者；卡片/对话 ⋯ 可单独关闭完成提醒。前台与服务共享 E2EE 连接，轮次去重，点通知进入确切会话。

常驻通知可一键停止。请允许后台运行并按需关闭电池优化；这不是云推送，强停/省电/断网期间无法保证实时送达。锁屏不显示对话正文。

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
