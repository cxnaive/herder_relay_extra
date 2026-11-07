package main

import (
	"context"
	"errors"
	"fmt"
	"image/png"
	"os"
	"strconv"
	"testing"

	"github.com/cxnaive/herdr-desktop/internal/relay"
	"github.com/egoist/mygo/ui"
)

func historyEntries(start, end int) []entry {
	rows := []entry{}
	for i := start; i < end; i++ {
		role := "assistant"
		if i%100 == 0 {
			role = "user"
		}
		rows = append(rows, entry{ID: fmt.Sprintf("entry-%05d", i), Role: role, Text: fmt.Sprint(i)})
	}
	return rows
}
func TestLongHistoryRecoversBeyondOldPageAndEntryLimits(t *testing.T) {
	for _, all := range []bool{false, true} {
		t.Run(fmt.Sprint(all), func(t *testing.T) {
			c := &conversation{entries: historyEntries(10, 100), revision: "rev", historyBookmark: historyBookmark{Anchor: "entry-00010"}}
			r := newHistoryRecovery(c, false, all)
			end := 4260
			for !r.done {
				req := r.request(relay.Agent{})
				if r.pages == 0 && req["observe"] != true || r.pages > 0 && req["observe"] != false {
					t.Fatal("wrong subscription intent")
				}
				start := max(0, end-60)
				cursor := ""
				if start > 0 {
					cursor = strconv.Itoa(start)
				}
				preparing, err := r.accept(historyPage{Available: true, Revision: "rev", Cursor: cursor, Entries: historyEntries(start, end), Subscription: "sub", Sequence: 7})
				if err != nil || preparing {
					t.Fatal(err)
				}
				end = start
			}
			got := r.entries()
			if r.pages <= 32 || len(got) != 4260 || got[0].ID != "entry-00000" || got[len(got)-1].ID != "entry-04259" {
				t.Fatal("history was truncated", r.pages, len(got))
			}
			c.entries = got
			c.subscription = "sub"
			c.revision = "rev"
			c.sequence = 7
			if !c.applyBatch(relay.Message{"kind": "batch", "subscription_id": "sub", "source_revision": "rev", "sequence": 8, "entries": historyEntries(4260, 4261)}) || len(c.entries) != 4261 {
				t.Fatal("long history rejected live update")
			}
		})
	}
}
func TestRecoveryRequiresCurrentTurnAndResumesPreparationCursor(t *testing.T) {
	r := newHistoryRecovery(&conversation{}, false, false)
	if wait, err := r.accept(historyPage{State: "preparing", Cursor: "job:1"}); !wait || err != nil {
		t.Fatal(wait, err)
	}
	request := r.request(relay.Agent{})
	if request["cursor"] != "job:1" || request["observe"] != true {
		t.Fatal("preparation lost recent intent", request)
	}
	if wait, err := r.accept(historyPage{State: "preparing", Cursor: "job:2"}); !wait || err != nil {
		t.Fatal(wait, err)
	}
	_, err := r.accept(historyPage{Available: true, Revision: "r", Cursor: "older", Entries: []entry{{ID: "tool", Role: "assistant"}}})
	if err != nil || r.done {
		t.Fatal("assistant tail counted as full turn")
	}
	_, err = r.accept(historyPage{State: "preparing", Cursor: "old-job"})
	if err != nil || r.request(relay.Agent{})["observe"] != false {
		t.Fatal("older preparation acquired new subscription")
	}
	_, err = r.accept(historyPage{Available: true, Revision: "r", Cursor: "earlier", Entries: []entry{{ID: "user", Role: "user"}}})
	if err != nil || !r.done || r.cursor != "earlier" {
		t.Fatal(err, r)
	}
}
func TestRecoveryOverlapUsesFreshToolAndRejectsCyclesAndRevisions(t *testing.T) {
	c := &conversation{revision: "r", entries: []entry{{ID: "tail", Role: "assistant", Text: "completed"}}, next: "old"}
	r := newHistoryRecovery(c, true, false)
	_, err := r.accept(historyPage{Available: true, Revision: "r", Entries: []entry{{ID: "user", Role: "user"}, {ID: "tail", Role: "assistant", Text: "running"}}})
	got := r.entries()
	if err != nil || len(got) != 2 || got[1].Text != "completed" {
		t.Fatal("older page overwrote fresh output", got, err)
	}
	for _, variant := range []string{"revision", "cursor", "expired", "duplicate"} {
		r = newHistoryRecovery(&conversation{}, false, true)
		r.accept(historyPage{Available: true, Revision: "r", Cursor: "page2", Entries: historyEntries(60, 120)})
		p := historyPage{Available: true, Revision: "r", Cursor: "page3", Entries: historyEntries(0, 60)}
		switch variant {
		case "revision":
			p.Revision = "changed"
		case "cursor":
			p.Cursor = "page2"
		case "expired":
			p.Available = false
			p.ReasonCode = "snapshot_expired"
		case "duplicate":
			p.Entries = append(p.Entries, p.Entries[0])
		}
		if _, err = r.accept(p); err == nil {
			t.Fatal("accepted invalid continuation", variant)
		}
		if (variant == "revision" || variant == "expired") && !errors.Is(err, errHistoryRestart) {
			t.Fatal("cannot restart invalid cursor", err)
		}
		if len(r.rows) != 60 {
			t.Fatal("failed page partially changed recovery")
		}
	}
}
func TestHistoryPauseKeepsWorkAndPersistedDepthBeyondCache(t *testing.T) {
	a, m, c := managementFixture(t)
	c.entries = historyEntries(0, 4000)
	c.revision = "r"
	c.historyBookmark = historyBookmark{Anchor: c.entries[0].ID, All: true}
	c.historyWork = newHistoryRecovery(c, false, true)
	r := c.historyWork
	r.accept(historyPage{Available: true, Revision: "r", Cursor: "next", Entries: historyEntries(4000, 4060)})
	ctx, cancel := context.WithCancel(a.ctx)
	c.historyCancel = cancel
	c.loading = true
	a.cancelHistoryLoad(c, true)
	if ctx.Err() == nil || c.loading || !c.historyBookmark.Paused || c.historyWork != r || r.cursor != "next" || len(c.entries) != 4000 {
		t.Fatal("pause discarded recovery or visible history")
	}
	key := m.config.ID + ":" + c.agent.Identity()
	if err := a.store.saveHistory(key, c.entries, c.revision, c.historyBookmark); err != nil {
		t.Fatal(err)
	}
	saved := a.store.cachedHistory(key)
	if len(saved.Entries) != 300 || saved.Bookmark.Anchor != "entry-00000" || !saved.Bookmark.All || !saved.Bookmark.Paused {
		t.Fatal("cache lost restore depth", saved.Bookmark, len(saved.Entries))
	}
	restored := &conversation{entries: saved.Entries, revision: saved.Revision, historyBookmark: saved.Bookmark}
	fresh := newHistoryRecovery(restored, false, restored.historyBookmark.All)
	if fresh.anchor != "entry-00000" || !fresh.all {
		t.Fatal("restart only restored last cached page")
	}
}

func TestRoutineRefreshKeepsLoadedHistoryWithoutRefetchingIt(t *testing.T) {
	c := &conversation{entries: historyEntries(0, 4000), revision: "r", historyBookmark: historyBookmark{Anchor: "entry-00000", All: true}, provisionalIDs: map[string]bool{"temporary": true}}
	c.entries = append(c.entries, entry{ID: "temporary", Text: "unfinished"})
	r := newRecentRecovery(c)
	if r.anchor != "entry-03999" || r.all {
		t.Fatal("routine refresh would refetch the whole history")
	}
	rows := historyEntries(3940, 4020)
	rows[59].Text = "updated"
	_, err := r.accept(historyPage{Available: true, Revision: "r", Cursor: "older", Entries: rows})
	if err != nil || !r.done {
		t.Fatal(err)
	}
	got := r.entries()
	if len(got) != 4020 || got[3999].Text != "updated" || got[0].ID != "entry-00000" {
		t.Fatal("refresh lost old entries or retained temporary message", len(got))
	}
	r = newRecentRecovery(c)
	_, err = r.accept(historyPage{Available: true, Revision: "replaced", Cursor: "older", Entries: rows})
	if err != nil || r.done || r.fast || !r.all || r.anchor != "entry-00000" {
		t.Fatal("replacement mixed with cached source", r, err)
	}
}

func TestHistoryProgressPausesWithoutAutomaticRestart(t *testing.T) {
	a, _, c := managementFixture(t)
	c.historyWork = newHistoryRecovery(c, false, true)
	c.loading = true
	c.historyPages = 71
	c.historyCount = 4260
	ctx, cancel := context.WithCancel(a.ctx)
	c.historyCancel = cancel
	tt := ui.NewTester(a.view, 1280, 840)
	if !tt.HasText("正在恢复历史 · 71 页 · 4260 条") {
		t.Fatal("missing actual recovery progress")
	}
	if output := os.Getenv("HERDR_HISTORY_SCREENSHOT"); output != "" {
		f, err := os.Create(output)
		if err != nil {
			t.Fatal(err)
		}
		defer f.Close()
		if err = png.Encode(f, tt.Image()); err != nil {
			t.Fatal(err)
		}
	}
	if err := tt.Click("取消加载"); err != nil {
		t.Fatal(err)
	}
	if c.loading || ctx.Err() == nil || !tt.HasText("继续加载") {
		t.Fatal("pause did not preserve continuation")
	}
	a.refreshCount = 3
	a.syncConversation()
	if c.loading {
		t.Fatal("polling resumed cancelled history")
	}
}
