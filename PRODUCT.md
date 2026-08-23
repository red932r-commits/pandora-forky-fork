# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Native Android application built with Kotlin and Jetpack Compose. Pandora targets Android SDK 35, supports Android 8.0 and later, and currently ships an ARM64 Debian PRoot runtime.

## Users

Pandora is for technically experienced people who want to run and supervise coding agents from an Android phone. They are comfortable with projects, Git, terminals, model providers, permissions, and agent workflows, but still need consequential operations to be legible, reviewable, recoverable, and efficient on a mobile device. Tablet layouts are responsive adaptations of the same product rather than a separate primary experience.

## Product Purpose

Pandora is a local, persistent AI-agent workspace for Android. It combines durable Codex chats, project-aware workspaces, concurrent background tasks, a real Debian terminal, on-device speech, artifacts, and opt-in device control. Success means a user can start serious coding work, leave it running, return from a notification, understand what happened, review the result, and safely direct the next action without moving to a desktop.

## Positioning

Pandora is not a mobile chat client or a remote terminal wrapper. Its differentiating mechanism is a persistent on-device operations environment in which agents, project context, terminals, artifacts, voice, and Android control remain available together.

## Operating Context

- Users create or register projects, clone repositories, run multiple Codex chats, and keep terminals alive across navigation.
- Work commonly moves among project status, chat context, terminal output, changed files, generated artifacts, notifications, and Android apps.
- Users may run hosted Codex or an OpenAI-compatible provider. The workspace and agent runtime are local to the device; model inference may be remote.
- High-impact workflows include provider switching, backup and restore, Linux repair, file changes, package installation, and opt-in ADB phone control.
- The primary environment is a compact Android phone used with touch, voice, the system keyboard, and occasional hardware keyboards or accessibility services.

## Capabilities and Constraints

- Persistent project and general chats, concurrent Codex sessions, persistent PTY terminals, archive, backup/restore, local dictation and speech, attachments, APK installation, custom providers, and phone-control skills already exist.
- Android Material 3 behavior, system Back, edge-to-edge layout, 48dp touch targets, scalable type, light/dark themes, and compact/expanded window support are binding platform constraints.
- The Debian PRoot environment is not a VM or security boundary. Codex currently runs without an additional sandbox inside that environment, so scope, consent, preflight, audit, and recovery must be explicit where actions are consequential.
- New work in the current roadmap is organized as six independently reviewable feature commits. Shared foundations belong to the first feature commit that consumes them rather than a separate refactor commit.
- Screen-bearing features require a feature-specific mockup packet and explicit approval before implementation.

## Brand Commitments

Preserve the Pandora name, the existing calm native Android character, the restrained purple accent, and the product's direct technical voice. Technical depth is welcome, but readiness, locality, consequences, and recovery must not be ambiguous.

## Evidence on Hand

- Product and runtime truth: `README.md`, `CHANGELOG.md`, `TODO.md`, and `docs/`.
- Existing implementation: `app/src/main/java/com/pandora/mobile/` and `app/src/main/java/com/pandora/mobile/linux/`.
- Existing screenshots and concepts: `artifacts/` and `design/`.
- Prior critique snapshot: `.impeccable/critique/2026-08-23T18-19-35Z__p-src-main-java-com-pandora-mobile-mainactivity-kt.md`.
- No external customer evidence, usage analytics, performance benchmarks, or tested pricing claims are present and none should be fabricated.

## Product Principles

1. Make concurrent agent work observable and interruptible.
2. Turn agent output into reviewable outcomes, changes, evidence, and next actions.
3. Preserve technical power while making consequences, locality, permissions, and recovery explicit.
4. Reduce context switching among chat, projects, terminal, files, and artifacts.
5. Follow native Android expectations and make every critical workflow accessible.

## Accessibility & Inclusion

Critical workflows must support TalkBack, keyboard navigation, scalable type, explicit semantic state, and 48dp minimum targets. The terminal must provide an accessible linear transcript and understandable recovery state rather than relying solely on a custom-drawn canvas.
