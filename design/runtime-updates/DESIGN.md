---
name: Pandora runtime updates
description: Feature-scoped record of the native Settings extension.
colors:
  primary: "#6654D9"
  on-primary: "#FFFFFF"
  primary-container: "#E9E5FF"
  on-primary-container: "#261765"
  surface: "#FAFAF7"
  on-surface: "#1D1E1C"
  on-surface-variant: "#656760"
  outline: "#D8D9D2"
  outline-variant: "#E7E7E1"
  error: "#BA3A3A"
spacing:
  related: "8dp"
  row: "12dp"
  group: "16dp"
  section: "24dp"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
  button-outlined:
    textColor: "{colors.primary}"
  button-disclosure:
    textColor: "{colors.primary}"
  component-row:
    textColor: "{colors.on-surface}"
  operation-status:
    textColor: "{colors.on-surface-variant}"
---

# Design System: Pandora runtime updates

## Overview

**Creative North Star: "Calm native operation"**

This record applies only to Updates and its integration with Terminal & Linux Settings. It extends Pandora's existing Material 3 interface and restrained purple identity. It does not establish a replacement app-wide system. The direction is Operate: help a user check installed tools, review consequences, run maintenance, and understand the result.

Implementation is the authority: [RuntimeUpdatesScreen.kt](../../app/src/main/java/com/pandora/mobile/RuntimeUpdatesScreen.kt), [SettingsArchitecture.kt](../../app/src/main/java/com/pandora/mobile/SettingsArchitecture.kt), [ImpactPreflight.kt](../../app/src/main/java/com/pandora/mobile/ImpactPreflight.kt), and [PandoraTheme.kt](../../app/src/main/java/com/pandora/mobile/PandoraTheme.kt). The frontmatter records observed light-theme roles and repeated native spacing in dp. Theme selection and remaining Material defaults stay in code. The sidecar contains illustrative browser translations for documentation; its CSS, interaction states, and synthesized tonal ramps are not Android implementation tokens.

**Key Characteristics:**

- Plain rows with installed and available versions.
- A readable operation summary with optional technical detail.
- Explicit session-stop confirmation and bounded recovery.

## Colors

Purple identifies actionable choices and available versions against warm neutral surfaces.

### Primary

- **Pandora purple** (`primary`): available-version text, primary action, outlined/text button labels, and progress.
- **White action text** (`on-primary`): filled button labels.
- **Pale purple** (`primary-container`) and **deep purple ink** (`on-primary-container`): inherited preflight caution and icon treatments.

### Neutral

- **Warm surface** (`surface`) and **dark ink** (`on-surface`): page and primary content.
- **Muted ink** (`on-surface-variant`): installed versions, progress summaries, explanatory text, and diagnostics.
- **Soft outline** (`outline`, `outline-variant`): outlined controls and row separators.
- **Error red** (`error`): error summaries, accompanied by text.

**The State in Words Rule.** Version, readiness, progress, and error meaning remain explicit in text; color supplements them.

The existing dark scheme is selected by `PandoraTheme`. No feature-specific palette or dynamic color override was introduced. Dark-mode appearance has not been visually verified for this feature.

## Typography

The Updates content uses inherited `MaterialTheme.typography`: `bodyLarge` for the introduction, `titleMedium` for component and result headings, `bodyMedium` for versions/status/results, `bodySmall` for diagnostics and scope notes, and `labelLarge` for package names. Material buttons retain their native label style. These are semantic roles, not a new font scale.

The shared Settings header remains at (20sp), semibold. The existing preflight sheet retains its established typography. No font asset, display typeface, or new typography token is added.

**The Summary First Rule.** Keep operation meaning in the normal body role; reserve smaller text and disclosures for technical output.

## Layout

The inherited scaffold has a persistent Back/header row and a vertically scrolling content column with system-bar insets. Content padding is (20dp horizontal, 18dp vertical). The feature remains one full-width column in both captured compact and wide layouts; there is no feature breakpoint or invented tablet grid.

Component rows separate title and version text from a trailing checkbox using a weighted text column. Selection is attached to the row with a checkbox role and a minimum height of (48dp). Check and update controls are full-width with the same explicit minimum height. Dividers, recurring spacing, and text hierarchy establish groups without nested cards.

The compact and wide progress captures show wrapping explanatory text and a readable status above the component list. Dark mode, increased Android font scale, TalkBack navigation, and keyboard traversal remain unverified; source-level semantics do not establish those outcomes.

## Elevation & Depth

Updates is flat at rest: neutral dividers separate rows and purple identifies actions. It adds no custom shadow or raised card vocabulary. The existing Material modal bottom sheet provides temporary depth during confirmation; its platform behavior remains authoritative.

## Shapes

Native Material buttons and checkboxes retain library shapes. The Updates list adds no custom radius. The inherited circular Back target and preflight sheet, bordered fact group, and rounded confirmation controls remain shared components; their local geometry is not promoted into a new app-wide shape system.

## Components

### Actions and selection

The outlined check control is the first action. Available tools are selected after a completed check; users can toggle each eligible row. A filled “Update selected” action appears for available updates and requires a nonempty selection. Maintenance and conflicting workspace work disable mutations. Recovery actions use outlined buttons. Native pressed, focus, and disabled behavior remains with Compose.

### Operation status and diagnostics

An indeterminate progress indicator appears while busy, followed by the operation's readable status and the explicit reassurance that leaving this screen does not stop progress. Status has a polite live region. Command output, package changes, and long per-component errors are independently disclosed. Error summaries and completion results remain readable without opening raw output.

Completion names the result and the next action: reopen a chat or terminal to use updated tools. Application-owned work survives navigation; an interrupted-process journal yields an instruction to check versions and package state. This is recovery messaging, not a promise of automatic operation resumption.

### Confirmation and recovery

The shared, scrolling `ImpactPreflightSheet` lists affected sessions, saved state, data, duration, cancellation, and rollback. The confirmation label explicitly says sessions will stop; Cancel is available before starting.

Previous versions restore the single executable saved before the last Codex or zmx update. They do not restore Debian packages or Codex data migrations. [RuntimeExecutableStore.kt](../../app/src/main/java/com/pandora/mobile/linux/RuntimeExecutableStore.kt) prunes unreferenced app-owned version directories after activation/rollback while preserving referenced versions; cleanup is best-effort. The UI does not represent an unlimited version history. Interrupted Debian setup gets a separate “Finish package setup” action. Repair reinstalls Debian system files while preserving persistent /root, and warns that extra packages may need reinstalling.

Terminal & Linux renders a saved Repair status after the busy state ends when the last operation is Repair. A later maintenance operation can supersede that status.

### Verification record

Finish-review verdict supplied for this implementation: **SHIP**, after progress context, persistent Repair completion, and retained-version cleanup were resolved.

Reviewed native captures:

- `/tmp/pandora-updates-native-progress.png`: real update progress with readable context.
- `/tmp/pandora-updates-wide-fixed.png`: wide adaptation of the same native layout.
- `/tmp/pandora-updates-native-result.png`: actual completion/result presentation.
- `/tmp/pandora-repair-completion-ui-fixture.png`: saved-status UI fixture only; it is not evidence that Repair executed. Original app state was restored afterward.

Functional QA reported in the implementation handoff covers real Codex (0.147.0 → 0.153.3), six Debian package upgrades, zmx (0.7.0 → 0.8.0), Codex rollback, offline errors, interruption messaging, and terminal reopening. The temporary capture paths are session evidence, not committed artifacts. Repair execution, dark mode, scaled text, TalkBack, and keyboard coverage are not claimed.

## Do's and Don'ts

### Do:

- **Do** use the existing Material theme and semantic type roles.
- **Do** keep operation summaries visible when diagnostics are collapsed.
- **Do** state session interruption and recovery limits before confirmation.
- **Do** distinguish actual operation evidence from saved-status UI fixtures.

### Don't:

- **Don't** turn this Settings extension into a new app-wide visual identity.
- **Don't** imply executable rollback restores Debian packages or Codex data.
- **Don't** replace operation meaning with raw command output.
- **Don't** treat illustrative browser previews as native accessibility or rendering verification.

Not canonized: the inherited single-line Settings-entry subtitle truncation is not a rule for maintenance status; the dedicated Updates screen wraps status. Unverified native states and browser preview styling are not established design-system behavior.
