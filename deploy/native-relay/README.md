# 电脑 Relay 部署模板

Relay `0.23.5+codexfix.9` 应与 Herdr/Agent 使用同一个普通 Linux 用户。

完整下载、Gateway 安装、配置、用户 systemd 服务、配对及升级流程见 [部署指南](../../docs/native-deployment.md)。Release 归档包含已编译的 Relay、这些模板及 `DEPLOYMENT.md`，源码此目录不包含二进制。

- `init-config.py`：只初始化不存在的私有配置；输出公开 Relay ID，不输出主密钥；需要 Python 3.8+。
- `run.sh serve` / `run.sh tcp-pair '设备名'`：加载相同私有配置；服务与 CLI 必须使用相同 `HERDR_RELAY_ENV`。
- `herdr-mobile-relay.service`：首次安装用的 systemd **用户**服务。已有服务应保留自定义配置，不要直接覆盖。
- `relay.env.example`：字段说明，不包含可直接使用的密钥。

Relay 需要当前用户的项目/会话读写权限；不要为了部署方便改成 root，不要重启 Herdr/Agent，也不要把一个活跃设备数据库交给多个 Relay 进程。
