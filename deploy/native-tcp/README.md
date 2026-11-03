# 公网 Gateway 部署模板

Gateway `0.23.5+codexfix.6`，配套电脑 Relay `0.23.5+codexfix.9` 和 Android `0.2.15`。

- 从零安装、下载校验、电脑 Relay 配置、systemd、配对、升级/回滚：见 [完整部署指南](../../docs/native-deployment.md)。
- 原生 TCP 协议及 E2EE 边界：见 [native-tcp.md](../../docs/native-tcp.md)。
- `install.sh` 必须从**已解压的 Gateway Release 包**运行；源码此目录没有二进制，不能直接运行安装。
- 安装器以 root 安装，但服务使用非登录用户 `herdr-tcp`；首次要求 `HERDR_GATEWAY_ALLOWED_RELAY_IDS`，随机生成注册秘密；重装保留既有环境并重启此 Gateway。
- 公网仅开放 8377/TCP；健康 HTTP 18377 仅 loopback；脚本不会修改云安全组/防火墙。
- 这是手动部署的本地扩展，不是上游插件自动升级格式。源码根目录 `scripts/package-native-release.sh` 可生成完整归档。
