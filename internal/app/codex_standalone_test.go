package app

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/0cv/herdr-mobile-relay/internal/coordinator"
	"github.com/0cv/herdr-mobile-relay/internal/herdr"
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
)

func TestCodexNoDaemonArgs(t *testing.T) {
	for _, tc := range []struct {
		argv []string
		want bool
	}{
		{[]string{"codex", "--no-daemon"}, true},
		{[]string{"codex", "-c", `tui.terminal_title=["thread-id","thread-name"]`, "--no-daemon"}, true},
		{[]string{"codex", "-c", `tui.terminal_title=["thread-id","thread-name"]`, "resume", "--last", "--no-daemon"}, true},
		{[]string{"codex", "-c", `tui.terminal_title=["thread-id","thread-name"]`}, false},
		{[]string{"codex", "-c", `tui.terminal_title=["thread-id","thread-name"]`, "--", "--no-daemon"}, false},
		{[]string{"/bin/codex", "--no-alt-screen", "-C", "/project", "--no-daemon", "resume", reportID}, true},
		{[]string{"codex", "resume", "--last", "--no-daemon"}, true},
		{[]string{"codex", "fork", reportID, "--no-daemon"}, true},
		{[]string{"codex", "-cfoo=true", "--no-daemon"}, true},
		{[]string{"codex", "--config=foo=true", "--no-daemon"}, true},
		{nil, false},
		{[]string{"node", "codex.js", "--no-daemon"}, false},
		{[]string{"codex", "explain --no-daemon"}, false},
		{[]string{"codex", "--", "--no-daemon"}, false},
		{[]string{"codex", "--config", "--no-daemon"}, false},
		{[]string{"codex", "--config=--no-daemon"}, false},
		{[]string{"codex", "--unknown", "--no-daemon"}, false},
		{[]string{"codex", "--no-daemon=false"}, false},
		{[]string{"codex", "--no-daemon", "--remote", "unix:///other"}, false},
		{[]string{"codex", "--no-daemon", "app-server"}, false},
		{[]string{"codex", "exec", "--no-daemon"}, false},
	} {
		if got := codexNoDaemonArgs(tc.argv); got != tc.want {
			t.Errorf("%q = %v, want %v", tc.argv, got, tc.want)
		}
	}
}

func standaloneRollout(t *testing.T, home, id, cwd string, extra map[string]any) string {
	t.Helper()
	p := filepath.Join(home, ".codex", "sessions", "2026", "11", "04", "rollout-"+id+".jsonl")
	payload := map[string]any{"id": id, "session_id": id, "cwd": cwd, "source": "cli", "thread_source": "user"}
	for k, v := range extra {
		payload[k] = v
	}
	writeInvariantRows(t, p, map[string]any{"type": "session_meta", "payload": payload})
	return p
}

func standaloneServer(t *testing.T) (*Server, *reportPaneFixture, *coordinator.AgentState, map[string]string) {
	t.Helper()
	s, f, a, _ := reportServer(t)
	writers := map[string]string{reportID: "dev:inode-1"}
	s.codexProcessProbe = func(context.Context, *coordinator.AgentState) (codexRuntimeProbe, string) {
		return codexRuntimeProbe{pid: 42, process: "boot:42:100", root: filepath.Join(s.home, ".codex", "sessions"), mode: "standalone", writers: writers}, ""
	}
	standaloneRollout(t, s.home, reportID, a.Cwd, nil)
	return s, f, a, writers
}

func assertNoSharedCalls(t *testing.T, f *reportPaneFixture) {
	t.Helper()
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, call := range f.calls {
		if call != "pane.get" {
			t.Fatalf("standalone reached shared runtime: %s", call)
		}
	}
}

func TestCodexStandaloneUniqueWriterWithoutHookOrSharedSocket(t *testing.T) {
	for _, socket := range []bool{false, true} {
		t.Run(map[bool]string{true: "unrelated_shared_server", false: "no_socket"}[socket], func(t *testing.T) {
			s, f, a, _ := standaloneServer(t)
			if !socket {
				if err := os.Remove(filepath.Join(s.home, ".codex", "app-server-control", "app-server-control.sock")); err != nil {
					t.Fatal(err)
				}
			}
			for _, report := range []herdr.AgentSession{{}, {Agent: "codex", Kind: "id", Value: otherReportID}, {Agent: "claude", Kind: "id", Value: reportID}} {
				f.mu.Lock()
				f.pane.SessionRaw = report
				f.mu.Unlock()
				got := commitReport(s, a)
				if got.BindingState != "verified" || got.SessionID != reportID || got.ConversationSource != codexStandaloneSource+reportID || got.BindingMode != "standalone" || !s.codexPrimaryCurrent(t.Context(), got) {
					t.Fatalf("bad standalone binding: %+v", got)
				}
			}
			assertNoSharedCalls(t, f)
		})
	}
}

func TestCodexStandaloneFailsClosed(t *testing.T) {
	for _, tc := range []struct{ name, reason string }{
		{"no_writers", "standalone_runtime_unavailable"},
		{"unmaterialized", "standalone_metadata_unavailable"},
		{"foreign_project", "standalone_metadata_conflict"},
		{"wrong_header", "standalone_metadata_conflict"},
		{"missing_source", "standalone_ancestry_unavailable"},
		{"child_missing_parent", "standalone_ancestry_unavailable"},
		{"child_parent_cycle", "standalone_ancestry_unavailable"},
		{"two_roots_matching_old_report", "standalone_ambiguous"},
		{"two_roots_missing_report", "standalone_ambiguous"},
		{"fork_not_child", "standalone_ambiguous"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s, f, a, writers := standaloneServer(t)
			switch tc.name {
			case "no_writers":
				clear(writers)
			case "unmaterialized":
				os.RemoveAll(filepath.Join(s.home, ".codex", "sessions"))
			case "foreign_project":
				standaloneRollout(t, s.home, reportID, "/other", nil)
			case "wrong_header":
				standaloneRollout(t, s.home, reportID, a.Cwd, map[string]any{"id": otherReportID})
			case "missing_source":
				standaloneRollout(t, s.home, reportID, a.Cwd, map[string]any{"source": nil})
			case "child_missing_parent", "child_parent_cycle":
				extra := map[string]any{"source": "subagent", "thread_source": "subagent"}
				if tc.name == "child_parent_cycle" {
					extra["parent_thread_id"] = reportID
				}
				standaloneRollout(t, s.home, reportID, a.Cwd, extra)
			default:
				writers[otherReportID] = "dev:inode-2"
				extra := map[string]any{}
				if tc.name == "fork_not_child" {
					extra["forked_from_id"] = reportID
				}
				standaloneRollout(t, s.home, otherReportID, a.Cwd, extra)
				if tc.name == "two_roots_missing_report" {
					f.pane.SessionRaw = herdr.AgentSession{}
				}
			}
			got := commitReport(s, a)
			if got.BindingState == "verified" || got.SessionID != "" || got.BindingReason != tc.reason {
				t.Fatalf("want %s, got %+v", tc.reason, got)
			}
			assertNoSharedCalls(t, f)
		})
	}
}

func TestCodexStandaloneReducesExplicitChildren(t *testing.T) {
	s, f, a, writers := standaloneServer(t)
	writers[childReportID] = "dev:inode-child"
	standaloneRollout(t, s.home, childReportID, "/child-worktree", map[string]any{"session_id": reportID, "parent_thread_id": reportID, "source": map[string]any{"subagent": map[string]any{"thread_spawn": map[string]any{"parent_thread_id": reportID}}}, "thread_source": "subagent"})
	f.pane.SessionRaw = herdr.AgentSession{}
	got := commitReport(s, a)
	if got.BindingState != "verified" || got.SessionID != reportID {
		t.Fatalf("explicit child hid root: %+v", got)
	}
	assertNoSharedCalls(t, f)
}

func TestCodexStandaloneRevalidationRevokesOldLease(t *testing.T) {
	for _, change := range []string{"process", "mode", "writer_inode", "new_root", "store"} {
		t.Run(change, func(t *testing.T) {
			s, _, a, writers := standaloneServer(t)
			old := commitReport(s, a)
			probe := s.codexProcessProbe
			s.codexProcessProbe = func(ctx context.Context, a *coordinator.AgentState) (codexRuntimeProbe, string) {
				p, r := probe(ctx, a)
				switch change {
				case "process":
					p.process = "boot:42:200"
				case "mode":
					p.mode = "shared"
				case "writer_inode":
					p.writers = map[string]string{reportID: "dev:replaced"}
				case "store":
					p.root = filepath.Join(s.home, "other", "sessions")
				}
				return p, r
			}
			if change == "new_root" {
				writers[otherReportID] = "dev:inode-2"
				standaloneRollout(t, s.home, otherReportID, a.Cwd, nil)
			}
			if s.codexPrimaryCurrent(t.Context(), old) || s.codexBindings.current(old) {
				t.Fatal("old standalone lease survived proof change")
			}
		})
	}
}

func TestCodexStandaloneChangeDuringValidation(t *testing.T) {
	s, _, a, _ := standaloneServer(t)
	original := s.codexProcessProbe
	calls := 0
	s.codexProcessProbe = func(ctx context.Context, a *coordinator.AgentState) (codexRuntimeProbe, string) {
		p, r := original(ctx, a)
		calls++
		if calls >= 3 {
			p.process = "boot:42:200"
		}
		return p, r
	}
	got := commitReport(s, a)
	if got.BindingReason != "runtime_changed" || got.SessionID != "" {
		t.Fatalf("process changed after metadata validation: %+v", got)
	}
}

func TestCodexStandaloneNeverUsesSharedSettingsOrTiming(t *testing.T) {
	s, f, a, writers := standaloneServer(t)
	current := commitReport(s, a)
	target := bindingDiagnosticTarget(current)
	for _, action := range []string{"get_agent_settings", "set_agent_settings"} {
		_, err := s.agentSettings(t.Context(), protocol.Inbound{Type: action, PaneID: a.PaneID, Target: &target, Model: "ignored"})
		if !errors.Is(err, errStandaloneSettings) {
			t.Fatalf("standalone %s: %v", action, err)
		}
	}
	in := protocol.Inbound{Type: "get_agent_run_state", PaneID: a.PaneID, Target: &target}
	state, err := s.agentRunState(t.Context(), in)
	if err != nil || state.Source != "herdr" || state.Timing != "unknown" {
		t.Fatalf("invented standalone timing: %+v %v", state, err)
	}
	diagnostics, err := s.sessionBindingDiagnostics(t.Context(), in)
	if err != nil || diagnostics.Details["runtime"].(map[string]any)["mode"] != "standalone" {
		t.Fatalf("missing mode: %+v %v", diagnostics, err)
	}
	clear(writers)
	if _, err := s.agentRunState(t.Context(), in); err == nil {
		t.Fatal("stale binding produced reassuring timing fallback")
	}
	assertNoSharedCalls(t, f)
}

func TestCodexStandaloneMessagesDoNotSuggestAddingTheFlagAgain(t *testing.T) {
	for _, reason := range []string{"standalone_runtime_unavailable", "standalone_metadata_unavailable", "standalone_ambiguous", "standalone_metadata_conflict", "standalone_ancestry_unavailable"} {
		text := codexReportMessage(reason)
		if strings.Contains(text, "可尝试启动时使用") || !strings.Contains(text, "独立") && !strings.Contains(text, "已使用") {
			t.Fatalf("wrong standalone advice: %s", text)
		}
	}
}

func TestCodexSharedFailureNeverBecomesStandaloneReportFallback(t *testing.T) {
	s, _, a, _ := reportServer(t)
	if err := os.Remove(filepath.Join(s.home, ".codex", "app-server-control", "app-server-control.sock")); err != nil {
		t.Fatal(err)
	}
	got := commitReport(s, a)
	if got.BindingState == "verified" || got.SessionID != "" || got.BindingReason != "runtime_unavailable" {
		t.Fatalf("shared failure adopted hook-only identity: %+v", got)
	}
}

func TestCodexModeChangeDoesNotReturnOldRunState(t *testing.T) {
	s, _, a, _ := reportServer(t)
	old := commitReport(s, a)
	probe := s.codexProcessProbe
	s.codexProcessProbe = func(ctx context.Context, a *coordinator.AgentState) (codexRuntimeProbe, string) {
		p, r := probe(ctx, a)
		p.mode = "standalone"
		p.writers = map[string]string{reportID: "dev:inode-1"}
		return p, r
	}
	standaloneRollout(t, s.home, reportID, a.Cwd, nil)
	target := bindingDiagnosticTarget(old)
	if _, err := s.agentRunState(t.Context(), protocol.Inbound{Type: "get_agent_run_state", PaneID: a.PaneID, Target: &target}); err == nil {
		t.Fatal("mode change returned old Herdr status")
	}
}
