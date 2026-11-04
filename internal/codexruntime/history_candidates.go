package codexruntime

import (
	"context"
	"errors"
	"fmt"
)

// Materialization is history availability, never proof of the TUI's selected thread.
type HistoryState string

const (
	HistoryPersisted      HistoryState = "persisted"
	HistoryUnmaterialized HistoryState = "unmaterialized"
)

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

func (e *rpcError) Error() string { return "Codex runtime request unavailable" }
func (e *rpcError) Unwrap() error { return errUnavailable }

func unmaterializedHistory(err error, id string) bool {
	var failure *rpcError
	// This observed server response is used only to offer read-only history.
	// Different/unknown errors fail closed; never infer it from an absent file.
	return errors.As(err, &failure) && failure.Code == -32600 &&
		failure.Message == fmt.Sprintf("thread %s is not materialized yet; thread/turns/list is unavailable before first user message", id)
}

func (r *rpc) ambiguousHistoryCandidates(ctx context.Context, ids []association, metadata map[string]Thread) []Thread {
	if len(ids) > 8 {
		return nil
	}
	var roots []Thread
	for _, id := range ids {
		t := metadata[id.ID]
		if !id.Connected {
			return nil
		}
		if t.Delegated() {
			continue
		}
		if !threadID.MatchString(t.ID) || t.CWD == "" || t.Path == "" || (t.SessionID != "" && t.SessionID != t.ID) {
			return nil
		}
		roots = append(roots, t)
	}
	if len(roots) < 2 {
		return nil
	}
	for i := range roots {
		var turns struct {
			Data []struct {
				ItemsView string `json:"itemsView"`
			} `json:"data"`
		}
		err := r.call(ctx, "thread/turns/list", map[string]any{
			"threadId": roots[i].ID, "itemsView": "notLoaded", "sortDirection": "desc", "limit": 1,
		}, &turns)
		switch {
		case err == nil && turns.Data != nil && len(turns.Data) <= 1:
			if len(turns.Data) == 1 && turns.Data[0].ItemsView != "notLoaded" {
				return nil
			}
			roots[i].HistoryState = HistoryPersisted
		case unmaterializedHistory(err, roots[i].ID):
			roots[i].HistoryState = HistoryUnmaterialized
		default:
			return nil
		}
	}
	return roots
}

func sameHistoryCandidates(before, after []Thread) bool {
	if len(before) != len(after) {
		return false
	}
	for i, a := range before {
		b := after[i]
		if a.ID != b.ID || a.SessionID != b.SessionID || a.CWD != b.CWD || a.Path != b.Path || a.HistoryState != b.HistoryState {
			return false
		}
	}
	return true
}
