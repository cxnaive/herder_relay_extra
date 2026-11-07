//go:build linux

package codexruntime

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
)

func stamp(v int64) *int64 { return &v }
func TestRunTimingUsesCurrentTurnNotThreadAgeOrOutputTimestamp(t *testing.T) {
	now := time.Unix(1700000500, 123000000)
	turn := &runTurn{ID: "turn-a", Status: "inProgress", Started: stamp(1700000000), ItemsView: "notLoaded"}
	s := deriveRunState(runtimeStatus{Type: "active"}, turn, now)
	if s.Status != "working" || s.ElapsedMS == nil || *s.ElapsedMS != 500123 || !s.Ticking || s.StartedAt != 1700000000000 {
		t.Fatalf("timing: %+v", s)
	}
	for _, flag := range []string{"waitingOnApproval", "waitingOnUserInput"} {
		waiting := deriveRunState(runtimeStatus{Type: "active", Flags: []string{flag}}, turn, now)
		if waiting.Status != "blocked" || waiting.Attention == "" || waiting.ElapsedMS == nil || *waiting.ElapsedMS != 500123 || !waiting.Ticking {
			t.Fatal("waiting lost whole-turn timer")
		}
	}
}
func TestCompletedFailedAndInterruptedDurationsStopTicking(t *testing.T) {
	for _, kind := range []string{"completed", "failed", "interrupted"} {
		turn := &runTurn{ID: "turn-a", Status: kind, Started: stamp(100), Completed: stamp(115), Duration: stamp(15314), ItemsView: "notLoaded"}
		first := deriveRunState(runtimeStatus{Type: "idle"}, turn, time.Unix(116, 0))
		later := deriveRunState(runtimeStatus{Type: "idle"}, turn, time.Unix(90000, 0))
		if first.ElapsedMS == nil || *first.ElapsedMS != 15314 || later.ElapsedMS == nil || *later.ElapsedMS != *first.ElapsedMS || first.Ticking || later.Ticking {
			t.Fatal("finished timer continued")
		}
	}
}
func TestUnknownInconsistentAndFutureTimingDoesNotInventAStart(t *testing.T) {
	now := time.Unix(500, 0)
	cases := []struct {
		status string
		turn   *runTurn
	}{
		{"active", nil},
		{"active", &runTurn{ID: "a", Status: "inProgress", ItemsView: "notLoaded"}},
		{"active", &runTurn{ID: "a", Status: "inProgress", Started: stamp(900), ItemsView: "notLoaded"}},
		{"active", &runTurn{ID: "old", Status: "completed", Started: stamp(100), Completed: stamp(400), ItemsView: "notLoaded"}},
		{"idle", &runTurn{ID: "a", Status: "inProgress", Started: stamp(100), ItemsView: "notLoaded"}},
		{"idle", &runTurn{ID: "a", Status: "completed", Started: stamp(200), Completed: stamp(100), ItemsView: "notLoaded"}},
		{"active", &runTurn{ID: "a", Status: "inProgress", Started: stamp(100), ItemsView: "full"}},
		{"notLoaded", &runTurn{ID: "a", Status: "inProgress", Started: stamp(100), ItemsView: "notLoaded"}},
	}
	for _, c := range cases {
		s := deriveRunState(runtimeStatus{Type: c.status}, c.turn, now)
		if s.ElapsedMS != nil || s.Ticking || s.Timing != "unknown" {
			t.Fatalf("invented timing: %+v", s)
		}
	}
}

type runFixture struct{ wrongID, wrongCWD, changing, unsupported, oversized, unloaded bool }

func runServer(t *testing.T, f runFixture) string {
	t.Helper()
	dir, err := os.MkdirTemp("", "codex-run-")
	if err != nil {
		t.Fatal(err)
	}
	socket := filepath.Join(dir, "control.sock")
	l, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(t.Context())
	var wg sync.WaitGroup
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		wg.Add(1)
		defer wg.Done()
		c, err := websocket.Accept(w, req, nil)
		if err != nil {
			return
		}
		defer c.CloseNow()
		reads := 0
		for {
			var q struct {
				ID     int                        `json:"id"`
				Method string                     `json:"method"`
				Params map[string]json.RawMessage `json:"params"`
			}
			if wsjson.Read(ctx, c, &q) != nil {
				return
			}
			if q.Method == "initialized" {
				continue
			}
			var result any
			var failure any
			switch q.Method {
			case "initialize":
				result = map[string]any{}
			case "thread/read":
				if len(q.Params) != 2 || string(q.Params["includeTurns"]) != "false" {
					t.Error("read requested conversation content")
				}
				reads++
				id, cwd, status := idA, "/work/project", "active"
				if f.wrongID {
					id = idB
				}
				if f.wrongCWD {
					cwd = "/other"
				}
				if f.unloaded {
					status = "notLoaded"
				}
				if f.changing && reads > 1 {
					status = "idle"
				}
				result = map[string]any{"thread": map[string]any{"id": id, "cwd": cwd, "status": runtimeStatus{Type: status}}}
			case "thread/turns/list":
				if len(q.Params) != 4 || string(q.Params["itemsView"]) != `"notLoaded"` || string(q.Params["sortDirection"]) != `"desc"` || string(q.Params["limit"]) != "1" || string(q.Params["threadId"]) != `"`+idA+`"` {
					t.Error("unbounded or wrong turn read")
				}
				rows := []runTurn{{ID: "turn-a", Status: "inProgress", Started: stamp(time.Now().Unix() - 120), ItemsView: "notLoaded"}}
				if f.oversized {
					rows = append(rows, rows[0])
				}
				result = map[string]any{"data": rows, "nextCursor": "older-not-needed"}
				if f.unsupported {
					failure = map[string]any{"code": -32601, "message": "method unavailable"}
				}
			default:
				t.Errorf("run status invoked forbidden RPC %s", q.Method)
				return
			}
			if wsjson.Write(ctx, c, map[string]any{"id": q.ID, "result": result, "error": failure}) != nil {
				return
			}
		}
	})}
	go server.Serve(l)
	t.Cleanup(func() { cancel(); _ = server.Close(); wg.Wait(); _ = os.RemoveAll(dir) })
	return socket
}
func TestCurrentRunReadsOnlyLatestTurnHeadersAndRevalidates(t *testing.T) {
	socket := runServer(t, runFixture{})
	guards := 0
	state, err := CurrentRun(t.Context(), socket, idA, "/work/project", func() error { guards++; return nil })
	if err != nil || state.ElapsedMS == nil || *state.ElapsedMS < 120000 || *state.ElapsedMS > 122000 || guards != 2 {
		t.Fatalf("run read failed: %+v %v, guards=%d", state, err, guards)
	}
}
func TestCurrentRunRefusesWrongTargetAndExpiredGuard(t *testing.T) {
	for _, kind := range []string{"id", "cwd", "guard"} {
		t.Run(kind, func(t *testing.T) {
			socket := runServer(t, runFixture{wrongID: kind == "id", wrongCWD: kind == "cwd"})
			count := 0
			_, err := CurrentRun(t.Context(), socket, idA, "/work/project", func() error {
				count++
				if kind == "guard" && count > 1 {
					return errors.New("replaced")
				}
				return nil
			})
			if err == nil {
				t.Fatal("accepted stale target")
			}
		})
	}
}
func TestCurrentRunUnknownTimingOnTransitionUnsupportedOrUnexpectedPage(t *testing.T) {
	for _, kind := range []string{"changed", "unsupported", "extra-turn", "unloaded"} {
		t.Run(kind, func(t *testing.T) {
			socket := runServer(t, runFixture{changing: kind == "changed", unsupported: kind == "unsupported", oversized: kind == "extra-turn", unloaded: kind == "unloaded"})
			state, err := CurrentRun(t.Context(), socket, idA, "/work/project", func() error { return nil })
			if err != nil || state.ElapsedMS != nil || state.Ticking {
				t.Fatalf("unsafe timer: %+v %v", state, err)
			}
		})
	}
}

func TestCompletionProofRequiresSettledConsistentTurn(t *testing.T) {
	turn := &runTurn{ID: "a", Status: "completed", Started: stamp(100), Completed: stamp(110), ItemsView: "notLoaded"}
	pending := deriveRunState(runtimeStatus{Type: "idle"}, turn, time.Unix(111, 0))
	if !pending.CompletionPending || pending.CompletionConfirmed {
		t.Fatalf("not pending %+v", pending)
	}
	done := deriveRunState(runtimeStatus{Type: "idle"}, turn, time.Unix(114, 0))
	if done.CompletionPending || !done.CompletionConfirmed {
		t.Fatalf("no proof %+v", done)
	}
	for _, status := range []string{"active", "notLoaded", "systemError"} {
		if s := deriveRunState(runtimeStatus{Type: status}, turn, time.Unix(114, 0)); s.CompletionConfirmed {
			t.Fatal("inconsistent completion")
		}
	}
	turn.Completed = stamp(90)
	if s := deriveRunState(runtimeStatus{Type: "idle"}, turn, time.Unix(114, 0)); s.CompletionConfirmed {
		t.Fatal("invalid timestamps confirmed")
	}
}
