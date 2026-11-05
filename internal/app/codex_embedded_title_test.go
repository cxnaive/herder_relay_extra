package app

import (
	"context"
	"errors"
	"testing"

	"github.com/0cv/herdr-mobile-relay/internal/conversation"
	"github.com/0cv/herdr-mobile-relay/internal/coordinator"
)

func TestCodexTitleRuntimeModeRequiresPositiveWriterOwnership(t *testing.T) {
	for _, tc := range []struct {
		name                       string
		title, explicit            bool
		writers                    int
		writerError, listenerError bool
		mode, reason               string
		reads                      int
	}{
		{"implicit embedded", true, false, 2, false, false, "standalone", "", 0},
		{"explicit standalone", false, true, 1, false, false, "standalone", "", 0},
		{"explicit missing writers", true, true, 0, false, false, "standalone", "standalone_runtime_unavailable", 0},
		{"explicit invalid writers", true, true, 1, true, false, "standalone", "standalone_runtime_unavailable", 0},
		{"ordinary shared", false, false, 2, false, false, "shared", "", 1},
		{"title shared no writers", true, false, 0, false, false, "shared", "", 1},
		{"invalid writers not embedded", true, false, 2, true, false, "shared", "", 1},
		{"no proof no inference", true, false, 0, true, true, "shared", "runtime_unavailable", 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			reads := 0
			probe := codexRuntimeProbe{pid: 42, root: "/test/sessions", mode: "shared", titleID: tc.title}
			got, reason := inspectCodexRuntimeOwnership(probe, tc.explicit, func(pid int, root string) (map[string]string, error) {
				if !tc.title && !tc.explicit {
					t.Fatal("unconfigured shared launch probed writers")
				}
				if pid != 42 || root != "/test/sessions" {
					t.Fatal("wrong process/store")
				}
				rows := map[string]string{}
				if tc.writers > 0 {
					rows[reportID] = "inode-1"
				}
				if tc.writers > 1 {
					rows[otherReportID] = "inode-2"
				}
				if tc.writerError {
					return rows, errors.New("unverified")
				}
				return rows, nil
			}, func(pid int) (map[string]string, error) {
				reads++
				if tc.listenerError {
					return nil, errors.New("missing")
				}
				return map[string]string{reportOrigin: "inode"}, nil
			})
			if got.mode != tc.mode || reason != tc.reason || reads != tc.reads {
				t.Fatalf("%+v %s; listener reads %d", got, reason, reads)
			}
		})
	}
}

func TestEmbeddedTitleCanSelectMaterializedRootBesideEmptyRetainedWriter(t *testing.T) {
	s, f, a, writers := standaloneServer(t)
	writers[otherReportID] = "inode-empty" // A /new thread that has never written a metadata header.
	f.pane.TerminalTitle = shortTitle(reportID)
	old := s.codexProcessProbe
	s.codexProcessProbe = func(ctx context.Context, a *coordinator.AgentState) (codexRuntimeProbe, string) {
		p, r := old(ctx, a)
		if r != "" {
			return p, r
		}
		p.mode = "shared"
		p.titleID = true
		return inspectCodexRuntimeOwnership(p, false, func(int, string) (map[string]string, error) { return writers, nil }, func(int) (map[string]string, error) {
			t.Fatal("inferred embedded TUI queried shared mode")
			return nil, nil
		})
	}
	got := commitReport(s, a)
	if got.SessionID != reportID || got.BindingMode != "standalone" || !s.codexPrimaryCurrent(t.Context(), got) {
		t.Fatalf("%+v", got)
	}
	assertNoSharedCalls(t, f)
	f.mu.Lock()
	f.pane.TerminalTitle = shortTitle(otherReportID)
	f.mu.Unlock()
	if s.codexPrimaryCurrent(t.Context(), got) {
		t.Fatal("empty selected thread reused old history")
	}
	got = commitReport(s, a)
	if got.SessionID != "" || got.BindingReason != "standalone_metadata_unavailable" {
		t.Fatalf("%+v", got)
	}
	standaloneRollout(t, s.home, otherReportID, a.Cwd, nil)
	// Expire the existing bounded location-miss cache without sleeping in the test.
	s.conversationM.RefreshLocation("codex", conversation.ProjectContext{CWD: a.Cwd}, otherReportID)
	got = commitReport(s, a)
	if got.SessionID != otherReportID {
		t.Fatal("first metadata did not make selected root readable")
	}
	assertNoSharedCalls(t, f)
}
