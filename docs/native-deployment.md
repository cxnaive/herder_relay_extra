# Relay + Gateway 从零部署（Herdr Pocket / IP + TCP）

适用本仓库发布的 **Relay 0.23.5+codexfix.12、Gateway 0.23.5+codexfix.6、Android 0.2.17**。这不是上游插件的自动更新包。本次预编译服务端包仅提供 **Linux x86_64 / amd64**，不适用于 ARM64、Windows 或 macOS。

工作区逐层分页最低需要 Android 0.2.15 / Relay codexfix.9；本次 0.2.17 / codexfix.12 新增超限文件下载及可选的工作区外只读访问，包含中途对话漏补修复。已有 Gateway codexfix.6 不必重新安装。详见 `workspace-directory.md`、`file-access.md` 和 `conversation-recovery.md`（压缩包中为 `WORKSPACE-DIRECTORY.md`、`FILE-ACCESS.md`、`CONVERSATION-RECOVERY.md`）。

## 1. 先分清三端

```text
运行 Herdr/Agent 的电脑                         公网 Linux 服务器
  Herdr + 会话/项目 ← 本机 Relay ──出站 TCP──→ Gateway :8377
                                                ↑
                                  Android App ──┘
```

- **Relay 装在运行 Herdr 的电脑上**，使用同一个普通 Linux 用户，读取该用户的 Herdr socket、会话及工作区；不是随便找一台云主机安装就能读本机 Agent。
- **Gateway 装在有公网地址的服务器上**，转发已加密的数据，不要求安装 Herdr、Codex、Android SDK 或 Go。若 Agent 本身就在云服务器上运行，两者也可在同一台机器，但 Relay 仍应使用 Agent 所属用户。
- **手机只装 APK**。扫码或粘贴 App 专用邀请，不在手机上运行 Relay/Gateway。

| 端口 | 绑定/方向 | 是否公网开放 |
| --- | --- | --- |
| Gateway TCP 8377 | 手机和电脑主动连接公网服务器 | **是，唯一业务端口** |
| Gateway HTTP 18377 | `127.0.0.1`，健康检查及旧 WS 路由 | 否 |
| Relay HTTP 8375 | 电脑 `127.0.0.1` | 否 |
| Relay UDP 8376 | 电脑 `127.0.0.1`，Herdr 事件 | 否 |
| SSH 22 或自定义端口 | 服务器维护 | 按自己的管理策略 |

纯原生模式不需要公开 80/443、UDP 或 STUN；不需要域名或 TLS 证书。**TCP 本身不加密**，应用消息使用设备鉴权和 ECDH/HMAC/AES-GCM E2EE。地址、路由 ID、握手元数据、大小及时序仍可见；不能把此方案当作独立安全审计或运营合规承诺。详见同目录的 `native-tcp.md`（压缩包中为 `NATIVE-TCP.md`）。

## 2. 下载、校验、解压

服务端 Release：**`native-v0.23.5-codexfix.12`**，附件：

- `herdr-mobile-relay_0.23.5-codexfix.12_linux_amd64.tar.gz`
- `herdr-gateway_0.23.5-codexfix.6_linux_amd64.tar.gz`
- `SHA256SUMS.txt`

APK 使用配套 Release **`android-v0.2.24`**，普通 ARM64 手机选择 `Herdr-Pocket-0.2.24-release-arm64-v8a.apk`。旧 APK 不支持 codexfix.12 的绑定租约，必须配套更新；已有 Gateway codexfix.6 不变。Android Release 保持为 Latest；服务端版本不等于 Android 版本。

在电脑的空目录中下载（也可直接在 GitHub Releases 页面点附件）。先确认两台 Linux 机器的 `uname -m` 都是 `x86_64`：

```sh
set -eu
mkdir -p "$HOME/Downloads/herdr-native"
cd "$HOME/Downloads/herdr-native"
base=https://github.com/cxnaive/herder_relay_extra/releases/download/native-v0.23.5-codexfix.12
for name in herdr-mobile-relay_0.23.5-codexfix.12_linux_amd64.tar.gz \
            herdr-gateway_0.23.5-codexfix.6_linux_amd64.tar.gz SHA256SUMS.txt; do
  curl -fL --retry 4 --connect-timeout 15 --max-time 600 --continue-at - \
    "$base/$name" -o "$name"
done
sha256sum -c SHA256SUMS.txt
# 只有两个归档都显示 OK 才继续。
tar -xzf herdr-mobile-relay_0.23.5-codexfix.12_linux_amd64.tar.gz
tar -xzf herdr-gateway_0.23.5-codexfix.6_linux_amd64.tar.gz
```

每包包含二进制、部署模板、本文、许可证、`VERSION.json` 和 `BUILD-INFO.json`。Relay 包还包含原样复制的旧版 `web/`，避免缺失静态资源；原生 Android 界面不使用它。SHA-256 用于完整性核对，请仍从可信的 GitHub Release 获取文件。

## 3. 电脑：初始化配置并取得 Relay ID（只限首次）

前提：Herdr 已安装、该用户已运行 Herdr；需要 `python3`、POSIX shell 和 systemd 用户服务。预编译包运行不需要 Go/Node/Android SDK。不要用 root 代替自己的 Agent 用户运行电脑 Relay。

**如果已有 Relay，跳到第 7 节。不要生成新主密钥、删除设备库或再启动一个相同端口的 Relay。** 先检查 `systemctl --user status herdr-mobile-relay.service` 和本机端口占用。

```sh
cd "$HOME/Downloads/herdr-native/herdr-mobile-relay_0.23.5-codexfix.12_linux_amd64"
command -v herdr
# 203.0.113.10 只是文档保留地址，必须换成你的公网 IP。
python3 ./init-config.py --gateway tcp://203.0.113.10:8377
```

脚本新建 `~/.config/herdr-mobile-relay/relay.env`（目录 0700、文件 0600），生成一个新的 32 字符 Relay 主密钥，仅打印 **22 字符的公开 Relay ID**；已有配置会拒绝覆盖。默认使用 `command -v herdr` 的绝对路径和 `~/.config/herdr/herdr.sock`。

如 Herdr 安装位置或 socket 不同，加 `--herdr-bin /绝对路径/herdr --socket /绝对路径/herdr.sock`。使用自定义 `XDG_CONFIG_HOME`/`--config` 时，第 5 节的 systemd 环境和手动命令也要使用相同配置路径。不要猜另一个用户的 socket。

此时注册秘密仍是占位值，**还不能启动 Relay**。记下输出的 Relay ID，用于下一步白名单。Relay 主密钥不需要、也不应该复制到 Gateway。

## 4. 公网服务器：安装 Gateway

将校验过的 Gateway 归档和 `SHA256SUMS.txt` 传到服务器。例如在电脑执行：

```sh
cd "$HOME/Downloads/herdr-native"
scp herdr-gateway_0.23.5-codexfix.6_linux_amd64.tar.gz SHA256SUMS.txt root@203.0.113.10:/root/
```

服务器需具备 systemd、GNU coreutils、tar 和 useradd（例如常规 Ubuntu/Debian 服务器）。在服务器的 SSH 会话中执行以下命令。示例使用 root 安装 systemd 服务，但进程会降为独立的 `herdr-tcp` 用户：

```sh
set -eu
cd /root
# 这里只校验本机拥有的 Gateway 归档，不忽略其他校验错误。
grep '  herdr-gateway_0.23.5-codexfix.6_linux_amd64.tar.gz$' SHA256SUMS.txt | sha256sum -c -
tar -xzf herdr-gateway_0.23.5-codexfix.6_linux_amd64.tar.gz
cd herdr-gateway_0.23.5-codexfix.6_linux_amd64
HERDR_GATEWAY_ALLOWED_RELAY_IDS='填上一步的22字符RelayID' sh ./install.sh
systemctl status herdr-tcp-gateway.service --no-pager
curl -fsS http://127.0.0.1:18377/healthz
```

安装器创建：

- `/opt/herdr-tcp-gateway/releases/0.23.5-codexfix.6/herdr-gateway` 和 `current` 链接。
- `/etc/herdr-tcp-gateway/relay.env`（root 专用 0600），自动生成随机注册秘密。
- `/var/lib/herdr-tcp-gateway/`（网关用户专用状态目录）。
- `herdr-tcp-gateway.service`，启用并重启**此 Gateway 服务**，不会改动 Herdr/Agent。

首次安装必须提供白名单；升级时保留已有环境文件。以后增加电脑要编辑环境文件中的 `HERDR_GATEWAY_ALLOWED_RELAY_IDS`（多个 22 字符 ID 用逗号分隔）并检查 `HERDR_GATEWAY_MAX_RELAYS`，然后重启 Gateway。默认最多 2 台电脑、8 个客户端，每月流量不设上限。

**云安全组与系统防火墙放行 8377/TCP**；安装器不会自动修改防火墙。UFW 已启用时可 `ufw allow 8377/tcp`，不要为了此指南开启/重置防火墙或破坏 SSH 规则。18377 和 8375 不要映射到公网。

## 5. 电脑：安全传入注册秘密并启动 Relay

Gateway 和电脑需要相同的 **注册秘密**，它不同于电脑的 Relay 主密钥，也不同于手机设备凭据。通过可信 SSH 私下传递，不要贴到聊天、命令参数、截图或 Git。

以下命令仅把所需配置行写入私有临时文件，不把秘密打印到终端。替换 SSH 地址；仍使用运行 Herdr 的普通用户：

```sh
set -eu
umask 077
runtime="$HOME/.config/herdr-mobile-relay"
ssh root@203.0.113.10 "grep '^HERDR_GATEWAY_REGISTRATION_TOKEN=' /etc/herdr-tcp-gateway/relay.env" \
  > "$runtime/gateway-registration.tmp"
python3 - "$runtime" <<'PY'
from pathlib import Path
import re, sys
root = Path(sys.argv[1])
transfer = root / 'gateway-registration.tmp'
try:
    line = transfer.read_text().strip()
    if not re.fullmatch(r'HERDR_GATEWAY_REGISTRATION_TOKEN=[0-9a-f]{64}', line):
        raise SystemExit('Missing or invalid Gateway registration secret')
    path = root / 'relay.env'
    text = path.read_text()
    placeholder = 'HERDR_GATEWAY_REGISTRATION_TOKEN=REPLACE_WITH_GATEWAY_REGISTRATION_SECRET'
    if text.count(placeholder) != 1:
        raise SystemExit('Config already configured or not initialized; preserve it and use the upgrade guide')
    path.write_text(text.replace(placeholder, line))
    path.chmod(0o600)
finally:
    transfer.unlink(missing_ok=True)
PY
```

安装到用户目录，不把配置或设备库放在可替换的二进制目录内：

```sh
set -eu
base="$HOME/.local/share/herdr-pocket-relay"
release=herdr-mobile-relay_0.23.5-codexfix.12_linux_amd64
mkdir -p "$base/releases" "$HOME/.config/systemd/user"
# 若目标已存在，先检查，不要嵌套覆盖另一次安装。
test ! -e "$base/releases/$release"
cp -a "$HOME/Downloads/herdr-native/$release" "$base/releases/$release"
ln -sfn "releases/$release" "$base/current.new"
mv -Tf "$base/current.new" "$base/current"
# 此步骤仅适用于没有既有同名服务的首次部署。
install -m 0644 "$base/current/herdr-mobile-relay.service" "$HOME/.config/systemd/user/herdr-mobile-relay.service"
systemctl --user daemon-reload
systemctl --user enable --now herdr-mobile-relay.service
systemctl --user status herdr-mobile-relay.service --no-pager
curl -fsS http://127.0.0.1:8375/healthz
```

健康响应应显示 Relay `version=0.23.5+codexfix.12`，`gateway.registered=true`，网关版本 `0.23.5+codexfix.6`。`inventory.state=ready` 才表示成功读取 Herdr；只有 HTTP 200 不代表 Agent 清单可用。原生 App 不依赖网页 bundle 的版本号。

用户服务若需退出 SSH/登录后继续运行，可由管理员执行 `sudo loginctl enable-linger "$USER"`；这只保留用户服务管理器，**不会自动启动 Herdr 或恢复 Agent**。电脑必须保持在线、不休眠，Herdr 本身也必须运行。

## 6. 手机配对与使用

覆盖安装同签名 APK，保留已有配对/草稿。首次添加这台电脑时，在电脑的私密终端运行：

```sh
"$HOME/.local/share/herdr-pocket-relay/current/run.sh" tcp-pair '我的手机'
```

命令联系**正在运行的同一 Relay** 的私有 Unix socket，生成有效期 10 分钟的单设备邀请，不用第二个进程打开活跃的设备库。App 中扫码或粘贴 `herdr-tcp://公网IP:8377/#…`；它不是网页地址，不要用浏览器打开。

二维码和完整邀请含秘密，不能公开。过期后重新生成即可。邀请在手机凭据确认后消耗；同一条邀请不能配多个手机。`herdr-phone` / `herdr-phone-tcp` 不是此包必需或自动提供的命令。

## 7. 已有部署的升级、保留状态与回滚

### 电脑 Relay

1. 先检查自己的 `systemctl --user cat herdr-mobile-relay.service`，确认当前可执行文件、`HERDR_RELAY_ENV`/runtime、Herdr 用户及 socket。它可能不是本文首次安装的路径。不要公开配置/日志。
2. **保留原来的 `HERDR_RELAY_TOKEN`、runtime 目录、`device-auth/` 和手机凭据**。不要运行 `init-config.py`、旋转密钥或复制一份设备库给同时运行的另一个 Relay。切换 TCP 只需更新 Gateway URL、注册秘密、force-relay 设置。
3. 用私有目录备份环境文件和 systemd 单元；如果要备份设备库，先停止**此 Relay**，再一致复制状态，不能把正在写入的数据库当作普通文件热拷贝。
4. 新包解压到新的版本目录；停 Relay 后切换二进制/current 或自己的 service override。已有自定义单元不要被模板覆盖，若改动单元则 `systemctl --user daemon-reload`。
5. `systemctl --user restart herdr-mobile-relay.service` 后检查健康/注册和手机历史。**不重启 Herdr，不停止/重命名 Agent。** 只更新二进制且保持同一 Gateway/密钥/设备库时不需重新配对。

### Gateway

备份 `/etc/herdr-tcp-gateway/relay.env`、服务文件、旧可执行文件及 current 指向，再运行新包安装器。已有环境配置会保留，安装会重启该 Gateway，连接会短暂断开后重连。相同版本重装会替换该版本目录中的二进制，因此也要单独备份旧文件。不要让升级脚本或备份把秘密写入公开目录。

### 回滚

恢复旧二进制/current 或服务 override，必要时恢复旧环境，再 reload/restart **对应 Relay/Gateway**。不要用旧设备库覆盖升级后新配对的状态，否则会丢失授权；不要为了应用更新恢复 Agent 的工作目录。

这些归档是手动部署包，不是上游 `herdr plugin install/update` 的完整版本包。不要用上游自动更新命令覆盖本地扩展，否则可能丢失 IP/TCP 和会话修复功能。继续从本仓库 Release 更新。

## 8. 常见问题

| 现象 | 先检查 |
| --- | --- |
| 下载慢/中途断开 | 上面的 curl 支持断点续传，每次允许 10 分钟；必须以 SHA-256 校验通过为准 |
| `Exec format error` | `uname -m` 是否为 x86_64；本次不提供 ARM64 包 |
| Gateway 连不上 | 公网地址/8377、安全组/防火墙，服务器本地 `/healthz` 和 `ss -lnt`；ping 不通不能单独证明 TCP 不通 |
| `gateway.registered=false` | 两端注册秘密是否一致，公开 Relay ID 是否在白名单，最大 Relay 数和服务日志 |
| 已注册但 Agent 列表为空 | Relay 是否与 Herdr 同用户，`HERDR_BIN`、`HERDR_SOCKET_PATH`、`inventory.state`；不要仅凭健康 HTTP 200 判断 |
| `tcp-pair` 找不到 socket | Relay 是否启动、配置路径/runtime 是否一致；不要用 sudo 变成另一个用户 |
| 端口占用/第二个设备库写入者 | 是否重复启动了旧服务和新包；先确认对应 PID，不要杀掉 Herdr/Agent |
| 手机后台没提醒 | App 开启常驻监控及通知权限；用“测试完成提醒”，检查音量、通道和省电；服务器在线不代表手机服务一直活着 |

诊断命令（在对应机器本地执行，日志可能含本机信息，不要直接公开）：

```sh
# 公网服务器
systemctl status herdr-tcp-gateway.service --no-pager
journalctl -u herdr-tcp-gateway.service -n 40 --no-pager
curl -fsS http://127.0.0.1:18377/healthz
# 运行 Herdr 的电脑
systemctl --user status herdr-mobile-relay.service --no-pager
journalctl --user -u herdr-mobile-relay.service -n 40 --no-pager
curl -fsS http://127.0.0.1:8375/healthz
```

## 9. 从源码构建与验证范围

构建要求 Linux amd64、仓库 `go.mod` 指定的 Go 1.27.3、Python 3.8+、Git、GNU tar 和 sha256sum；运行验证脚本还需要 binutils 的 `readelf`。安装/初始化只需要已发布包和系统工具，不需要编译器。

```sh
# 在干净、已提交的源码根目录，输出目录必须不存在。
scripts/package-native-release.sh /tmp/herdr-native-release
python3 scripts/check-native-release.py /tmp/herdr-native-release
```

构建固定 `CGO_ENABLED=0 GOOS=linux GOARCH=amd64 GOAMD64=v1`，把完整 commit 注入两个二进制；每包 `BUILD-INFO.json` 记录目标、版本、revision、Go 版本及二进制 SHA-256。包内网页原样复制，不手改或重新生成。

发布前检查完整 Go tests/vet、相关 race tests，以及**解压后的真实二进制**：loopback Gateway 健康、原生 TCP probe、Relay 认证注册、单设备邀请、本地主配置保留和空临时 Herdr 路径隔离。测试不会连接用户的真实 Herdr、Gateway 或手机。

Linux amd64 已做本机运行检查；本次没有 ARM/macOS/Windows 制品，也没有替用户重新部署生产服务。systemd 模板提供参考，未把进程 smoke test 宣称为所有发行版的 systemd/SELinux/云网络验收。
