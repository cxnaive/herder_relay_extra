package conversation

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestCodexImageResultNeverSerializesBase64AsToolText(t *testing.T) {
	for _, mixed := range []bool{false, true} {
		output := []any{map[string]any{"type": "input_image", "image_url": "data:image/png;base64," + strings.Repeat("A", 512*1024)}}
		if mixed {
			output = append(output, map[string]any{"type": "text", "text": "image caption"})
		}
		text := codexToolOutput(output)
		if len(text) > 100 || strings.Contains(text, "base64") || (mixed && !strings.Contains(text, "image caption")) {
			t.Fatal("image payload leaked into tool detail text")
		}
		call := map[string]any{"type": "response_item", "payload": map[string]any{"type": "function_call", "call_id": "view", "name": "view_image", "arguments": `{"path":"/fixture/plot.png"}`}}
		result := map[string]any{"type": "response_item", "payload": map[string]any{"type": "function_call_output", "call_id": "view", "output": output}}
		a, _ := json.Marshal(call)
		b, _ := json.Marshal(result)
		entries := parseTranscript("codex", string(a)+"\n"+string(b))
		if len(entries) != 1 || len(entries[0].Tools) != 1 || entries[0].Tools[0].Input != `{"path":"/fixture/plot.png"}` || entries[0].Tools[0].Output != text {
			t.Fatal("image call/path/result association lost")
		}
	}
	if got := codexToolOutput(map[string]any{"result": "ordinary json"}); !strings.Contains(got, "ordinary json") {
		t.Fatal("ordinary output changed")
	}
}
