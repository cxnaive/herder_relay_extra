package app

import (
	"encoding/json"
	"os"
	"testing"
	"time"

	"github.com/0cv/herdr-mobile-relay/internal/conversation"
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
)

func TestRecordedRunDoesNotMistakeIdleToolGapOrOldTurnForCompletion(t *testing.T) {
	now := time.Unix(1000, 0)
	active := conversation.CodexTurn{ID: "a", Status: "working", StartedAt: 900000, EventAt: 900000}
	for _, hint := range []string{"idle", "done", "working", "blocked"} {
		s := recordedRunState(active, hint, "", now)
		if s.Status != "working" && s.Status != "blocked" || s.ElapsedMS == nil || *s.ElapsedMS != 100000 || !s.Ticking || s.CompletionConfirmed {
			t.Fatalf("gap %s: %+v", hint, s)
		}
	}
	done := conversation.CodexTurn{ID: "a", Status: "done", StartedAt: 900000, FinishedAt: 990000, EventAt: 990000}
	s := recordedRunState(done, "idle", "", now)
	if s.Status != "done" || !s.CompletionConfirmed || s.ElapsedMS == nil || *s.ElapsedMS != 90000 || s.Ticking {
		t.Fatalf("done %+v", s)
	}
	for _, hint := range []string{"working", "blocked"} {
		s = recordedRunState(done, hint, "", now)
		if s.CompletionConfirmed || s.TurnID != "" || s.ElapsedMS != nil {
			t.Fatalf("old completion %+v", s)
		}
	}
	s = recordedRunState(active, "idle", "idle", now)
	if s.Status != "unknown" || s.Ticking || s.CompletionConfirmed {
		t.Fatalf("runtime idle conflicts %+v", s)
	}
	for _, status := range []string{"failed", "interrupted"} {
		done.Status = status
		s = recordedRunState(done, "idle", "", now)
		if s.Status != status || s.CompletionConfirmed || s.Ticking {
			t.Fatalf("failure %+v", s)
		}
	}
}
func TestRecordedRunMissingFutureAndPendingEvidence(t *testing.T) {
	now := time.Unix(1000, 0)
	for _, turn := range []conversation.CodexTurn{
		{}, {ID: "a", Status: "done", EventAt: 1002000, FinishedAt: 1002000},
		{ID: "a", Status: "done", EventAt: 990000, StartedAt: 995000, FinishedAt: 990000},
	} {
		s := recordedRunState(turn, "done", "", now)
		if s.Status != "unknown" || s.CompletionConfirmed || s.Ticking || s.ElapsedMS != nil {
			t.Fatalf("invented %+v", s)
		}
	}
	done := conversation.CodexTurn{ID: "a", Status: "done", EventAt: 999000, StartedAt: 900000, FinishedAt: 999000}
	if s := recordedRunState(done, "idle", "", now); s.Status != "working" || s.CompletionConfirmed || s.Ticking {
		t.Fatalf("pending %+v", s)
	}
	if s := recordedRunState(done, "idle", "", now.Add(3*time.Second)); s.Status != "done" || !s.CompletionConfirmed {
		t.Fatalf("settled %+v", s)
	}
}
func TestStandaloneRunLifecycleUsesOnlyVerifiedWriter(t *testing.T) {
	s, f, a, _ := standaloneServer(t)
	a.Status = "idle"
	path := standaloneRollout(t, s.home, reportID, a.Cwd, nil)
	add := func(kind string) {
		t.Helper()
		file, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0600)
		if err != nil {
			t.Fatal(err)
		}
		defer file.Close()
		payload := map[string]any{"type": kind, "turn_id": "a", "started_at": time.Now().Unix() - 60}
		if kind == "task_complete" {
			payload["completed_at"] = time.Now().Unix() - 5
		}
		if err = json.NewEncoder(file).Encode(map[string]any{"type": "event_msg", "timestamp": time.Now().Add(-5 * time.Second).UTC().Format(time.RFC3339Nano), "payload": payload}); err != nil {
			t.Fatal(err)
		}
	}
	add("task_started")
	current := commitReport(s, a)
	target := bindingDiagnosticTarget(current)
	in := protocol.Inbound{Type: "get_agent_run_state", PaneID: a.PaneID, Target: &target}
	run, err := s.agentRunState(t.Context(), in)
	if err != nil || run.Status != "working" || run.ElapsedMS == nil || !run.Ticking || run.Evidence != "rollout_lifecycle" {
		t.Fatalf("active %+v %v", run, err)
	}
	add("task_complete")
	current = commitReport(s, a)
	target = bindingDiagnosticTarget(current)
	in.Target = &target
	run, err = s.agentRunState(t.Context(), in)
	if err != nil || run.Status != "done" || run.ElapsedMS == nil || run.Ticking || !run.CompletionConfirmed {
		t.Fatalf("completed %+v %v", run, err)
	}
	assertNoSharedCalls(t, f)
}
