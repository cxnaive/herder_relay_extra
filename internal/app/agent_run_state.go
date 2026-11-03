package app

import (
	"context"
	"time"

	"github.com/0cv/herdr-mobile-relay/internal/codexruntime"
	"github.com/0cv/herdr-mobile-relay/internal/herdr"
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
	"github.com/0cv/herdr-mobile-relay/internal/transport"
)

var runStateSlots = make(chan struct{}, 4)

func (s *Server) agentRunState(ctx context.Context, in protocol.Inbound) (codexruntime.RunState, error) {
	ctx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	if in.PaneID == "" || in.Target == nil || validateExactPaneTarget(s.state, in, true) != nil {
		return codexruntime.RunState{}, codexruntime.ErrRunStateUnavailable
	}
	if err := herdr.CheckDispatch(ctx); err != nil {
		return codexruntime.RunState{}, err
	}
	agent, ok := s.state.Agent(in.PaneID)
	if !ok {
		return codexruntime.RunState{}, codexruntime.ErrRunStateUnavailable
	}
	// Herdr's updated_at changes with output; it is never a turn-start timestamp.
	fallback := codexruntime.RunState{Status: agent.Status, Source: "herdr", Attention: string(agent.AttentionKind), ObservedAt: time.Now().UnixMilli(), Timing: "unknown"}
	if agent.Agent != "codex" {
		return fallback, nil
	}
	select {
	case runStateSlots <- struct{}{}:
		defer func() { <-runStateSlots }()
	default:
		return codexruntime.RunState{}, codexruntime.ErrRunStateUnavailable
	}
	socket, id, cwd, guard, err := s.codexRuntimeTarget(ctx, in)
	if err != nil {
		return fallback, nil
	}
	result, err := codexruntime.CurrentRun(ctx, socket, id, cwd, guard)
	if err != nil {
		// A lost/replaced exact target must not produce a reassuring fallback result.
		if ctx.Err() != nil || guard() != nil {
			return codexruntime.RunState{}, codexruntime.ErrRunStateUnavailable
		}
		return fallback, nil
	}
	return result, nil
}
func (s *Server) handleAgentRunState(ctx context.Context, client *transport.ClientConn, in protocol.Inbound) {
	state, err := s.agentRunState(ctx, in)
	if err != nil {
		s.sendCommandResult(client, in.RequestID, in.Type, false, "failed", "暂时无法确认 Agent 运行状态，请等待重试。", in.PaneID, nil)
		return
	}
	s.sendCommandResult(client, in.RequestID, in.Type, true, "completed", "", in.PaneID, state)
}
