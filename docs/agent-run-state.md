# Native conversation run status and elapsed time

Current extension: Android **0.2.31 (versionCode 34)** + computer Relay
**0.23.5+codexfix.19**. Gateway and pairing are unchanged.

## What the phone shows

The conversation and terminal detail pages have a fixed status strip above the
scrolling content, separate from history-loading/synchronization messages:

- **运行中**: highlighted text and progress indicator, plus `本轮 MM:SS` (or
  `HH:MM:SS`) when the actual current turn start is available.
- **等待审批 / 等待输入 / 需要处理**: amber state, no running animation.
  The timer is explicitly the whole turn's elapsed time, including tool execution
  and waiting, not CPU time or uninterrupted model-generation time.
- **已完成 / 已中断 / 运行失败**: stop incrementing and retain the last turn duration.
- **空闲**: no active turn. A terminal/session that disappeared is marked as ended
  or replaced, not kept running based on old history.
- **连接中断 / 运行状态待确认**: stop animation and extrapolation; show only the last
  server-confirmed duration, clearly labeled. Reconnect refreshes the real state.

Unknown start times are not shown as zero. Other agent types and older Relays can
still display Herdr status, but the UI says reliable turn timing is unavailable.
The generic `updated_at`, last output time, total thread age and phone page-open
time are never used as turn start.

## Data path

New read-only authenticated command `get_agent_run_state` and capability
`agent_run_state_v1`. Request includes the exact existing pane target tuple.
Both readers and controllers may read it; it performs no mutations.

For shared-runtime Codex, the Relay resolves the exact terminal-owned local daemon using the same
live pane/process/thread guard as model settings. It reads:

1. `thread/read` with `includeTurns:false` (metadata only);
2. `thread/turns/list`, `limit:1`, `sortDirection:desc`, `itemsView:notLoaded`;
3. another metadata read and the live target/authorization guard.

It never reads full turn items, resumes/starts a thread, alters model settings,
or sends terminal input. RPC messages are
bounded to 128 KiB, each request has a four-second budget and four local query
slots. Unsupported turn-header APIs still allow a status without a fabricated timer.
Concurrent status transitions discard the uncertain timing. The start/end seconds
and supplied duration milliseconds are validated; completed duration stays fixed.

For standalone/embedded Codex (including `--no-daemon`), there is no external
turn endpoint. The Relay verifies the existing TUI/writer/primary binding and
reads **lifecycle metadata only** from that exact rollout: `task_started` /
`task_complete` (and `turn_started` / `turn_complete` aliases), `turn_aborted`,
turn ID, start/end/duration and error presence. Transcript/tool text is not used
as proof. Each new read is bounded to a 4 MiB tail; a 128-entry inode/size/mtime
cache parses bounded appends incrementally, retaining a known start through long
tool output. Rotation, partial appends, conflicts and missing records do not
reuse a previous completion. A fresh reader whose start lies outside the window
may report unknown timing until another lifecycle record arrives.

Inventory includes optional `run_state` for desktop notifications. Its lifecycle
state is reconciled with shared-runtime status when present. Terminal idle/done
cannot end a lifecycle-active standalone turn; a positive busy signal vetoes an
old persisted completion. A recorded unmatched old start cannot itself prove
liveness after a crash/resume; these records are not an atomic TUI status API.

Response fields include `status`, `source`, optional `attention_kind`/`turn_id`,
`started_at`/`finished_at` (epoch ms), `elapsed_ms` (nullable), `ticking`,
`observed_at`, `evidence` (`runtime_turn`, `rollout_lifecycle`, `unavailable`),
`completion_confirmed`, `completion_pending` and `timing` (`turn_elapsed` or `unknown`). These are observation-time
snapshots, not promises that the remote process remains running afterward.

The phone polls only the open agent, roughly every five seconds with at most one
in-flight status request for the current view. It advances verified live duration
using Android's monotonic clock once per second, not the phone wall-clock. After
15 seconds without a valid state sample it stops extrapolating. Each callback is
fenced to view epoch, request identity, pane/session tuple and intervening status
changes. Leaving the page or backgrounding stops timers; reconnect cannot revive
an old callback. A tick updates the status strip only, without rebuilding history
or moving its scroll position.

## Completion notification boundary

- Only a verified exact target may supply Codex completion evidence. An idle
  snapshot is not a completion record. Success must include a valid turn ID;
  failed/aborted turns never emit successful completion.
- A just-finished turn stays pending (`completion_pending:true`) for at least three seconds to suppress
  immediate continuations. Android/desktop require observing that **same turn**
  working/blocked on the same binding, with the same evidence source, followed
  by `done` plus `completion_confirmed`. A pending snapshot cannot establish a
  new active baseline. Unknown observations invalidate it.
- Android active observations expire after 90 seconds, are not restored from
  disk, and are cleared on disconnect/rebinding. Durable recent completion IDs
  still suppress duplicate delivery. Desktop clears run observations on reconnect.
- Very short turns entirely between polls, offline completions and missing
  lifecycle records can miss notifications. This deliberately favors avoiding
  false completion over speculative catch-up. Other agent providers keep their
  existing Herdr rules. Old Relays cannot supply trusted Codex completion proof.
- A notification means **this turn completed**, not that an entire goal/workflow
  cannot continue. Neither polling nor a three-second grace can predict a future
  autonomous continuation. `--no-daemon` timing is whole-turn wall time, including
  tools/approvals, not token-generation or CPU time.

## Validation

- Local Codex metadata fixtures: real turn start, waiting flags, completed/failed/
  interrupted fixed duration, invalid/future/missing timestamps, wrong target,
  metadata transitions, unsupported API and unexpected page size.
- Android: monotonic timing, duration formatting, re-opening an already-running
  turn, new-turn reset, unknown timing, stale/disconnected status, source labeling,
  real detail view, non-rebuilt scrolling content, no duplicate in-flight requests,
  late callbacks after navigation/disconnect/pause and narrow screen rendering.
- Local live read-only QA distinguishes active turns from finished turns and
  verifies duration progresses only on active turns. No real agent was started,
  stopped, renamed or reconfigured for testing.
- No physical phone attached. Native graphics/Robolectric screenshots are test
  fixtures, not proof of on-device lifecycle/network behavior.

Only the computer Relay needs restarting. Cover-install the same-signed APK;
**no re-pairing** or public Gateway change is needed.
