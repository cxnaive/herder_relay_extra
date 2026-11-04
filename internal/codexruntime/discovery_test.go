//go:build linux

package codexruntime

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
)

const originA = "http://127.0.0.1:46223"
const originB = "http://127.0.0.1:46224"

type fixture struct {
	childAppears     bool
	metadata         map[string]Thread
	paginated        bool
	unnamed          bool
	delegated        bool
	sessionMismatch  bool
	ids              []string
	origins          map[string]string
	disconnected     bool
	truncated        bool
	statusError      bool
	changed          bool
	metadataMismatch bool
}

func runtimeServer(t *testing.T, f fixture) string {
	t.Helper()
	// Unix path length is limited; Go's full per-subtest TempDir can exceed it.
	dir, err := os.MkdirTemp("", "codex-runtime-")
	if err != nil {
		t.Fatal(err)
	}
	socket := filepath.Join(dir, "control.sock")
	l, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		wg.Add(1)
		defer wg.Done()
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer c.CloseNow()
		ctx, cancel := context.WithTimeout(t.Context(), 3*time.Second)
		defer cancel()
		scans := 0
		for {
			var q struct {
				ID     int             `json:"id"`
				Method string          `json:"method"`
				Params json.RawMessage `json:"params"`
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
			case "thread/loaded/list":
				scans++
				var next any
				if f.truncated {
					next = "more"
				}
				ids := f.ids
				if f.childAppears && scans > 1 {
					ids = append(append([]string{}, ids...), idB)
				}
				if f.paginated {
					var params struct {
						Cursor string `json:"cursor"`
					}
					_ = json.Unmarshal(q.Params, &params)
					offset, _ := strconv.Atoi(params.Cursor)
					end := min(len(f.ids), offset+threadPageSize)
					ids = f.ids[offset:end]
					if end < len(f.ids) {
						next = strconv.Itoa(end)
					}
				}
				result = map[string]any{"data": ids, "nextCursor": next}
			case "mcpServerStatus/list":
				var p struct {
					ThreadID   string `json:"threadId"`
					ServerName string `json:"serverName"`
					Detail     string `json:"detail"`
				}
				if json.Unmarshal(q.Params, &p) != nil {
					return
				}
				if p.ServerName != "codex_tui" || p.Detail != "toolsAndAuthOnly" {
					t.Error("unexpected inventory request")
				}
				origin := f.origins[p.ThreadID]
				if f.changed && scans > 1 {
					origin = originB
				}
				status := "connected"
				if f.disconnected {
					status = "disconnected"
				}
				rows := []any{}
				if origin != "" {
					rows = append(rows, map[string]any{"name": "codex_tui", "httpOrigin": origin, "runtimeStatus": status})
				}
				result = map[string]any{"data": rows, "nextCursor": nil}
				if f.statusError {
					failure = map[string]any{"code": -1, "message": "unavailable"}
				}
			case "thread/read":
				var p struct {
					ThreadID     string `json:"threadId"`
					IncludeTurns bool   `json:"includeTurns"`
				}
				if json.Unmarshal(q.Params, &p) != nil {
					return
				}
				if p.IncludeTurns {
					t.Error("must never request conversation text")
				}
				id := p.ThreadID
				if f.metadataMismatch {
					id = idB
				}
				thread := Thread{ID: id, SessionID: id, CWD: "/work/project", Name: "Same title", Originator: "codex-tui"}
				if f.unnamed {
					thread.Name = ""
				}
				if f.delegated {
					thread.Source = json.RawMessage(`{"subAgent":{"thread_spawn":{"parent_thread_id":"parent"}}}`)
				}
				if f.sessionMismatch {
					thread.SessionID = idB
				}
				if custom, ok := f.metadata[p.ThreadID]; ok {
					thread = custom
				}
				result = map[string]any{"thread": thread}
			default:
				t.Errorf("forbidden runtime mutation/tool call: %s", q.Method)
				return
			}
			msg := map[string]any{"id": q.ID, "result": result}
			if failure != nil {
				msg = map[string]any{"id": q.ID, "error": failure}
			}
			if wsjson.Write(ctx, c, msg) != nil {
				return
			}
		}
	})}
	go func() { _ = server.Serve(l) }()
	t.Cleanup(func() { _ = server.Close(); wg.Wait(); _ = os.RemoveAll(dir) })
	return socket
}

func TestDiscoverUniqueProcessEndpointNotTitle(t *testing.T) {
	socket := runtimeServer(t, fixture{ids: []string{idA, idB}, origins: map[string]string{idA: originA, idB: originB}})
	ctx, cancel := context.WithTimeout(t.Context(), 2*time.Second)
	defer cancel()
	rows, err := Discover(ctx, socket, map[string]bool{originA: true})
	if err != nil || rows[originA].ID != idA || len(rows) != 1 {
		t.Fatalf("wrong exact endpoint selection: %v %v", rows, err)
	}
}

func TestDiscoverFailsClosed(t *testing.T) {
	cases := map[string]fixture{
		"shared endpoint after resume": {ids: []string{idA, idB}, origins: map[string]string{idA: originA, idB: originA}},
		"truncated inventory":          {ids: []string{idA}, origins: map[string]string{idA: originA}, truncated: true},
		"duplicate ids":                {ids: []string{idA, idA}, origins: map[string]string{idA: originA}},
		"disconnected":                 {ids: []string{idA}, origins: map[string]string{idA: originA}, disconnected: true},
		"unavailable thread":           {ids: []string{idA}, origins: map[string]string{idA: originA}, statusError: true},
		"mid-read session switch":      {ids: []string{idA}, origins: map[string]string{idA: originA}, changed: true},
		"metadata id mismatch":         {ids: []string{idA}, origins: map[string]string{idA: originA}, metadataMismatch: true},
		"nonloopback endpoint":         {ids: []string{idA}, origins: map[string]string{idA: "http://192.0.2.1:46223"}},
		"no TUI":                       {ids: []string{idA}, origins: map[string]string{}},
	}
	for name, f := range cases {
		t.Run(name, func(t *testing.T) {
			socket := runtimeServer(t, f)
			ctx, cancel := context.WithTimeout(t.Context(), 2*time.Second)
			defer cancel()
			rows, err := Discover(ctx, socket, map[string]bool{originA: true})
			if err == nil && rows[originA].ID != "" {
				t.Fatalf("unsafe mapping: %v", rows)
			}
		})
	}
}

func TestLocalOriginRefusesRemoteAndMalformedAddresses(t *testing.T) {
	if !LocalOrigin(originA) {
		t.Fatal("valid origin rejected")
	}
	for _, s := range []string{"http://localhost:46223", "https://127.0.0.1:46223", "http://127.0.0.1:0", "http://127.0.0.1:65536", "http://user@127.0.0.1:46223", "http://127.0.0.1:46223/path", "http://127.0.0.1:46223?x=y", "http://127.0.0.1:046223", "http://0.0.0.0:46223"} {
		if LocalOrigin(s) {
			t.Errorf("unsafe origin accepted: %s", s)
		}
	}
}

func TestDiscoverCompletesPagesAndNeverDropsUnnamedCandidates(t *testing.T) {
	ids := []string{idA}
	origins := map[string]string{idA: originA}
	for i := 0; i < 65; i++ {
		id := fmt.Sprintf("123e4567-e89b-12d3-a456-%012d", i)
		ids = append(ids, id)
		origins[id] = originB
	}
	f := fixture{ids: ids, origins: origins, paginated: true, unnamed: true}
	rows, err := Discover(t.Context(), runtimeServer(t, f), map[string]bool{originA: true})
	if err != nil || rows[originA].ID != idA {
		t.Fatal("complete paginated association rejected", err)
	}
	origins[ids[len(ids)-1]] = originA
	rows, err = Discover(t.Context(), runtimeServer(t, f), map[string]bool{originA: true})
	if err == nil && rows[originA].ID != "" {
		t.Fatal("second-page unnamed candidate was ignored")
	}
}
func TestDiscoverRejectsDelegationAndSessionFieldMismatch(t *testing.T) {
	for _, f := range []fixture{{ids: []string{idA}, origins: map[string]string{idA: originA}, delegated: true}, {ids: []string{idA}, origins: map[string]string{idA: originA}, sessionMismatch: true}} {
		rows, err := Discover(t.Context(), runtimeServer(t, f), map[string]bool{originA: true})
		if err == nil && rows[originA].ID != "" {
			t.Fatal("invalid primary metadata accepted")
		}
	}
}

func TestDiscoverProvedChildDoesNotDisplaceOrFinishPrimary(t *testing.T) {
	root := Thread{ID: idA, SessionID: idA, CWD: "/project", Originator: "codex-tui", Status: runtimeStatus{Type: "active"}}
	child := Thread{ID: idB, SessionID: idA, CWD: "/project", Originator: "codex-tui", ParentThreadID: idA, Status: runtimeStatus{Type: "idle"}}
	f := fixture{ids: []string{idA, idB}, origins: map[string]string{idA: originA, idB: originA}, metadata: map[string]Thread{idA: root, idB: child}}
	rows, err := Discover(t.Context(), runtimeServer(t, f), map[string]bool{originA: true})
	if err != nil || rows[originA].ID != idA || rows[originA].AgentStatus() != "working" {
		t.Fatal("child changed primary identity/status", err)
	}
	child.ParentThreadID = ""
	child.Source = json.RawMessage(`{"subAgent":{"thread_spawn":{"parent_thread_id":"` + idA + `"}}}`)
	f.metadata = map[string]Thread{idA: root, idB: child}
	rows, err = Discover(t.Context(), runtimeServer(t, f), map[string]bool{originA: true})
	if err != nil || rows[originA].ID != idA {
		t.Fatal("explicit source ancestry ignored", err)
	}
	child.Source = json.RawMessage(`"subagent"`)
	f.metadata = map[string]Thread{idA: root, idB: child}
	rows, err = Discover(t.Context(), runtimeServer(t, f), map[string]bool{originA: true})
	if err == nil && rows[originA].ID != "" {
		t.Fatal("unclassified child was silently ignored")
	}
}

func TestDiscoverChildLifecycleDoesNotInvalidateStablePrimary(t *testing.T) {
	root := Thread{ID: idA, CWD: "/project", Originator: "codex-tui", Status: runtimeStatus{Type: "active"}}
	child := Thread{ID: idB, CWD: "/project", ParentThreadID: idA}
	f := fixture{ids: []string{idA}, origins: map[string]string{idA: originA, idB: originA}, metadata: map[string]Thread{idA: root, idB: child}, childAppears: true}
	rows, err := Discover(t.Context(), runtimeServer(t, f), map[string]bool{originA: true})
	if err != nil || rows[originA].ID != idA {
		t.Fatal("proven child churn invalidated primary", err)
	}
	child.ParentThreadID = ""
	child.Originator = "codex-tui"
	f.metadata = map[string]Thread{idA: root, idB: child}
	rows, err = Discover(t.Context(), runtimeServer(t, f), map[string]bool{originA: true})
	if err == nil && rows[originA].ID != "" {
		t.Fatal("new unrelated thread was ignored")
	}
}

func TestRuntimeRootSetRejectsIncompleteOrCyclicAncestry(t *testing.T) {
	root := Thread{ID: idA, SessionID: idA, CWD: "/project"}
	for name, child := range map[string]Thread{
		"missing parent":             {ID: idB, SessionID: idA, ParentThreadID: "323e4567-e89b-12d3-a456-426614174002"},
		"self cycle":                 {ID: idB, SessionID: idA, ParentThreadID: idB},
		"unclassified child":         {ID: idB, Source: json.RawMessage(`"subagent"`)},
		"contradictory session tree": {ID: idB, SessionID: idB, ParentThreadID: idA},
		"contradictory parents":      {ID: idB, SessionID: idA, ParentThreadID: idA, Source: json.RawMessage(`{"subAgent":{"thread_spawn":{"parent_thread_id":"` + idB + `"}}}`)},
	} {
		t.Run(name, func(t *testing.T) {
			got := primaryThread([]association{{idA, true}, {idB, true}}, map[string]Thread{idA: root, idB: child})
			if got.ID != "" || len(got.Candidates) != 0 {
				t.Fatalf("invalid graph produced usable roots: %+v", got)
			}
		})
	}
}
func TestRuntimeMultipleRootsRemainCandidatesWithoutLookingAtHistory(t *testing.T) {
	a := Thread{ID: idA, SessionID: idA, CWD: "/project", Originator: "Codex Desktop", Path: "/missing/rollout"}
	b := Thread{ID: idB, SessionID: idB, CWD: "/project", ForkedFromID: idA}
	f := fixture{ids: []string{idA, idB}, origins: map[string]string{idA: originA, idB: originA}, metadata: map[string]Thread{idA: a, idB: b}}
	rows, err := Discover(t.Context(), runtimeServer(t, f), map[string]bool{originA: true})
	got := rows[originA]
	if err != nil || got.Unavailable != "runtime_ambiguous" || got.ID != "" || len(got.Candidates) != 2 {
		t.Fatalf("lost independent roots: %+v %v", got, err)
	}
}
