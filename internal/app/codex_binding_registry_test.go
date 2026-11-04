package app

import (
	"github.com/0cv/herdr-mobile-relay/internal/activity"
	"github.com/0cv/herdr-mobile-relay/internal/conversation"
	"io"
	"log/slog"
	"path/filepath"
	"sync"
	"testing"

	"github.com/0cv/herdr-mobile-relay/internal/coordinator"
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
)

func registryAgent() *coordinator.AgentState {
	return &coordinator.AgentState{PaneID: "pane", RawPaneID: "pane", TerminalID: "terminal", Agent: "codex", Cwd: "/project", Session: "thread", SessionKind: "id", ConversationProcess: "boot:20:100", ConversationSource: codexRuntimeSource + "thread", BindingStoreID: "store", BindingRuntime: "daemon", BindingState: "verified", BindingVerifiedAt: 1000, Status: "working"}
}
func registryState() *coordinator.State {
	return coordinator.NewState(slog.New(slog.NewTextHandler(io.Discard, nil)))
}
func commitRegistry(r *codexBindingRegistry, s *coordinator.State, a *coordinator.AgentState) *coordinator.AgentState {
	r.reconcile([]*coordinator.AgentState{a})
	a.SessionID = a.Session
	a.AgentSessionID = a.Session
	s.CommitInventory([]*coordinator.AgentState{a}, s.RevisionCounter())
	current, _ := s.Agent(a.PaneID)
	return current
}
func registryTarget(a *coordinator.AgentState) *protocol.TargetRef {
	return &protocol.TargetRef{ServerSessionID: "primary", PaneID: a.PaneID, TerminalID: a.TerminalID, Generation: a.Generation, AgentSessionID: a.SessionID, BindingEpoch: a.BindingEpoch}
}
func TestCodexBindingRecoveryKeepsIdentityButRevokesLease(t *testing.T) {
	var r codexBindingRegistry
	s := registryState()
	first := commitRegistry(&r, s, registryAgent())
	old := protocol.Inbound{PaneID: first.PaneID, Target: registryTarget(first)}
	if validateExactPaneTarget(s, old, true) != nil || !r.current(first) {
		t.Fatal("verified binding unavailable")
	}
	miss := registryAgent()
	miss.BindingStoreID = ""
	miss.BindingState = "verifying"
	miss.BindingReason = "herdr_report_unavailable"
	stale := commitRegistry(&r, s, miss)
	if stale.BindingState != "stale" || stale.SessionID != first.SessionID || stale.Generation != first.Generation || stale.BindingEpoch == first.BindingEpoch || stale.BindingVerifiedAt != first.BindingVerifiedAt {
		t.Fatalf("transient failure replaced identity: %+v", stale)
	}
	if r.current(stale) || validateExactPaneTarget(s, old, true) == nil {
		t.Fatal("stale lease remained usable")
	}
	cleanup := old
	cleanup.Type = "unwatch_conversation"
	if validateExactPaneTarget(s, cleanup, true) != nil {
		t.Fatal("revocation stranded owner-scoped stream cleanup")
	}

	recovered := commitRegistry(&r, s, registryAgent())
	if recovered.Generation != first.Generation || recovered.SessionID != first.SessionID || recovered.BindingEpoch == stale.BindingEpoch || !r.current(recovered) {
		t.Fatal("recovery failed to separate identity and permission")
	}
	if validateExactPaneTarget(s, old, true) == nil {
		t.Fatal("old queued request revived after recovery")
	}
	stable := commitRegistry(&r, s, registryAgent())
	if stable.BindingEpoch != recovered.BindingEpoch {
		t.Fatal("normal polling revoked a valid lease")
	}
}
func TestCodexBindingNeverCarriesHistoryAcrossReplacements(t *testing.T) {
	for _, kind := range []string{"terminal", "process", "store", "thread", "directory"} {
		t.Run(kind, func(t *testing.T) {
			var r codexBindingRegistry
			s := registryState()
			first := commitRegistry(&r, s, registryAgent())
			a := registryAgent()
			switch kind {
			case "terminal":
				a.TerminalID = "new-terminal"
			case "process":
				a.ConversationProcess = "boot:20:200"
			case "store":
				a.BindingStoreID = "other-store"
			case "thread":
				a.Session = "other-thread"
			case "directory":
				a.Cwd = "/other"
			}
			if kind != "thread" {
				a.Session = ""
				a.BindingState = "verifying"
				a.ConversationSource = ""
			}
			after := commitRegistry(&r, s, a)
			if after.SessionID == first.SessionID || after.BindingEpoch == first.BindingEpoch || after.Generation == first.Generation {
				t.Fatalf("replacement reused identity: %+v", after)
			}
		})
	}
}
func TestCodexMissingReportNeverReusesPreviouslyReportedID(t *testing.T) {
	var r codexBindingRegistry
	s := registryState()
	first := commitRegistry(&r, s, registryAgent())
	a := registryAgent()
	a.Session = ""
	a.ConversationSource = ""
	a.BindingState = "verifying"
	a.BindingReason = "herdr_session_missing"
	after := commitRegistry(&r, s, a)
	if after.SessionID != "" || after.Generation == first.Generation || r.current(after) {
		t.Fatal("missing report reused the previous session or retained permission")
	}
}
func TestCodexLegacyManualPinNoLongerAuthorizesReadOrMutation(t *testing.T) {
	var r codexBindingRegistry
	s := registryState()
	a := registryAgent()
	a.BindingState = "pinned_history"
	a.ConversationSource = "legacy-pin:" + "thread"
	pinned := commitRegistry(&r, s, a)
	server := &Server{state: s}
	// Runtime proof is deliberately absent: even a valid history pin cannot authorize a write.
	for _, name := range []string{"send_terminal_text", "respond", "set_agent_settings", "agent_clear"} {
		action, _ := protocol.ClassifyAction(name)
		if server.codexInteractionCurrent(t.Context(), protocol.Inbound{PaneID: pinned.PaneID, Target: registryTarget(pinned)}, action) {
			t.Fatal("manual history authorized", name)
		}
	}
	if r.current(pinned) {
		t.Fatal("legacy pin bypassed Herdr report authority")
	}
}
func TestCodexBindingInvalidationIsConcurrentAndDoesNotReviveOldEpoch(t *testing.T) {
	var r codexBindingRegistry
	s := registryState()
	a := commitRegistry(&r, s, registryAgent())
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); r.invalidate(a); _ = r.current(a) }()
	}
	wg.Wait()
	if r.current(a) {
		t.Fatal("revoked binding usable")
	}
	recovered := commitRegistry(&r, s, registryAgent())
	if recovered.BindingEpoch == a.BindingEpoch {
		t.Fatal("revoked epoch reused")
	}
	forged := *recovered
	forged.ServerSessionID = "other-server"
	if r.current(&forged) {
		t.Fatal("server scope ignored")
	}
	var restarted codexBindingRegistry
	next := commitRegistry(&restarted, registryState(), registryAgent())
	if next.BindingEpoch == a.BindingEpoch {
		t.Fatal("Relay restart reused a lease")
	}
	if next.BindingEpoch >= 1<<53 {
		t.Fatal("epoch not exactly representable in JSON clients")
	}
}
func TestCodexManagedStatusCannotBeOverwrittenByUnboundHook(t *testing.T) {
	var r codexBindingRegistry
	s := registryState()
	a := commitRegistry(&r, s, registryAgent())
	if s.CommitEvent(a.PaneID, "idle", 1001) {
		t.Fatal("unbound child hook changed the primary status")
	}
	after, _ := s.Agent(a.PaneID)
	if after.Status != "working" {
		t.Fatal("child completion made the primary idle")
	}
}

func TestCodexFirstProofIsDiscoveryNotAReplacement(t *testing.T) {
	var r codexBindingRegistry
	s := registryState()
	waiting := registryAgent()
	waiting.Session = ""
	waiting.ConversationProcess = ""
	waiting.ConversationSource = ""
	waiting.BindingStoreID = ""
	waiting.BindingRuntime = ""
	waiting.BindingState = "verifying"
	first := commitRegistry(&r, s, waiting)
	proven := commitRegistry(&r, s, registryAgent())
	if proven.Generation != first.Generation+1 || proven.SessionID == "" {
		t.Fatal("first proof did not establish a single identity revision")
	}
}

func TestCodexStaleBindingDoesNotResolveANewTitle(t *testing.T) {
	state := registryState()
	a := registryAgent()
	a.SessionID = a.Session
	a.Session = "cached display title"
	a.SessionName = a.Session
	state.CommitInventory([]*coordinator.AgentState{a}, state.RevisionCounter())
	next := registryAgent()
	next.BindingState = "stale"
	server := &Server{state: state}
	// No title resolver is configured: any attempt to read one would panic.
	server.resolveAgentSessionName(next)
	if next.SessionID != "thread" || next.SessionName != a.SessionName || next.ConversationHistoryAvailable {
		t.Fatal("stale view lost identity/title or advertised live history")
	}
}

func TestCodexActivityDoesNotBackfillOldEntriesFromReusedPane(t *testing.T) {
	home := t.TempDir()
	id := "123e4567-e89b-12d3-a456-426614174000"
	path := filepath.Join(home, ".codex", "sessions", "2026", "11", "04", "rollout-"+id+".jsonl")
	writeInvariantRows(t, path, map[string]any{"type": "session_meta", "payload": map[string]any{"id": id, "cwd": "/project"}}, map[string]any{"type": "response_item", "payload": map[string]any{"type": "message", "role": "assistant", "content": []map[string]any{{"type": "output_text", "text": "new thread response"}}}})
	state := registryState()
	a := registryAgent()
	a.SessionID = id
	state.CommitInventory([]*coordinator.AgentState{a}, state.RevisionCounter())
	s := &Server{state: state, conversationM: conversation.NewReader(home)}
	rows := s.enrichActivityResponses([]activity.Entry{{Kind: "finished", Agent: "codex", PaneID: a.PaneID, Session: "old thread", Timestamp: activity.MilliTimestamp(2000000000000), Extract: "captured old response"}})
	if rows[0].Extract != "captured old response" {
		t.Fatal("current thread overwrote historical activity")
	}
	a.BindingState = "stale"
	state.CommitInventory([]*coordinator.AgentState{a}, state.RevisionCounter())
	if s.captureFinishedPane(t.Context(), a.PaneID, a.Agent, a.Cwd, a.SessionID) != "" {
		t.Fatal("stale primary read terminal text as a completion")
	}
}
