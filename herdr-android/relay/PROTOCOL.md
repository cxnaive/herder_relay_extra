# Workspace mobile files v1

Capability: `workspace_mobile_files_v1`. Protocol: 3. Transport: existing authenticated E2EE v2 channel. This extension adds no public HTTP file endpoint.

Both operations carry the same `pane_id` and exact `target` tuple as the relay's other workspace commands. `cwd` is the Agent's current workspace from its snapshot, not a client-selected filesystem root. Responses are ordinary correlated `command_result` messages.

```json
{
  "type": "workspace_file_read",
  "protocol": 3,
  "request_id": "unique-id",
  "pane_id": "pane-id",
  "cwd": "/home/dev/project",
  "target": {
    "server_session_id": "primary",
    "pane_id": "pane-id",
    "terminal_id": "terminal-id",
    "generation": 1,
    "agent_session_id": "agent-session-id"
  },
  "path": "docs/README.md"
}
```

`workspace_file_read` is read-only and permitted for readers and controllers. File size is at most 12 MiB, keeping its base64 JSON payload below the upstream 21 MiB logical-frame limit. Result `data` is an object containing `path`, `media_type`, `kind` (`text`, `pdf`, `image`, `binary`), `size`, base64 `data`, and hex SHA-256 `revision`. UTF-8 text is classified editable only up to 1 MiB and without NUL bytes.

`workspace_file_write` uses the same envelope and adds `text` (including the empty string when clearing a file) and `expected_revision`. Controller access and protocol 3 are mandatory. The server checks the current Agent workspace, reads the existing regular UTF-8 file, validates the hash, writes a same-directory temporary file, checks the original file identity/hash again, revalidates authorization and target, then renames the temporary file. The response contains the same file object with the new revision. Attempts/results use the existing remote-write audit without recording text content.

Path traversal, external-root access, final symlinks, any symlink component on writes, `.git` edits, oversized content and stale revisions are rejected. A connection loss after commit is an uncertain result: clients retain the draft and re-read; they must not silently replay the save.
