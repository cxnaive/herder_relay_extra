# Read-only document chunks · Android 0.2.5 / local Relay codexfix.4

Capability: `workspace_file_read_v1`. Action: `workspace_file_read`, protocol 3,
existing authenticated E2EE connection, exact `target` tuple, current Agent cwd.
No public HTTP file URL, no file write action and no new device enrollment.

First request: `path` is workspace-relative. Further requests repeat the exact
path/target and send `cursor` from `next_cursor`, plus `expected_revision` equal
to the first response's `sha256`.

Response `command_result.data`:

```json
{
  "path": "papers/main.pdf",
  "media_type": "application/pdf",
  "kind": "pdf",
  "data": "<base64 chunk>",
  "size": 3146852,
  "read_only": true,
  "offset": 0,
  "next_cursor": "524288",
  "sha256": "<whole-file SHA-256>"
}
```

- File bound: 12 MiB. Chunk bound: 512 KiB decoded (~683 KiB base64), safely below
  Relay 0.23.5's 4 MiB outbound plaintext budget; global transport limits are unchanged.
- Final `next_cursor` is empty. The client validates path, exact byte offset,
  total length, media kind and SHA-256 across chunks, then the assembled hash.
- The descriptor-rooted inspector refuses traversal, outside-root symlinks,
  non-regular files and oversized files. Target/authorization and runtime-bound
  Codex identity are rechecked after reads. Two concurrent document reads at most.
- Text classification is UTF-8, no NUL, up to 1 MiB; larger binary files can still
  be downloaded but are not rendered as editable text. No `revision` field is
  provided, so this read-only interface cannot accidentally enable Save in old UI.
- This is not `workspace_mobile_files_v1`; that older optional extension also
  implements writes. Do not blindly combine its `mobile-files.patch` with this
  action of the same name. Integrate the two protocols explicitly if needed.

Source patches bundled here, relative to the adjacent Relay source:

1. Existing `codex-resumed-history.patch`: fixes through codexfix.2.
2. `codex-runtime-identity.patch`: daemon/TUI identity fallback, codexfix.3.
3. `reviewer-readonly-files.patch`: bounded document chunks, codexfix.4.

Use `patch --dry-run -p1` on a copy before applying; these local patches target
Relay 0.23.5, not arbitrary upstream releases. The original `web/` bundle is not
modified. The user's live local service already runs the combined patches.

## codexfix.5 后续增量

`history-slow-link.patch` 在上面 3 个补丁之后应用，缩小 history 响应预算并放宽有界的 gateway write/probe 时间。不改变本文文件分片协议或配对。当前正在使用的恢复会话通过本机配置中的显式 terminal/process binding 恢复 ID；该私密运行配置没有随源码分发。
