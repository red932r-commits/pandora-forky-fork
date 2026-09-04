# Runtime updates

The update UI is in Settings → Terminal & Linux → Updates and is also searchable
as “Updates”. Checks are manual; no update installs automatically on launch.

## Sources and scope

| Component | Source | Activation |
| --- | --- | --- |
| Codex | Stable `@openai/codex` version from npm, matching official `openai/codex` GitHub release package | Verify the published SHA-256 digest and full ARM64 musl package layout in persistent staging; check version and an isolated app-server `thread/list` handshake; atomically replace the launcher symlink |
| Debian | The existing apt sources, including Trixie updates/security | Refresh metadata, simulate upgrades, show package versions, revalidate the reviewed plan, and install exact versions with removals disabled |
| zmx | Latest stable release of `neurosnap/zmx` on GitHub | Require the ARM64 Linux asset and its SHA-256 digest; verify the archive, version, and session-list command before atomic activation |

PRoot, the PTY bridge, Android code, and native Android dependencies remain APK
updates. The updater does not change Debian releases, perform `dist-upgrade`,
remove packages, or update project-local npm dependencies. Packages apt holds back
remain installed at their current version. Package checks do not stop sessions.

## Coordination and lifetime

`RuntimeUpdateManager` belongs to `PandoraApplication`. It retains status and
per-component results across navigation/rotation and holds the Linux foreground
service active. A small journal records whether maintenance was interrupted; the
next app process asks the user to check versions and package state again. It does
not silently resume an interrupted upgrade.

`WorkspaceCoordinator` serializes checks with updates, backup, restore, and repair.
Before mutations, `RuntimeAccessGate` closes admission to new PRoot processes and
PTY launches. Session managers stop chats, attached/detached terminals, and device
control. Existing installation scopes and transient PRoot processes must drain
before maintenance starts; a busy command aborts maintenance instead of being
killed to make way for it. Package command failures and timeouts are visible.
Tagged package processes are also checked through `/proc` before new maintenance,
so an installer orphaned by Android process death must finish before another
operation is admitted.

PRoot working files use Android's private no-backup directory instead of the
evictable cache. They remain available to running Linux processes during storage
pressure and are outside the persistent workspace backup.

## Recovery and limits

- Codex/zmx live under `/root/.local/share/pandora/runtimes/`. Relative launcher
  symlinks and previous executables stay inside `/root`, so workspace backup and
  restore preserve them. Legacy npm launchers and standalone zmx binaries can be
  updated without migrating projects or account settings.
- After activation/rollback, pruning keeps only the active and previous managed
  versions. It ignores legacy/unmanaged directories and never follows symlinks
  while deleting obsolete files.
- Failed staging leaves the active executable unchanged. Successful activation
  retains the previous executable; restoring it stops sessions again. This is
  executable rollback, not rollback of Codex databases or configuration. A new
  Codex version can migrate its data when actually used; the pre-activation probe
  uses a disposable `CODEX_HOME` to avoid doing so during validation.
- Debian upgrades are not atomic. If Android terminates the process during dpkg,
  `dpkg --audit` identifies unfinished configuration. **Finish package setup** runs
  `dpkg --configure -a`, preserving local conffiles. Dependency problems that this
  cannot repair require terminal diagnosis (for example `apt-get -f install`) or
  Linux Repair. Neither workspace backups nor executable rollback restore Debian.
- A failed component does not conceal successful updates to other components.
  Recheck before retrying. Checks expire after 15 minutes, and Debian plans are
  revalidated immediately before installation.
- Download metadata and runtime archives have size limits and network requests
  have timeouts. Both native releases require a published SHA-256 digest. Codex
  retains its helper binaries/resources; archive extraction rejects links,
  traversal paths, duplicates, and oversized entries. A version and protocol
  probe cannot guarantee compatibility with every Codex feature or Android device.
- Codex updates bypass npm installation because its optional native dependency
  can fail to install inside the Android PRoot process even when npm exits
  successfully. The registry still selects the stable version; the matching
  official GitHub package supplies the executable and its resources.

## Validation

JVM tests cover apt-plan parsing/rejection, version comparison, maintenance
admission/draining, executable activation/rollback, relocation of persistent
storage, archive extraction boundaries, and session-impact disclosure.
Run `./gradlew test lint assembleDebug`.

### Verified on 2026-09-04

The required build passed with JDK 17: 111 tests in each of the debug and release
variants, no failures, lint successful, and a debug APK assembled. The ARM64
Android emulator exercised the production Settings flow:

| Check | Observed result |
| --- | --- |
| Codex update | 0.147.0 → 0.153.3 using the verified complete native package; isolated app-server probe passed |
| Debian update | Six packages upgraded, including OpenSSL, libexpat, and Linux headers; a subsequent check reported no pending upgrades |
| zmx update | 0.7.0 → 0.8.0; a new persistent terminal opened successfully |
| Codex rollback | Restored 0.147.0 and stopped the active test terminal after confirmation |
| Navigation and expanded window | In-progress updates continued with the same readable status |
| Offline check | Each source reported an error, installed versions remained visible, and no install action was offered |
| Interrupted check | Force-stopping during metadata inspection produced the recovery message on restart; networking was restored afterward |
| Repair completion display | A temporary saved-status fixture rendered the completed outcome with `busy=false`; original preferences were restored and verified |

Actual destructive Linux Repair was not run: automatic approval review rejected
the test because it resets Debian packages without rollback. The completion
capture proves UI rendering only. Interrupted dpkg recovery, TalkBack, dark theme,
and enlarged system text have not been exercised on-device in this pass.

The scoped UI finish review returned **ship**, with all three findings resolved:
stable operation context, retained repair outcomes, and bounded version storage.
