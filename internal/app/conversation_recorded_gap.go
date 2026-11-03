package app

import (
	"context"
	"encoding/json"
	"time"

	"github.com/0cv/herdr-mobile-relay/internal/conversation"
)

// Raw image/tool result records can evict every visible entry from the recent
// byte window. Walk the existing bounded history pages back to the last entry
// we actually published before treating a new tail as a contiguous update.
func recoverRecordedWindow(ctx context.Context, scope conversation.BrowseScope, previous, page conversation.BrowsePage, read func(context.Context, conversation.BrowseRequest) (conversation.BrowsePage, error)) conversation.BrowsePage {
	if previous.SourceRevision != page.SourceRevision || previous.State != conversation.BrowseReady || page.State != conversation.BrowseReady || len(previous.Entries) == 0 || page.NextCursor == "" {
		return page
	}
	anchor := previous.Entries[len(previous.Entries)-1].ID
	for _, entry := range page.Entries {
		if entry.ID == anchor {
			return page
		}
	}
	ctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	window := append([]conversation.Entry(nil), page.Entries...)
	cursor := page.NextCursor
	bytes := 0
	for pages := 0; pages < 32 && cursor != ""; pages++ {
		older, err := read(ctx, conversation.BrowseRequest{Scope: scope, Cursor: cursor, Limit: 200})
		if err != nil || ctx.Err() != nil {
			return page
		}
		if older.State == conversation.BrowsePreparing {
			if older.NextCursor != "" {
				cursor = older.NextCursor
			}
			select {
			case <-ctx.Done():
				return page
			case <-time.After(100 * time.Millisecond):
			}
			continue
		}
		if !older.Available || older.State != conversation.BrowseReady || older.SourceRevision != page.SourceRevision {
			return page
		}
		raw, _ := json.Marshal(older.Entries)
		bytes += len(raw)
		if bytes > 1024*1024 || len(window)+len(older.Entries) > 1000 {
			return page
		}
		seen := make(map[string]bool, len(window))
		for _, entry := range window {
			seen[entry.ID] = true
		}
		prefix := make([]conversation.Entry, 0, len(older.Entries))
		found := false
		for _, entry := range older.Entries {
			if entry.ID == anchor {
				found = true
			}
			if !seen[entry.ID] {
				prefix = append(prefix, entry)
			}
		}
		window = append(prefix, window...)
		if found {
			page.Entries = window
			return page
		}
		if cursor == older.NextCursor {
			return page
		}
		cursor = older.NextCursor
	}
	return page
}
