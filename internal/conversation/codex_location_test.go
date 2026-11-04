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

func TestCodexResumeFreshLookupReplacesCachedSegmentNotConversation(t *testing.T) {
	reader, home := testReader(t)
	root := filepath.Join(home, ".codex", "sessions")
	old := codexRolloutFixture(t, root, "rollout-old-"+testSessionID+".jsonl", testSessionID, "old segment")
	codexIndexFixture(t, filepath.Dir(root), "state_5.sqlite", testSessionID, old)
	if got := reader.Locate("codex", "/work", testSessionID); got.Path != old {
		t.Fatal(got)
	}
	current := codexRolloutFixture(t, root, "rollout-new-"+testSessionID+"_"+secondSessionID+".jsonl", testSessionID, "resumed current segment")
	db, err := sql.Open("sqlite", filepath.Join(filepath.Dir(root), "state_5.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.Exec("UPDATE threads SET rollout_path=? WHERE id=?", current, testSessionID); err != nil {
		t.Fatal(err)
	}
	db.Close()
	reader.RefreshLocation("codex", ProjectContext{CWD: "/work"}, testSessionID)
	loc, reason := reader.ValidateCodexReport("/work", testSessionID)
	if reason != "" || loc.Path != current {
		t.Fatal("fresh lookup retained obsolete segment", loc, reason)
	}
	page, err := reader.ReadFor("codex", "/work", testSessionID, "", 20)
	if err != nil || len(page.Entries) != 1 || page.Entries[0].Text != "resumed current segment" {
		t.Fatal("old cache suppressed refresh", page, err)
	}
}
func TestCodexReportMissingSourceCanBeRefreshedWithoutChangingID(t *testing.T) {
	reader, home := testReader(t)
	_, reason := reader.ValidateCodexReport("/work", testSessionID)
	if reason != "herdr_history_unavailable" {
		t.Fatal(reason)
	}
	codexRolloutFixture(t, filepath.Join(home, ".codex", "sessions"), "rollout-new-"+testSessionID+".jsonl", testSessionID, "first persisted message")
	reader.RefreshLocation("codex", ProjectContext{CWD: "/work"}, testSessionID)
	if _, reason := reader.ValidateCodexReport("/work", testSessionID); reason != "" {
		t.Fatal("cached miss retained", reason)
	}
}
func TestIndexedHerdrReportMismatchIsConflictNotMissing(t *testing.T) {
	reader, home := testReader(t)
	root := filepath.Join(home, ".codex", "sessions")
	path := codexRolloutFixture(t, root, "rollout-"+testSessionID+".jsonl", secondSessionID, "wrong header")
	codexIndexFixture(t, filepath.Dir(root), "state_5.sqlite", testSessionID, path)
	if _, reason := reader.ValidateCodexReport("/work", testSessionID); reason != "herdr_session_mismatch" {
		t.Fatal(reason)
	}
}

func TestCodexBrowserRefreshUsesNewSegmentAndRejectsOldCursor(t *testing.T) {
	reader, home := testReader(t)
	root := filepath.Join(home, ".codex", "sessions")
	old := codexRolloutFixture(t, root, "rollout-old-"+testSessionID+".jsonl", testSessionID, "old segment")
	file, err := os.OpenFile(old, os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	_, err = file.WriteString(`{"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"old tail"}]}}` + "\n")
	file.Close()
	if err != nil {
		t.Fatal(err)
	}
	codexIndexFixture(t, filepath.Dir(root), "state_5.sqlite", testSessionID, old)
	browser, err := NewBrowser(reader, t.TempDir(), DefaultBrowserOptions())
	if err != nil {
		t.Fatal(err)
	}
	defer browser.Close()
	scope := BrowseScope{Provider: "codex", CWD: "/work", SessionID: testSessionID}
	first, err := browser.ReadPage(t.Context(), BrowseRequest{Scope: scope, Limit: 1})
	if err != nil || len(first.Entries) != 1 || first.Entries[0].Text != "old tail" || first.NextCursor == "" {
		t.Fatal("first tail", first, err)
	}
	current := codexRolloutFixture(t, root, "rollout-new-"+testSessionID+"_"+secondSessionID+".jsonl", testSessionID, "new tail")
	db, err := sql.Open("sqlite", filepath.Join(filepath.Dir(root), "state_5.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.Exec("UPDATE threads SET rollout_path=? WHERE id=?", current, testSessionID); err != nil {
		t.Fatal(err)
	}
	db.Close()
	fresh, err := browser.ReadPage(t.Context(), BrowseRequest{Scope: scope, Limit: 1})
	if err != nil || len(fresh.Entries) != 1 || fresh.Entries[0].Text != "new tail" || fresh.SourceRevision == first.SourceRevision {
		t.Fatal("refresh used old source", fresh, err)
	}
	stale, err := browser.ReadPage(t.Context(), BrowseRequest{Scope: scope, Cursor: first.NextCursor, Limit: 1})
	if err != nil {
		t.Fatal(err)
	}
	if len(stale.Entries) != 0 || stale.Error == nil || stale.Error.Code != "source_changed" {
		t.Fatal("old cursor reused", stale)
	}
}

func TestCodexBindingMetadataReadsOnlyExactHeaderAndPreservesChildScope(t *testing.T) {
	reader, home := testReader(t)
	path := filepath.Join(home, ".codex", "sessions", "2026", "11", "05", "rollout-"+testSessionID+".jsonl")
	writeRows(t, path, map[string]any{"type": "session_meta", "payload": map[string]any{
		"id": testSessionID, "cwd": "/child-worktree", "session_id": secondSessionID,
		"parent_thread_id": secondSessionID, "source": "subagent", "thread_source": "subagent",
	}}, map[string]any{"type": "response_item", "payload": "do not use conversation content as identity"})
	meta, loc, reason := reader.CodexBindingMetadata("/parent-worktree", testSessionID)
	if reason != "" || loc.Path != path || meta.ParentThreadID != secondSessionID || meta.CWD != "/child-worktree" || meta.SessionID != secondSessionID || meta.ThreadSource != "subagent" {
		t.Fatalf("metadata = %+v %+v %s", meta, loc, reason)
	}
	if _, reason := reader.ValidateCodexReport("/parent-worktree", testSessionID); reason != "herdr_project_mismatch" {
		t.Fatalf("primary project validation weakened: %s", reason)
	}
	if _, _, reason := reader.CodexBindingMetadata("/parent-worktree", secondSessionID); reason != "herdr_history_unavailable" {
		t.Fatalf("selected another session: %s", reason)
	}
}
