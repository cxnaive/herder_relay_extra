package app

import (
	"context"
	"crypto/sha256"
	"fmt"
	"maps"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/0cv/herdr-mobile-relay/internal/agentroots"
	"github.com/0cv/herdr-mobile-relay/internal/codexruntime"
	"github.com/0cv/herdr-mobile-relay/internal/coordinator"
	"github.com/0cv/herdr-mobile-relay/internal/herdr"
)

const codexRuntimeSource = "codex-tui-runtime:"
const codexHistorySource = "codex-associated-history:"

type codexRuntimeProbe struct {
	binding   codexSessionBinding
	listeners map[string]string
	root      string
}
type codexRuntimeThread struct {
	thread codexruntime.Thread
	root   string
}
type codexObservation struct {
	binding codexSessionBinding
	reason  string
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
	t := selected.thread
	if origin == "" || !codexBindingID.MatchString(t.ID) || t.CWD != probe.binding.CWD || t.Delegated() {
		return codexRuntimeThread{}, "", false
	}
	return selected, origin, true
}

func codexStoreID(root string) string {
	return fmt.Sprintf("%x", sha256.Sum256([]byte(filepath.Clean(root))))
}

// Only the native process's configured store is queried. A slow or unrelated
// daemon cannot invalidate every other terminal on the machine.
func (s *Server) observeCodexRuntime(ctx context.Context, agents []*coordinator.AgentState) map[string]codexObservation {
	out := map[string]codexObservation{}
	probes := map[string]codexRuntimeProbe{}
	roots := map[string][]string{}
	allowed := map[string]bool{}
	for _, root := range agentroots.Codex(s.home) {
		if canonical, err := filepath.EvalSymlinks(root); err == nil {
			allowed[canonical] = true
		}
	}
	for _, a := range agents {
		if a.Agent != "codex" || (a.ServerSessionID != "" && a.ServerSessionID != "primary") {
			continue
		}
		obs := codexObservation{reason: "process_unavailable"}
		out[a.PaneID] = obs
		if s.home == "" || s.herdrC == nil || s.conversationM == nil || len(probes) >= 128 {
			continue
		}
		check, cancel := context.WithTimeout(ctx, time.Second)
		pane, err := s.herdrC.PaneGet(check, a.PaneID)
		if err != nil || pane == nil || pane.ID != a.PaneID || pane.TerminalID != a.TerminalID || pane.Agent != "codex" || pane.Cwd != a.Cwd {
			cancel()
			continue
		}
		info, err := s.herdrC.PaneProcessInfoFresh(check, a.PaneID)
		cancel()
		if err != nil {
			continue
		}
		process, err := codexRuntimeForegroundProcess(info, a.PaneID)
		if err != nil {
			continue
		}
		birth, err := codexProcessStart(process.PID)
		if err != nil {
			continue
		}
		obs.binding = codexSessionBinding{PaneID: a.PaneID, TerminalID: a.TerminalID, CWD: a.Cwd, PID: process.PID, ProcessStart: birth}
		obs.reason = "runtime_unavailable"
		out[a.PaneID] = obs
		if process.Cwd != a.Cwd {
			obs.reason = "process_project_conflict"
			out[a.PaneID] = obs
			continue
		}
		home, err := codexProcessHome(process.PID, s.home)
		if err != nil {
			continue
		}
		root, err := filepath.EvalSymlinks(filepath.Join(home, "sessions"))
		if err != nil || !allowed[root] {
			obs.reason = "provider_store_unavailable"
			out[a.PaneID] = obs
			continue
		}
		obs.binding.Root = root
		out[a.PaneID] = obs
		listeners, err := codexProcessListeners(process.PID)
		if err != nil || len(listeners) == 0 {
			continue
		}
		probes[a.PaneID] = codexRuntimeProbe{binding: obs.binding, listeners: listeners, root: root}
		roots[root] = append(roots[root], a.PaneID)
	}

	wantedByRoot := map[string]map[string]bool{}
	for root, panes := range roots {
		wantedByRoot[root] = map[string]bool{}
		for _, pane := range panes {
			for origin := range probes[pane].listeners {
				wantedByRoot[root][origin] = true
			}
		}
	}
	results := queryCodexStores(ctx, wantedByRoot, readCodexStore)

	for pane, probe := range probes {
		obs := out[pane]
		r := results[probe.root]
		if r.err != nil || r.threads == nil {
			continue
		}
		candidates := map[string][]codexRuntimeThread{}
		reason := "runtime_unavailable"
		for origin, t := range r.threads {
			candidates[origin] = []codexRuntimeThread{{thread: t, root: probe.root}}
			if t.Unavailable != "" {
				reason = t.Unavailable
			}
		}
		chosen, origin, ok := codexRuntimeCandidate(probe, candidates)
		historyOnly := false
		if !ok {
			chosen, origin, ok = s.codexAssociatedHistoryCandidate(probe, r.threads)
			historyOnly = ok
		}
		if !ok {
			obs.reason = reason
			out[pane] = obs
			continue
		}
		b := probe.binding
		b.SessionID = chosen.thread.ID
		b.HistoryOnly = historyOnly
		b.Status = chosen.thread.AgentStatus()
		if historyOnly {
			b.Status = "unknown"
		}
		b.RuntimeInstance = r.instance
		check, cancel := context.WithTimeout(ctx, time.Second)
		valid := verifyCodexProcessBinding(check, s.herdrC, b, codexProcessStart, codexRuntimeBindingMatches)
		info, e := s.herdrC.PaneProcessInfoFresh(check, pane)
		if e == nil {
			p, e := codexRuntimeForegroundProcess(info, pane)
			valid = valid && e == nil && p.PID == b.PID && p.Cwd == b.CWD
		} else {
			valid = false
		}
		after, e := codexProcessListeners(b.PID)
		valid = valid && e == nil && codexListenerSetCurrent(probe.listeners, after, origin)
		birth, e := codexProcessStart(b.PID)
		valid = valid && e == nil && birth == b.ProcessStart && check.Err() == nil
		cancel()
		if !valid {
			obs.reason = "process_changed"
			out[pane] = obs
			continue
		}
		location := s.conversationM.Locate("codex", b.CWD, b.SessionID)
		root, e := filepath.EvalSymlinks(location.Root)
		if e != nil || root != probe.root || !s.conversationM.CodexSessionProjectMatches(b.CWD, b.SessionID) {
			obs.reason = "runtime_metadata_conflict"
			out[pane] = obs
			continue
		}
		out[pane] = codexObservation{binding: b}
	}
	return out
}

func (s *Server) resolveCodexRuntime(ctx context.Context, agents []*coordinator.AgentState) map[string]codexSessionBinding {
	result := map[string]codexSessionBinding{}
	for pane, obs := range s.observeCodexRuntime(ctx, agents) {
		if obs.reason == "" && obs.binding.SessionID != "" {
			result[pane] = obs.binding
		}
	}
	return result
}

func (s *Server) enrichCodexRuntime(ctx context.Context, agents []*coordinator.AgentState) {
	if !codexProcessProofSupported() {
		return
	}
	observations := s.observeCodexRuntime(ctx, agents)
	for _, a := range agents {
		obs, managed := observations[a.PaneID]
		if !managed {
			continue
		}
		reported := a.ReportedSession
		if reported.Value == "" && a.Session != "" {
			reported.Value = a.Session
			reported.Kind = a.SessionKind
			a.ReportedSession = reported
		}
		a.Session = ""
		a.SessionKind = ""
		a.ConversationSource = ""
		a.ConversationProcess = obs.binding.ProcessStart
		a.BindingState = "verifying"
		a.BindingReason = obs.reason
		a.ConversationReason = obs.reason
		if obs.binding.Root != "" {
			a.BindingStoreID = codexStoreID(obs.binding.Root)
		}
		if obs.reason != "" {
			if strings.Contains(obs.reason, "ambiguous") || obs.reason == "primary_thread_unconfirmed" {
				a.BindingState = "ambiguous"
			}
			if strings.Contains(obs.reason, "conflict") {
				a.BindingState = "conflict"
			}
			if reported.Value != "" && (reported.Kind == "id" || (reported.Kind == "" && codexBindingID.MatchString(reported.Value))) && s.conversationM != nil && !s.conversationM.CodexSessionProjectMatches(a.Cwd, reported.Value) {
				a.BindingState = "conflict"
				a.BindingReason = "reported_session_project_mismatch"
				a.ConversationReason = "codex_session_project_mismatch"
			}
			continue
		}
		b := obs.binding
		a.Session = b.SessionID
		a.SessionKind = "id"
		a.ConversationSource = codexRuntimeSource + b.SessionID
		a.ConversationReason = ""
		a.BindingRuntime = b.RuntimeInstance
		a.BindingState = "verified"
		a.BindingReason = ""
		if b.HistoryOnly {
			a.ConversationSource = codexHistorySource + b.SessionID
			a.BindingState = "history_only"
			a.BindingReason = "associated_history_primary_unconfirmed"
		}
		a.BindingVerifiedAt = time.Now().UnixMilli()
		if !b.HistoryOnly && reported.Value != "" && (reported.Value != b.SessionID || reported.Kind != "id" || (reported.Agent != "" && reported.Agent != "codex")) {
			a.BindingReason = "reported_session_conflict"
		}
		a.Status = b.Status
	}
}

func (s *Server) codexRuntimeCurrent(ctx context.Context, agent *coordinator.AgentState) bool {
	if agent == nil {
		return false
	}
	if agent.Agent != "codex" {
		return true
	}
	if !codexProcessProofSupported() && agent.BindingState == "" && !strings.HasPrefix(agent.ConversationSource, codexRuntimeSource) && !strings.HasPrefix(agent.ConversationSource, codexHistorySource) {
		return true
	}

	if agent.BindingState != "" && !usableCodexBinding(agent.BindingState) {
		return false
	}
	if !s.codexBindings.current(agent) {
		return false
	}
	if agent.ConversationReason != "" {
		return false
	}
	if strings.HasPrefix(agent.ConversationSource, codexBindingSource) {
		return true
	}
	if !strings.HasPrefix(agent.ConversationSource, codexRuntimeSource) && !strings.HasPrefix(agent.ConversationSource, codexHistorySource) {
		if s.home != "" && s.herdrC != nil {
			return false
		}
		return s.conversationM == nil || agent.SessionID == "" || s.conversationM.CodexSessionProjectMatches(agent.Cwd, agent.SessionID)
	}
	b, ok := s.resolveCodexRuntime(ctx, []*coordinator.AgentState{agent})[agent.PaneID]
	valid := ok && b.HistoryOnly == (agent.BindingState == "history_only") &&
		strings.HasPrefix(agent.ConversationSource, codexHistorySource) == b.HistoryOnly &&
		b.SessionID == agent.SessionID && b.ProcessStart == agent.ConversationProcess && (agent.BindingRuntime == "" || agent.BindingRuntime == b.RuntimeInstance) && (agent.BindingStoreID == "" || agent.BindingStoreID == codexStoreID(b.Root))
	if !valid {
		s.codexBindings.invalidate(agent)
		if s.poller != nil {
			s.poller.Wake()
		}
	}
	return valid
}

type codexStoreResult struct {
	threads  map[string]codexruntime.Thread
	instance string
	err      error
}

func readCodexStore(ctx context.Context, root string, wanted map[string]bool) codexStoreResult {
	socket := filepath.Join(filepath.Dir(root), "app-server-control", "app-server-control.sock")
	before, err := os.Stat(socket)
	if err != nil || before.Mode()&os.ModeSocket == 0 {
		return codexStoreResult{err: errCodexProcessStart}
	}
	threads, err := codexruntime.Discover(ctx, socket, wanted)
	after, e := os.Stat(socket)
	if e != nil || !os.SameFile(before, after) {
		return codexStoreResult{err: errCodexProcessStart}
	}
	return codexStoreResult{threads: threads, instance: codexSocketIdentity(before), err: err}
}

func queryCodexStores(ctx context.Context, wanted map[string]map[string]bool, query func(context.Context, string, map[string]bool) codexStoreResult) map[string]codexStoreResult {
	results := map[string]codexStoreResult{}
	var mu sync.Mutex
	var wg sync.WaitGroup
	slots := make(chan struct{}, 4)
	roots := make([]string, 0, len(wanted))
	for root := range wanted {
		roots = append(roots, root)
	}
	sort.Strings(roots)
	for _, root := range roots {
		if ctx.Err() != nil {
			break
		}
		wg.Add(1)
		go func(root string) {
			defer wg.Done()
			select {
			case slots <- struct{}{}:
				defer func() { <-slots }()
			case <-ctx.Done():
				return
			}
			check, cancel := context.WithTimeout(ctx, 2*time.Second)
			defer cancel()
			result := query(check, root, wanted[root])
			mu.Lock()
			results[root] = result
			mu.Unlock()
		}(root)
	}
	wg.Wait()
	return results
}

func codexListenerSetCurrent(before, after map[string]string, origin string) bool {
	return before[origin] != "" && maps.Equal(before, after)
}
