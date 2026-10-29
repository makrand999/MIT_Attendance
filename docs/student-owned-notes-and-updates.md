# Student-owned notes and app updates

Purpose: a student-managed BTech class companion. Each student owns their GitHub
credentials and personal notes while sharing class material through a public
subject repository. Reviews are outside this change.

## Notes flow (implemented)

GitHub does not support private forks of public repositories. Create an independent
private repository from the public repository's Git history instead, and track the
public repository as `upstream`. The student's private repository is `origin`.
Describe this as a private copy in the UI, not a GitHub fork.

1. Student enters their PAT; validate identity and required permissions.
2. Student connects a public notes repository to a subject.
3. Show the source and proposed private repository name before creating it.
4. Create the private repository, copy the public history, and retain the upstream
   repository identity and default branch. Reconnect an existing matching copy on
   subsequent setup attempts; do not adopt unrelated repositories with the same name.
5. Save edits locally; push commits to the student's private origin.
6. Fetch class updates from upstream and merge without discarding student edits.
   Stop for conflict resolution; PDFs and other binary conflicts support keeping
   either version or both copies. Never automatically force-push.

A private copy cannot directly open a cross-repository pull request against the
public source. A later contribution flow needs a public fork/branch, starting from
the public upstream history, containing only explicitly selected changes. It must
not publish the private repository's other files or history.

Protect PATs using Android Keystore-backed encryption; exclude them from backups,
logs, URLs, and Git configuration. A repository-scoped token must not implicitly
enable unrelated GitHub permissions. Private repository content also needs review:
authentication cookies, backup credentials, and other secrets should not be
committed merely because the destination is private.

Open **Notes** from the home tools to connect or disconnect GitHub and add subject
sources. A classic PAT needs `repo`; fine-grained tokens need permissions to create
private repositories and read/write their contents. API errors explain missing
access. No GitHub credential is compiled into BuildConfig. Token storage uses
AES-GCM with an Android Keystore key in the app's no-backup directory. Git transports
receive credentials in memory, using clean remote URLs.
ERP logout also disconnects GitHub while retaining local subject files.

Each connected subject opens its own file explorer, with push and pull controls.
Copies live under `Notes/<github-user>/<upstream-repository-id>`. Existing course
folders remain accessible through **Browse existing local notes**, with no automatic
upload or reassignment to a new account. A deterministic private repository name
and upstream-ID description allow reconnecting a matching copy on another phone.
Changed default branches are discovered from GitHub. The private copy's branch is
recorded locally. Repositories with symbolic links or submodules are rejected.

Sync first commits additions, changes, and deletions locally, then fetches personal
origin updates and class upstream updates. A conservative three-way file merge
preserves Git ancestry. Both text and binary conflicts have explicit choices:
keep mine, use incoming, or keep both. Untracked file collisions stop checkout.
Pending conflicts survive restarting the flow; changing a file invalidates stale
choices. Push uses normal non-force Git semantics and checks destination privacy
again before uploading. Local commits retain previous versions for recovery.

Personal Google session backups now use a separate private
`<github-user>/mit-attendance-backup` repository and account-specific local folders.
The existing backup payload is base64 encoded, not encrypted; the repository must
remain private. Legacy shared/public backup repositories are not adopted.
Public certification scripts remain readable without connecting GitHub; optional
authentication uses the student's PAT only for known GitHub HTTPS hosts.

Legacy class alerts still use the existing shared Gist index. Reading does not need
a shared token. Posting/deleting uses the student's PAT and requires the Gist
owner's account with `gist` permission; the app reports failure instead of silently
ignoring it. GitHub does not allow a classmate's PAT to edit someone else's Gist.
A multi-author alert backend is a separate migration. Reviews remain unchanged.

## Validation

Use a debug build to validate compilation and packaging:

```sh
ANDROID_HOME=/path/to/android-sdk ./gradlew :app:assembleDebug
```

Do not add or run tests in this repository; see `AGENTS.md`.
Firebase configuration is optional. The removed Agent feature no longer contributes
SSH/Qwen configuration or release-secret requirements.
Device permission prompts and real-account GitHub provisioning require a device and
student-owned PAT for manual validation.

## GitHub Releases updater (implemented)

Continue using `makrand999/MIT_Attendance/releases/latest`. No APK hosting migration.

- Preserve the existing `v<Android versionCode>` release tag convention. The tag
  must match the APK's numeric version code; the installed version must be lower.
- Publish one universal APK per release. Ambiguous APK selection fails rather
  than choosing an arbitrary asset. ABI-specific selection would need an explicit
  asset naming convention in a future change.
- Read the asset size and optional GitHub `sha256:` digest.
- Send user-requested downloads through unique, network-constrained WorkManager
  work, with foreground progress, cancellation, bounded exponential retries, and
  a low-storage constraint. Closing the screen does not cancel the download.
- Retain partial downloads across retry/process restart. Resume using HTTP Range
  only when a SHA-256 digest is available to validate the assembled file. If the
  server ignores Range, restart. Without a digest, retry from the beginning.
- Verify length, available digest, package identity, version, minimum Android
  version, and compatible signing identity before promoting the temporary file.
  The Android installer performs final APK signature and device compatibility
  enforcement.
- Completion notification opens a private installation activity. The same
  activity handles APKs opened from the existing Updates browser and returns from
  Android's install-source permission screen before opening the installer.
- Installation remains user initiated. This implementation does not request
  unattended installation or automatically restart the app.

Checks remain on the existing app entry points; this change does not add periodic
release polling. Background execution follows Android's scheduling and force-stop
restrictions. If notifications are disabled, the downloaded APK remains accessible
through the update prompt or Updates browser.

Keep signing future updates with a compatible signing key. The project currently
uses the debug keystore for release builds; changing to a production key requires
an explicit migration decision for already-installed copies.

## References

- https://docs.github.com/en/pull-requests/reference/forks
- https://docs.github.com/en/rest/releases/assets
- https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- https://developer.android.com/studio/publish/app-signing
