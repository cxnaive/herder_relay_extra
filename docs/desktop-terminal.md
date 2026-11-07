# Desktop direct terminal

The desktop is an independent Go module in `desktop/`, using MyGo v0.8.1 native UI and its Ghostty terminal plugin. It reuses Kotlin Android's TCP invitation, E2EE v2, exact target and uncertain-delivery contracts. The main interface has no JavaScript frontend; PDF previews use a private embedded system WebView with the Go bridge disabled.

## Relay extension

`desktop_terminal_v1` is advertised only on Linux/macOS when the Herdr server reports private protocol 22. The adapter connects to the sibling `*-client.sock` Unix socket, negotiates `TerminalAnsi`, and sends `AttachTerminal` with takeover disabled. It does not spawn a shell, send a prompt, or change the PTY through ioctl.

| Request | Fields beyond the existing exact target | Result |
| --- | --- | --- |
| `terminal_open` | `columns`, `rows` | `stream_id` |
| `terminal_input` | `stream_id`, `terminal_sequence`, base64 `data` | Command result |
| `terminal_resize` | `stream_id`, `terminal_sequence`, `columns`, `rows` | Command result |
| `terminal_scroll` | `stream_id`, `terminal_sequence`, signed `lines` | Command result |
| `terminal_close` | `stream_id` | Owner-scoped cleanup |

Mutations require a paired controller and an exact live pane/terminal target. Terminal operations do not require a confirmed Codex conversation; they cannot grant conversation authority. Every operation rechecks device authorization and the live `pane.get` result. Input sequences start at 1 and cannot skip or repeat. Failed writes close the stream and never replay. Open is audited; raw terminal input is not included in write audit records.

`terminal_output` events carry `stream_id`, sequence starting at 1, geometry and base64 ANSI bytes. Native render frames must start with a complete frame and consecutive sequence. Mouse and keyboard modes are translated into terminal escapes; unsupported notifications/clipboard messages are not acted on. The desktop disallows remote clipboard writes. Output and packet buffers are bounded; overload fails closed. `terminal_closed` permits reconnecting, leaving the remote Agent alive.

One stream is allowed per transport client, at most 64 per Relay. Disconnect, target replacement and revoked device authorization release it. The desktop creates `terminal.Terminal` with `Conn` and `ResizeByConn`, so Herdr owns the final dimensions. Scroll is forwarded semantically to Herdr's scrollback.

## Files

The desktop uses existing `workspace_list`, `workspace_file_read` and `workspace_file_download` actions with `file_scope=workspace` and the captured exact target on every request. Directory responses are checked for path containment, duplicate rows, cursor progress and unchanged revisions. Preview chunks require a single complete SHA-256 digest. Original downloads require an unchanged download revision, exact offset/size/path and a matching per-chunk digest. A local temporary file is renamed into the user-selected destination only after successful completion.

## Validation

Windows runs the desktop's headless native UI, Ghostty stream, transport and file tests. Linux/WSL runs the Unix socket adapter and existing Codex terminal authority tests. The root server module remains Linux/macOS only; its Windows syscall build failures are unrelated to the desktop's independent module. A socket fixture is not a deployment test: validate against a live protocol-22 Herdr before distributing this preview.
