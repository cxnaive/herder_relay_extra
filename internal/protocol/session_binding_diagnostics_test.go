package protocol

import (
	"encoding/json"
	"testing"
)

func TestSessionBindingDiagnosticsHasNoSelectionCommands(t *testing.T) {
	for _, action := range []string{"get_session_candidates", "select_primary_session", "confirm_primary_session", "pin_primary_session", "codex_status_probe"} {
		if _, ok := ClassifyAction(action); ok {
			t.Fatalf("phone identity selection action remains: %s", action)
		}
	}
	action, ok := ClassifyAction(SessionBindingDiagnosticsAction)
	if !ok || action.Class != ActionReadOnly {
		t.Fatal("diagnostic interface not read-only")
	}
}
func TestSessionBindingDiagnosticsKeepsStructuredExtensionFields(t *testing.T) {
	original := SessionBindingDiagnostics{SchemaVersion: 1, Available: true, Observation: "inventory_snapshot", Target: TargetRef{PaneID: "pane", TerminalID: "terminal"}, Details: map[string]any{"future_evidence": []any{map[string]any{"kind": "relation", "nodes": []string{"root-a", "child-a"}}}}}
	raw, err := json.Marshal(original)
	if err != nil {
		t.Fatal(err)
	}
	var decoded SessionBindingDiagnostics
	if err = json.Unmarshal(raw, &decoded); err != nil || decoded.SchemaVersion != 1 || len(decoded.Details["future_evidence"].([]any)) != 1 {
		t.Fatal("structured diagnostic fields were lost", err)
	}
}
