# 移动客户端文件扩展

此分支在 Herdr Mobile Relay 0.24.0 上增加文件下载、PDF 读取和文本保存能力。
它安装在运行 Herdr、保存项目文件的那台电脑或服务器上；网关只转发加密数据，
不需要安装此扩展。Android 客户端是单独的项目。

## 构建

需要 Linux 或 macOS、Git、Go 1.27.3，以及正常运行的 Herdr。
上游不支持原生 Windows；本扩展的 Linux 检查在 WSL 中完成。

```bash
git -c core.autocrlf=false clone --branch feat/android-file-companion \
  https://github.com/cxnaive/herder_relay_extra.git
cd herder_relay_extra
go build -trimpath -o bin/herdr-mobile-relay ./cmd/herdr-mobile-relay
```

仓库代码已经包含扩展，不需要再应用补丁。
`core.autocrlf=false` 确保内置网页资源保持上游原始字节，避免 SRI 哈希校验失败。
Go module 路径保持上游身份，以保留内部包引用。

## 在已有 relay 环境运行

先确认原服务使用的配置文件和二进制位置。使用你现有的服务管理方式停止旧 relay，
再使用相同配置运行本次构建的二进制，避免两个进程争用同一个端口或设备数据库。
例如下面的命令，其中配置路径需要换成你的实际路径：

```bash
HERDR_RELAY_ENV=/absolute/path/to/relay.env \
HERDR_WEB_ROOT="$PWD/web" \
  ./bin/herdr-mobile-relay serve
```

保留已有配置、运行状态目录和设备数据库，才能保留设备配对。
Cloudflare、Tailscale 或网关连接方式继续使用原来的配置。
确认前台启动正常后，再把对应的后台服务指向这个二进制。

新安装请先阅读上游 [QUICKSTART.md](../QUICKSTART.md)，完成 Herdr、网络路径和
relay 配置，再切换到本分支构建的二进制。本仓库尚未发布预编译 release，
因此不能把 `herdr plugin install cxnaive/herder_relay_extra` 当作已经验证的安装命令。
上游安装器要求与版本匹配的已验证 release 包。

连接成功时，relay 会在 `push_config.capabilities` 中声明
`workspace_mobile_files_v1`。支持该能力的客户端据此启用扩展。
原版网页界面继续使用原有的只读文件预览；本扩展并未给网页增加编辑按钮。

## 权限与边界

| 操作 | 设备权限 | 限制 |
| --- | --- | --- |
| 读取/下载 | reader、controller | Agent 工作目录中的普通文件，最多 12 MiB |
| 保存文本 | controller | 已有 UTF-8 文件、无 NUL 字节、最多 1 MiB、必须提交读到的版本哈希 |

文件路径限制在 Agent 工作目录内。写入拒绝路径中的符号链接和 `.git` 元数据。
保存前重新验证设备权限、Agent 会话和工作目录；同目录临时文件写入后执行 rename，
并保留原文件权限。变更尝试和结果写入 relay 原有审计日志，不记录文件正文。

没有新增公开 HTTP 文件端点，也没有任意全盘文件访问。
文件内容通过原有的 E2EE v2 连接传输。接口详情见
[mobile-files-protocol.md](mobile-files-protocol.md)。

暂不支持新建、删除、重命名、二进制编辑、断点续传或后台大文件下载。
文本替换不承诺保留 ACL、扩展属性或特殊所有权。
版本校验到最终 rename 之间，外部不协作写入者仍存在极小竞争窗口，
不应视为操作系统级跨进程 compare-and-swap。
手机端若在保存后遇到断线或超时，应保留草稿、重新读取确认，不能静默重试写入。

上游自动更新可能用原版 release 覆盖本扩展；更新后请检查能力字段。
当前验证范围和已知测试失败见 [mobile-files-validation.md](mobile-files-validation.md)。

## 来源和许可证

上游：[0cv/herdr-mobile-relay](https://github.com/0cv/herdr-mobile-relay)，
基线 `09b52781399114a7576d40fcdf91ccefd7686be6`，版本 0.24.0。
保留上游 Git 历史和 GNU AGPL-3.0-or-later [许可证](../LICENSE)。
