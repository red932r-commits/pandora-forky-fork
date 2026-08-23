---
target: Pandora product exploration
total_score: 24
max_score: 40
na_heuristics:
p0_count: 0
p1_count: 5
timestamp: 2026-08-23T18-19-35Z
slug: p-src-main-java-com-pandora-mobile-mainactivity-kt
---
# Pandora Product Critique and Feature Opportunities

## Design Health Score

| # | Heuristic | Score | Key issue |
|---|---|---:|---|
| 1 | Visibility of System Status | 3 | Strong working, ready, stopped, setup, backup, download, and Voice Mode feedback; some consequential transitions lack explicit confirmation. |
| 2 | Match System / Real World | 2 | Developer vocabulary such as Codex OSS, ADB, `/root`, ARM64, provider endpoints, and inference diagnostics is exposed early. |
| 3 | User Control and Freedom | 2 | Stop, back, cancel, archive, and retry exist, but provider switching and backup can stop active work without a consistent preflight contract. |
| 4 | Consistency and Standards | 3 | Cohesive Compose components overall, with inconsistent target sizes and a terminal interaction layer that behaves like a separate product. |
| 5 | Error Prevention | 3 | Destructive confirmations, transactional restore, validation, and archive-before-delete are strong; some high-impact side effects remain secondary copy. |
| 6 | Recognition Rather Than Recall | 2 | Project/chat actions rely on long press, slash commands appear only after typing `/`, and multi-app setup flows require memory. |
| 7 | Flexibility and Efficiency | 2 | Persistent sessions and slash commands help experts, but there is no universal search, task triage, global palette, bulk action, or native diff surface. |
| 8 | Aesthetic and Minimalist Design | 3 | Calm and legible, but Settings and Voice are long configuration walls and completed chat output becomes visually exhausting. |
| 9 | Error Recovery | 2 | Repair, archive, and restore are thoughtful, but failed chats, terminals, permissions, downloads, and archive loads often lack actionable recovery. |
| 10 | Help and Documentation | 2 | Onboarding and ADB setup are helpful; contextual explanations for security, locality, system health, and advanced terms are missing. |
| **Total** |  | **24/40** | **Acceptable — strong foundation, substantial experience work remains.** |

## Design Specificity Verdict

**Product-specific architecture, category-generic presentation.** Pandora is genuinely authored around a distinctive idea: persistent local agent workspaces where projects, chats, terminals, voice, artifacts, and device control coexist. That architecture could not be transplanted unchanged into a generic chat app.

The visual and interaction layer does not yet express that advantage. Home reads as a sparse chat list; Settings reads as an infrastructure inventory; completed work resolves into long Markdown rather than a confident outcome. The biggest opportunity is to make Pandora feel like an **agent operations system**, not ChatGPT plus a terminal.

**Deterministic scan:** The bundled detector returned exit code 0 and `[]` — zero findings, rules, locations, or false positives. This is not an accessibility clearance. Source and emulator evidence found native-specific gaps the detector does not model: an opaque custom-drawn terminal for screen readers, unlabeled switches, segmented controls without selected semantics, visual-only async status, and underspecified recovery states.

**Visual evidence:** Browser overlays are inapplicable to this native Jetpack Compose surface. Emulator screenshots and UIAutomator trees were used instead; no user-visible overlay was created. Two clipped chat action nodes marked NAF by UIAutomator were false positives because source labels them; the Skills and Voice switch findings were genuine.

## Overall Impression

Pandora already has the hard systems work: durable concurrent Codex sessions, project-scoped workspaces, a real Debian PTY, local speech, backup/restore, custom providers, and opt-in phone control. Its next leap is not more raw capability. It is making ongoing work easy to find, supervise, review, recover, and safely authorize from a phone.

Configuration surfaces reach high cognitive load: six of eight checklist items fail. Grouping and chunking are generally good, but single focus, visual hierarchy, one-decision-at-a-time flow, minimal choices, working-memory demands, and progressive disclosure need work.

## What's Working

1. **Durable workspace model.** Projects contain chats, terminals persist, general chats remain isolated, concurrent work survives navigation, and larger screens already have a two-pane path.
2. **System-state coverage.** Starting, Working, Ready, Failed, Stopped, setup, download, backup, ADB, dictation, and Voice Mode states are unusually well represented.
3. **Recovery and safety foundations.** Archive is reversible, restore is staged, provider keys use Android Keystore, backups are clearly labeled sensitive, and phone control has a visible safety frame.

## Priority Issues

### [P1] Home is a list, not mission control

**Why it matters:** Concurrent agents are Pandora's defining capability, but task state is distributed across chronological rows. As projects grow, users cannot quickly answer: What is working? What needs me? What finished? What changed?

**Fix:** Add Working, Needs attention, Ready, and Recent lanes; show the last meaningful event, elapsed time, artifact/change counts, and one-tap resume/stop/retry.

**Suggested command:** `$impeccable shape`

### [P1] Essential workspace actions are undiscoverable

**Why it matters:** Rename, pin, archive, stop, and delete are hidden behind long press. That violates recognition over recall and blocks motor/accessibility users from discovering core organization controls.

**Fix:** Add visible 48dp overflow buttons and expanded/collapsed semantics to project, chat, and terminal rows. Keep long press as an accelerator; add bulk selection later.

**Suggested command:** `$impeccable harden`

### [P1] High-impact actions lack one consistent preflight contract

**Why it matters:** Provider switching, backup/restore, repair, and ADB control affect sessions, credentials, files, or the device. Their impact is currently spread across secondary copy and separate patterns.

**Fix:** Reuse one preflight sheet showing affected sessions, saved state, credentials/data involved, expected duration, cancellability, scope, expiry, rollback, and an action receipt.

**Suggested command:** `$impeccable harden`

### [P1] Settings exposes implementation before readiness

**Why it matters:** Appearance, archive, skills, voice, terminal, container, providers, account, backup, and diagnostics share one long scroll. Voice similarly begins with processors and model files rather than a simple ready/not-ready path.

**Fix:** Make Settings a searchable index with status summaries. Move details into Account & Models, Voice, Device Control, Terminal & Linux, Storage & Recovery, Appearance, and About & Diagnostics. Put recommended defaults first and advanced controls behind disclosure.

**Suggested command:** `$impeccable distill`

### [P1] Terminal accessibility and failure recovery are incomplete

**Why it matters:** The custom terminal canvas exposes no transcript structure to accessibility services. Failed terminals are labeled only Stopped, and failed chats can disable the composer without Retry, Reconnect, or diagnostics.

**Fix:** Add an accessible linear transcript mode, semantic line navigation, labeled input/cursor status, 48dp controls, terminal failure details, and actionable chat recovery cards.

**Suggested command:** `$impeccable audit`

## Persona Red Flags

**Alex — Power User:** No global search, attention queue, project cockpit, bulk actions, native diff, or task branching. Long press hides management actions. Positive signals are persistent sessions, slash commands, model controls, and terminal accessory keys.

**Sam — Accessibility-Dependent User:** The terminal transcript is opaque to TalkBack; several switches are unlabeled; segmented controls do not announce selection; terminal-font controls lack useful names; async backup/repair progress is visual-only.

**Casey — Distracted Mobile User:** The same composer button changes between Voice, Send, and Stop; Settings and Voice require long scrolling; Voice discovers missing dependencies one at a time; returning to a failed chat provides weak recovery. Persistent tasks, bottom-aligned composition, and notifications are strong foundations.

## Ten Ranked Feature Opportunities

| Rank | Feature | User value | Effort |
|---:|---|---|---|
| 1 | **Agent Mission Control** | A Home dashboard for Working, Needs attention, Ready, Failed, and Recent tasks with progress, last event, artifacts, and quick controls. Makes concurrency understandable. | M |
| 2 | **Native Changes Review + Completion Cards** | End each task with outcome, files changed, diff, tests, warnings, artifacts, and next actions. Add a first-class changed-files/diff view instead of relying on long Markdown or `/review`. | L |
| 3 | **Universal Search and Command Palette** | Search projects, chats, terminals, archive, files, artifacts, settings, and commands; filter by task state and jump directly to actions. | M |
| 4 | **Scoped Consent and Action Ledger** | Per-run phone/file/command scopes, expiry, pause/revoke, affected-session preflight, live action history, and a post-run receipt. Turns broad capability into durable trust. | L |
| 5 | **Project Cockpit** | A project overview with Git branch/dirty state, active chats and terminals, recent changes/artifacts, instructions, pinned tasks, health, and one-tap new work. | M |
| 6 | **Context Composer and Cross-Surface Handoffs** | Add `@file`, `@folder`, `@diff`, `@terminal`, and `@artifact` mentions; send terminal selections to chat and open changed files from results. Reduces copy/paste and context switching. | M |
| 7 | **Skills, Apps, and MCP Hub** | Discover, install, inspect, enable, update, and remove skills/integrations with permission manifests and status. The Git skill installer already exists but has no product surface. | M |
| 8 | **Fork and Compare Tasks** | Branch an existing task into alternative approaches, run both concurrently, then compare outcome, diff, tests, time, and cost before choosing. Builds directly on durable concurrent sessions. | M |
| 9 | **Terminal Workbench** | Add session switching, transcript search/export/share, command history, explicit failure diagnostics, and an accessible transcript mode. | M |
| 10 | **Checkpoints and Recovery Timeline** | Automatic checkpoints before risky operations; searchable archive/history; last-backup date, size, destination, validation, affected contents, retention, and rollback. | L |

## Minor Observations

- Replace the ambiguous chat pill “Local” with “Local workspace” or explicit provider/network wording; a remote model currently sits beside a label that can imply local inference.
- The Voice screen can show a selected model that is not installed. Use Recommended → Downloading → Ready → Active states.
- The Skills destination currently contains one device skill plus notifications; the proposed hub would make the name honest.
- Auto-scroll to the newest message can displace someone reading earlier output; add an unread boundary and jump-to-latest control.
- Current documentation/artifacts contain stale claims: an Alpine terminal image conflicts with Debian 13, and README leaves Interactive Voice Mode unchecked although the changelog says it shipped.
- There are no Compose UI/accessibility, font-scale, foldable, or screenshot regression tests. The unit test attempt could not start because this host has no Java runtime.

## Questions to Consider

1. Should Pandora's next milestone optimize **agent supervision**, **native code review**, or **security and trust** first?
2. Should the product feel primarily like an **operations console**, a **mobile IDE**, or a **calm assistant workspace**?
3. Is the desired scope the **top three opportunities**, the **top six**, or the **full ten-feature roadmap**?
