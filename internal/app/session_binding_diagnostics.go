package app

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/0cv/herdr-mobile-relay/internal/coordinator"
	"github.com/0cv/herdr-mobile-relay/internal/herdr"
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
	"github.com/0cv/herdr-mobile-relay/internal/transport"
)

const maxBindingDiagnosticBytes = 64 * 1024

var errBindingDiagnosticsUnavailable = errors.New("binding diagnostics unavailable")

func bindingDiagnosticTarget(a *coordinator.AgentState) protocol.TargetRef {
	server := a.ServerSessionID
	if server == "" {
		server = "primary"
	}
	return protocol.TargetRef{ServerSessionID: server, PaneID: a.PaneID, TerminalID: a.TerminalID, Generation: a.Generation, AgentSessionID: a.SessionID, BindingEpoch: a.BindingEpoch}
}

// Only reads the existing inventory/lease snapshot. In particular, an ambiguous
// binding must remain diagnosable without probing processes or changing selection.
func (s *Server) sessionBindingDiagnostics(ctx context.Context, in protocol.Inbound) (protocol.SessionBindingDiagnostics, error) {
	var result protocol.SessionBindingDiagnostics
	if in.PaneID == "" || in.Target == nil || validateExactPaneTarget(s.state, in, true) != nil {
		return result, coordinator.ErrPaneReplaced
	}
	if err := herdr.CheckDispatch(ctx); err != nil {
		return result, err
	}
	agent, ok := s.state.Agent(in.PaneID)
	if !ok || bindingDiagnosticTarget(agent) != *in.Target {
		return result, coordinator.ErrPaneReplaced
	}
	result = protocol.SessionBindingDiagnostics{SchemaVersion: 1, Available: agent.Agent == "codex", GeneratedAt: time.Now().UnixMilli(), Observation: "inventory_snapshot", InventoryRevision: agent.StateRevision, Agent: agent.Agent, Target: bindingDiagnosticTarget(agent), Details: map[string]any{}}
	if !result.Available {
		result.Reason = "unsupported_agent"
		return fitBindingDiagnostics(result)
	}
	bounded := func(value string) string {
		const limit = 512
		value = strings.ToValidUTF8(value, "�")
		if len(value) > limit {
			value = value[:limit]
			for !utf8.ValidString(value) {
				value = value[:len(value)-1]
			}
			result.Truncated = true
		}
		return value
	}
	result.Binding = protocol.SessionBindingDiagnosticResult{State: bounded(agent.BindingState), Reason: bounded(agent.BindingReason), VerifiedAt: agent.BindingVerifiedAt}
	result.Details = map[string]any{
		"policy":                "codex-terminal-roots-v1",
		"selection_source":      bounded(agent.ConversationSource),
		"binding_lease_current": s.codexBindings.current(agent),
		"herdr_report":          map[string]any{"provider": bounded(agent.ReportedSession.Agent), "kind": bounded(agent.ReportedSession.Kind), "id": bounded(agent.ReportedSession.Value), "source": bounded(agent.ReportedSession.Source)},
		"runtime":               map[string]any{"mode": bounded(agent.BindingMode), "process_instance": bounded(agent.ConversationProcess), "runtime_instance": bounded(agent.BindingRuntime), "store_id": bounded(agent.BindingStoreID)},
	}
	// Keep future rich details bounded too. Never return transcript, argv, raw
	// environment or credential fields by copying an entire AgentState here.

	if err := herdr.CheckDispatch(ctx); err != nil {
		return protocol.SessionBindingDiagnostics{}, err
	}
	return fitBindingDiagnostics(result)
}

func fitBindingDiagnostics(result protocol.SessionBindingDiagnostics) (protocol.SessionBindingDiagnostics, error) {
	encoded, err := json.Marshal(result)
	if err != nil {
		return protocol.SessionBindingDiagnostics{}, errBindingDiagnosticsUnavailable
	}
	if len(encoded) <= maxBindingDiagnosticBytes {
		return result, nil
	}
	result.Details = map[string]any{"reason": "diagnostic_details_omitted"}
	result.Truncated = true
	encoded, err = json.Marshal(result)
	if err != nil || len(encoded) > maxBindingDiagnosticBytes {
		return protocol.SessionBindingDiagnostics{}, errBindingDiagnosticsUnavailable
	}
	return result, nil
}

func (s *Server) handleSessionBindingDiagnostics(ctx context.Context, client *transport.ClientConn, in protocol.Inbound) {
	result, err := s.sessionBindingDiagnostics(ctx, in)
	if err != nil {
		s.sendCommandResult(client, in.RequestID, in.Type, false, "failed", "暂时无法读取绑定诊断，请刷新会话并确认读取权限后重试。", in.PaneID, nil)
		return
	}
	s.sendCommandResult(client, in.RequestID, in.Type, true, "completed", "", in.PaneID, result)
}
