package app

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"strings"
	"testing"

	"github.com/0cv/herdr-mobile-relay/internal/coordinator"
	"github.com/0cv/herdr-mobile-relay/internal/herdr"
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
	"github.com/0cv/herdr-mobile-relay/internal/transport"
)

func diagnosticFixture(t *testing.T, state string) (*Server, *coordinator.AgentState, protocol.Inbound) {
	t.Helper()
	s := &Server{state: registryState()}
	s.codexProcessProbe = func(context.Context, *coordinator.AgentState) (codexRuntimeProbe, string) {
		t.Fatal("diagnostic read attempted runtime discovery")
		return codexRuntimeProbe{}, ""
	}
	a := registryAgent()
	a.BindingState = state
	a.BindingReason = "runtime_ambiguous_no_report"
	a.ReportedSession = herdr.AgentSession{Agent: "codex", Kind: "id", Value: otherReportID, Source: "herdr:codex"}
	if state != "verified" {
		a.Session = ""
	}
	a.Prompt = "never-copy-prompt"
	a.Command = "never-copy-command"
	a.Cwd = "/never-copy-path"
	current := commitRegistry(&s.codexBindings, s.state, a)
	target := bindingDiagnosticTarget(current)
	return s, current, protocol.Inbound{Type: protocol.SessionBindingDiagnosticsAction, PaneID: current.PaneID, Target: &target}
}
func TestSessionBindingDiagnosticsIsPairedReadOnly(t *testing.T) {
	action, ok := protocol.ClassifyAction(protocol.SessionBindingDiagnosticsAction)
	if !ok || action.Class != protocol.ActionReadOnly || action.Coordinated || action.Audited {
		t.Fatal("diagnostic read became a mutation")
	}
	if codexMutationNeedsPrimary(action) {
		t.Fatal("unresolved sessions cannot read diagnostics")
	}
	for _, role := range []string{"reader", "controller"} {
		if authorizeAuthenticatedIdentity(transport.AuthenticatedIdentity{Role: role}, true, action, "") != nil {
			t.Fatal("paired role denied", role)
		}
	}
	for _, role := range []string{"", "bootstrap", "unknown"} {
		if authorizeAuthenticatedIdentity(transport.AuthenticatedIdentity{Role: role}, true, action, "") == nil {
			t.Fatal("non-device role allowed", role)
		}
	}
	if authorizeAuthenticatedIdentity(transport.AuthenticatedIdentity{Role: "controller"}, false, action, "") == nil {
		t.Fatal("anonymous access permitted")
	}
}
func TestSessionBindingDiagnosticsDoesNotConfirmOrProbeAnAmbiguousSession(t *testing.T) {
	s, before, in := diagnosticFixture(t, "ambiguous")
	result, err := s.sessionBindingDiagnostics(t.Context(), in)
	if err != nil || !result.Available || result.SchemaVersion != 1 || result.Observation != "inventory_snapshot" || result.Binding.State != "ambiguous" || result.Target.AgentSessionID != "" {
		t.Fatalf("invalid snapshot: %+v %v", result, err)
	}
	if result.Binding.Reason != "runtime_ambiguous_no_report" || result.Details["policy"] != "codex-terminal-roots-v1" {
		t.Fatal("missing diagnostic context")
	}
	if result.Details["herdr_report"].(map[string]any)["id"] != otherReportID {
		t.Fatal("reported ID lost")
	}
	after, _ := s.state.Agent(before.PaneID)
	if !reflect.DeepEqual(before, after) || s.codexBindings.current(after) {
		t.Fatal("diagnostics mutated or confirmed a binding")
	}
	encoded, err := json.Marshal(result)
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{"never-copy-prompt", "never-copy-command", "/never-copy-path"} {
		if strings.Contains(string(encoded), secret) {
			t.Fatal("copied unrelated private fields")
		}
	}
}
func TestSessionBindingDiagnosticsRejectsStaleOrForeignTargets(t *testing.T) {
	for _, field := range []string{"missing", "pane", "terminal", "generation", "session", "epoch", "server"} {
		t.Run(field, func(t *testing.T) {
			s, _, in := diagnosticFixture(t, "verified")
			switch field {
			case "missing":
				in.Target = nil
			case "pane":
				in.PaneID = "other"
			case "terminal":
				in.Target.TerminalID = "other"
			case "generation":
				in.Target.Generation++
			case "session":
				in.Target.AgentSessionID = "other"
			case "epoch":
				in.Target.BindingEpoch++
			case "server":
				in.Target.ServerSessionID = "other"
			}
			if _, err := s.sessionBindingDiagnostics(t.Context(), in); err == nil {
				t.Fatal("invalid target accepted")
			}
		})
	}
}
func TestSessionBindingDiagnosticsDoesNotExposeCodexDataForOtherProviders(t *testing.T) {
	s, _, in := diagnosticFixture(t, "ambiguous")
	a := registryAgent()
	a.Agent = "claude"
	a.BindingState = ""
	a.Session = "original-id"
	a.SessionID = a.Session
	s.state.CommitInventory([]*coordinator.AgentState{a}, s.state.RevisionCounter())
	current, _ := s.state.Agent(a.PaneID)
	target := bindingDiagnosticTarget(current)
	in.Target = &target
	result, err := s.sessionBindingDiagnostics(t.Context(), in)
	if err != nil || result.Available || result.Reason != "unsupported_agent" || len(result.Details) != 0 {
		t.Fatalf("non-Codex changed: %+v %v", result, err)
	}
}
func TestSessionBindingDiagnosticsHonorsRevokedDispatch(t *testing.T) {
	s, _, in := diagnosticFixture(t, "verified")
	denied := errors.New("credential revoked")
	ctx := herdr.WithDispatchCheck(t.Context(), func() error { return denied })
	if _, err := s.sessionBindingDiagnostics(ctx, in); !errors.Is(err, denied) {
		t.Fatal("revoked access was not enforced")
	}
}
func TestSessionBindingDiagnosticsBoundsUntrustedReportAndFutureDetails(t *testing.T) {
	s, _, in := diagnosticFixture(t, "verified")
	a := registryAgent()
	a.ReportedSession = herdr.AgentSession{Agent: "codex", Source: strings.Repeat("多", 10000), Value: strings.Repeat("x", 10000)}
	current := commitRegistry(&s.codexBindings, s.state, a)
	target := bindingDiagnosticTarget(current)
	in.Target = &target
	result, err := s.sessionBindingDiagnostics(t.Context(), in)
	if err != nil || !result.Truncated {
		t.Fatal("large report not bounded", err)
	}
	encoded, _ := json.Marshal(result)
	if len(encoded) > maxBindingDiagnosticBytes {
		t.Fatal("snapshot exceeds budget")
	}
	result.Details = map[string]any{"future": strings.Repeat("x", maxBindingDiagnosticBytes*2)}
	clipped, err := fitBindingDiagnostics(result)
	if err != nil || !clipped.Truncated || clipped.Details["reason"] != "diagnostic_details_omitted" {
		t.Fatal("future details exceeded budget")
	}
	result.Target.AgentSessionID = strings.Repeat("x", maxBindingDiagnosticBytes*2)
	if _, err = fitBindingDiagnostics(result); err == nil {
		t.Fatal("oversized envelope accepted")
	}
}
