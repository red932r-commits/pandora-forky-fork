---
target: re-review the implemented screens, especially Account & models provider card heights and Codex OSS icon semantics
total_score: 31
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 4
timestamp: 2026-08-23T20-29-20Z
slug: app-src-main-java-com-pandora-mobile
---
Method: dual-agent (A: /root/assessment_a · B: /root/assessment_b)

## Design-specificity verdict

Pandora now has a product-specific technical workflow: Universal Search, read-only Changes Review, prepared workspace context, chat handoffs, explicit readiness, and high-impact preflights form a coherent local agent workspace. The visual skin remains conservative Material 3, but it no longer feels like a generic chatbot. The unfinished areas are mostly reusable primitives and state boundaries rather than a need to redesign the shell.

## Overall impression

The six implemented features materially improved orientation, safety, and expert workflow. The Account & models screenshot exposes the clearest polish defect: binary provider choices do not share a stable visual rhythm, long technical metadata is carrying too much hierarchy, and the key icon describes a credential rather than Codex OSS. Live emulator inspection found the same content-driven height problem in the Settings index and profile rows, so this should be fixed as a system rather than patched on one card.

## Design health

| Nielsen heuristic | Score / 4 | Current state |
|---|---:|---|
| Visibility of system status | 3 | Strong readiness, loading, diff, and preflight states; optional and failure states sometimes collapse together. |
| Match with the real world | 3 | Technical vocabulary fits the audience; the key/provider metaphor and some generic icons misstate concepts. |
| User control and freedom | 3 | Read-only review, Back handling, removable context, and cancelable preflights are good. |
| Consistency and standards | 3 | Cohesive surfaces, but row geometry, disclosure icons, selectable semantics, and tokens drift. |
| Error prevention | 4 | Preflights, model-install gating, redaction, and review-before-send are strong. |
| Recognition rather than recall | 3 | Search, settings index, readiness panels, and context suggestions improve discoverability. |
| Flexibility and efficiency | 3 | Search, filters, @ context, diffs, handoffs, and slash commands fit experts; settings deep links are incomplete. |
| Aesthetic and minimalist design | 3 | Calm and legible; raw model IDs, URLs, preflight facts, and mobile diffs can dominate. |
| Error recognition and recovery | 3 | Several retry paths exist; some indexing, context, and diff failures still look like empty data. |
| Help and documentation | 3 | Contextual explanations are strong; exact context payload and some state distinctions remain opaque. |
| **Total** | **31 / 40** | **Good, with localized high-impact polish and trust gaps.** |

## Cognitive load

The settings redesign changed the product from a long infrastructure inventory into focused destinations. The remaining load is localized: seven peer settings destinations, five Search filters, up to eight mixed context suggestions, and six equally weighted preflight facts. Progressive disclosure is otherwise strong.

## Emotional journey

- Settings begins with orientation and explicit state instead of configuration overload.
- Preflights make consequential actions feel responsible, though the uniform fact table can feel like a compliance form.
- Changes Review and workspace handoff create a strong competence moment for technical users.
- Search feels fast, but landing at the Settings index after choosing a specific setting breaks the promise of direct navigation.
- Context preparation feels powerful, but “review before sending” is not fully earned until the user can inspect the exact serialized, truncated, and redacted payload.
- The Account & models provider pair is the most conspicuously unfinished screen: unequal peers and credential-based identity weaken trust in an otherwise polished architecture.

## Strengths

1. Technical-user fit is strong: `/root`, remote inference, Git metrics, read-only diffs, explicit session counts, and model readiness are direct rather than euphemistic.
2. Search, Changes, context preparation, terminal selection, and handoff now form one coherent expert workflow.
3. Provider switching, backup, restore, repair, and device control share a much stronger safety contract.

## Priority issues

### [P1] Normalize provider and settings-row geometry; fix the Codex OSS identity

The supplied capture and live UI tree show Codex OSS at 235 px versus Codex at 190 px, a 24% height difference. ChatGPT account is 180 px versus Codex OSS profile at 225 px, and the Settings index repeats the issue: Account & models is 241 px versus 195 px for ordinary rows. The source allows summaries and full endpoints to determine height in `ProviderChoice`, `ArchitectureActionRow`, and `SettingsIndexRow`.

Treatment:

- Use a shared row/card geometry with a consistent two-line summary slot and a minimum height, while still permitting growth at large font scale.
- Show concise identity metadata in selection surfaces: model plus host, not the full URL. Keep the endpoint in profile detail.
- Replace the Codex OSS provider key with a connected-nodes/hub/API icon. Use the actual Codex mark for hosted Codex if the brand asset is appropriate. Reserve a key for the credential field itself, not service identity; use Tune/Manage accounts for the profile row.
- Add a subtle selected container tint; selection should never alter height.
- Source: `SettingsArchitecture.kt` — `SettingsIndexRow`, `AccountAndModelsSettings`, `ProviderChoice`, and `ArchitectureActionRow`.

Recommended card content:

```text
[Hub]       Codex OSS                         (selected)
            Remote via openrouter.ai
            deepseek-v4-flash-0731

[Codex mark] Codex
             Hosted by OpenAI
             Uses your ChatGPT account
```

### [P1] Make every visual choice a real semantic choice

Provider cards draw a radio ring but use `clickable(role = RadioButton)` without exporting selected/checked state. The live accessibility tree reports both provider radios as unchecked. The same pattern affects Appearance, Universal Search filters, Changes filters, and Voice CPU/GPU choices. Several new controls are also below the 48 dp touch-target baseline.

Treatment:

- Use `selectableGroup` plus `selectable(selected = …, role = RadioButton/Tab)`.
- Give Search its own editable label, label the ADB switch, and merge visible row labels with toggle semantics.
- Standardize 48 dp hit bounds for Home entry points, Search clear, context close/remove, Changes filters, and Device control Back.
- Add Compose UI semantics tests for selected state, labels, and 2.0× font scale.

### [P1] Complete Universal Search navigation and recovery

`UniversalSearchTarget.Setting(destination)` is typed correctly, but `MainActivity` ignores `destination` and opens the Settings index. The emulator confirmed that choosing Account & models from Search lands on generic Settings. Some source-index failures are also swallowed into empty lists, so failure can masquerade as “nothing found.”

Treatment:

- Pass an initial `SettingsDestination` into Settings and preserve Search as Back destination.
- Retain partial results, name failed sources, and show Retry/Re-index instead of presenting absence.
- Add selected semantics to filters and include Terminals/Settings/Archive either as filters or a documented All-only decision.
- Source: `MainActivity.kt:406`, `UniversalSearch.kt` search state/filter/result components.

### [P1] Make context and Changes Review trustworthy under failure

Prepared context chips can be removed but cannot preview the payload despite “review before sending” copy. Context catalog failures can look like no matches. Changes Review omits a Renamed filter, can become blank when a chosen filter has zero matches, and reports diff-load failures as “No text diff.”

Treatment:

- Make prepared chips open a read-only preview showing source, timestamp, line/byte/token estimate, truncation, and redaction summary.
- Distinguish context `Empty`, `Partial`, and `Failed` with Retry.
- Add Renamed plus per-filter counts and a filtered-empty state.
- Model diff state as Loading, Text, Binary/Unsupported, and Failed(detail) with Retry.
- Source: `ContextComposer.kt` prepared-chip/handoff code and `ChangesReview.kt` filter/diff state.

### [P2] Tighten readiness and preflight hierarchy

Voice displays optional “Performance · Not tested” with the same amber treatment as missing required setup. Device readiness compresses working/error into Setup required. Preflight sheets give affected sessions, saved state, data, duration, cancellation, and rollback equal visual weight, so primary risk and actions can move below the fold at large text sizes.

Treatment:

- Separate Required, Optional, Ready, Working, Warning, and Error instead of Boolean readiness.
- Label Performance as neutral “Optional test” with Test now.
- Keep affected sessions, saved state, caution, and actions visible; collapse duration/cancellation/rollback behind More details.
- Pin Confirm/Cancel below the scroll region and vary neutral/amber/destructive treatment by actual risk.

## Persona red flags

- **Alex, power user:** Search, diffs, context, and terminal handoff are excellent accelerators. Alex will immediately notice setting results that do not deep-link and mobile diffs without wrap/text-size controls.
- **Sam, accessibility-dependent engineer:** provider/filter/CPU choices do not consistently announce selected state; several context and navigation targets are under 48 dp; dynamic technical strings destabilize row rhythm.
- **Riley, security-conscious technical user:** Riley needs to inspect exactly what context was captured, truncated, and redacted, and needs indexing/diff failures distinguished from legitimate emptiness.

## Minor observations

- Use a single disclosure chevron rather than mixing ArrowForward and ChevronRight.
- Define one semantic icon matrix: command, change set, file, device control, Linux workspace, app storage, remote provider, credential.
- Chat transcript inline-code backgrounds fragment across wraps and create a noisy text wall; this remains a separate polish item outside the six-feature approval scope.
- Changes Review should offer Wrap/No wrap and text-size controls if line-by-line mobile review is a core use case.
- The Context picker is clear, but long prepared-context labels need bounded width so the remove target remains reachable.
- The Impeccable static detector returned zero findings because it scans web extensions and does not inspect Kotlin/Compose; source review and emulator UI/accessibility trees were used instead.

## Questions for alignment

1. Should Codex OSS be visually framed as a remote provider connection (Hub), an open-source code agent (Code), or a Codex-family product (brand mark)? Recommendation: Hub in provider selection, Tune for profile, key only inside credential editing.
2. Should selection cards expose two fixed conceptual lines for both providers and move full URLs to details? Recommendation: yes.
3. For the next implementation pass, should scope include only the provider/settings-row system or the full four P1 items? Recommendation: full four P1s in separate commits, with mockup approval for provider cards and context preview first.
4. Is Changes Review intended for quick risk scanning or detailed line-by-line review? Recommendation: quick scanning by default, with an explicit expanded review mode.
