# Codex terminal-scoped primary session discovery

Relay codexfix.15 / Pocket 0.2.28 applies this policy **only to `agent == codex`**.
All other providers keep the existing Herdr Relay discovery, session identifiers,
history parsers, and interaction authorization. No Codex runtime or hook report
is required for them.

## Shared app-server selection order

1. Resolve the exact Herdr terminal to its outer native Codex TUI process. Verify
   the foreground process group, tty/session, process ancestry, owner and birth
   identity. Node launchers and helper/zombie descendants are not extra TUI roots.
2. For shared mode, read this process's owned loopback listeners and its configured, allowed Codex
   store. Query the local app-server's loaded-thread metadata and `codex_tui` MCP
   origin associations. **Only origins owned by this TUI process become candidates**;
   the daemon's global loaded list is never a terminal's candidate list by itself.
3. Reduce this terminal's candidates using explicit parent/subagent metadata.
   Every excluded child must reach a root within the same association, without
   missing links, cycles or contradictory session-tree metadata. An independently
   forked conversation remains a root. A single connected root is selected without
   requiring or trusting Herdr's reported ID.
4. If multiple roots remain, validate the fresh Herdr report's provider/kind/UUID
   and match it against those roots. A matching connected root is selected; an ID
   outside the candidate set, a child ID, a missing report or a malformed report
   cannot supply a replacement candidate.
5. If still unresolved, retain no chosen current ID and show a reason. Suggest
   trying `codex --no-daemon` and checking the Herdr hook where appropriate. This
   is guidance, **not a guarantee**. Explicit standalone startup uses the separate
   writer-ownership path below, never this shared-server endpoint.

Names, terminal titles, creation origin, recent activity, direct-input flags,
transcript length and whether a conversation has materialized do not choose a
root. Discovery never sends `/status`, reads a footer ID, installs a dynamic hook,
attaches a debugger, resumes a thread, or invokes a model/tool. Manual pins and
associated-history substitutions are not part of this policy.

In a multiple-root tie, the matching Herdr report is a trusted input, not an
independent TUI selection getter. If Herdr incorrectly names another otherwise
valid root in this same candidate set, these checks cannot establish the actual
TUI selection. The policy must not be described as eliminating that trust boundary.

## Explicit `--no-daemon` / standalone path

An explicit `--no-daemon` option on the verified **native TUI** selects this path.
Arguments after `--`, prompt text, option values, Node wrappers and non-TUI
subcommands cannot enable it. Unknown startup options are handled conservatively;
implicit embedded fallback is not inferred just because a shared RPC fails.

1. Preserve the terminal, foreground group, owner, PID birth and allowlisted
   `CODEX_HOME` checks. Do not require a loopback listener or a shared socket.
2. Read `/proc/<TUI-pid>/fd` and `fdinfo` for this store's
   `thread-writer-locks/<thread-id>.lock`. Require a live, exclusive, whole-file
   kernel **FLOCK** owned by that PID, matching the regular file's device/inode.
   Lock-file existence, another process's locks, read locks, open history files,
   removed/replaced files and symlinked lock namespaces are not proof. Relay never
   opens/acquires/releases/deletes these lock files.
3. Read only exact-ID, contained rollout metadata for those owned threads and
   reduce explicit parent/subagent trees. Unknown classification, incomplete
   ancestry, incompatible session IDs, foreign stores and conflicting project
   metadata fail closed. Independent forks stay roots. A single root can bind
   without a Herdr report, including a resumed session before its next hook runs.
4. A new unmaterialized thread waits for its metadata, normally after the first
   local turn. A lock ID by itself is **not** proof that this is a primary rather
   than a child. Other open roots cannot be ignored because their history is empty.
5. **Multiple standalone roots remain unconfirmed even with a matching report.**
   Original Codex 0.166.0 retained old writer locks after same-process `/new` and
   `/resume`; Herdr's public `agent_session` does not expose a report timestamp,
   sequence or process birth. Its retained report can still name the old root
   before the new SessionStart hook runs. This is deliberately stricter than the
   shared-mode tie-breaker, not a generic hook-only fallback.

Ownership and identity/ancestry metadata are rechecked, including after the final
metadata read. Process, mode, writer inode, store or selected-ID changes revoke
old leases. Bound history/send/approval still work; no mobile selection API is
added. Mode and proof source are included in the existing diagnostic snapshot.

Standalone has no externally queryable settings/turn endpoint in the tested
version: model changes must be made in the computer TUI. Run status uses Herdr with
**unknown timing**, after revalidating the binding. Neither operation may query or
write a same-store shared server just because that socket exists.

To clear retained multiple roots, the user can normally exit the old TUI and run
`codex --no-daemon resume <thread-id>`. A session still owned by another backend
can display an ownership-conflict dialog; Relay does not kill that backend,
interact with the dialog, fork the conversation or alter Codex Desktop settings.

## Phone/API boundary

The phone does not enumerate processes, analyze candidate trees or choose a report.
It consumes the final `agent_session_id`, `binding_state` and `binding_reason` from
normal inventory messages. There is no separate manual-confirmation or candidate-
selection API. The optional [diagnostic snapshot API](session-binding-diagnostics.md) exposes existing inventory metadata as bounded, extensible JSON; it cannot select or confirm a candidate.

Keep normal history/stream/send/approval APIs and the target's `binding_epoch`:
identity and cache scope can stay the same while an old request lease is revoked.
`get_agent_run_state` remains for turn status/timing; `get_agent_settings` and
`set_agent_settings` remain for model/reasoning controls. These features use the
Relay's resolved exact ID, not a requirement that Herdr report the same ID. Store,
process and ancestry data are resolved on the computer, not by the Android app.

## Validation and lifecycle

Runtime association/root metadata is read twice. Child lifecycle changes are
allowed only if the verified root set remains unchanged. Terminal identity,
process birth, store and owned listeners are rechecked after discovery. The
fresh Herdr report must still select the same candidate when needed to break a
tie; an irrelevant report change does not veto a unique runtime root.

The selected exact ID is checked against available history ID/project metadata.
Conflicting metadata fails closed. In shared mode, missing or unmaterialized
history does not erase identity established by live runtime metadata. Standalone
requires an available header to classify each owned thread before selecting a root. Historical
creator names do not establish the current executor.

History reads and protected conversation operations revalidate the binding.
Existing device/role authorization and exact terminal request leases remain in
force; this does not replace them. Workspace/file authorization remains separate
from Codex conversation identity. Changing terminal, process instance, selected
ID, runtime endpoint or proof state invalidates old requests and subscriptions.

## Terminal recovery is not conversation authority

Starting with Pocket 0.2.28 / Relay codexfix.15, an unresolved conversation does
not disable explicit terminal control. These use terminal scope:

- Literal terminal text/input, raw text/keys, constrained filter input and masked
  terminal input; existing character/no-echo validation still applies.
- Explicit size lease and Stop/Restart/Clear. Restart/Clear still require an owned
  launch profile; this does not guess a profile or resume an unknown conversation.

Only an authenticated controller with a ready inventory can perform them. Relay
retains exact server/pane/terminal/generation/session-field/binding-epoch request
checks (the session field may be empty). It freshly reads `pane.get` to verify
terminal incarnation, provider and cwd both at admission and just before writes,
including after queue/capacity waits. Discovery and global loaded-thread matching
are not part of this check. A stale request is not silently retargeted. Lifecycle
confirmations on Android are captured to the original exact target.

This permits manual keystrokes in the currently displayed terminal—including
keys that the program may interpret as a confirmation—but does **not** authorize
Relay-generated approval decisions based on an unverified history. `submit_prompt`,
structured question/approval actions, model changes and conversation reads retain
their primary checks. Unknown mutations remain primary-bound by default. Readers,
unauthenticated clients and uncertain/pending sends do not gain a bypass.

The browser keeps the normal conversation composer and structured controls gated,
but exposes the explicit Keyboard path and Stop/Clear. Android's Terminal composer
sends literal text with Enter separate; its Conversation composer stays gated.
Terminal access neither confirms a primary ID nor discards the user's draft.

## Resume and cache behavior

- Same selected ID: cached content can display immediately, labelled as cached,
  while the source location/current tail refreshes. Old pagination cursors,
  subscriptions and sequence positions are not restored as current.
- Different or now-unresolved ID: revoke old requests and change/isolate the
  history and draft scope. Never display one conversation as another.
- Conflicting metadata: display the error, not cached content as current proof.
- A transient source failure is not proof that Herdr supplied a bad ID.

## Failures and platform boundary

- `runtime_ambiguous_no_report`: multiple roots with no report to break the tie.
- `reported_session_not_candidate`: report does not match these terminal-scoped roots.
- `herdr_session_invalid`: malformed/provider-mismatched report needed for a tie.
- `runtime_ancestry_unavailable`: missing, unclassified or cyclic ancestry.
- `runtime_metadata_conflict` / `process_project_conflict`: conflicting scope data.
- `runtime_unavailable` / `runtime_disconnected`: no usable runtime association.
- `runtime_changed`: terminal/process/association/selection changed during validation.
- `runtime_platform_unsupported`: process proof is not implemented on that platform.
- `standalone_runtime_unavailable`: no readable, valid writer ownership proof.
- `standalone_metadata_unavailable`: owned thread metadata is not readable yet.
- `standalone_ambiguous`: multiple roots; retained hook reports cannot break this tie.
- `standalone_ancestry_unavailable` / `standalone_metadata_conflict`: incomplete or
  conflicting standalone identity/parent/project/store metadata.

Process/socket proof currently targets Linux; no root or process injection is used.
Do not promise that `--no-daemon` repairs malformed metadata or enables unsupported
platforms. The Relay does not change startup arguments, stop a daemon, restart an
agent or change Codex Desktop settings automatically.

## Verification and release

Synthetic tests cover terminal isolation, unique roots without reports, child
ancestry, independent forks, multiple-root report matching, incomplete/cyclic
metadata, changing reports/processes, missing history, lease replacement and the
non-Codex bypass. Standalone coverage includes real kernel locks, first-turn
metadata, unrelated shared sockets, child/fork reduction, stale reports, process/
mode/inode changes, settings isolation and unknown timing. Runtime reads are metadata-only; private live audits never enter
public fixtures. Same-ID source/cache refresh has separate reader/mobile coverage.

Source and published versions are separate. Distribution remains CI-only; this
change does not deploy or restart Relay, Gateway, Herdr, Codex or the desktop app.
