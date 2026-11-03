package conversation

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"
)

func TestMobileHistoryPagesStaySmallWithoutSkippingOlderMessages(t *testing.T) {
	const count = 240
	reader, scope, _ := syntheticCodexHistory(t, count, 16*1024)
	browser, err := NewBrowser(reader, t.TempDir(), DefaultBrowserOptions())
	if err != nil {
		t.Fatal(err)
	}
	defer browser.Close()
	seen := map[string]bool{}
	cursor := ""
	pages := 0
	for attempts := 0; attempts < 500; attempts++ {
		page, err := browser.ReadPage(context.Background(), BrowseRequest{Scope: scope, Cursor: cursor, Limit: 80})
		if err != nil {
			t.Fatal(err)
		}
		if page.State == BrowsePreparing {
			page = waitForReadySnapshot(t, browser, scope, page.NextCursor, 80)
		}
		if !page.Available || page.Error != nil {
			t.Fatalf("unavailable page: %+v", page.Error)
		}
		encoded, err := json.Marshal(page)
		if err != nil || len(encoded) > 256*1024 {
			t.Fatalf("mobile payload bytes=%d err=%v", len(encoded), err)
		}
		for _, entry := range page.Entries {
			if entry.Truncated || len(entry.Text) != 16*1024+9 {
				t.Fatal("ordinary record truncated instead of paginated")
			}
			key := entry.Text[:8]
			if seen[key] {
				t.Fatalf("duplicate record %s", key)
			}
			seen[key] = true
		}
		pages++
		if page.NextCursor == "" {
			break
		}
		if page.NextCursor == cursor {
			t.Fatal("history cursor failed to advance")
		}
		cursor = page.NextCursor
	}
	if len(seen) != count || pages < 10 {
		t.Fatalf("read %d/%d entries in %d pages", len(seen), count, pages)
	}
	for i := 0; i < count; i++ {
		if !seen[fmt.Sprintf("%08d", i)] {
			t.Fatalf("missing message %d", i)
		}
	}
}
