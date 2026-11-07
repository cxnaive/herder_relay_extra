package conversation

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func turnRow(kind, id string, extra map[string]any) []byte {
	p := map[string]any{"type": kind, "turn_id": id}
	for k, v := range extra {
		p[k] = v
	}
	row, _ := json.Marshal(map[string]any{"type": "event_msg", "timestamp": "2026-11-07T01:00:00Z", "payload": p})
	return append(row, '\n')
}
func TestCodexTurnLifecycleOnly(t *testing.T) {
	start := turnRow("task_started", "a", nil)
	for _, tc := range []struct {
		name       string
		rows       []byte
		status, id string
	}{
		{"start", start, "working", "a"},
		{"tool gap", append(append([]byte{}, start...), turnRow("agent_message", "a", nil)...), "working", "a"},
		{"complete", append(append([]byte{}, start...), turnRow("task_complete", "a", map[string]any{"duration_ms": 3000})...), "done", "a"},
		{"failed", turnRow("task_complete", "a", map[string]any{"error": map[string]any{"message": "failed"}}), "failed", "a"},
		{"abort", turnRow("turn_aborted", "a", nil), "interrupted", "a"},
		{"alias", turnRow("turn_started", "a", nil), "working", "a"},
		{"child cannot finish root", append(append([]byte{}, start...), turnRow("task_complete", "child", map[string]any{"root_turn_id": "a"})...), "working", "a"},
		{"old turn cannot finish new", append(append([]byte{}, start...), turnRow("task_complete", "old", nil)...), "", ""},
		{"missing id", turnRow("task_complete", "", nil), "", ""},
		{"no lifecycle", turnRow("agent_message", "a", nil), "", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got, err := parseCodexTurn(t.Context(), tc.rows, CodexTurn{})
			if err != nil || got.Status != tc.status || got.ID != tc.id {
				t.Fatalf("%+v %v", got, err)
			}
			if got.Status == "working" && got.StartedAt != time.Date(2026, 11, 7, 1, 0, 0, 0, time.UTC).UnixMilli() {
				t.Fatal("not lifecycle start")
			}
		})
	}
	if _, err := parseCodexTurn(t.Context(), []byte("{broken}\n"), CodexTurn{}); err == nil {
		t.Fatal("malformed accepted")
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if _, err := parseCodexTurn(ctx, start, CodexTurn{}); err == nil {
		t.Fatal("cancel ignored")
	}
}
func appendTurn(t *testing.T, path string, data []byte) {
	t.Helper()
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if _, err = f.Write(data); err != nil {
		t.Fatal(err)
	}
}
func TestCodexTurnExactFileCacheAndAppend(t *testing.T) {
	r, home := testReader(t)
	root := filepath.Join(home, ".codex", "sessions")
	path := codexRolloutFixture(t, root, "rollout-"+testSessionID+".jsonl", testSessionID, "not lifecycle")
	read := func() CodexTurn {
		t.Helper()
		v, e := r.CodexTurnFor(t.Context(), "/work", testSessionID, root)
		if e != nil {
			t.Fatal(e)
		}
		return v
	}
	if read().ID != "" {
		t.Fatal("invented lifecycle")
	}
	appendTurn(t, path, turnRow("task_started", "a", nil))
	if read().Status != "working" || read().Status != "working" {
		t.Fatal("cached start")
	}
	// A series of bounded appends can exceed the tail without losing its start.
	for range 5 {
		row, _ := json.Marshal(map[string]any{"type": "response_item", "payload": strings.Repeat("x", 1024*1024)})
		appendTurn(t, path, append(row, '\n'))
		if read().Status != "working" {
			t.Fatal("lost incremental start")
		}
	}
	appendTurn(t, path, turnRow("task_complete", "a", map[string]any{"duration_ms": 0}))
	done := read()
	if done.Status != "done" || done.DurationMS == nil {
		t.Fatal("missing completion")
	}
	*done.DurationMS = 777
	if *read().DurationMS != 0 {
		t.Fatal("cache pointer escaped")
	}
	// Incomplete rows can conceal a new start: never replay the prior completion.
	appendTurn(t, path, []byte("{\"type\":"))
	if _, e := r.CodexTurnFor(t.Context(), "/work", testSessionID, root); e == nil {
		t.Fatal("partial append accepted")
	}
	if _, e := r.CodexTurnFor(t.Context(), "/other", testSessionID, root); e == nil {
		t.Fatal("foreign cwd accepted")
	}
	if _, e := r.CodexTurnFor(t.Context(), "/work", testSessionID, filepath.Dir(root)); e == nil {
		t.Fatal("foreign store accepted")
	}
}
func TestCodexTurnReplacementTruncationAndBound(t *testing.T) {
	r, home := testReader(t)
	root := filepath.Join(home, ".codex", "sessions")
	path := codexRolloutFixture(t, root, "rollout-"+testSessionID+".jsonl", testSessionID, "text")
	appendTurn(t, path, turnRow("task_started", "a", nil))
	if _, e := r.CodexTurnFor(t.Context(), "/work", testSessionID, root); e != nil {
		t.Fatal(e)
	}
	// Fresh inode, same path and identity: do not reuse the previous turn.
	old := path + ".old"
	if e := os.Rename(path, old); e != nil {
		t.Fatal(e)
	}
	codexRolloutFixture(t, root, filepath.Base(path), testSessionID, "text")
	if v, e := r.CodexTurnFor(t.Context(), "/work", testSessionID, root); e != nil || v.ID != "" {
		t.Fatalf("replacement reused %+v %v", v, e)
	}
	appendTurn(t, path, turnRow("task_started", "b", nil))
	appendTurn(t, path, []byte(strings.Repeat(" ", codexTurnTailBytes+100)+"\n"))
	// New readers do not scan beyond 4 MiB or invent an old start.
	other := NewReader(home)
	if v, _ := other.CodexTurnFor(t.Context(), "/work", testSessionID, root); v.ID != "" {
		t.Fatalf("read unbounded history %+v", v)
	}
	if e := os.Truncate(path, 0); e != nil {
		t.Fatal(e)
	}
	if _, e := r.CodexTurnFor(t.Context(), "/work", testSessionID, root); e == nil {
		t.Fatal("truncated source accepted")
	}
}
