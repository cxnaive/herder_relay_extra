package app

import (
	"github.com/0cv/herdr-mobile-relay/internal/config"
	"github.com/0cv/herdr-mobile-relay/internal/conversation"
	"net"
	"os"
	"path/filepath"
	"testing"

	"github.com/0cv/herdr-mobile-relay/internal/codexruntime"
	"github.com/0cv/herdr-mobile-relay/internal/coordinator"
	"github.com/0cv/herdr-mobile-relay/internal/herdr"
)

func TestCodexRuntimeSelectsExactProcessAndRejectsAmbiguousEndpoints(t *testing.T) {
	b, _, _ := codexBindingFixture()
	b.Title = "Same name | project"
	b.CWD = "/work/project"
	probe := codexRuntimeProbe{binding: b, listeners: map[string]string{"http://127.0.0.1:1234": "555"}}
	chosen := codexRuntimeThread{thread: codexruntime.Thread{ID: b.SessionID, CWD: b.CWD, Name: "Same name", Originator: "codex-tui"}, root: "/home/test/.codex/sessions"}
	candidates := map[string][]codexRuntimeThread{"http://127.0.0.1:1234": {chosen}, "http://127.0.0.1:9876": {chosen}}
	if _, _, ok := codexRuntimeCandidate(probe, candidates); !ok {
		t.Fatal("exact owned endpoint rejected because another process shares the title")
	}
	cases := map[string]func(map[string][]codexRuntimeThread){
		"multiple threads on same endpoint": func(m map[string][]codexRuntimeThread) {
			m["http://127.0.0.1:1234"] = []codexRuntimeThread{chosen, chosen}
		},
		"ambiguous other daemon": func(m map[string][]codexRuntimeThread) { m["http://127.0.0.1:1234"] = []codexRuntimeThread{chosen, {}} },
		"wrong project": func(m map[string][]codexRuntimeThread) {
			x := chosen
			x.thread.CWD = "/work/other"
			m["http://127.0.0.1:1234"] = []codexRuntimeThread{x}
		},

		"no owned endpoint": func(m map[string][]codexRuntimeThread) { delete(m, "http://127.0.0.1:1234") },
	}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			m := map[string][]codexRuntimeThread{}
			for k, v := range candidates {
				m[k] = append([]codexRuntimeThread{}, v...)
			}
			change(m)
			if _, _, ok := codexRuntimeCandidate(probe, m); ok {
				t.Fatal("unsafe identity accepted")
			}
		})
	}
}

func TestCodexRuntimeNormalizesOnlyLeadingSpinner(t *testing.T) {
	for _, title := range []string{"⠋ Same name | project", "⠇ Same name | project", "Same name | project"} {
		p := &herdr.Pane{TerminalTitleStripped: title}
		if got := codexTerminalTitle(p); got != "Same name | project" {
			t.Fatalf("title %q => %q", title, got)
		}
	}
	if got := codexTerminalTitle(&herdr.Pane{TerminalTitle: "Name ⠋ middle | project"}); got != "Name ⠋ middle | project" {
		t.Fatal("modified real title")
	}
}

func TestCodexRuntimeRevalidationFailsClosedAndPreservesAuthority(t *testing.T) {
	s := &Server{}
	authoritative := &coordinator.AgentState{Agent: "codex", SessionID: "123e4567-e89b-12d3-a456-426614174000"}
	if !s.codexRuntimeCurrent(t.Context(), authoritative) {
		t.Fatal("authoritative Herdr session was changed")
	}
	runtime := *authoritative
	runtime.ConversationSource = codexRuntimeSource + runtime.SessionID
	if s.codexRuntimeCurrent(t.Context(), &runtime) {
		t.Fatal("unverified stale runtime identity survived")
	}
}

func TestCodexRuntimeIgnoresStaleReportedIDOnlyAfterExactRuntimeBinding(t *testing.T) {
	b, p, i := codexBindingFixture()
	p.SessionRaw.Value = "223e4567-e89b-12d3-a456-426614174001"
	if codexBindingMatches(b, p, i, b.ProcessStart) {
		t.Fatal("manual binding bypassed reported identity conflict")
	}
	if !codexRuntimeBindingMatches(b, p, i, b.ProcessStart) {
		t.Fatal("proved live runtime cannot correct stale hook identity")
	}
	p.TerminalID = "different-terminal"
	if codexRuntimeBindingMatches(b, p, i, b.ProcessStart) {
		t.Fatal("runtime bypassed terminal isolation")
	}
}

func TestCodexReportedForeignProjectIsNeverPublishedOrReadable(t *testing.T) {
	home := t.TempDir()
	id := "123e4567-e89b-12d3-a456-426614174000"
	path := filepath.Join(home, ".codex", "sessions", "2026", "11", "04", "rollout-"+id+".jsonl")
	writeInvariantRows(t, path, map[string]any{"type": "session_meta", "payload": map[string]any{"id": id, "cwd": "/other-project"}})
	s := &Server{home: home, conversationM: conversation.NewReader(home)}
	a := &coordinator.AgentState{Agent: "codex", Cwd: "/current-project", Session: id, SessionID: id}
	if s.codexRuntimeCurrent(t.Context(), a) {
		t.Fatal("unrelated-project history accepted before the next poll")
	}
	s.enrichCodexRuntime(t.Context(), []*coordinator.AgentState{a})
	if a.Session != "" || a.ConversationReason != "codex_session_project_mismatch" {
		t.Fatal("foreign session remained in inventory")
	}
}

func TestCodexRuntimeRequiresProofEvenForAReportedSameProjectSession(t *testing.T) {
	home, err := os.MkdirTemp("", "codex-proof-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(home) })
	dir := filepath.Join(home, ".codex", "app-server-control")
	if err := os.MkdirAll(dir, 0700); err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("unix", filepath.Join(dir, "app-server-control.sock"))
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	s := &Server{home: home, conversationM: conversation.NewReader(home), cfg: &config.Config{RuntimeDir: t.TempDir()}}
	a := &coordinator.AgentState{PaneID: "pane", Agent: "codex", Cwd: "/project", Session: "123e4567-e89b-12d3-a456-426614174000", SessionID: "123e4567-e89b-12d3-a456-426614174000"}
	if s.codexRuntimeCurrent(t.Context(), a) {
		t.Fatal("unverified hook bypassed runtime identity")
	}
	pinned := *a
	pinned.ConversationSource = codexBindingSource + a.SessionID
	if !s.codexRuntimeCurrent(t.Context(), &pinned) {
		t.Fatal("explicit pin was blocked before its independent validation")
	}
	if s.conversationSelectionCurrent(t.Context(), &pinned) {
		t.Fatal("missing explicit binding escaped independent validation")
	}
	s.enrichCodexRuntime(t.Context(), []*coordinator.AgentState{a})
	if a.Session != "" || a.BindingState == "verified" || a.ConversationReason == "" {
		t.Fatal("unproved identity remained in inventory")
	}
}

func TestCodexRuntimeSelectionDoesNotDependOnTitle(t *testing.T) {
	p := codexRuntimeProbe{binding: codexSessionBinding{CWD: "/project"}, listeners: map[string]string{"http://127.0.0.1:1234": "inode"}}
	chosen := codexRuntimeThread{thread: codexruntime.Thread{ID: "123e4567-e89b-12d3-a456-426614174000", CWD: "/project", Originator: "codex-tui"}}
	if _, _, ok := codexRuntimeCandidate(p, map[string][]codexRuntimeThread{"http://127.0.0.1:1234": {chosen}}); !ok {
		t.Fatal("unnamed exact runtime thread rejected")
	}
	chosen.thread.ParentThreadID = "parent"
	if _, _, ok := codexRuntimeCandidate(p, map[string][]codexRuntimeThread{"http://127.0.0.1:1234": {chosen}}); ok {
		t.Fatal("delegated thread accepted as primary")
	}
}

func TestCodexListenerSetFencesNewEndpointAndSocketReuse(t *testing.T) {
	before := map[string]string{"http://127.0.0.1:1234": "inode-1"}
	if !codexListenerSetCurrent(before, before, "http://127.0.0.1:1234") {
		t.Fatal("stable listener rejected")
	}
	for _, after := range []map[string]string{
		{"http://127.0.0.1:1234": "inode-2"},
		{"http://127.0.0.1:1234": "inode-1", "http://127.0.0.1:1235": "inode-3"},
		{},
	} {
		if codexListenerSetCurrent(before, after, "http://127.0.0.1:1234") {
			t.Fatal("changed listener set accepted")
		}
	}
}
