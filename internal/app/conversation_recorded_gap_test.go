package app

import (
	"context"
	"errors"
	"testing"

	"github.com/0cv/herdr-mobile-relay/internal/conversation"
)

func TestRecordedRecoveryIsBoundedRevisionScopedAndSkipsUnnecessaryReads(t *testing.T) {
	page := func(cursor string, ids ...string) conversation.BrowsePage {
		p := conversation.BrowsePage{Available: true, State: conversation.BrowseReady, SourceRevision: "same", NextCursor: cursor}
		for _, id := range ids {
			p.Entries = append(p.Entries, conversation.Entry{ID: id})
		}
		return p
	}
	old := page("before", "old")
	tail := page("middle", "new")
	for _, scenario := range []string{"overlap", "revision", "failed", "cancelled", "stalled", "bound"} {
		t.Run(scenario, func(t *testing.T) {
			newest := tail
			ctx := t.Context()
			calls := 0
			if scenario == "overlap" {
				newest = page("before", "old", "new")
			}
			if scenario == "revision" {
				newest.SourceRevision = "other"
			}
			if scenario == "cancelled" {
				var cancel context.CancelFunc
				ctx, cancel = context.WithCancel(ctx)
				cancel()
			}
			got := recoverRecordedWindow(ctx, conversation.BrowseScope{}, old, newest, func(context.Context, conversation.BrowseRequest) (conversation.BrowsePage, error) {
				calls++
				if scenario == "failed" {
					return conversation.BrowsePage{}, errors.New("fixture")
				}
				if scenario == "stalled" {
					return page("middle", "unrelated"), nil
				}
				if scenario == "bound" {
					return page(string(rune('a'+calls)), "unrelated"), nil
				}
				return page("", "old"), nil
			})
			if calls > 32 {
				t.Fatal("unbounded backfill")
			}
			if (scenario == "overlap" || scenario == "revision") && calls != 0 {
				t.Fatal("unneeded read")
			}
			if got.SourceRevision != newest.SourceRevision || len(got.Entries) != len(newest.Entries) {
				t.Fatal("invalid recovery replaced tail")
			}
		})
	}
}
