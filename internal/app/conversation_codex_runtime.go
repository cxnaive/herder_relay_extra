package app

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/0cv/herdr-mobile-relay/internal/agentroots"
	"github.com/0cv/herdr-mobile-relay/internal/codexruntime"
	"github.com/0cv/herdr-mobile-relay/internal/coordinator"
	"github.com/0cv/herdr-mobile-relay/internal/herdr"
)

const codexRuntimeSource = "codex-tui-runtime:"

type codexRuntimeProbe struct {
	binding   codexSessionBinding
	listeners map[string]string
}

type codexRuntimeThread struct {
	thread codexruntime.Thread
	root   string
}

func codexTerminalTitle(pane *herdr.Pane) string {
	title := pane.TerminalTitleStripped
	if title == "" {
		title = pane.TerminalTitle
	}
	title = strings.TrimSpace(title)
	r, n := utf8.DecodeRuneInString(title)
	// Some Herdr revisions retain the leading working spinner in stripped titles.
	if r >= 0x2800 && r <= 0x28ff && strings.HasPrefix(title[n:], " ") {
		title = strings.TrimSpace(title[n:])
	}
	return title
}

func codexRuntimeCandidate(probe codexRuntimeProbe, candidates map[string][]codexRuntimeThread) (codexRuntimeThread, string, bool) {
	var selected codexRuntimeThread
	origin := ""
	for addr := range probe.listeners {
		rows := candidates[addr]
		if len(rows) > 1 {
			return selected, "", false
		}
		if len(rows) == 0 {
			continue
		}
		if origin != "" {
			return selected, "", false
		}
		selected, origin = rows[0], addr
	}
	b := probe.binding
	t := selected.thread
	if origin == "" || !codexBindingID.MatchString(t.ID) || t.Originator != "codex-tui" || t.CWD != b.CWD || t.Name == "" || b.Title != t.Name+" | "+filepath.Base(b.CWD) {
		return codexRuntimeThread{}, "", false
	}
	return selected, origin, true
}

func (s *Server) resolveCodexRuntime(ctx context.Context, agents []*coordinator.AgentState) map[string]codexSessionBinding {
	result := map[string]codexSessionBinding{}
	if s.home == "" || s.herdrC == nil || s.conversationM == nil || len(agents) == 0 || len(agents) > 32 {
		return result
	}
	check, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	probes := map[string]codexRuntimeProbe{}
	wanted := map[string]bool{}
	for _, a := range agents {
		if a.Agent != "codex" || (a.ServerSessionID != "" && a.ServerSessionID != "primary") {
			continue
		}
		pane, err := s.herdrC.PaneGet(check, a.PaneID)
		if err != nil || pane == nil || pane.ID != a.PaneID || pane.TerminalID != a.TerminalID || pane.Agent != "codex" || pane.Cwd != a.Cwd {
			continue
		}
		info, err := s.herdrC.PaneProcessInfoFresh(check, a.PaneID)
		if err != nil {
			continue
		}
		pid, err := foregroundRoot(info, a.PaneID)
		if err != nil {
			continue
		}
		birth, err := codexProcessStart(pid)
		if err != nil {
			continue
		}
		listeners, err := codexProcessListeners(pid)
		if err != nil || len(listeners) == 0 {
			continue
		}
		b := codexSessionBinding{PaneID: a.PaneID, TerminalID: a.TerminalID, CWD: a.Cwd, Title: codexTerminalTitle(pane), PID: pid, ProcessStart: birth}
		process := info.ForegroundProcesses[0]
		if process.Cwd != b.CWD || len(process.Argv) == 0 || filepath.Base(process.Argv[0]) != "codex" {
			continue
		}
		probes[a.PaneID] = codexRuntimeProbe{b, listeners}
		for addr := range listeners {
			wanted[addr] = true
		}
	}
	if len(probes) == 0 {
		return result
	}
	candidates := map[string][]codexRuntimeThread{}
	seenSockets := map[string]bool{}
	roots := agentroots.Codex(s.home)
	if len(roots) > 4 {
		return result
	}
	for _, root := range roots {
		socket := filepath.Join(filepath.Dir(root), "app-server-control", "app-server-control.sock")
		stat, err := os.Stat(socket)
		if err != nil || stat.Mode()&os.ModeSocket == 0 {
			continue
		}
		socket, err = filepath.EvalSymlinks(socket)
		if err != nil {
			return result
		}
		if seenSockets[socket] {
			continue
		}
		seenSockets[socket] = true
		found, err := codexruntime.Discover(check, socket, wanted)
		if err != nil {
			return result
		}
		for origin, t := range found {
			candidates[origin] = append(candidates[origin], codexRuntimeThread{t, root})
		}
	}
	for pane, probe := range probes {
		chosen, origin, ok := codexRuntimeCandidate(probe, candidates)
		if !ok {
			continue
		}
		b := probe.binding
		b.SessionID = chosen.thread.ID
		if !verifyCodexBinding(check, s.herdrC, b, codexProcessStart) {
			continue
		}
		after, err := codexProcessListeners(b.PID)
		if err != nil || after[origin] == "" || after[origin] != probe.listeners[origin] {
			continue
		}
		location := s.conversationM.Locate("codex", b.CWD, b.SessionID)
		if filepath.Clean(location.Root) != filepath.Clean(chosen.root) || !s.conversationM.CodexSessionProjectMatches(b.CWD, b.SessionID) {
			continue
		}
		if check.Err() == nil {
			result[pane] = b
		}
	}
	return result
}

func (s *Server) enrichCodexRuntime(ctx context.Context, agents []*coordinator.AgentState) {
	missing := []*coordinator.AgentState{}
	for _, a := range agents {
		if a.Agent == "codex" && strings.TrimSpace(a.Session) == "" {
			missing = append(missing, a)
		}
	}
	resolved := s.resolveCodexRuntime(ctx, missing)
	for _, a := range missing {
		b, ok := resolved[a.PaneID]
		if !ok {
			continue
		}
		a.Session = b.SessionID
		a.SessionKind = "id"
		a.ConversationProcess = b.ProcessStart
		a.ConversationSource = codexRuntimeSource + b.SessionID
	}
}

func (s *Server) codexRuntimeCurrent(ctx context.Context, agent *coordinator.AgentState) bool {
	if agent == nil {
		return false
	}
	if !strings.HasPrefix(agent.ConversationSource, codexRuntimeSource) {
		return true
	}
	b, ok := s.resolveCodexRuntime(ctx, []*coordinator.AgentState{agent})[agent.PaneID]
	return ok && b.SessionID == agent.SessionID && b.ProcessStart == agent.ConversationProcess && agent.ConversationSource == codexRuntimeSource+b.SessionID
}
