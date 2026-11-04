# Primary Codex session binding

This implementation targets same-user Linux/WSL Codex TUIs in the existing Herdr inventory. It does not change Gateway registration, pairing identities, encryption, or device roles. No new dependency or Herdr-managed hook is required.

## Evidence, not labels

1. Preserve Herdr's reported `{source, agent, kind, value}` consistently through list/get/snapshot adapters. A report is a candidate, not current-thread authority.
2. Read `pane.process_info`, then validate native process ownership using `/proc`: user/PID namespace, shell, process group, controlling terminal, parent chain, executable and process birth. Select the outer native Codex, not its Node launcher or tool/delegated descendants. Recheck before publishing a binding.
3. Query only the native process's configured, allowed Codex store. Each store has an independent timeout; at most four store queries run concurrently. A daemon elsewhere in the home directory does not impose a global requirement on other terminals.
4. Enumerate runtime associations completely (64 threads per page, 256 total). Reject truncated, duplicated or cyclic inventories and changed primary selections; explicitly proved child lifecycle changes do not invalidate a stable primary. The owned loopback socket and its inode must remain current.
5. Accept one primary candidate only. Explicit child ancestry must lead to that primary on the same endpoint before a child can be excluded. Unnamed threads, `canAcceptDirectInput`, and missing parent fields do not resolve ambiguity. Fork ancestry alone is not delegation. Metadata reads never request turns or start/resume/focus threads.
6. Cross-check the selected thread with its provider store and rollout metadata. A conflicting Herdr report is diagnostic only after this independent verification succeeds. Never choose another thread by title, recency or directory similarity.

The existing runtime interface is an association interface, not an authoritative frontend `activeThread` API. Ambiguous or unclassified associations remain unavailable. A future explicit frontend-selection integration would improve coverage; this implementation does not invent that capability or promise every possible TUI session can be discovered automatically.

## One registry; two kinds of change

`codexBindingRegistry` retains the last verified tuple separately from its current availability. It is process-memory state, not persisted authorization.

- `generation` is the existing identity revision. A real thread/process/store/terminal replacement invalidates the old identity. The same verified session disappearing temporarily and returning does not advance it.
- `binding_epoch` is a revocable request lease. Losing proof, changing proof scope, or recovery changes it. Old requests/responses cannot become valid again merely because the same thread was rediscovered. Epochs are initialized independently on Relay restart and remain exactly representable by JSON clients.
- The encrypted transport's existing connection lifecycle remains separate. No credentials or pairing state are replaced by a binding epoch.

The additive `primary_session_binding_v1` capability advertises the state/lease semantics on supported Linux/WSL hosts. Target-bearing clients must echo `binding_epoch`. Android and the bundled web target adapters do so. A client that discards a nonzero epoch is refused rather than silently weakening target validation.

| Binding state | New history reads | Mutations to the current Agent |
| --- | --- | --- |
| `verified` | Allowed after current-proof checks | Existing authorization plus fresh proof at dispatch |
| `verifying` | Not yet available | Denied |
| `stale` | Existing client cache only, explicitly labelled | Denied |
| `ambiguous` / `conflict` | No candidate history; old cache retains its old identity | Denied |
| `pinned_history` | Existing operator-selected history after independent revalidation | Denied |

Existing local pin files are retained, not rewritten. Their terminal/process/store/title fences are still checked. Pins are a read-only historical fallback, not proof that sending input to the live terminal would reach that thread. This change does not add a new general-purpose history picker or manage subagent conversations.

## History, UI and commands

- Android keeps a selected page across availability-epoch changes when its stable identity is unchanged; cancels old subscriptions/callbacks; retains process-memory history and the existing draft/delivery storage keys. True identity changes still require reopening the target.
- A first verified binding may open a waiting, previously unbound page only for the same terminal/workspace and the expected identity revision.
- Android displays a binding status and a metadata-only diagnostic view. It disables sending, approvals and live model settings when the current primary cannot be verified.
- Cached history is not a source of current write permission. Manual pins do not receive live provisional streams.
- Workspace/document reads validate their terminal and workspace independently, while preserving existing file-root and device authorization.
- Queued Agent mutations recheck both the exact target lease and current runtime proof at dispatch. The copy-response command preserves that dispatch context as well.
- Managed Codex status comes from the verified primary's runtime metadata. Unbound hook status cannot mark a child completion as a primary completion. Historical activity extracts are not reconstructed from a reused pane's current thread.

## Scope and limitations

Linux/WSL kernel ownership checks are the supported discovery path. Other providers and the existing reported-session path on other platforms retain their behavior; this change does not establish equivalent native-Windows, container/cross-namespace, cross-user or macOS discovery support. Unavailable OS/runtime evidence fails closed. Existing fallback/pin code must not be mistaken for a new cross-platform automatic discovery contract.

No ptrace, process injection, terminal-body matching, full environment logging, automatic thread switching, Gateway redesign, or child-agent UI is introduced. Parent/child relationships that the installed runtime does not expose cannot safely be inferred from names.

## Verification and deployment

Regression coverage includes:

- report provenance through all Herdr read paths;
- native/Node launchers, helper and zombie children, nested Codex, wrong TTY/parent/group, and process reuse;
- complete pagination, unnamed competing threads, explicit child ancestry, metadata mismatch and independent store failures;
- stable identity across miss/recovery, revoked queued targets, real replacements, manual-read-only bindings, and hook completion isolation;
- Android cache/draft identity, first discovery, lease-bound callbacks, and non-writable uncertainty states;
- bundled web target forwarding, route identity versus request leases.

Live diagnostics are independent read-only executables. They do not replace the running service or write session bindings. Machine-specific evidence, IDs and transcripts do not belong in public fixtures.

Deploy the updated Android client and Relay together after review; the Relay's bundled web assets must be regenerated with `make web-release`. Old services, pairing files, signing material and release assets remain untouched until a separate deployment/release request.
