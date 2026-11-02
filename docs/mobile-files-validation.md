# 文件扩展验证记录

2026-11-03，Go 1.27.3，WSL Ubuntu Linux。

## 已通过

```bash
go build -trimpath -o bin/herdr-mobile-relay ./cmd/herdr-mobile-relay
go vet ./...
go test -race ./internal/workspace ./internal/protocol ./internal/transport ./internal/deviceauth
go test -race ./internal/app -run \
  'TestMobileFile|TestValidateExactPaneTarget|TestAuthorizeAuthenticatedIdentity|TestAudit|TestCanonicalOperationAuditPolicy'
git diff --check
```

新增文件测试覆盖：中文和 CRLF 内容往返、旧版本冲突拒绝、路径越界与符号链接拒绝、
禁止编辑 `.git`、PDF 二进制读取、下载和编辑大小限制、提交前权限撤销、
临时文件清理，以及两个并发编辑只有一个提交成功。

权限专项测试覆盖 reader 可读不可写、controller 可写、协议版本要求和审计正文脱敏。
原有精确目标测试校验服务器会话、pane、terminal、代际与 Agent 会话标识。

## 完整测试包的失败与未验证范围

扩大运行整个 `internal/app` race 测试包时，约 556 秒后出现以下超时失败：

- `TestDatabaseRecordedConversation/mixed-v2`：数据库更新超时。
- `TestProductionInventoryOutcomesAtPublicationBarriers` 的部分 event 场景：连接关闭或读锁超时。
- `TestPublishCurrentInventoryOrdersRecoveryForConnectedRefreshAndReconnect`。
- `TestProductionInventoryPublisherOrdersRefreshAndRegistrationBarriers`。
- `TestCommittedInventoryPublicationRepairsZeroListenerRefreshAndReconnect`。
- `TestProductionEventInventoryRecoveryDrainsRefreshAcrossReconnect`。

将原始 LF 源码复制到 WSL 原生 `/tmp` 目录重跑后，长时间未结束，已停止该次重跑。
没有证明这些失败由本扩展导致，也没有证明与本扩展无关，不能宣称整套测试通过。
与本改动相关的上述专项测试已通过。

尚未对用户真实 relay 做配对、网络切换、真实 Agent 对话/审批和长期运行验证。
发布源码不等于已经部署到用户的电脑，也不代表 GitHub Actions 已通过。
