# Pandora roadmap mockups

Status: mockups 1–6 approved on 2026-08-23. Mockups 7–8 are excluded from the implementation scope.

The approved boards define the interaction and hierarchy for a six-commit roadmap. They inherit Pandora's current Android Material 3 visual system; implementation must translate them into responsive, semantic Compose UI rather than rasterizing controls or text.

## Approved implementation set

1. `01-settings-architecture-v2.png`
   - Settings becomes a status-summary index with focused destinations.
   - Local workspace and remote provider are separated explicitly.
2. `02-explicit-readiness.png`
   - Chat distinguishes workspace locality from model provider.
   - Voice and device control use explicit readiness state machines.
3. `03-high-impact-preflights.png`
   - Provider switching, restore, and device control share one preflight anatomy.
4. `04-changes-review-v2.png`
   - Completion card leads to a read-only changed-file list and unified diff viewer.
5. `05-universal-search.png`
   - Search spans commands, chats, files, settings, projects, terminals, and archive with actionable state filters.
6. `06-context-composer-handoffs-v2.png`
   - Structured context is selected through suggestions and chips; terminal selections can be handed to chat.

## Excluded explorations

- `07-terminal-accessibility-recovery.png`
- `08-terminal-workbench-v2.png`

These terminal concepts were not approved and must not be implemented as part of this roadmap.

## Implementation rules

- Phone-first compact layouts; expanded widths adapt using panes, rails, or wider sheets rather than stretching phone screens.
- Material 3 navigation, system Back, edge-to-edge insets, 48dp minimum targets, scalable type, light/dark themes, and explicit semantics are mandatory.
- Generated boards are north stars for topology and hierarchy. Core UI text, controls, code, accessibility, and responsive behavior remain native Compose.
- Each approved feature is implemented and verified in its own commit.
