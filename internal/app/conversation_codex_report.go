package app

import (
	"context"
	"crypto/sha256"
	"fmt"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/0cv/herdr-mobile-relay/internal/coordinator"
	"github.com/0cv/herdr-mobile-relay/internal/herdr"
)

var codexBindingID = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

func codexStoreID(root string) string {
	return fmt.Sprintf("%x", sha256.Sum256([]byte(filepath.Clean(root))))
}

func codexReportReason(ref herdr.AgentSession) string {
	if strings.TrimSpace(ref.Value) == "" {
		return "herdr_session_missing"
	}
	if ref.Kind != "id" || (ref.Agent != "" && ref.Agent != "codex") || !codexBindingID.MatchString(ref.Value) {
		return "herdr_session_invalid"
	}
	return ""
}
func codexReportMessage(reason string) string {
	switch reason {
	case "terminal_title_missing", "terminal_title_invalid":
		return "Codex 已配置线程 ID 标题，但 Herdr 尚未返回可识别的 ID 标题；等待终端刷新后重试，不会按会话名或正文猜选。"
	case "terminal_title_ambiguous":
		return "Codex 标题里的截断 ID 同时匹配多个主会话，无法唯一确认；不会改用旧 hook 上报猜选。"
	case "terminal_title_conflict":
		return "Codex 标题 ID 未匹配此终端已验证的主会话候选；可能处于切换或子代理界面，暂不绑定其他历史。"
	case "standalone_runtime_unavailable":
		return "此 Codex 已使用 --no-daemon，但无法读取本进程持有的会话写锁；请检查版本及同用户进程权限，不会使用共享后台或旧上报代替。"
	case "standalone_metadata_unavailable":
		return "独立 Codex 的会话元数据尚不可读；新会话可能需在电脑端开始首轮输入后再刷新，不会改读旧会话。"
	case "standalone_ambiguous":
		return "独立 Codex 仍持有多个主会话；/new 或 /resume 后旧 hook 上报不足以确认当前选择。请在电脑端正常退出原 TUI 后，用 codex --no-daemon resume <会话ID> 恢复目标会话；Relay 不会自动终止进程。"
	case "standalone_ancestry_unavailable":
		return "独立 Codex 的主会话与子代理关系无法完整确认，不会猜选；请检查 Codex 版本及会话元数据。"
	case "standalone_metadata_conflict":
		return "独立 Codex 的会话写锁与历史 ID、工作目录或主子关系冲突；不会采用旧 hook 上报覆盖冲突。"
	case "runtime_changed":
		return "终端或主会话已变化，正在重新确认；旧请求已失效。"
	case "runtime_metadata_conflict", "process_project_conflict":
		return "Codex 运行时与会话元数据冲突，无法确认当前主会话；请检查终端与工作目录。"
	case "reported_session_not_candidate", "herdr_session_invalid":
		return "Herdr 上报未匹配本终端的主会话候选，请检查 Codex hook；可尝试以 codex --no-daemon 重新启动，不能保证消除歧义。"
	case "runtime_platform_unsupported":
		return "此平台暂不支持 Codex 终端运行时确认；不会按目录或历史时间猜测会话。"
	default:
		return "无法唯一确认此 Codex 终端的主会话；可尝试启动时使用 codex --no-daemon，并检查 Herdr 的 Codex hook。该选项不保证修复，Relay 不会猜选会话。"
	}
}

func (s *Server) codexPrimaryError(ctx context.Context, a *coordinator.AgentState) string {
	if a.BindingReason != "" {
		return codexReportMessage(a.BindingReason)
	}
	obs, ok := s.observeCodexRuntime(ctx, []*coordinator.AgentState{a})[a.PaneID]
	if !ok || obs.reason == "" {
		return "会话历史暂不可读，请稍后刷新；不会改用其他会话。"
	}
	return codexReportMessage(obs.reason)
}
