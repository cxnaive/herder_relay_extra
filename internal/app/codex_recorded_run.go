package app

import (
	"context"
	"time"

	"github.com/0cv/herdr-mobile-relay/internal/codexruntime"
	"github.com/0cv/herdr-mobile-relay/internal/conversation"
	"github.com/0cv/herdr-mobile-relay/internal/coordinator"
)

func (s *Server) recordedCodexRun(ctx context.Context, a *coordinator.AgentState, probe codexRuntimeProbe, id, herdrStatus, runtimeStatus string) codexruntime.RunState {
	now := time.Now()
	var turn conversation.CodexTurn
	if s.conversationM != nil {
		turn, _ = s.conversationM.CodexTurnFor(ctx, a.Cwd, id, probe.root)
	}
	return recordedRunState(turn, herdrStatus, runtimeStatus, now)
}

// A terminal idle/done guess is not a completion event. A busy/approval hint may
// veto a persisted old completion, but cannot end an explicitly active turn.
func recordedRunState(turn conversation.CodexTurn, herdrStatus, runtimeStatus string, now time.Time) codexruntime.RunState {
	r := codexruntime.RunState{Source: "codex_runtime", Status: "unknown", Evidence: "unavailable", ObservedAt: now.UnixMilli(), Timing: "unknown"}
	busy := herdrStatus == "working" || herdrStatus == "blocked"
	if runtimeStatus != "" {
		busy = runtimeStatus == "working" || runtimeStatus == "blocked"
	}
	if turn.ID == "" || turn.EventAt <= 0 || turn.EventAt > now.UnixMilli()+1000 {
		if busy {
			r.Status = "working"
			if herdrStatus == "blocked" || runtimeStatus == "blocked" {
				r.Status = "blocked"
			}
		}
		return r
	}
	if (turn.Status != "working" && busy) || (turn.Status == "working" && runtimeStatus == "idle") {
		if busy {
			r.Status = "working"
			if herdrStatus == "blocked" || runtimeStatus == "blocked" {
				r.Status = "blocked"
			}
		}
		return r
	}
	r.Status = turn.Status
	r.TurnID = turn.ID
	r.Evidence = "rollout_lifecycle"
	if turn.Status == "working" && herdrStatus == "blocked" {
		r.Status = "blocked"
	}
	// Give immediate continuations time to publish their next start. This is not
	// the only safeguard: clients also require the same observed active turn.
	validFinish := turn.FinishedAt > 0 && turn.FinishedAt <= now.UnixMilli()+1000 && (turn.StartedAt == 0 || turn.FinishedAt >= turn.StartedAt)
	if turn.Status == "done" && !validFinish {
		return codexruntime.RunState{Source: "codex_runtime", Status: "unknown", Evidence: "unavailable", ObservedAt: now.UnixMilli(), Timing: "unknown"}
	}
	r.CompletionConfirmed = turn.Status == "done" && now.UnixMilli()-max(turn.EventAt, turn.FinishedAt) >= 3000
	if turn.Status == "done" && !r.CompletionConfirmed {
		r.Status = "working"
		r.CompletionPending = true
		return r
	}
	if turn.StartedAt <= 0 || turn.StartedAt > now.UnixMilli()+1000 {
		return r
	}
	r.StartedAt = turn.StartedAt
	elapsed := int64(0)
	if turn.Status == "working" {
		elapsed = max(int64(0), now.UnixMilli()-turn.StartedAt)
		r.Ticking = true
	} else {
		if turn.FinishedAt < turn.StartedAt || turn.FinishedAt > now.UnixMilli()+1000 {
			return r
		}
		r.FinishedAt = turn.FinishedAt
		elapsed = turn.FinishedAt - turn.StartedAt
		if turn.DurationMS != nil && *turn.DurationMS >= 0 && *turn.DurationMS <= elapsed+2000 {
			elapsed = *turn.DurationMS
		}
	}
	r.ElapsedMS = &elapsed
	r.Timing = "turn_elapsed"
	return r
}
