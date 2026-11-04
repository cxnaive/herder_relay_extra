package herdr

import (
	"context"
	"encoding/json"
	"net"
	"path/filepath"
	"testing"
)

func TestPaneGetReturnsTerminalIdentity(t *testing.T) {
	socketPath, done := startUnaryTestSocket(t, "pane.get", map[string]any{
		"type": "pane_info",
		"pane": map[string]any{"pane_id": "w1:p2", "terminal_id": "terminal-7", "workspace_id": "w1", "tab_id": "w1:t1"},
	})
	client := NewClient("", socketPath)
	pane, err := client.PaneGet(context.Background(), "w1:p2")
	if err != nil {
		t.Fatalf("PaneGet() error = %v", err)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if pane.ID != "w1:p2" || pane.TerminalID != "terminal-7" {
		t.Fatalf("PaneGet() = %#v", pane)
	}
}

func TestPaneProcessInfoFreshUsesSocket(t *testing.T) {
	socketPath, done := startUnaryTestSocket(t, "pane.process_info", map[string]any{
		"type": "pane_process_info",
		"process_info": map[string]any{
			"pane_id": "w1:p2", "shell_pid": 101, "foreground_process_group_id": 202,
			"foreground_processes": []any{map[string]any{"pid": 202, "name": "vim"}},
		},
	})
	client := NewClient("", socketPath)
	info, err := client.PaneProcessInfoFresh(context.Background(), "w1:p2")
	if err != nil {
		t.Fatalf("PaneProcessInfoFresh() error = %v", err)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if info.PaneID != "w1:p2" || info.ShellPID != 101 || info.ForegroundProcessGroupID != 202 {
		t.Fatalf("PaneProcessInfoFresh() = %#v", info)
	}
}

func TestPaneGetSurfacesPaneNotFound(t *testing.T) {
	socketPath := filepath.Join(testSocketDir(t), "herdr.sock")
	listener, err := net.Listen("unix", socketPath)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()
		var request struct {
			ID string `json:"id"`
		}
		if json.NewDecoder(conn).Decode(&request) != nil {
			return
		}
		_ = json.NewEncoder(conn).Encode(map[string]any{
			"id":    request.ID,
			"error": map[string]any{"code": "pane_not_found", "message": "pane w1:p2 not found"},
		})
	}()
	_, err = NewClient("", socketPath).PaneGet(context.Background(), "w1:p2")
	if !IsPaneNotFound(err) {
		t.Fatalf("PaneGet() error = %v, want pane_not_found", err)
	}
	if IsPaneNotFound(context.DeadlineExceeded) {
		t.Fatal("a timeout was classified as pane_not_found")
	}
}

func TestPaneGetPreservesReportedSessionAndOptionalProcessFields(t *testing.T) {
	for _, kind := range []string{"id", "path"} {
		t.Run(kind, func(t *testing.T) {
			ref := AgentSession{Source: "herdr:codex", Agent: "codex", Kind: kind, Value: "native-reference"}
			path, done := startUnaryTestSocket(t, "pane.get", map[string]any{"type": "pane_info", "pane": map[string]any{"pane_id": "w1:p2", "agent_session": ref}})
			client := NewClient("", path)
			defer client.Close()
			pane, err := client.PaneGet(t.Context(), "w1:p2")
			if err != nil {
				t.Fatal(err)
			}
			if err := <-done; err != nil {
				t.Fatal(err)
			}
			if pane.SessionRaw != ref || pane.Session != ref.Value {
				t.Fatalf("reference lost: %+v", pane.SessionRaw)
			}
		})
	}
	var info PaneProcessInfo
	if err := json.Unmarshal([]byte(`{"pane_id":"p","shell_pid":null,"foreground_process_group_id":null,"tty":"/dev/pts/4","foreground_processes":[{"pid":12,"argv0":"codex"}]}`), &info); err != nil {
		t.Fatal(err)
	}
	if info.ShellPID != 0 || info.ForegroundProcessGroupID != 0 || info.TTY != "/dev/pts/4" || info.ForegroundProcesses[0].Argv0 != "codex" {
		t.Fatal("optional process fields lost")
	}
}

func TestReportedSessionProvenanceIsConsistentAcrossInventoryAndSnapshot(t *testing.T) {
	ref := AgentSession{Source: "custom:codex", Agent: "codex", Kind: "id", Value: "native-reference"}
	for _, method := range []string{"agent.list", "pane.list"} {
		t.Run(method, func(t *testing.T) {
			key, kind := "agents", "agent_list"
			if method == "pane.list" {
				key, kind = "panes", "pane_list"
			}
			path, done := startUnaryTestSocket(t, method, map[string]any{"type": kind, key: []any{map[string]any{"pane_id": "p", "agent_session": ref}}})
			client := NewClient("", path)
			defer client.Close()
			var panes []Pane
			if method == "agent.list" {
				inv, err := client.GetInventory(t.Context())
				if err != nil {
					t.Fatal(err)
				}
				panes = inv.Panes
			} else {
				var err error
				panes, err = client.PaneList(t.Context())
				if err != nil {
					t.Fatal(err)
				}
			}
			if err := <-done; err != nil {
				t.Fatal(err)
			}
			if len(panes) != 1 || panes[0].SessionRaw != ref || panes[0].Session != ref.Value {
				t.Fatal("reference lost")
			}
		})
	}
	raw, _ := json.Marshal(map[string]any{"panes": []any{map[string]any{"pane_id": "p"}}, "agents": []any{map[string]any{"pane_id": "p", "agent": "codex", "agent_session": ref}}})
	var snapshot SessionSnapshot
	if err := json.Unmarshal(raw, &snapshot); err != nil {
		t.Fatal(err)
	}
	panes := NewSessionCache(snapshot).Snapshot().Panes
	if len(panes) != 1 || panes[0].SessionRaw != ref || panes[0].Session != ref.Value {
		t.Fatal("snapshot lost reported source/provider")
	}
}
