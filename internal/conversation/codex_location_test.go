package conversation

import (
	"database/sql"
	"os"
	"path/filepath"
	"testing"
)

func codexRolloutFixture(t *testing.T, root, name, id, text string) string {
	t.Helper()
	path := filepath.Join(root, "2026", "11", "03", name)
	writeRows(t, path,
		map[string]any{"type": "session_meta", "payload": map[string]any{"id": id, "cwd": "/work"}},
		map[string]any{"type": "response_item", "payload": map[string]any{"type": "message", "role": "assistant", "content": []any{map[string]any{"type": "output_text", "text": text}}}},
	)
	return path
}

func codexIndexFixture(t *testing.T, home, filename, id, path string) {
	t.Helper()
	if err := os.MkdirAll(home, 0700); err != nil {
		t.Fatal(err)
	}
	db, err := sql.Open("sqlite", filepath.Join(home, filename))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err = db.Exec("CREATE TABLE threads (id TEXT PRIMARY KEY, rollout_path TEXT)"); err != nil {
		t.Fatal(err)
	}
	if _, err = db.Exec("INSERT INTO threads VALUES (?,?)", id, path); err != nil {
		t.Fatal(err)
	}
}

func TestCodexIndexReadsResumedThreadAndKeepsLogicalSession(t *testing.T) {
	reader, home := testReader(t)
	root := filepath.Join(home, ".codex", "sessions")
	path := codexRolloutFixture(t, root, "rollout-2026-11-03T01-00-00-"+testSessionID+"_"+secondSessionID+".jsonl", testSessionID, "resumed response")
	codexIndexFixture(t, filepath.Dir(root), "state_5.sqlite", testSessionID, path)
	location := reader.Locate("codex", "/work", testSessionID)
	if location.Path != path {
		t.Fatalf("location = %#v", location)
	}
	page, err := reader.ReadFor("codex", "/work", testSessionID, "", 80)
	if err != nil || !page.Available || len(page.Entries) != 1 || page.Entries[0].Text != "resumed response" {
		t.Fatalf("page=%#v err=%v", page, err)
	}
	if other := reader.Locate("codex", "/work", secondSessionID); other.Path != "" {
		t.Fatalf("segment id selected another thread: %#v", other)
	}
}

func TestCodexIndexWinsOverNewerNamedFile(t *testing.T) {
	_, home := testReader(t)
	root := filepath.Join(home, ".codex", "sessions")
	active := codexRolloutFixture(t, root, "rollout-2026-11-03T01-00-00-"+testSessionID+"_"+secondSessionID+".jsonl", testSessionID, "active")
	codexRolloutFixture(t, root, "rollout-2026-11-03T23-00-00-"+testSessionID+".jsonl", testSessionID, "obsolete")
	codexIndexFixture(t, filepath.Dir(root), "state_5.sqlite", testSessionID, active)
	if got := findCodexSession([]string{root}, testSessionID); got.Path != active {
		t.Fatalf("selected %#v", got)
	}
}

func TestCodexIndexUsesNumericSchemaVersion(t *testing.T) {
	_, home := testReader(t)
	root := filepath.Join(home, ".codex", "sessions")
	old := codexRolloutFixture(t, root, "rollout-old-"+testSessionID+".jsonl", testSessionID, "old")
	current := codexRolloutFixture(t, root, "rollout-new-"+testSessionID+"_"+secondSessionID+".jsonl", testSessionID, "current")
	codexIndexFixture(t, filepath.Dir(root), "state_9.sqlite", testSessionID, old)
	codexIndexFixture(t, filepath.Dir(root), "state_10.sqlite", testSessionID, current)
	if got := findCodexSession([]string{root}, testSessionID); got.Path != current {
		t.Fatalf("selected %#v", got)
	}
}

func TestCodexIndexRejectsOutsideRootAndDoesNotFallBack(t *testing.T) {
	_, home := testReader(t)
	root := filepath.Join(home, ".codex", "sessions")
	codexRolloutFixture(t, root, "rollout-old-"+testSessionID+".jsonl", testSessionID, "obsolete")
	outside := codexRolloutFixture(t, t.TempDir(), "rollout-outside-"+testSessionID+".jsonl", testSessionID, "private")
	codexIndexFixture(t, filepath.Dir(root), "state_5.sqlite", testSessionID, outside)
	if got := findCodexSession([]string{root}, testSessionID); got.Path != "" {
		t.Fatalf("escaped confinement: %#v", got)
	}
}

func TestCodexIndexRejectsMismatchedSessionHeader(t *testing.T) {
	_, home := testReader(t)
	root := filepath.Join(home, ".codex", "sessions")
	path := codexRolloutFixture(t, root, "rollout-2026-11-03-"+testSessionID+".jsonl", secondSessionID, "another thread")
	codexIndexFixture(t, filepath.Dir(root), "state_5.sqlite", testSessionID, path)
	if got := findCodexSession([]string{root}, testSessionID); got.Path != "" {
		t.Fatalf("cross-thread read: %#v", got)
	}
}

func TestCodexResumedFilenameWithoutDatabaseRequiresSessionMetadata(t *testing.T) {
	_, home := testReader(t)
	root := filepath.Join(home, ".codex", "sessions")
	path := codexRolloutFixture(t, root, "rollout-2026-11-03-"+testSessionID+"_"+secondSessionID+".jsonl", testSessionID, "resumed")
	if got := findCodexSession([]string{root}, testSessionID); got.Path != path {
		t.Fatalf("selected %#v", got)
	}
	writeRows(t, path, map[string]any{"type": "session_meta", "payload": map[string]any{"id": secondSessionID}})
	if got := findCodexSession([]string{root}, testSessionID); got.Path != "" {
		t.Fatalf("cross-thread filename read: %#v", got)
	}
}

func TestCodexIndexedCompressedSegmentIsNotReplacedWithStaleHistory(t *testing.T) {
	_, home := testReader(t)
	root := filepath.Join(home, ".codex", "sessions")
	path := codexRolloutFixture(t, root, "rollout-old-"+testSessionID+".jsonl", testSessionID, "old")
	codexIndexFixture(t, filepath.Dir(root), "state_5.sqlite", testSessionID, path+".zst")
	if got := findCodexSession([]string{root}, testSessionID); got.Path != "" {
		t.Fatalf("unexpected stale fallback: %#v", got)
	}
}

func TestCodexExplicitBindingChecksTheRolloutProject(t *testing.T) {
	reader, home := testReader(t)
	root := filepath.Join(home, ".codex", "sessions")
	codexRolloutFixture(t, root, "rollout-2026-11-03-"+testSessionID+".jsonl", testSessionID, "response")
	if !reader.CodexSessionProjectMatches("/work", testSessionID) {
		t.Fatal("valid thread project rejected")
	}
	if reader.CodexSessionProjectMatches("/other", testSessionID) || reader.CodexSessionProjectMatches("relative", testSessionID) {
		t.Fatal("cross-project binding allowed")
	}
}

func TestCodexPinnedTitleRejectsAmbiguousOrRenamedThreads(t *testing.T) {
	reader, home := testReader(t)
	root := filepath.Join(home, ".codex", "sessions")
	codexRolloutFixture(t, root, "rollout-2026-11-03-"+testSessionID+".jsonl", testSessionID, "response")
	index := filepath.Join(filepath.Dir(root), "session_index.jsonl")
	writeRows(t, index, map[string]any{"id": testSessionID, "thread_name": "Research"})
	if !reader.CodexPinnedTitleCurrent("/work", testSessionID, "Research | work") {
		t.Fatal("unique operator title rejected")
	}
	writeRows(t, index, map[string]any{"id": testSessionID, "thread_name": "Research"}, map[string]any{"id": secondSessionID, "thread_name": "Research"})
	if reader.CodexPinnedTitleCurrent("/work", testSessionID, "Research | work") {
		t.Fatal("duplicate title still authorized")
	}
	writeRows(t, index, map[string]any{"id": testSessionID, "thread_name": "Research"}, map[string]any{"id": testSessionID, "thread_name": "Renamed"})
	if reader.CodexPinnedTitleCurrent("/work", testSessionID, "Research | work") {
		t.Fatal("stale title authorized")
	}
	if !reader.CodexPinnedTitleCurrent("/work", testSessionID, "Renamed | work") {
		t.Fatal("latest index title ignored")
	}
}
