package app

import (
	"context"
	"errors"
	"fmt"
	"github.com/0cv/herdr-mobile-relay/internal/codexruntime"
	"sync/atomic"
	"testing"
	"time"
)

func TestCodexStoreFailureIsScopedAndQueriesAreBounded(t *testing.T) {
	wanted := map[string]map[string]bool{}
	for i := 0; i < 12; i++ {
		wanted[fmt.Sprint(i)] = map[string]bool{"http://127.0.0.1:1234": true}
	}
	var active, peak atomic.Int32
	query := func(ctx context.Context, root string, origins map[string]bool) codexStoreResult {
		n := active.Add(1)
		defer active.Add(-1)
		for {
			p := peak.Load()
			if n <= p || peak.CompareAndSwap(p, n) {
				break
			}
		}
		if _, ok := ctx.Deadline(); !ok {
			t.Error("store query has no deadline")
		}
		if !origins["http://127.0.0.1:1234"] {
			t.Error("scope lost")
		}
		time.Sleep(time.Millisecond)
		if root == "0" {
			return codexStoreResult{err: errors.New("unavailable")}
		}
		return codexStoreResult{threads: map[string]codexruntime.Thread{"http://127.0.0.1:1234": {ID: root}}}
	}
	result := queryCodexStores(t.Context(), wanted, query)
	if len(result) != 12 || peak.Load() > 4 || result["0"].err == nil {
		t.Fatal("store query scheduling failed")
	}
	for root, r := range result {
		if root != "0" && (r.err != nil || r.threads["http://127.0.0.1:1234"].ID != root) {
			t.Fatal("one failed daemon invalidated another store")
		}
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if len(queryCodexStores(ctx, wanted, query)) != 0 {
		t.Fatal("cancelled discovery queried stores")
	}
}
