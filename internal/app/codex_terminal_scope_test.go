package app

import (
	"context"
	"errors"
	"github.com/0cv/herdr-mobile-relay/internal/herdr"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/0cv/herdr-mobile-relay/internal/coordinator"
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
	"github.com/0cv/herdr-mobile-relay/internal/transport"
)

func unresolvedTerminal(t *testing.T) (*Server, *reportPaneFixture, *coordinator.AgentState) {
	t.Helper()
	s, f, a, _ := reportServer(t)
	f.threads[otherReportID] = rootReportThread(otherReportID)
	f.pane.SessionRaw.Value = ""
	current := commitReport(s, a)
	if current.BindingState != "ambiguous" || current.SessionID != "" {
		t.Fatalf("fixture not unresolved: %+v", current)
	}
	f.mu.Lock()
	f.calls = nil
	f.mu.Unlock()
	return s, f, current
}

func TestCodexUnresolvedTerminalAndLifecycleHaveIndependentAuthority(t *testing.T) {
	for _, name := range []string{"send_terminal_text", "send_text", "send_keys", "send_input", "send_filter_text", "send_secret", "lease_pane_size", "agent_stop", "agent_restart", "agent_clear"} {
		t.Run(name, func(t *testing.T) {
			s, f, a := unresolvedTerminal(t)
			action, _ := protocol.ClassifyAction(name)
			in := protocol.Inbound{Type: name, PaneID: a.PaneID, Target: registryTarget(a)}
			if !s.codexInteractionCurrent(t.Context(), in, action) {
				t.Fatal("terminal authority incorrectly needs a main conversation")
			}
			f.mu.Lock()
			defer f.mu.Unlock()
			if len(f.calls) == 0 {
				t.Fatal("terminal was not freshly checked")
			}
			for _, call := range f.calls {
				if call != "pane.get" {
					t.Fatal("terminal operation queried chat runtime:", call)
				}
			}
			if s.codexBindings.current(a) {
				t.Fatal("terminal operation confirmed chat")
			}
			if authorizeAuthenticatedIdentity(transport.AuthenticatedIdentity{Role: "reader"}, true, action, "") == nil {
				t.Fatal("reader allowed")
			}
			if authorizeAuthenticatedIdentity(transport.AuthenticatedIdentity{Role: "controller"}, true, action, "") != nil {
				t.Fatal("controller denied")
			}
		})
	}
}

func TestCodexUnresolvedChatOperationsStillNeedPrimary(t *testing.T) {
	s, _, a := unresolvedTerminal(t)
	for _, name := range []string{"submit_prompt", "respond", "answer_question", "navigate_question", "clarify_question", "set_agent_settings", "copy_agent_response"} {
		action, _ := protocol.ClassifyAction(name)
		if s.codexInteractionCurrent(t.Context(), protocol.Inbound{Type: name, PaneID: a.PaneID, Target: registryTarget(a)}, action) {
			t.Fatal("unresolved chat allowed:", name)
		}
	}
}

func TestCodexTerminalScopeRejectsStaleAndForgedTargets(t *testing.T) {
	for _, change := range []string{"missing_target", "terminal", "generation", "session", "epoch", "server", "cwd", "live_terminal", "live_provider", "live_cwd", "cancelled"} {
		t.Run(change, func(t *testing.T) {
			s, f, a := unresolvedTerminal(t)
			action, _ := protocol.ClassifyAction("send_terminal_text")
			in := protocol.Inbound{Type: action.Operation, PaneID: a.PaneID, Target: registryTarget(a)}
			ctx := t.Context()
			switch change {
			case "missing_target":
				in.Target = nil
			case "terminal":
				in.Target.TerminalID = "other"
			case "generation":
				in.Target.Generation++
			case "session":
				in.Target.AgentSessionID = reportID
			case "epoch":
				in.Target.BindingEpoch++
			case "server":
				in.Target.ServerSessionID = "other"
			case "cwd":
				in.Cwd = "/other"
			case "live_terminal":
				f.pane.TerminalID = "replacement"
			case "live_provider":
				f.pane.Agent = "claude"
			case "live_cwd":
				f.pane.Cwd = "/other"
			case "cancelled":
				var cancel context.CancelFunc
				ctx, cancel = context.WithCancel(ctx)
				cancel()
			}
			if s.codexInteractionCurrent(ctx, in, action) {
				t.Fatal("invalid terminal authority accepted")
			}
		})
	}
}

func TestCodexTerminalRecheckDoesNotReuseAdmissionAfterQueueWait(t *testing.T) {
	s, f, a := unresolvedTerminal(t)
	action, _ := protocol.ClassifyAction("agent_restart")
	in := protocol.Inbound{Type: action.Operation, PaneID: a.PaneID, Target: registryTarget(a)}
	if !s.codexInteractionCurrent(t.Context(), in, action) {
		t.Fatal("admission denied")
	}
	f.mu.Lock()
	f.pane.TerminalID = "replacement-during-queue-wait"
	f.mu.Unlock()
	if s.codexInteractionCurrent(t.Context(), in, action) {
		t.Fatal("old admission authorized replacement terminal")
	}
}

func TestCodexTerminalRecoveryUsesGuardedDispatcherAndCannotReplayOnReplacement(t *testing.T) {
	s, f, a := unresolvedTerminal(t)
	f.mu.Lock()
	f.acceptTerminalInput = true
	f.mu.Unlock()
	d := coordinator.NewDispatcher(s.herdrC, s.state, nil, slog.New(slog.NewTextHandler(io.Discard, nil)))
	defer d.Close(context.Background())
	action, _ := protocol.ClassifyAction("send_terminal_text")
	in := protocol.Inbound{Type: action.Operation, PaneID: a.PaneID, Target: registryTarget(a)}
	guard := herdr.WithDispatchCheck(t.Context(), func() error {
		if !s.codexInteractionCurrent(t.Context(), in, action) {
			return coordinator.ErrPaneReplaced
		}
		return nil
	})
	message := map[string]any{"action": action.Operation, "request_id": "manual-one", "pane_id": a.PaneID, "text": "  synthetic terminal text  "}
	for i := 0; i < 2; i++ {
		if result := d.Handle(guard, message); !result.OK {
			t.Fatalf("terminal send: %+v", result)
		}
	}
	f.mu.Lock()
	if len(f.terminalInputs) != 1 || f.terminalInputs[0]["text"] != message["text"] {
		t.Fatal("literal text not sent exactly once")
	}
	f.pane.TerminalID = "replacement"
	f.mu.Unlock()
	message["request_id"] = "after-replacement"
	if result := d.Handle(guard, message); result.OK {
		t.Fatal("stale queued terminal input succeeded")
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.terminalInputs) != 1 {
		t.Fatal("replacement received stale text")
	}
}

func TestCodexStopAndRestartDispatchDoNotRequireChat(t *testing.T) {
	for _, operation := range []string{"agent_stop", "agent_restart", "agent_clear"} {
		t.Run(operation, func(t *testing.T) {
			s, f, a := unresolvedTerminal(t)
			logPath := filepath.Join(s.home, "closed-pane.log")
			script := filepath.Join(s.home, "fake-herdr")
			if err := os.WriteFile(script, []byte("#!/bin/sh\nprintf '%s\\n' \"$*\" >> '"+logPath+"'\n"), 0700); err != nil {
				t.Fatal(err)
			}
			client := herdr.NewClient(script, filepath.Join(s.home, "herdr.sock"))
			defer client.Close()
			s.herdrC = client
			d := coordinator.NewDispatcher(client, s.state, nil, slog.New(slog.NewTextHandler(io.Discard, nil)))
			defer d.Close(context.Background())
			action, _ := protocol.ClassifyAction(operation)
			in := protocol.Inbound{Type: operation, PaneID: a.PaneID, Target: registryTarget(a)}
			ctx := herdr.WithDispatchCheck(t.Context(), func() error {
				if !s.codexInteractionCurrent(t.Context(), in, action) {
					return errors.New("terminal changed")
				}
				return nil
			})
			// Nil lifecycle uses the dispatcher's stop-only fixture; full owned-profile
			// restart creation is separately covered by coordinator lifecycle tests.
			result := d.Handle(ctx, map[string]any{"action": operation, "pane_id": a.PaneID, "request_id": "recovery"})
			if !result.OK {
				t.Fatalf("recovery denied: %+v", result)
			}
			raw, err := os.ReadFile(logPath)
			if err != nil || strings.TrimSpace(string(raw)) != "pane close pane" {
				t.Fatalf("wrong target: %q %v", raw, err)
			}
			f.mu.Lock()
			defer f.mu.Unlock()
			for _, method := range f.calls {
				if method != "pane.get" {
					t.Fatal("recovery queried runtime:", method)
				}
			}
		})
	}
}
