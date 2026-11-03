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
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
)

type settingsFixture struct {
	unloaded, wrongCWD, wrongID, changeBeforeWrite, dropAck, ignoreWrite bool
	writes                                                               atomic.Int32
}

func settingsServer(t *testing.T, f *settingsFixture) string {
	t.Helper()
	dir, err := os.MkdirTemp("", "codex-settings-")
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
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		wg.Add(1)
		defer wg.Done()
		conn, err := websocket.Accept(w, req, nil)
		if err != nil {
			return
		}
		defer conn.CloseNow()
		model, effort := "model-a", "medium"
		reads := 0
		for {
			var q struct {
				ID     int                        `json:"id"`
				Method string                     `json:"method"`
				Params map[string]json.RawMessage `json:"params"`
			}
			if wsjson.Read(ctx, conn, &q) != nil {
				return
			}
			if q.Method == "initialized" {
				continue
			}
			var result any
			switch q.Method {
			case "initialize":
				result = map[string]any{}
			case "thread/loaded/list":
				ids := []string{idA}
				if f.unloaded {
					ids = nil
				}
				result = map[string]any{"data": ids, "nextCursor": nil}
			case "thread/read":
				var turns bool
				_ = json.Unmarshal(q.Params["includeTurns"], &turns)
				if turns {
					t.Error("settings must not read conversation bodies")
				}
				var id string
				_ = json.Unmarshal(q.Params["threadId"], &id)
				if id != idA {
					t.Error("wrong target")
				}
				reads++
				if f.changeBeforeWrite && reads == 2 {
					effort = "high"
				}
				cwd := "/work/project"
				if f.wrongCWD {
					cwd = "/other"
				}
				if f.wrongID {
					id = idB
				}
				result = map[string]any{"thread": map[string]any{"id": id, "cwd": cwd, "originator": "Codex Desktop", "model": model, "reasoningEffort": effort, "modelProvider": "fixture", "status": map[string]any{"type": "active"}}}
			case "model/list":
				result = map[string]any{"data": []ModelOption{{Model: "model-a", DisplayName: "A", DefaultEffort: "medium", Efforts: []EffortOption{{Value: "medium"}, {Value: "high"}}}, {Model: "model-b", DisplayName: "B", DefaultEffort: "high", Efforts: []EffortOption{{Value: "high"}, {Value: "max"}}}, {Model: "hidden", Hidden: true}}, "nextCursor": nil}
			case "thread/settings/update":
				if len(q.Params) != 3 || q.Params["threadId"] == nil || q.Params["model"] == nil || q.Params["effort"] == nil {
					t.Error("privileged or extra setting sent")
				}
				f.writes.Add(1)
				if !f.ignoreWrite {
					_ = json.Unmarshal(q.Params["model"], &model)
					_ = json.Unmarshal(q.Params["effort"], &effort)
				}
				if f.dropAck {
					return
				}
				result = map[string]any{}
			default:
				t.Errorf("forbidden runtime RPC: %s", q.Method)
				return
			}
			if wsjson.Write(ctx, conn, map[string]any{"id": q.ID, "result": result}) != nil {
				return
			}
		}
	})}
	go srv.Serve(l)
	t.Cleanup(func() { cancel(); _ = srv.Close(); wg.Wait(); _ = os.RemoveAll(dir) })
	return socket
}
func readSettings(t *testing.T, socket string) AgentSettings {
	t.Helper()
	ctx, cancel := context.WithTimeout(t.Context(), 3*time.Second)
	defer cancel()
	result, err := Settings(ctx, socket, idA, "/work/project", nil, func() error { return nil })
	if err != nil {
		t.Fatal(err)
	}
	return result
}
func TestSettingsReadOnlyUsesLiveModelCatalog(t *testing.T) {
	f := &settingsFixture{}
	socket := settingsServer(t, f)
	s := readSettings(t, socket)
	if !s.Available || !s.NextTurn || s.Model != "model-a" || s.Effort != "medium" || len(s.Models) != 2 || len(s.Revision) != 64 {
		t.Fatalf("bad settings: %+v", s)
	}
	if f.writes.Load() != 0 {
		t.Fatal("read mutated runtime")
	}
}
func TestSettingsUpdateOnlyTouchesExactThreadModelAndEffort(t *testing.T) {
	f := &settingsFixture{}
	socket := settingsServer(t, f)
	before := readSettings(t, socket)
	after, err := Settings(t.Context(), socket, idA, "/work/project", &SettingsUpdate{Model: "model-b", Effort: "max", Revision: before.Revision}, func() error { return nil })
	if err != nil || after.Model != "model-b" || after.Effort != "max" || after.Revision == before.Revision || f.writes.Load() != 1 {
		t.Fatalf("update failed: %v %+v", err, after)
	}
}
func TestSettingsRejectUnsupportedStaleOrRevokedBeforeWrite(t *testing.T) {
	for _, kind := range []string{"model", "effort", "hidden", "stale", "changed", "revoked", "unloaded", "cwd", "id"} {
		t.Run(kind, func(t *testing.T) {
			f := &settingsFixture{changeBeforeWrite: kind == "changed", unloaded: kind == "unloaded", wrongCWD: kind == "cwd", wrongID: kind == "id"}
			socket := settingsServer(t, f)
			revision := settingsResult(settingsThread{ID: idA, CWD: "/work/project", Model: "model-a", Effort: "medium", Provider: "fixture"}, nil).Revision
			u := SettingsUpdate{Model: "model-b", Effort: "max", Revision: revision}
			switch kind {
			case "model":
				u.Model = "arbitrary"
			case "effort":
				u.Effort = "unadvertised"
			case "hidden":
				u.Model = "hidden"
			case "stale":
				u.Revision = "old"
			}
			guards := 0
			guard := func() error {
				guards++
				if kind == "revoked" && guards >= 2 {
					return errors.New("revoked")
				}
				return nil
			}
			_, err := Settings(t.Context(), socket, idA, "/work/project", &u, guard)
			if err == nil || f.writes.Load() != 0 {
				t.Fatal("invalid operation reached write")
			}
		})
	}
}
func TestSettingsLostAckOrReadbackMismatchIsUnconfirmedWithoutReplay(t *testing.T) {
	for _, drop := range []bool{true, false} {
		t.Run(map[bool]string{true: "lost-ack", false: "mismatch"}[drop], func(t *testing.T) {
			f := &settingsFixture{dropAck: drop, ignoreWrite: !drop}
			socket := settingsServer(t, f)
			s := readSettings(t, socket)
			ctx, cancel := context.WithTimeout(t.Context(), 2*time.Second)
			defer cancel()
			_, err := Settings(ctx, socket, idA, "/work/project", &SettingsUpdate{Model: "model-b", Effort: "max", Revision: s.Revision}, func() error { return nil })
			if !errors.Is(err, ErrSettingsUnknown) || f.writes.Load() != 1 {
				t.Fatalf("update replayed or falsely confirmed: %v", err)
			}
		})
	}
}
