package app

import (
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
		"title switched": func(m map[string][]codexRuntimeThread) {
			x := chosen
			x.thread.Name = "Changed"
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
