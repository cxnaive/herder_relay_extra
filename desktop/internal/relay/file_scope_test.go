package relay

import (
	"context"
	"errors"
	"testing"
)

func TestFileReaderScopesEveryReadAndNeverWrites(t *testing.T) {
	agent := Agent{Target: Target{PaneID: "p", TerminalID: "t", Generation: 4}}
	for _, filesystem := range []bool{false, true} {
		calls := 0
		r := FileReader{Context: t.Context(), Filesystem: filesystem, Requester: fileRequester(func(_ context.Context, m Message) (Message, error) {
			calls++
			scope := "workspace"
			if filesystem {
				scope = "filesystem"
			}
			if Text(m, "file_scope") != scope || m["target"] != agent.Target {
				t.Fatal("wrong scope or target", m)
			}
			return Message{"ok": true}, nil
		})}
		for _, action := range []string{"workspace_list", "workspace_file", "workspace_file_read", "workspace_file_download"} {
			req := agent.Request(action)
			req["file_scope"] = "injected"
			if _, err := r.Request(t.Context(), req); err != nil {
				t.Fatal(err)
			}
			if req["file_scope"] != "injected" {
				t.Fatal("mutated caller request")
			}
		}
		if _, err := r.Request(t.Context(), agent.Request("submit_prompt")); err == nil || calls != 4 {
			t.Fatal("write escaped read adapter")
		}
	}
}
func TestScopeRevocationCancelsInflightAndRejectsLateReply(t *testing.T) {
	scope, cancel := context.WithCancel(t.Context())
	defer cancel()
	entered := make(chan struct{})
	finished := make(chan error, 1)
	r := FileReader{Context: scope, Filesystem: true, Requester: fileRequester(func(ctx context.Context, _ Message) (Message, error) {
		close(entered)
		<-ctx.Done()
		return Message{"private": "late result"}, nil
	})}
	go func() {
		data, err := r.Request(t.Context(), Message{"type": "workspace_file_read"})
		if data != nil {
			finished <- errors.New("revoked content exposed")
		} else {
			finished <- err
		}
	}()
	<-entered
	cancel()
	if err := <-finished; !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	r.Requester = fileRequester(func(context.Context, Message) (Message, error) {
		t.Fatal("revoked scope dispatched another request")
		return nil, nil
	})
	if _, err := r.Request(t.Context(), Message{"type": "workspace_list"}); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}
func TestFileAccessRequiresExplicitBoolean(t *testing.T) {
	for _, value := range []any{nil, "true", 1, Message{"enabled": true}} {
		if _, err := FileAccessValue(Message{"filesystem_read": value}); err == nil {
			t.Fatal("accepted ambiguous grant", value)
		}
	}
	if enabled, err := FileAccessValue(Message{"filesystem_read": true}); err != nil || !enabled {
		t.Fatal(enabled, err)
	}
}
