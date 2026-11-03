package workspace

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestDirectoryListsRootSiblingsEvenWhenFirstChildExceedsTreeBudget(t *testing.T) {
	root := t.TempDir()
	for i := 0; i < maxTreeEntries+1; i++ {
		writeTestFile(t, filepath.Join(root, ".cache-tree", fmt.Sprintf("%05d.txt", i)), "fixture")
	}
	writeTestFile(t, filepath.Join(root, "docs", "guide.md"), "guide")
	writeTestFile(t, filepath.Join(root, "scripts", "run.sh"), "fixture")
	writeTestFile(t, filepath.Join(root, "README.md"), "fixture")
	legacy, err := TreeFor(root)
	if err != nil || !legacy.Truncated {
		t.Fatalf("legacy tree did not reproduce budget truncation: %v", err)
	}
	for _, entry := range legacy.Entries {
		if entry.Path == "docs" {
			t.Fatal("fixture did not reproduce missing root siblings")
		}
	}
	page, err := ListDirectory(t.Context(), root, "", "", 0)
	if err != nil || page.Total != 4 || len(page.Entries) != 4 || page.NextCursor != "" {
		t.Fatalf("root listing: %+v %v", page, err)
	}
	for i, name := range []string{".cache-tree", "docs", "scripts", "README.md"} {
		if page.Entries[i].Path != name {
			t.Fatalf("root order %v", page.Entries)
		}
	}
	child, err := ListDirectory(t.Context(), root, "docs", "", 0)
	if err != nil || child.Path != "docs" || child.Entries[0].Path != "docs/guide.md" {
		t.Fatalf("child %+v %v", child, err)
	}
}

func TestDirectoryPaginationHasNoDuplicatesOrMissingItems(t *testing.T) {
	root := t.TempDir()
	for i := 0; i < 431; i++ {
		writeTestFile(t, filepath.Join(root, "result", fmt.Sprintf("%04d.txt", i)), "fixture")
	}
	for _, name := range []string{"ABC", "abc", "目录"} {
		if err := os.Mkdir(filepath.Join(root, "result", name), 0700); err != nil {
			t.Fatal(err)
		}
	}
	cursor := ""
	seen := map[string]bool{}
	for attempts := 0; attempts < 100; attempts++ {
		page, err := ListDirectory(t.Context(), root, "result", cursor, 7)
		if err != nil || page.Total != 434 || len(page.Entries) > 7 || len(page.Entries) == 0 {
			t.Fatalf("page: %+v %v", page, err)
		}
		for _, entry := range page.Entries {
			if seen[entry.Path] {
				t.Fatalf("duplicate %s", entry.Path)
			}
			seen[entry.Path] = true
		}
		if page.NextCursor == "" {
			break
		}
		if page.NextCursor == cursor {
			t.Fatal("cursor did not advance")
		}
		cursor = page.NextCursor
	}
	if len(seen) != 434 {
		t.Fatalf("only %d entries read", len(seen))
	}
}

func TestDirectoryCursorRejectsChangesAndOtherRootsOrDirectories(t *testing.T) {
	root := t.TempDir()
	writeTestFile(t, filepath.Join(root, "one", "a.txt"), "a")
	writeTestFile(t, filepath.Join(root, "one", "b.txt"), "b")
	writeTestFile(t, filepath.Join(root, "two", "a.txt"), "a")
	page, err := ListDirectory(t.Context(), root, "one", "", 1)
	if err != nil || page.NextCursor == "" {
		t.Fatal(err)
	}
	for _, path := range []string{"", "two"} {
		if _, err := ListDirectory(t.Context(), root, path, page.NextCursor, 1); err == nil {
			t.Fatal("cross-directory cursor accepted")
		}
	}
	other := t.TempDir()
	writeTestFile(t, filepath.Join(other, "one", "a.txt"), "a")
	if _, err := ListDirectory(t.Context(), other, "one", page.NextCursor, 1); err == nil {
		t.Fatal("cross-workspace cursor accepted")
	}
	stamp := time.Now().Add(time.Second)
	if err := os.Chtimes(filepath.Join(root, "one"), stamp, stamp); err != nil {
		t.Fatal(err)
	}
	if _, err := ListDirectory(t.Context(), root, "one", page.NextCursor, 1); err == nil {
		t.Fatal("stale cursor accepted")
	}
}

func TestDirectoryRejectsEscapesSymlinksInvalidRequestsAndCancelledReads(t *testing.T) {
	root := t.TempDir()
	writeTestFile(t, filepath.Join(root, "normal", "file.txt"), "fixture")
	if err := os.Symlink(t.TempDir(), filepath.Join(root, "outside")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("normal", filepath.Join(root, "inside")); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"../", "/tmp", "normal/../", "outside", "inside", "inside/child", "normal/file.txt"} {
		if _, err := ListDirectory(t.Context(), root, path, "", 10); err == nil {
			t.Fatalf("unsafe directory accepted: %s", path)
		}
	}
	page, err := ListDirectory(t.Context(), root, "", "", 10)
	if err != nil || len(page.Entries) != 1 || page.Skipped != 2 {
		t.Fatalf("symlink listing: %+v %v", page, err)
	}
	for _, limit := range []int{-1, maxDirectoryPage + 1} {
		if _, err := ListDirectory(t.Context(), root, "", "", limit); err == nil {
			t.Fatal("invalid limit")
		}
	}
	for _, cursor := range []string{"garbage", strings.Repeat("x", 5000)} {
		if _, err := ListDirectory(t.Context(), root, "", cursor, 10); err == nil {
			t.Fatal("invalid cursor")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := ListDirectory(ctx, root, "", "", 10); err == nil {
		t.Fatal("cancelled read succeeded")
	}
}

func TestDirectoryLongPathsStayWithinMobileMessageBudget(t *testing.T) {
	root := t.TempDir()
	path := strings.TrimSuffix(strings.Repeat(strings.Repeat("x", 90)+"/", 18), "/")
	for i := 0; i < 201; i++ {
		writeTestFile(t, filepath.Join(root, path, fmt.Sprintf("%03d.txt", i)), "fixture")
	}
	page, err := ListDirectory(t.Context(), root, path, "", 200)
	if err != nil {
		t.Fatal(err)
	}
	data, err := json.Marshal(page)
	if err != nil || len(data) > 240*1024 || page.NextCursor == "" || len(page.Entries) >= 200 {
		t.Fatalf("budget: %d bytes %d entries: %v", len(data), len(page.Entries), err)
	}
}
