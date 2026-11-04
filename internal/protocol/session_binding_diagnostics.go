package protocol

const SessionBindingDiagnosticsCapability = "session_binding_diagnostics_v1"
const SessionBindingDiagnosticsAction = "get_session_binding_diagnostics"

// SessionBindingDiagnostics is a read-only inventory snapshot, never a binding
// command. Details may gain bounded nested fields without changing the envelope.
type SessionBindingDiagnostics struct {
	SchemaVersion     int                            `json:"schema_version"`
	Available         bool                           `json:"available"`
	Reason            string                         `json:"reason,omitempty"`
	GeneratedAt       int64                          `json:"generated_at"`
	Observation       string                         `json:"observation"`
	InventoryRevision int64                          `json:"inventory_revision"`
	Agent             string                         `json:"agent"`
	Target            TargetRef                      `json:"target"`
	Binding           SessionBindingDiagnosticResult `json:"binding"`
	Details           map[string]any                 `json:"details"`
	Truncated         bool                           `json:"truncated"`
}

type SessionBindingDiagnosticResult struct {
	State      string `json:"state"`
	Reason     string `json:"reason,omitempty"`
	VerifiedAt int64  `json:"verified_at,omitempty"`
}
