# Herdr Pocket — UI design system

## Product and use scene

A native Android remote control for coding agents: check which workspace/Tab owns a task, read long streamed answers, review images/files, and send a short follow-up from a phone. Mixed Chinese/English filenames and long task names are normal. Reading and operating are the priorities; this is not a promotional dashboard.

The existing dark identity is retained for long reading sessions, with a quieter neutral surface palette. No new claims, agent behaviors, transports or data access are introduced by the visual pass.

## Direction

- Flatten the agent directory: one workspace heading, one agent title, state metadata and clear actions. Do not repeat the Tab title above a card with the same name.
- Keep the conversation dominant. No decorative avatar before every assistant message. Put the running state and true elapsed time in a compact row; preserve explanatory text for unavailable/offline/waiting/error states and accessibility descriptions.
- Use one clear primary action per local task; secondary actions are text or tonal buttons, not an equal row of bright blocks.
- Use authored vector icons with a consistent stroke, not platform-dependent Unicode glyphs for navigation, files or disclosures. Key labels and textual operators in the terminal remain text.
- Preserve two-level tool disclosure, visible assistant text/images, scrolling, drafts, file links, download/cancel/rename behavior, permissions and exact Agent identity.

## Tokens and components

`AgentStyle.kt` owns background/surface/raised/ink/muted/accent/onAccent/outline/warning/error roles and shared control styling. `res/values/styles.xml` maps the same palette into Android windows, native controls and dialogs.

| Role | Value |
| --- | --- |
| Background | `#111417` |
| Surface / raised | `#1B2025` / `#252C32` |
| Primary text / secondary | `#EDF0F3` / `#A5AFB9` |
| Action accent / on-accent | `#A6DBC9` / `#112B23` |
| Outline / selected | `#354049` / `#263B34` |
| Waiting / error | `#EBC18C` / `#FFB0AC` |

System sans typography, all sizes in sp: page title 24, workspace title 18, agent title 17, transcript body 16, controls/status 13, supporting labels 11–12. Medium weights establish hierarchy; monospace is reserved for actual code/terminal output. `PocketMarkdown` shares link, heading, code and block spacing across transcript/file readers.

Spacing uses a 4dp rhythm, typically 16–20dp page gutters and 8dp control spacing. 48dp minimum touch targets for shared actions; composer remains compact at normal font scale without shrinking controls. Large-text and offline states may grow rather than clip.

Rounded corners: 8–12dp for tabs/buttons/surfaces, 16dp for the message composer. No drop shadows or nested decorative cards. Neutral dividers separate directory rows.

## Responsive behavior and accessibility

Compact windows retain three bottom navigation destinations. At 600dp+ these become a left navigation rail. Reading columns are bounded on larger windows. System bar/IME insets and existing Back behavior are retained; the keyboard must not remove the send action or eliminate the transcript viewport.

Selected states use shape/text as well as color. Navigation and disclosure controls have button semantics and accessibility names; expanded/collapsed descriptions are exposed where supported. Existing system font scaling is retained; primary/secondary/link text color combinations are tested at 4.5:1 or better. This pass is a deliberate dark scheme, not a claimed light/dynamic-color implementation.

## Method and scope

Applied the Impeccable native Android guidance and its critique/quieter/typeset/layout/polish approach from `pbakaus/impeccable`, revision `9fadc41729c38fd47d7ae622b501558653f9d2e9` (Apache-2.0). Reference repository supplied by the user. Native Operate/Read assessment was performed locally; no browser-only detector was used or claimed to validate Android. The launcher was not installed/run. No Impeccable runtime or third-party UI dependency is bundled in the APK.

This document records decisions derived from the existing product and code, not a new externally approved product brief.

## Verification

`ImpeccablePolishUiTest` captures synthetic native Views/Canvas screens at 400×850dp, 360×800dp with 1.3× fonts, and 800×600dp, plus a keyboard-height simulation. Screens: agent list, conversation, nested tools, terminal, workspace, devices, file reviewer. `PocketStyleTest` checks text contrast, minimum action sizes, compact input/status sizing, and retained draft/action semantics.

Evidence is Robolectric native rendering, **not a connected phone/emulator**. There was no attached device or installed emulator during this pass; no hardware gesture/performance/TalkBack certification is claimed. Existing TCP/E2EE, file transfer, notification, reviewer and history regressions still run. Physical Android QA remains necessary before claiming full device acceptance.

Question round skipped: this was an implementation request on an established native product; task, audience and core constraints were inferable from the existing application and conversation. Physical-device acceptance is explicitly deferred, not implied by screenshot checks.


## 0.2.23：电脑与工作空间层级

- 电脑使用独立低对比度标题面板，工作空间缩进，Agent 再缩进；每级均可单独折叠，避免多电脑同名项目混在一起。
- 折叠工作空间仍显示路径、Agent 数量和独立的「＋ Agent」；标题整块可点击，箭头只作为状态指示，不把命中范围缩到小图标。
- 搜索临时展开，不覆盖持久折叠偏好；折叠标题在布局完成后恢复滚动锚点，辅助功能提供展开/折叠动作。
- 界面分组与连接/监控状态分离，收起不是断开；签名、配对和文件访问边界不变。详见 `AGENT-GROUPS.md`。
