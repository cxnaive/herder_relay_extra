# Read-only session binding diagnostics

## Boundary

The phone does not discover candidate sessions, analyze subagent ancestry, or
select/confirm a primary. It follows the final identity in Relay inventory.
Ordinary user navigation and generation/lease checks remain; they are not primary
selection algorithms. There is no public candidate-selection or binding-write API.

`get_session_binding_diagnostics` is an optional **read-only diagnostic snapshot**.
It does not trigger runtime discovery, read conversation bodies, send terminal
input, confirm a binding, change a lease, or install hooks. An unresolved/ambiguous
Codex session can be diagnosed. Other providers return `available: false` and
`reason: unsupported_agent`; their normal Herdr Relay behavior is unchanged.

## Request

Capability: `session_binding_diagnostics_v1`.

Authenticated paired reader and controller devices may request it. Anonymous,
bootstrap and unknown roles are denied. Use protocol v3 with an exact `target`
(server, pane, terminal, generation, selected session ID and binding epoch). An
empty selected session ID is valid when the current unresolved inventory also has
an empty ID. Stale or foreign targets are rejected, not redirected to a new pane.

```json
{
  "type": "get_session_binding_diagnostics",
  "protocol": 3,
  "request_id": "diagnostic-1",
  "pane_id": "pane-1",
  "target": {
    "server_session_id": "primary",
    "pane_id": "pane-1",
    "terminal_id": "terminal-1",
    "generation": 2,
    "agent_session_id": "",
    "binding_epoch": 3
  }
}
```

There is no candidate/session override parameter. `target.agent_session_id` is an
expected-current-identity assertion, not an instruction to select that session.

## Response

The normal `command_result.data` contains:

```json
{
  "schema_version": 1,
  "available": true,
  "generated_at": 1700000000000,
  "observation": "inventory_snapshot",
  "inventory_revision": 12,
  "agent": "codex",
  "target": {
    "server_session_id": "primary",
    "pane_id": "pane-1",
    "terminal_id": "terminal-1",
    "generation": 2,
    "agent_session_id": "",
    "binding_epoch": 3
  },
  "binding": {
    "state": "ambiguous",
    "reason": "runtime_ambiguous_no_report"
  },
  "details": {
    "policy": "codex-terminal-roots-v1",
    "selection_source": "",
    "binding_lease_current": false,
    "herdr_report": {"provider": "codex", "kind": "", "id": "", "source": ""},
    "runtime": {"mode": "shared", "process_instance": "", "runtime_instance": "", "store_id": ""}
  },
  "truncated": false
}
```

`generated_at` is response-generation time, not proof that a new process probe ran.
`inventory_revision` identifies the stored observation; `binding.verified_at`, when
present, is its last successful confirmation time. The snapshot is diagnostic,
not new permission to send or approve anything.

`details.runtime.mode` distinguishes `shared` and explicitly detected `standalone`
Codex. `selection_source` distinguishes shared TUI-origin proof from standalone
writer-ownership proof. Empty mode means the inventory has not established it;
the diagnostic call never probes processes to fill it in.

## Extensibility and privacy

Keep the versioned envelope stable and all evidence within the requested terminal
scope. Future bounded nested objects/arrays can be
added under `details`, for example root/child evidence, candidate counts or failure
steps. These remain diagnostic evidence, **not a selectable candidate list**.
Android displays unknown fields as structured JSON and does not use them to update
its selected agent. No new UI parser is required for additive details.

Current diagnostic string fields are capped at 512 UTF-8 bytes. The JSON payload
is capped at 64 KiB; oversized rich details are omitted with `truncated: true`, and
an oversized envelope is rejected. Copy only explicitly allowed identity metadata,
never an entire agent/process object. Do not add transcript, prompt, command-line,
raw environment or credential contents.

The Android “会话绑定诊断” menu fetches this interface and permits local viewing,
search and user-initiated copying. It does not automatically upload to GitHub,
telemetry, or another third-party service. A Relay without this capability prompts
for an upgrade rather than invoking an old discovery/confirmation path.
