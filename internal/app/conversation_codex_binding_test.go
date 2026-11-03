package app

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"

	"github.com/0cv/herdr-mobile-relay/internal/coordinator"
	"github.com/0cv/herdr-mobile-relay/internal/herdr"
)

func codexBindingFixture() (codexSessionBinding, *herdr.Pane, *herdr.PaneProcessInfo) {
	b := codexSessionBinding{PaneID: "pane", TerminalID: "terminal", CWD: "/work/project", Title: "Selected research | project", SessionID: "123e4567-e89b-12d3-a456-426614174000", PID: 20, ProcessStart: "boot:20:100"}
	pane := &herdr.Pane{ID: b.PaneID, TerminalID: b.TerminalID, Cwd: b.CWD, Agent: "codex", TerminalTitleStripped: b.Title}
	processes := &herdr.PaneProcessInfo{PaneID: b.PaneID, ShellPID: 10, ForegroundProcessGroupID: 20, ForegroundProcesses: []herdr.PaneProcess{{PID: 20, Cwd: b.CWD, Argv: []string{"/usr/bin/codex", "--yolo"}}}}
	return b, pane, processes
}

func TestCodexBindingRequiresExactTerminalProcessDirectoryAndTitle(t *testing.T) {
	b, pane, info := codexBindingFixture()
	if !codexBindingMatches(b, pane, info, b.ProcessStart) {
		t.Fatal("valid explicit binding rejected")
	}
	cases := map[string]func(*herdr.Pane, *herdr.PaneProcessInfo){
		"other pane":                 func(p *herdr.Pane, i *herdr.PaneProcessInfo) { p.ID = "other" },
		"new terminal":               func(p *herdr.Pane, i *herdr.PaneProcessInfo) { p.TerminalID = "replacement" },
		"changed cwd":                func(p *herdr.Pane, i *herdr.PaneProcessInfo) { p.Cwd = "/work/other" },
		"renamed or switched thread": func(p *herdr.Pane, i *herdr.PaneProcessInfo) { p.TerminalTitleStripped = "Another task | project" },
		"different agent":            func(p *herdr.Pane, i *herdr.PaneProcessInfo) { p.Agent = "pi" },
		"reported different id": func(p *herdr.Pane, i *herdr.PaneProcessInfo) {
			p.SessionRaw.Value = "223e4567-e89b-12d3-a456-426614174001"
		},
		"new foreground": func(p *herdr.Pane, i *herdr.PaneProcessInfo) {
			i.ForegroundProcessGroupID = 21
			i.ForegroundProcesses[0].PID = 21
		},
		"ambiguous foreground": func(p *herdr.Pane, i *herdr.PaneProcessInfo) {
			i.ForegroundProcesses = append(i.ForegroundProcesses, herdr.PaneProcess{PID: 22})
		},
		"different executable": func(p *herdr.Pane, i *herdr.PaneProcessInfo) { i.ForegroundProcesses[0].Argv = []string{"bash"} },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			b, p, i := codexBindingFixture()
			mutate(p, i)
			if codexBindingMatches(b, p, i, b.ProcessStart) {
				t.Fatal("unsafe match")
			}
		})
	}
	if codexBindingMatches(b, pane, info, "new-boot:20:100") || codexBindingMatches(b, pane, info, "") {
		t.Fatal("reboot/PID reuse not fenced")
	}
}

type bindingProcessReader struct {
	pane *herdr.Pane
	info *herdr.PaneProcessInfo
}

func (r bindingProcessReader) PaneGet(context.Context, string) (*herdr.Pane, error) {
	return r.pane, nil
}
func (r bindingProcessReader) PaneProcessInfoFresh(context.Context, string) (*herdr.PaneProcessInfo, error) {
	return r.info, nil
}

func TestCodexBindingRechecksProcessBirthAndCancellation(t *testing.T) {
	b, p, i := codexBindingFixture()
	reader := bindingProcessReader{p, i}
	if !verifyCodexBinding(t.Context(), reader, b, func(int) (string, error) { return b.ProcessStart, nil }) {
		t.Fatal("stable process rejected")
	}
	calls := 0
	if verifyCodexBinding(t.Context(), reader, b, func(int) (string, error) {
		calls++
		if calls == 1 {
			return b.ProcessStart, nil
		}
		return "new", nil
	}) {
		t.Fatal("mid-read replacement accepted")
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if verifyCodexBinding(ctx, reader, b, func(int) (string, error) { return b.ProcessStart, nil }) {
		t.Fatal("cancelled binding accepted")
	}
}

func TestCodexBindingConfigRejectsDuplicatesAndUnboundedFiles(t *testing.T) {
	dir := t.TempDir()
	b, _, _ := codexBindingFixture()
	path := filepath.Join(dir, "codex-session-bindings.json")
	write := func(bindings []codexSessionBinding) {
		raw, _ := json.Marshal(map[string]any{"schema": 1, "bindings": bindings})
		if err := os.WriteFile(path, raw, 0600); err != nil {
			t.Fatal(err)
		}
	}
	write([]codexSessionBinding{b})
	if len(loadCodexBindings(dir)) != 1 {
		t.Fatal("valid file rejected")
	}
	write([]codexSessionBinding{b, b})
	if len(loadCodexBindings(dir)) != 0 {
		t.Fatal("ambiguous duplicate accepted")
	}
	b.SessionID = "not a thread"
	write([]codexSessionBinding{b})
	if len(loadCodexBindings(dir)) != 0 {
		t.Fatal("malformed ID accepted")
	}
	if err := os.WriteFile(path, make([]byte, 65537), 0600); err != nil {
		t.Fatal(err)
	}
	if len(loadCodexBindings(dir)) != 0 {
		t.Fatal("oversize file accepted")
	}
}

func TestCodexBindingRemovalInvalidatesTheOldConversation(t *testing.T) {
	b, _, _ := codexBindingFixture()
	server := &Server{}
	// Unbound authoritative identities retain the original behavior without disk access.
	if !server.codexBindingCurrent(t.Context(), &coordinator.AgentState{Agent: "codex", SessionID: b.SessionID}) {
		t.Fatal("reported session affected")
	}
	state := coordinator.NewState(slog.New(slog.NewTextHandler(io.Discard, nil)))
	agent := &coordinator.AgentState{PaneID: b.PaneID, TerminalID: b.TerminalID, Agent: "codex", Cwd: b.CWD, SessionID: b.SessionID, ConversationSource: codexBindingSource + b.SessionID, ConversationProcess: b.ProcessStart}
	state.CommitInventory([]*coordinator.AgentState{agent}, state.RevisionCounter())
	before, _ := state.Agent(b.PaneID)
	replacement := *agent
	replacement.SessionID = ""
	replacement.ConversationSource = ""
	replacement.ConversationProcess = ""
	state.CommitInventory([]*coordinator.AgentState{&replacement}, state.RevisionCounter())
	after, _ := state.Agent(b.PaneID)
	if after.Generation <= before.Generation || sameConversationTuple(before, after) {
		t.Fatal("revoked binding did not invalidate exact target")
	}
}
