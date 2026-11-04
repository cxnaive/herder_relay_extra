//go:build linux

package app

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/0cv/herdr-mobile-relay/internal/codexruntime"
	"github.com/0cv/herdr-mobile-relay/internal/conversation"
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
)

const historyID = "123e4567-e89b-12d3-a456-426614174010"
const emptyID = "123e4567-e89b-12d3-a456-426614174011"
const historyOrigin = "http://127.0.0.1:1234"

func associatedHistoryFixture(t *testing.T) (*Server, codexRuntimeProbe, map[string]codexruntime.Thread) {
	t.Helper()
	home := t.TempDir()
	root := filepath.Join(home, ".codex", "sessions")
	path := filepath.Join(root, "2026", "11", "04", "rollout-"+historyID+"_"+emptyID+".jsonl")
	writeInvariantRows(t, path, map[string]any{"type": "session_meta", "payload": map[string]any{
		"id": historyID, "cwd": "/project", "originator": "Codex Desktop",
	}})
	s := &Server{home: home, conversationM: conversation.NewReader(home)}
	probe := codexRuntimeProbe{root: root, binding: codexSessionBinding{CWD: "/project"}, listeners: map[string]string{historyOrigin: "inode"}}
	candidates := []codexruntime.Thread{
		{ID: historyID, SessionID: historyID, CWD: "/project", Path: path, Originator: "Codex Desktop", HistoryState: codexruntime.HistoryPersisted},
		{ID: emptyID, SessionID: emptyID, CWD: "/project", Path: filepath.Join(filepath.Dir(path), "rollout-"+emptyID+".jsonl"), HistoryState: codexruntime.HistoryUnmaterialized},
	}
	return s, probe, map[string]codexruntime.Thread{historyOrigin: {Unavailable: "runtime_ambiguous", HistoryCandidates: candidates}}
}

func TestAssociatedHistoryReadsDesktopOriginWithoutClaimingPrimary(t *testing.T) {
	s, probe, origins := associatedHistoryFixture(t)
	selected, origin, ok := s.codexAssociatedHistoryCandidate(probe, origins)
	if !ok || selected.thread.ID != historyID || origin != historyOrigin || origins[origin].ID != "" {
		t.Fatal("unique proven history was lost or promoted to a live primary", selected, origin, ok)
	}
	page, err := s.conversationM.ReadFor("codex", "/project", selected.thread.ID, "", 20)
	if err != nil || !page.Available {
		t.Fatal("offered history was not readable", err)
	}
	if _, _, ok := codexRuntimeCandidate(probe, map[string][]codexRuntimeThread{historyOrigin: {selected}}); !ok {
		t.Fatal("historical Desktop creator wrongly rejected as current executor")
	}
	selected.thread.CWD = "/different"
	if _, _, ok := codexRuntimeCandidate(probe, map[string][]codexRuntimeThread{historyOrigin: {selected}}); ok {
		t.Fatal("creator fix bypassed exact project ownership")
	}
}

func TestAssociatedHistoryRejectsMissingUnknownChangedOrConflictingEvidence(t *testing.T) {
	for _, kind := range []string{"unknown materialization", "two persisted", "no persisted", "missing persisted file", "declared path mismatch", "wrong project", "wrong session", "delegated", "duplicate id", "different store", "multiple listeners", "no ambiguity", "pending path materialized", "pending parent escapes store"} {
		t.Run(kind, func(t *testing.T) {
			s, probe, origins := associatedHistoryFixture(t)
			observation := origins[historyOrigin]
			rows := observation.HistoryCandidates
			switch kind {
			case "unknown materialization":
				rows[1].HistoryState = ""
			case "two persisted":
				rows[1].HistoryState = codexruntime.HistoryPersisted
				writeInvariantRows(t, rows[1].Path, map[string]any{"type": "session_meta", "payload": map[string]any{"id": emptyID, "cwd": "/project"}})
			case "no persisted":
				rows[0].HistoryState = codexruntime.HistoryUnmaterialized
			case "missing persisted file":
				if err := os.Remove(rows[0].Path); err != nil {
					t.Fatal(err)
				}
			case "declared path mismatch":
				rows[0].Path = rows[1].Path
			case "wrong project":
				rows[1].CWD = "/different"
			case "wrong session":
				rows[0].SessionID = emptyID
			case "delegated":
				rows[0].ParentThreadID = emptyID
			case "duplicate id":
				rows[1] = rows[0]
			case "different store":
				probe.root = t.TempDir()
			case "multiple listeners":
				probe.listeners["http://127.0.0.1:1235"] = "other"
			case "no ambiguity":
				observation.Unavailable = "runtime_metadata_conflict"
			case "pending path materialized":
				writeInvariantRows(t, rows[1].Path, map[string]any{"type": "session_meta", "payload": map[string]any{"id": emptyID, "cwd": "/project"}})
			case "pending parent escapes store":
				escape := filepath.Join(probe.root, "escape")
				if err := os.Symlink(t.TempDir(), escape); err != nil {
					t.Fatal(err)
				}
				rows[1].Path = filepath.Join(escape, "missing.jsonl")
			}
			observation.HistoryCandidates = rows
			origins[historyOrigin] = observation
			if _, _, ok := s.codexAssociatedHistoryCandidate(probe, origins); ok {
				t.Fatal("unsafe or unconfirmed history accepted", kind)
			}
		})
	}
}

func TestAssociatedHistoryLeaseNeverAuthorizesMutationsOrCompletion(t *testing.T) {
	var registry codexBindingRegistry
	state := registryState()
	a := registryAgent()
	a.BindingState = "history_only"
	a.ConversationSource = codexHistorySource + a.Session
	history := commitRegistry(&registry, state, a)
	server := &Server{state: state}
	if !registry.current(history) || !usableCodexBinding(history.BindingState) {
		t.Fatal("read-only history lease unavailable")
	}
	for _, operation := range []string{"send_terminal_text", "respond", "set_agent_settings", "agent_clear", "copy_agent_response"} {
		action, known := protocol.ClassifyAction(operation)
		if !known {
			t.Fatal("unknown test operation", operation)
		}
		if server.codexInteractionCurrent(t.Context(), protocol.Inbound{PaneID: history.PaneID, Target: registryTarget(history)}, action) {
			t.Fatal("associated history authorized mutation", operation)
		}
	}
	if server.pushTargetCurrent(*registryTarget(history)) {
		t.Fatal("historical completion was considered current")
	}
	status, err := server.agentRunState(t.Context(), protocol.Inbound{PaneID: history.PaneID, Target: registryTarget(history)})
	if err != nil || status.Status != "unknown" || status.Source != "unverified_primary" || status.Ticking {
		t.Fatal("history supplied a misleading current turn status", status, err)
	}
	old := protocol.Inbound{PaneID: history.PaneID, Target: registryTarget(history)}
	current := commitRegistry(&registry, state, registryAgent())
	if current.Generation != history.Generation || current.BindingEpoch == history.BindingEpoch || validateExactPaneTarget(state, old, true) == nil {
		t.Fatal("history-to-primary transition replaced identity or retained old authority")
	}
}

func TestAssociatedHistoryAmbiguityRetainsOnlyOldCacheIdentity(t *testing.T) {
	var registry codexBindingRegistry
	state := registryState()
	a := registryAgent()
	a.BindingState = "history_only"
	a.ConversationSource = codexHistorySource + a.Session
	history := commitRegistry(&registry, state, a)
	ambiguous := registryAgent()
	ambiguous.Session = ""
	ambiguous.ConversationSource = ""
	ambiguous.BindingState = "ambiguous"
	after := commitRegistry(&registry, state, ambiguous)
	if after.SessionID != history.SessionID || after.Generation != history.Generation || after.BindingEpoch == history.BindingEpoch || registry.current(after) {
		t.Fatal("new ambiguity either dropped cache identity or retained read authority")
	}
}

func TestAssociatedHistoryDoesNotMaskAnExplicitHistoryPin(t *testing.T) {
	a := registryAgent()
	if codexHistoryMayUsePin(a) {
		t.Fatal("pin could override a verified primary")
	}
	a.BindingState = "history_only"
	if !codexHistoryMayUsePin(a) {
		t.Fatal("automatic history masked explicit pin")
	}
	a.BindingState = "ambiguous"
	a.Session = ""
	if !codexHistoryMayUsePin(a) {
		t.Fatal("unbound history cannot use explicit pin")
	}
}
