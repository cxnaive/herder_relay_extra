package app

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"sync"
	"time"

	"github.com/0cv/herdr-mobile-relay/internal/coordinator"
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
)

type codexBindingRecord struct {
	terminal, server, cwd, session, process, source, store, runtime, state, status string
	epoch                                                                          uint64
	verifiedAt                                                                     int64
}

// Proof availability is not session identity. Losing proof revokes the lease,
// but retains the last verified tuple for explicitly labelled cached history.
type codexBindingRegistry struct {
	mu       sync.Mutex
	records  map[string]codexBindingRecord
	sequence uint64
}

func usableCodexBinding(state string) bool {
	return state == "verified"
}

func (r *codexBindingRegistry) reconcile(agents []*coordinator.AgentState) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.records == nil {
		r.records = map[string]codexBindingRecord{}
		var seed [8]byte
		if _, err := rand.Read(seed[:]); err != nil {
			for _, a := range agents {
				if a.BindingState != "" {
					a.BindingState = "verifying"
					a.BindingReason = "binding_epoch_unavailable"
				}
			}
			return
		}
		// Stay exactly representable by JSON/JavaScript clients, without reusing
		// epochs after a Relay restart. This is a revocation ID, not a credential.
		r.sequence = binary.LittleEndian.Uint64(seed[:]) & ((1 << 48) - 1)
	}
	seen := map[string]bool{}
	for _, a := range agents {
		if a.Agent != "codex" || a.BindingState == "" {
			continue
		}
		seen[a.PaneID] = true
		old, exists := r.records[a.PaneID]
		sameTerminal := exists && old.terminal == a.TerminalID && old.server == a.ServerSessionID && old.cwd == a.Cwd
		sameProcess := sameTerminal && (a.ConversationProcess == "" || a.ConversationProcess == old.process) && (a.BindingStoreID == "" || old.store == "" || a.BindingStoreID == old.store)
		if !usableCodexBinding(a.BindingState) && sameProcess && old.session != "" && old.session == a.Session {
			if a.BindingStoreID == "" {
				a.BindingStoreID = old.store
			}
			a.BindingVerifiedAt = old.verifiedAt
			if a.BindingState == "verifying" && a.BindingReason == "herdr_report_unavailable" {
				a.BindingState = "stale"
			}
		}
		changed := !exists || !sameTerminal || old.session != a.Session || old.process != a.ConversationProcess || old.store != a.BindingStoreID || old.runtime != a.BindingRuntime || old.state != a.BindingState || old.source != a.ConversationSource
		if changed {
			r.sequence++
			a.BindingEpoch = r.sequence
		} else {
			a.BindingEpoch = old.epoch
		}
		r.records[a.PaneID] = codexBindingRecord{terminal: a.TerminalID, server: a.ServerSessionID, cwd: a.Cwd, session: a.Session, process: a.ConversationProcess, source: a.ConversationSource, store: a.BindingStoreID, runtime: a.BindingRuntime, state: a.BindingState, status: a.Status, epoch: a.BindingEpoch, verifiedAt: a.BindingVerifiedAt}
	}
	for pane := range r.records {
		if !seen[pane] {
			delete(r.records, pane)
		}
	}
}

func (r *codexBindingRegistry) current(a *coordinator.AgentState) bool {
	if a.BindingEpoch == 0 {
		return a.BindingState == ""
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	b, ok := r.records[a.PaneID]
	return ok && b.epoch == a.BindingEpoch && usableCodexBinding(b.state) && b.terminal == a.TerminalID && b.server == a.ServerSessionID && b.cwd == a.Cwd && b.session == a.SessionID && b.process == a.ConversationProcess && b.store == a.BindingStoreID
}

func (r *codexBindingRegistry) invalidate(a *coordinator.AgentState) {
	if a.BindingEpoch == 0 {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	b, ok := r.records[a.PaneID]
	if !ok || b.epoch != a.BindingEpoch || !usableCodexBinding(b.state) {
		return
	}
	r.sequence++
	b.epoch = r.sequence
	b.state = "stale"
	r.records[a.PaneID] = b
}

func (s *Server) enrichCodexSessions(ctx context.Context, agents []*coordinator.AgentState) {
	s.codexDiscoveryMu.Lock()
	defer s.codexDiscoveryMu.Unlock()
	s.enrichCodexRuntime(ctx, agents)
	s.codexBindings.reconcile(agents)
}

// These commands target the displayed terminal, not a resolved conversation.
// Unknown/future mutations remain primary-bound by default.
func codexTerminalMutation(action protocol.ActionMetadata) bool {
	if action.Class != protocol.ActionMutating {
		return false
	}
	switch action.Operation {
	case "send_terminal_text", "send_text", "send_keys", "send_input", "send_filter_text", "send_secret", "lease_pane_size", "agent_stop", "agent_restart", "agent_clear":
		return true
	default:
		return false
	}
}

func codexMutationNeedsPrimary(action protocol.ActionMetadata) bool {
	if action.Class != protocol.ActionMutating || codexTerminalMutation(action) {
		return false
	}
	switch action.Operation {
	case "agent_rename", "acknowledge_pane", "focus_pane", "set_pane_size", "release_pane_size", "upload_cancel", "cancel_speech":
		return false
	default:
		return true
	}
}

func (s *Server) codexInteractionCurrent(ctx context.Context, in protocol.Inbound, action protocol.ActionMetadata) bool {
	if in.PaneID == "" {
		return true
	}
	a, exists := s.state.Agent(in.PaneID)
	if !exists || a.Agent != "codex" || a.BindingState == "" {
		return true
	}
	if codexTerminalMutation(action) {
		return s.codexTerminalCurrent(ctx, in, a)
	}
	if !codexMutationNeedsPrimary(action) {
		return true
	}
	return a.BindingState == "verified" && s.codexPrimaryCurrent(ctx, a)
}

// Called both at admission and by the dispatch guard after scheduler/capacity
// waits. Session IDs may be empty but exact inventory leases may not be stale.
func (s *Server) codexTerminalCurrent(ctx context.Context, in protocol.Inbound, a *coordinator.AgentState) bool {
	if s.herdrC == nil || ctx.Err() != nil || validateExactPaneTarget(s.state, in, true) != nil || (in.Cwd != "" && in.Cwd != a.Cwd) {
		return false
	}
	check, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	pane, err := s.herdrC.PaneGet(check, a.PaneID)
	return err == nil && pane != nil && pane.ID == a.PaneID && pane.TerminalID == a.TerminalID && pane.Agent == a.Agent && pane.Cwd == a.Cwd && ctx.Err() == nil && validateExactPaneTarget(s.state, in, true) == nil
}

// File access proves terminal/workspace identity independently of chat discovery.
// Existing file-root authorization and exact request-target checks still apply.
func (s *Server) workspaceSelectionCurrent(ctx context.Context, a *coordinator.AgentState) bool {
	if a == nil {
		return false
	}
	if a.Agent != "codex" || a.BindingState == "" {
		return s.conversationSelectionCurrent(ctx, a)
	}
	if s.herdrC == nil {
		return false
	}
	pane, err := s.herdrC.PaneGet(ctx, a.PaneID)
	return err == nil && pane.ID == a.PaneID && pane.TerminalID == a.TerminalID && pane.Cwd == a.Cwd
}
