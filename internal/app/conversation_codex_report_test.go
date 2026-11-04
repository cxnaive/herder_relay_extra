package app

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"sync"
	"testing"

	"github.com/0cv/herdr-mobile-relay/internal/codexruntime"
	"github.com/0cv/herdr-mobile-relay/internal/conversation"
	"github.com/0cv/herdr-mobile-relay/internal/coordinator"
	"github.com/0cv/herdr-mobile-relay/internal/herdr"
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
)

const reportID = "123e4567-e89b-12d3-a456-426614174000"
const otherReportID = "223e4567-e89b-12d3-a456-426614174001"
const childReportID = "323e4567-e89b-12d3-a456-426614174002"
const reportOrigin = "http://127.0.0.1:1234"

type reportPaneFixture struct {
	acceptTerminalInput                      bool
	terminalInputs                           []map[string]any
	mu                                       sync.Mutex
	pane                                     herdr.Pane
	calls                                    []string
	reads, changeAt, probes, processChangeAt int
	changed                                  herdr.AgentSession
	threads                                  map[string]codexruntime.Thread
	origins                                  map[string]string
}

func rootReportThread(id string) codexruntime.Thread {
	return codexruntime.Thread{ID: id, SessionID: id, CWD: "/project"}
}
func reportServer(t *testing.T) (*Server, *reportPaneFixture, *coordinator.AgentState, string) {
	t.Helper()
	home, err := os.MkdirTemp("", "herdr-runtime-test-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(home) })
	path := filepath.Join(home, "herdr.sock")
	listener, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	f := &reportPaneFixture{pane: herdr.Pane{ID: "pane", TerminalID: "terminal", Agent: "codex", Cwd: "/project", Status: "idle", SessionRaw: herdr.AgentSession{Source: "herdr:codex", Agent: "codex", Kind: "id", Value: reportID}}, threads: map[string]codexruntime.Thread{reportID: rootReportThread(reportID)}, origins: map[string]string{}}
	var wg sync.WaitGroup
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			wg.Add(1)
			go func() {
				defer wg.Done()
				defer conn.Close()
				var req struct {
					ID, Method string
					Params     map[string]any
				}
				if json.NewDecoder(conn).Decode(&req) != nil {
					return
				}
				f.mu.Lock()
				f.calls = append(f.calls, req.Method)
				f.reads++
				if f.changeAt == f.reads {
					f.pane.SessionRaw = f.changed
				}
				p := f.pane
				acceptInput := f.acceptTerminalInput && req.Method == "pane.send_input"
				if acceptInput {
					f.terminalInputs = append(f.terminalInputs, req.Params)
				}
				f.mu.Unlock()
				if acceptInput {
					json.NewEncoder(conn).Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "ok"}})
					return
				}
				if req.Method != "pane.get" {
					t.Errorf("unexpected Herdr operation: %s", req.Method)
					return
				}
				if req.Params["pane_id"] != "pane" {
					t.Error("read another pane")
				}
				json.NewEncoder(conn).Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "pane_info", "pane": p}})
			}()
		}
	}()
	t.Cleanup(func() { listener.Close(); <-done; wg.Wait() })
	client := herdr.NewClient("/do-not-run-cli", path)
	t.Cleanup(func() { client.Close() })
	s := &Server{home: home, state: registryState(), herdrC: client, conversationM: conversation.NewReader(home)}
	s.codexProcessProbe = func(_ context.Context, a *coordinator.AgentState) (codexRuntimeProbe, string) {
		if a.Agent != "codex" {
			t.Errorf("non-Codex process probed: %s", a.Agent)
		}
		f.mu.Lock()
		defer f.mu.Unlock()
		f.probes++
		process := "boot:42:100"
		if f.processChangeAt > 0 && f.probes >= f.processChangeAt {
			process = "boot:42:200"
		}
		return codexRuntimeProbe{pid: 42, process: process, root: filepath.Join(home, ".codex", "sessions"), listeners: map[string]string{reportOrigin: "inode-1"}}, ""
	}
	socket := filepath.Join(home, ".codex", "app-server-control", "app-server-control.sock")
	os.MkdirAll(filepath.Dir(socket), 0700)
	runtimeListener, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer conn.CloseNow()
		for {
			var req struct {
				ID     int    `json:"id"`
				Method string `json:"method"`
				Params struct {
					ThreadID string `json:"threadId"`
				} `json:"params"`
			}
			if wsjson.Read(r.Context(), conn, &req) != nil {
				return
			}
			if req.Method == "initialized" {
				continue
			}
			f.mu.Lock()
			f.calls = append(f.calls, req.Method)
			var result any
			switch req.Method {
			case "initialize":
				result = map[string]any{}
			case "thread/loaded/list":
				ids := []string{}
				for id := range f.threads {
					ids = append(ids, id)
				}
				sort.Strings(ids)
				result = map[string]any{"data": ids, "nextCursor": nil}
			case "mcpServerStatus/list":
				origin := reportOrigin
				if custom := f.origins[req.Params.ThreadID]; custom != "" {
					origin = custom
				}
				result = map[string]any{"data": []any{map[string]any{"name": "codex_tui", "httpOrigin": origin, "runtimeStatus": "connected"}}, "nextCursor": nil}
			case "thread/read":
				result = map[string]any{"thread": f.threads[req.Params.ThreadID]}
			default:
				t.Errorf("unexpected runtime operation: %s", req.Method)
			}
			f.mu.Unlock()
			if wsjson.Write(r.Context(), conn, map[string]any{"id": req.ID, "result": result}) != nil {
				return
			}
		}
	})}
	go server.Serve(runtimeListener)
	t.Cleanup(func() { server.Close() })
	a := &coordinator.AgentState{PaneID: "pane", RawPaneID: "pane", TerminalID: "terminal", Agent: "codex", Cwd: "/project", Session: reportID, SessionKind: "id", ReportedSession: f.pane.SessionRaw, Status: "idle"}
	return s, f, a, reportRollout(t, home, reportID, "/project", "synthetic main history")
}
func reportRollout(t *testing.T, home, id, cwd, text string) string {
	t.Helper()
	p := filepath.Join(home, ".codex", "sessions", "2026", "11", "04", "rollout-"+id+".jsonl")
	writeInvariantRows(t, p, map[string]any{"type": "session_meta", "payload": map[string]any{"id": id, "cwd": cwd}}, map[string]any{"type": "response_item", "payload": map[string]any{"type": "message", "role": "assistant", "content": []any{map[string]any{"type": "output_text", "text": text}}}})
	return p
}
func commitReport(s *Server, a *coordinator.AgentState) *coordinator.AgentState {
	s.enrichCodexSessions(context.Background(), []*coordinator.AgentState{a})
	a.SessionID = a.Session
	a.AgentSessionID = a.Session
	s.state.CommitInventory([]*coordinator.AgentState{a}, s.state.RevisionCounter())
	v, _ := s.state.Agent(a.PaneID)
	return v
}
func TestCodexUniqueRuntimeDoesNotRequireOrTrustHerdrReport(t *testing.T) {
	for _, ref := range []herdr.AgentSession{{}, {Kind: "path", Value: "invalid"}, {Kind: "id", Agent: "codex", Value: otherReportID}} {
		t.Run(ref.Value, func(t *testing.T) {
			s, f, a, _ := reportServer(t)
			f.pane.SessionRaw = ref
			v := commitReport(s, a)
			if v.BindingState != "verified" || v.SessionID != reportID || !s.codexPrimaryCurrent(t.Context(), v) {
				t.Fatalf("unique runtime: %+v", v)
			}
		})
	}
}
func TestCodexPrimaryExcludesChildrenButNotIndependentForks(t *testing.T) {
	s, f, a, _ := reportServer(t)
	child := rootReportThread(childReportID)
	child.ParentThreadID = reportID
	child.SessionID = reportID
	f.threads[child.ID] = child
	f.pane.SessionRaw.Value = child.ID
	if v := commitReport(s, a); v.BindingState != "verified" || v.SessionID != reportID {
		t.Fatalf("child displaced primary: %+v", v)
	}
	independent := rootReportThread(otherReportID)
	independent.ForkedFromID = reportID
	f.threads[independent.ID] = independent
	f.pane.SessionRaw = herdr.AgentSession{}
	if v := commitReport(s, a); v.BindingState != "ambiguous" || v.SessionID != "" {
		t.Fatalf("fork was treated as child: %+v", v)
	}
}
func TestCodexMultipleRootsRequireMatchingHerdrReport(t *testing.T) {
	for _, tc := range []struct {
		name   string
		ref    herdr.AgentSession
		reason string
	}{
		{"matching", herdr.AgentSession{Kind: "id", Agent: "codex", Value: reportID}, ""},
		{"missing", herdr.AgentSession{}, "runtime_ambiguous_no_report"},
		{"invalid", herdr.AgentSession{Kind: "path", Value: reportID}, "herdr_session_invalid"},
		{"unrelated", herdr.AgentSession{Kind: "id", Value: childReportID}, "reported_session_not_candidate"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s, f, a, _ := reportServer(t)
			f.threads[otherReportID] = rootReportThread(otherReportID)
			f.pane.SessionRaw = tc.ref
			v := commitReport(s, a)
			if v.BindingReason != tc.reason {
				t.Fatalf("got %s want %s", v.BindingReason, tc.reason)
			}
			if tc.reason == "" {
				if v.SessionID != reportID || !s.codexPrimaryCurrent(t.Context(), v) {
					t.Fatal("matching root not usable")
				}
			} else if v.SessionID != "" || !strings.Contains(codexReportMessage(v.BindingReason), "--no-daemon") {
				t.Fatal("failure guessed ID or omitted guidance")
			}
		})
	}
}
func TestCodexRuntimeScopeCannotImportAnotherTerminal(t *testing.T) {
	s, f, a, _ := reportServer(t)
	f.threads[otherReportID] = rootReportThread(otherReportID)
	f.origins[otherReportID] = "http://127.0.0.1:9876"
	f.pane.SessionRaw.Value = otherReportID
	if v := commitReport(s, a); v.SessionID != reportID || v.BindingState != "verified" {
		t.Fatalf("other terminal leaked: %+v", v)
	}
}
func TestCodexBrokenAncestryNeverUsesReportToGuess(t *testing.T) {
	s, f, a, _ := reportServer(t)
	child := rootReportThread(childReportID)
	child.ParentThreadID = otherReportID
	child.SessionID = reportID
	f.threads[child.ID] = child
	v := commitReport(s, a)
	if v.SessionID != "" || v.BindingReason != "runtime_ancestry_unavailable" {
		t.Fatalf("broken ancestry guessed: %+v", v)
	}
}
func TestCodexRuntimeIdentityDoesNotRequireMaterializedHistory(t *testing.T) {
	s, f, a, file := reportServer(t)
	os.Remove(file)
	f.pane.SessionRaw = herdr.AgentSession{}
	v := commitReport(s, a)
	if v.BindingState != "verified" || v.SessionID != reportID {
		t.Fatalf("history absence discarded ID: %+v", v)
	}
}
func TestCodexRuntimeRejectsProjectAndProcessChanges(t *testing.T) {
	for _, kind := range []string{"project", "process", "history"} {
		t.Run(kind, func(t *testing.T) {
			s, f, a, file := reportServer(t)
			switch kind {
			case "project":
				thread := f.threads[reportID]
				thread.CWD = "/other"
				f.threads[reportID] = thread
			case "process":
				f.processChangeAt = 2
			case "history":
				os.Remove(file)
				reportRollout(t, s.home, reportID, "/other", "foreign history")
			}
			v := commitReport(s, a)
			if v.BindingState == "verified" || v.SessionID != "" {
				t.Fatal("changed scope accepted")
			}
		})
	}
}
func TestCodexHerdrTieChangingDuringValidationIsRejected(t *testing.T) {
	s, f, a, _ := reportServer(t)
	f.threads[otherReportID] = rootReportThread(otherReportID)
	f.changeAt = 2
	f.changed = herdr.AgentSession{Kind: "id", Agent: "codex", Value: otherReportID}
	v := commitReport(s, a)
	if v.BindingReason != "runtime_changed" || v.SessionID != "" {
		t.Fatal("changing tie-break accepted")
	}
}
func TestCodexRuntimeResumeRevokesOldBinding(t *testing.T) {
	s, f, a, _ := reportServer(t)
	old := commitReport(s, a)
	if !s.codexPrimaryCurrent(t.Context(), old) {
		t.Fatal("initial binding not current")
	}
	f.mu.Lock()
	f.threads = map[string]codexruntime.Thread{otherReportID: rootReportThread(otherReportID)}
	f.mu.Unlock()
	if s.codexPrimaryCurrent(t.Context(), old) {
		t.Fatal("old runtime lease survived")
	}
	next := commitReport(s, a)
	if next.SessionID != otherReportID || next.BindingEpoch == old.BindingEpoch {
		t.Fatal("runtime switch did not replace binding")
	}
}
func TestCodexDiscoveryLeavesOtherAgentTypesUntouched(t *testing.T) {
	s, f, _, _ := reportServer(t)
	for _, kind := range []string{"claude", "opencode", "gemini", "kimi", "pi"} {
		a := &coordinator.AgentState{Agent: kind, PaneID: "other", TerminalID: "other-terminal", Session: "upstream-id", SessionKind: "id", Status: "working", ConversationSource: "upstream"}
		before := *a
		s.enrichCodexSessions(t.Context(), []*coordinator.AgentState{a})
		if !reflect.DeepEqual(before, *a) {
			t.Fatalf("modified %s", kind)
		}
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.calls) != 0 || f.probes != 0 {
		t.Fatal("non-Codex invoked special discovery")
	}
}

func TestCodexRuntimeSettingsTargetDoesNotRequireHerdrReportOrHistory(t *testing.T) {
	s, f, a, file := reportServer(t)
	f.pane.SessionRaw = herdr.AgentSession{}
	if err := os.Remove(file); err != nil {
		t.Fatal(err)
	}
	current := commitReport(s, a)
	socket, id, cwd, guard, err := s.codexRuntimeTarget(t.Context(), protocol.Inbound{PaneID: current.PaneID, Target: registryTarget(current)})
	if err != nil || socket == "" || id != reportID || cwd != "/project" || guard == nil {
		t.Fatalf("runtime-only settings target: %q %q %v", socket, id, err)
	}
	if err := guard(); err != nil {
		t.Fatal(err)
	}
}
