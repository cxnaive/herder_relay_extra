package app

import (
	"context"
	"encoding/json"
	"github.com/0cv/herdr-mobile-relay/internal/conversation"
	"github.com/0cv/herdr-mobile-relay/internal/coordinator"
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestRecordedFeedRecoversCommentaryAndImageCallsAcrossLargeRawResults(t *testing.T) {
	home := t.TempDir()
	session := "123e4567-e89b-12d3-a456-426614174000"
	path := filepath.Join(home, ".codex", "sessions", "2026", "11", "03", "rollout-"+session+".jsonl")
	message := func(text string) map[string]any {
		return map[string]any{"type": "response_item", "payload": map[string]any{"type": "message", "role": "assistant", "content": []map[string]any{{"type": "output_text", "text": text}}}}
	}
	writeInvariantRows(t, path, message("fixture first"))
	server := testServer()
	if server.conversationB != nil {
		server.conversationB.Close()
	}
	reader := conversation.NewReader(home)
	browser, err := conversation.NewBrowser(reader, t.TempDir(), conversation.DefaultBrowserOptions())
	if err != nil {
		t.Fatal(err)
	}
	defer browser.Close()
	server.conversationM = reader
	server.conversationB = browser
	server.state.CommitInventory([]*coordinator.AgentState{{PaneID: "fixture-pane", Agent: "codex", Cwd: "/fixture", SessionID: session, AgentSessionID: session, TerminalID: "fixture-terminal", ServerSessionID: "primary", Status: "working"}}, server.state.RevisionCounter())
	agent, _ := server.state.Agent("fixture-pane")
	target := protocol.TargetRef{PaneID: agent.PaneID, TerminalID: agent.TerminalID, Generation: agent.Generation, ServerSessionID: agent.ServerSessionID, AgentSessionID: session}
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	feed := &conversationFeed{ctx: ctx, cancel: cancel, target: target, agent: agent, ready: make(chan struct{}), notify: make(chan struct{})}
	done := make(chan struct{})
	go func() { defer close(done); server.runRecordedConversationFeed(feed) }()
	defer func() { cancel(); <-done }()
	select {
	case <-feed.ready:
	case <-time.After(3 * time.Second):
		t.Fatal("initial source did not load")
	}
	feed.mu.Lock()
	initial := feed.sequence
	caps := feed.capabilities
	feed.mu.Unlock()
	if caps.Source != "recorded_history" || caps.ActiveBranch {
		t.Fatalf("misleading capabilities: %+v", caps)
	}
	file, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	encoder := json.NewEncoder(file)
	for _, record := range []map[string]any{
		{"type": "response_item", "payload": map[string]any{"type": "function_call", "call_id": "image-call", "name": "view_image", "arguments": `{"path":"/fixture/chart.png"}`}},
		{"type": "response_item", "payload": map[string]any{"type": "function_call_output", "call_id": "image-call", "output": []map[string]any{{"type": "input_image", "image_url": "data:image/png;base64," + strings.Repeat("A", 512*1024)}}}},
		message("fixture middle commentary"),
		{"type": "response_item", "payload": map[string]any{"type": "function_call", "call_id": "shell-call", "name": "exec_command", "arguments": `{"cmd":"echo fixture"}`}},
		{"type": "response_item", "payload": map[string]any{"type": "function_call_output", "call_id": "shell-call", "output": strings.Repeat("log", 100000)}},
		message("fixture second"),
	} {
		if err := encoder.Encode(record); err != nil {
			t.Fatal(err)
		}
	}
	file.Close()
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		feed.mu.Lock()
		sequence := feed.sequence
		var batch conversationBatch
		if len(feed.batches) > 0 {
			batch = feed.batches[len(feed.batches)-1]
		}
		feed.mu.Unlock()
		if sequence > initial {
			if batch.Kind != "batch" {
				t.Fatalf("unexpected reset across append: kind=%s", batch.Kind)
			}
			middle, image, latest := false, false, false
			for _, entry := range batch.Entries {
				if entry.Text == "fixture middle commentary" {
					middle = true
				}
				if entry.Text == "fixture second" {
					latest = true
				}
				for _, tool := range entry.Tools {
					if tool.Name == "view_image" {
						image = true
					}
				}
			}
			if !middle || !image || !latest {
				t.Fatalf("lost intervening content: commentary=%v image=%v latest=%v entries=%d", middle, image, latest, len(batch.Entries))
			}
			time.Sleep(300 * time.Millisecond)
			feed.mu.Lock()
			unchanged := feed.sequence == sequence
			feed.mu.Unlock()
			if !unchanged {
				t.Fatal("unchanged source produced updates")
			}
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("appended record did not publish")
}
