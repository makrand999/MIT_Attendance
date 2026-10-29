# Server alerts manager and Akatsuki integration

Created: 2026-10-21. Updated: 2026-10-23. Status: The earlier combined-backend implementation exists locally, but its architecture is superseded by the user's separation decision below. Separate alerts-service extraction and Android routing/compatibility changes remain to be implemented. The combined artifacts must not be deployed to the existing Akatsuki service.

## Current architecture decision (2026-10-23; takes precedence)

Latest priority: implement the Android Akatsuki feature against the existing
deployed API; set alerts aside for now. The Android compatibility work is recorded
in [Akatsuki Android integration](docs/akatsuki-android-integration.md). Application
details use existing authorized lists, chat uses the existing `{content}` contract,
and Akatsuki no longer depends on the alerts repository or push setup. No backend,
web frontend, Nginx or live database update is required by this client work.

The user requires the existing Akatsuki backend to remain independent because it
already serves its own web app. Do not add alerts or unrelated attendance features
to that backend. The earlier recommendation to extend Akatsuki is superseded.

- Keep the deployed Akatsuki backend, database, web frontend and Nginx routes
  unchanged. Do not install the combined local backend binary or run its new
  migrations against the Akatsuki database.
- Run class alerts, class membership, scheduling, synchronization and notification
  delivery in a separate service with its own database and API base.
- Android integrates opportunities, applications, listings and conversations using
  the existing Akatsuki API. Alerts use the separate alerts API. Keep authentication
  and account isolation explicit; do not copy Akatsuki's signing secret into the
  alerts service or assume that the two services share a database.
- Adapt native Akatsuki functionality to the deployed API: the local additions for
  application-detail reads and message-idempotency capability are not deployment
  prerequisites to impose on Akatsuki. Unsupported flows need compatible client
  behavior or the existing browser fallback.
- The local Class alerts web UI is unpublished and must not be added to the
  existing Akatsuki web deployment under this plan.

Only deployment inspection was performed during the interrupted update attempt.
No backend binary, service configuration, live database or served frontend was
changed. The implementation and validation history below describes the earlier
combined architecture, not a completed separate-service implementation.

## 1. Purpose and agreed context

Build a student-managed campus system in which the Android app uses server APIs for class alerts and Akatsuki opportunities. Students should be able to post alerts using their own verified campus identity, without needing the owner of a shared GitHub Gist.

The server must manage class membership, permissions, alert lifecycle, scheduling, and synchronization. Android remains useful offline and integrates Akatsuki's existing opportunities, applications, and private job conversations.

Keep the preceding notes implementation: personal GitHub PATs, public subject sources, independent private student copies, and Git synchronization. GitHub PATs are **not** campus server credentials. Keep GitHub Releases as the APK update source. Reviews remain outside this work.

This file is the handoff for a new session. Proposed architecture and defaults below are recommendations, not descriptions of features that already exist.

## Implementation follow-up (2026-10-22)

The implementation audit identified and addressed these local gaps:

- Campus API requests now reconnect once using saved ERP credentials on expiry or
  `401`, including background alert sync, images and push registration. Concurrent
  failures share the renewed token. Renewal retains the same verified account's
  session generation; logout and class/account switches prevent request replay.
  Foreground sockets use the latest token and perform a bounded reconnect as well.
- Alert links now focus the requested alert, including personally hidden/trashed
  and expired items, with an explicit return to the complete class feed.
- Application links now open a native detail screen. The additive authenticated
  `GET /api/applications/:id` returns content only to the applicant or job owner;
  other accounts receive `404`. Owners can change status from the detail screen.
- Android's membership dialog now accepts roll-bound invite IDs and secrets as
  well as approval requests, and retains the request key for unchanged retries.

The user requested fast implementation and no further tests. The running Android
test task was stopped; follow-up validation uses compilation/APK assembly only.
The previous candidate's test results apply to the 2026-10-21 implementation, not
these additions. Production setup, device acceptance and live cutover remain open.

Follow-up builds passed: Android offline `:app:assembleDebug` and backend
`cargo build --release --locked`. Current artifacts are
`app/build/outputs/apk/debug/app-debug.apk` and
`/home/max/akatsuki/backend/target/release/akatsuki_backend`.
The 2026-10-21 package under `/tmp/akatsuki-campus-final-candidate/` has not been
replaced and predates these additions. No live service or release was published.

## Full-plan continuation (2026-10-21)

The full-plan request added the web alerts manager, Android server cache/sync/outbox,
native opportunities/applications/listings/conversations, optional FCM integration,
and staged importer/backup/release tooling. Saved attendance ERP credentials remain
the campus login source; there is no duplicate password form or password store.

Actual implementation details and concrete pilot steps:

- [Current implementation](/home/max/akatsuki/docs/campus-alerts.md)
- [API schemas](/home/max/akatsuki/docs/campus-alerts-openapi.json)
- [Pilot, backup, artifact preparation and recovery](/home/max/akatsuki/docs/campus-rollout.md)

Sync now rehydrates the latest visible canonical content or tombstones instead of
exposing stored historical bodies to ordinary members; historical access remains
restricted to authors/representatives. Change positions/cursors retain their
transactional high-water semantics.

Room is now version **15**; it preserves legacy rows and stores campus content,
personal state, account/class cursors, subject mappings and outgoing changes
separately. The Android version is **15 / 1.1.0**. The old alert entry opens the
server UI; an explicit read-only legacy view remains. Gist writes are disabled.
Import mappings preserve eligible local seen/hidden/trash state after class setup.

Additive backend migrations are **0005–0009**: class alerts, optional sender-scoped
message idempotency, original import JSON/course provenance and push installations/
delivery claims. Existing migrations are unchanged. New server config includes
`TRUSTED_PROXY_IPS`, `CAMPUS_READ_ONLY` and optional `FCM_SERVICE_ACCOUNT_FILE`.
Firebase client identifiers are public app configuration; service-account keys stay
on the server. Missing Firebase settings keep push disabled while pull/WSS work.

All remaining production/device acceptance gates are explicitly pending; no real
roster, representative, Firebase project or live cutoff was invented. A default
frontend validation build briefly wrote Nginx's served `frontend/dist`; it was
restored from pre-change source, and all subsequent builds use `/tmp` output.
No live database migration, backend deployment/restart, credential change, Gist
mutation or `projects/app/` APK replacement has been performed.

Final local validation: 41 backend integration tests, Clippy with warnings denied,
optimized backend build, 15 Android unit/Robolectric tests and v15 debug assembly,
19 web unit tests, the class-manager browser suite, the existing responsive workspace
browser suite, 19 offline notes regressions and 5 importer/backup tests passed.
The release binary also passed a disposable staging smoke test covering migrations
1–9, verified-fixture bootstrap, authorship, retries, access denial, sync and online
backup/restore integrity. The v15 APK signature matches the pre-existing packaged
APK. No Android device was connected, so hardware/device and live Firebase checks
remain pending.

Read-only legacy inventory fetched two public course Gists with four alerts and
no duplicate source IDs. All four remain unimported pending authoritative class,
subject/timezone and author mappings. The local inventory is
`/tmp/akatsuki-legacy-inventory/report.json`; exports were not written to GitHub or
SQLite. The live health check remains healthy on the existing backend, and the
served frontend was verified against the restored pre-change build.

The final reviewable package is `/tmp/akatsuki-campus-final-candidate/`, with the
release backend, isolated web build, signed v15 APK, OpenAPI, importer/preflight
utilities, validation report, staging smoke report and SHA256 manifest. It is a
local release candidate, not a completed live pilot or published GitHub Release.

The earlier M0–M3 record below is historical and is superseded by this continuation
and the current implementation document where features have since advanced.

## Implementation record (2026-10-21)

The sections below retain the architecture and later-milestone plan. Actual M0–M3
contracts and limitations are documented in
[`/home/max/akatsuki/docs/campus-alerts.md`](/home/max/akatsuki/docs/campus-alerts.md)
and [`campus-alerts-openapi.json`](/home/max/akatsuki/docs/campus-alerts-openapi.json).
These take precedence over the original API examples where noted here.

| Milestone | Local result | Remaining acceptance work |
| --- | --- | --- |
| M0 | OpenAPI with 32 schemas/17 operations; shared fixtures for two classes and three verified users | Real class mapping, authoritative roster and first representatives before pilot |
| M1 | Campus home entry, saved ERP credential reuse, dedicated trusted client, encrypted campus token/profile/class binding, cached offline profile and isolated logout | Installed-device Keystore/lifecycle/WorkManager checks and real ERP verification |
| M2 | Migration 0005, directory, approval/roll-bound invites, roster/role controls, audited operator bootstrap | Production bootstrap/roster operation deliberately not executed |
| M3 | Versioned CRUD, personal state, durable sync/tombstones, persisted scheduler/outbox, socket reauthorization, offline importer inventory | Real Gist inventory, transactional final importer, staging data validation and later client integration |

User correction: Campus reuses the app's saved ERP login and valid Akatsuki session;
there is no second credential form. Opening Campus establishes a token through the
existing ERP-login route when needed. Expiry/401 permits one bounded reconnect;
rejected credentials require fixing the attendance app login. ERP cookies are not
Akatsuki Bearer tokens. GitHub credentials remain entirely separate.

Actual contract choices:

- Class creation/initial subjects and representatives use `--campus-bootstrap FILE`,
  an explicit audited operator CLI; `POST /v1/classes` is unavailable. Initial
  timezone support is `Asia/Kolkata` and `UTC`.
- Alert create accepts `{ "idempotency_key": "...", "content": { ... } }`.
  POST publish also takes a body idempotency key. PATCH replaces editable content
  using `expected_version`; DELETE takes that version in its JSON body. Role
  changes use `expected_generation`.
- Initial sync replays immutable full representations to a fixed high-water mark;
  no separate mutable snapshot. Merge content/personal-state arrays by sequence,
  apply cursor atomically, and suppress initial notifications. Cursors bind
  account/class/role/membership generation and expire after 30 days; reset is 410.
  Retention/pruning is disabled pending a rollout policy.
- Scheduled/draft content stays private. Already-expired missed schedules expire
  privately. Public content cannot return to private status; expired content stays
  expired when edited or restored from personal trash.
- Outbox delivery means attempted local broadcast, never a phone delivery receipt.
  Events contain references only, have stable IDs, and are reauthorized on open
  sockets. One backend process remains required. Optional push is absent.
- `legacy_alert_imports` supports future idempotent import, but the delivered script
  is an offline **dry run only**. It preserves originals, reports duplicates and
  missing mappings, and converts date-only deadlines to class-local 23:59:59.
  It does not grant author ownership from display strings or modify Gists/SQLite.
- Android Room stays at version 14 and the Gist alert writer remains intact until
  M5; the Campus profile cache is encrypted separately. M4 web screens, M5 Room
  sync/outbox, M6–M7 native opportunities/chat, M8 cutover and M9 push remain future.

Local validation: Android `:app:testDebugUnitTest :app:assembleDebug` passed
(8 campus tests); 19 existing offline notes tests passed; backend `cargo test
--locked` passed (35 tests), Clippy with warnings denied and formatting passed;
2 importer inventory tests passed. The 32 OpenAPI schemas, 17 route definitions
and shared create fixture were checked with JSON Schema/reference validation.
No production database migration, bootstrap, live-account login, deployment,
restart, Gist mutation or packaged APK replacement in `projects/app/` was performed.
The debug build artifact is `app/build/outputs/apk/debug/app-debug.apk`.

## 2. Repositories and observed baseline

| Component | Location | State inspected |
| --- | --- | --- |
| Android app | `/home/max/orca/workspaces/MIT_Attendance/mit-app` | Kotlin, Room, Retrofit/OkHttp, WorkManager; Git HEAD `2d0a7d5` plus substantial uncommitted changes |
| Akatsuki | `/home/max/akatsuki` (`~/akatsuki/`) | Rust/Axum/Tokio/SQLx/SQLite backend and React/TypeScript frontend; Git HEAD `a3dea10` plus substantial uncommitted changes |
| This plan | Android repository root | `ALERTS_AKATSUKI_IMPLEMENTATION_PLAN.md` |
| Previous handoff | Android repository | `docs/student-owned-notes-and-updates.md` |

Both working trees are dirty. Re-read their current files and `git status` before editing; preserve existing changes. Do not reset either repository or overwrite the existing APK in `projects/app/`. Any `AGENTS.md` instructions must be checked again in the next session; the Android root currently has a dangling link to `/home/max/AGENTS.md`.

### Android alerts today

- `data/repository/AlertRepository.kt`: reads a parent Gist index and per-course `alerts.json`/`meta.json`; writes require the Gist owner's PAT. Whole-file read/modify/write permits lost updates between writers.
- `data/api/AlertApiService.kt` and `AlertRetrofitClient.kt`: GitHub Gist APIs.
- `model/AlertModels.kt`: shared content and local flags currently occupy the same `AlertEntity`.
- `data/db/AlertDao.kt`: read, hide, trash, restore, expire, and seen operations.
- `ui/alerts/AlertsActivity.kt`: class/subject alerts, posting, local trash, and reminder UI.
- `service/AlertSyncWorker.kt`: immediate and approximately 30-minute periodic polling. Fetch exceptions are often swallowed by the repository, so a failed fetch can currently look like success.
- `ui/alerts/AlertReminderReceiver.kt`: device-local reminders. Preserve this behavior.
- `data/db/Database.kt`: Room schema version **14**, explicit migrations; subject badge queries join alerts by subject name and need account/class scoping during this work.
- `UserPreferences`: course name, roll number, legacy Gist ID and alert version. These display strings are not sufficient server authorization.
- `SubjectsActivity`, login/logout, and `MITAttendanceApp` control navigation and worker/notification setup.

Prior-session validation: the notes implementation passed 19 offline Git tests and `:app:assembleDebug`. Live personal-PAT provisioning and Android installation were not verified on a device. These results do not validate any future server integration.

### Akatsuki today

- Real ERP login: `POST /api/auth/erp-login`, body `{username,password}`; returns `{token,user}`. Identity is based on the verified ERP roll number, not a client-asserted email.
- `GET /api/auth/me` returns current profile and counts. Bearer JWTs currently expire after seven days; there is no refresh/revocation service yet.
- Existing authenticated jobs, applications, conversations, messages, and image APIs. Preserve their current routes and contracts.
- Native WebSocket clients can use an Authorization header on `/ws`. Events currently include connection, message, typing, and `resync_required`; delivery is best effort and the broadcast bus is process-local.
- ERP profile extraction supplies roll number, name, and course name. The user model stores course name in `department`; it does **not** establish semester/division/class memberships.
- SQLx migrations currently include `0001_initial.sql` through `0004_description_images.sql`. Choose the next migration number after rechecking the tree; do not modify applied migration checksums.
- Entry points: `backend/src/main.rs`, `handlers/auth.rs`, `middleware/auth.rs`, `utils/erp_client.rs`, `handlers/ws.rs`, `db.rs`, `models/`, `backend/src/tests.rs`.
- Frontend: `frontend/src/api.ts`, `frontend/src/workspace/api.ts`, `Workspace.tsx`, `Opportunities.tsx`, and `Activity.tsx`. It already implements campus discovery, applications, listing management, and private conversations.
- Read `README.md`, `backend/README.md`, `deploy/README.md`, and `docs/backend-audit.md`. The audit is dated 2026-10-13; parts describing frontend/deployment absence are older than the current working tree.

### Deployment observed, without changing it

Read-only checks during planning found `akatsuki-backend` and Nginx active, and `https://mitasia.in/akatsuki/api/health` returned healthy with image uploads enabled. Recheck next session; this is a point-in-time observation, not a guarantee that deployed code matches the checkout.

Deployment files describe:

- Public web app: `https://mitasia.in/akatsuki/`.
- Android API base: `https://mitasia.in/akatsuki/api/`.
- WebSocket URL: `wss://mitasia.in/akatsuki/ws`.
- Nginx strips `/akatsuki` when forwarding API/socket requests to `127.0.0.1:5001`. Avoid doubling the prefix.
- systemd service: `akatsuki-backend`; binary under `/opt/akatsuki/bin`; database under `/var/lib/akatsuki`; configuration at `/etc/akatsuki/backend.env`; backups under `/var/backups/akatsuki`.
- Do not print `.env` files, `/etc/akatsuki/backend.env`, signing secrets, or the checkout's `key` file. No deployment, database migration, service restart, or credential changes were performed for this plan.

## 3. Recommended architecture

Add a class/alerts module to the existing Akatsuki backend, sharing its verified users and authentication middleware. Keep alerts as a distinct domain with its own tables, handlers, authorization, and tests. Initially use the same SQLite database and service: there is no demonstrated need for a second process, duplicate ERP integration, or another authentication system.

If a separate alerts service is later required, extract it behind the same API contract and an explicit token-verification boundary. Do not simply copy JWT signing secrets into unrelated services.

```text
Android app                            Existing Akatsuki React app
  campus session + Room                 campus session + alerts manager
           \                                      /
            HTTPS REST APIs / foreground WSS
                         |
          Nginx -> Akatsuki Rust backend
                         |
        verified users + classes + memberships
             /                         \
      class alerts                 jobs/applications/chat
      scheduling + sync            existing domain rules
             \                         /
             SQLite + durable event outbox
                         |
          optional push delivery adapter (later)
```

REST and committed database rows are authoritative. WebSocket events and push messages tell clients to refresh; they do not bypass authorization or carry the entire database state.

## 4. Product defaults to implement

1. A class identifies a program/course, academic year, semester, and division, with an immutable server UUID. Subject IDs are scoped to that class.
2. Every active verified class member may create an alert. Authors may edit/delete their own alerts; class representatives may moderate any alert in their class. Ordinary members cannot edit another author's alert.
3. Class representatives manage membership and scheduling. Platform operators bootstrap the first representative through an audited administrative operation. A client cannot grant itself a role.
4. Joining requires representative approval or an invite bound to an approved roll number/roster. An unrestricted shared code alone must not grant private class access. ERP course text can suggest a class, but cannot prove semester/division membership.
5. Start with plain text alerts: type, subject (optional), topic, description, due date/time, and optional scheduled publication. No attachments or arbitrary HTML in the first release.
6. Distinguish server deletion, expiration, and personal state. Hide/trash/seen are personal actions; only authorized deletion removes content for classmates.
7. Native Akatsuki integration is the target. An external browser entry can be an interim link, with its own sign-in; do not inject JWTs into URLs or use a broad WebView JavaScript bridge as session sharing.

Defaults that need recording before rollout: who the first representative is; the authoritative class roster; and whether all members may publish or publication should be representative-only. Work can proceed with the defaults above and test fixtures before those production values are supplied.

## 5. Authentication and authorization

### Android session

- Add `CampusSessionStore` separate from `GitHubAccountStore`: Keystore-protected campus token, server user ID, expiry, and account-scoped class selection. Exclude credentials from Android backups and logs.
- **User correction (2026-10-21):** reuse the attendance app's saved ERP credentials to establish the campus session; no duplicate credential form. Send them only over HTTPS to the trusted Akatsuki endpoint. Reuse a valid campus token, and reconnect once on expiry/401 without retry loops. ERP cookies remain separate from Akatsuki Bearer authentication.
- The server verifies ERP credentials using the existing ERP client and does not retain the password. The app's existing direct-ERP attendance flow can remain separate in this release; do not assume an ERP cookie is an Akatsuki token.
- Use `/auth/me` to validate the session and populate current identity. Backend authorization always uses the authenticated server user and current membership, never a supplied `posted_by`, roll number, or course string.
- Retain the current seven-day session. Per the user correction, on `401` allow one reconnect with saved ERP credentials, then stop on failure; do not loop or invent refresh endpoints. ERP downtime must not remove cached notes/attendance.
- Campus sign-out/account switch cancels account-scoped workers, sockets, pending notification delivery, and device bindings; it clears tokens and isolates cached class/chat data. It must not submit one student's pending edits under another student's session.

### Server enforcement

- Reuse `AuthUser` and verified account checks. Add a shared membership/role guard for each class/alert endpoint, checked again inside mutation transactions.
- Audit role changes, membership approvals/removals, and moderation. Prevent removal of the last active representative without an explicit replacement/operator action.
- Decide whether immediate session revocation is required before campus-wide rollout. Current JWT expiry alone does not revoke a stolen token; membership removal must nevertheless take effect immediately on every class API/socket subscription.
- Preserve Akatsuki job owner and two-participant conversation rules. Class membership does not authorize access to unrelated private job conversations.
- Add bounded mutation rates by authenticated user/class. Address the documented reverse-proxy IP rate-limit limitation using explicitly trusted proxy handling or edge rate limiting, not untrusted forwarded headers.

## 6. Data model and consistency

Add SQLx migrations, indexes, foreign keys, and constraints for:

| Table | Principal fields / invariant |
| --- | --- |
| `classes` | UUID, program, academic year, semester, division, display name, IANA timezone, active flag; unique normalized class identity |
| `class_subjects` | UUID, class UUID, code/name, active flag; no authorization based on a free-text name |
| `class_memberships` | class UUID + user UUID, role (`MEMBER`/`REPRESENTATIVE`), state, joined/removed timestamps; unique pair |
| `class_join_requests` | user/class, pending/approved/rejected state, reviewer; prevent duplicate pending requests |
| `class_invites` | hashed, expiring invite secret plus intended roll/roster binding, use limits, issuer; never return hashes as credentials |
| `class_alerts` | UUID, class UUID, optional subject UUID, type, topic, description, author UUID (nullable for unmapped legacy imports), author display snapshot, status, publication/due/expiry UTC timestamps, integer version, deletion timestamp, server timestamps |
| `alert_user_state` | user UUID + alert UUID, seen/hidden/trashed timestamps and version; content updates preserve personal state |
| `class_alert_changes` | committed monotonic sequence, class UUID, alert UUID, operation, version, visibility; durable incremental sync and tombstones |
| `campus_outbox` | unique event ID, payload/reference, availability time, attempts, claim/lease, delivered status; committed with the domain mutation |
| `campus_audit_log` | actor, class/resource ID, action, timestamp, safe change summary; no passwords or tokens |
| `device_installations` (push phase) | installation UUID, user UUID, provider token, notification preferences, last-seen timestamp; private and removable on logout |
| `legacy_alert_imports` | source Gist ID + legacy alert ID -> new alert UUID, checksum/import batch; idempotent import |

Alert status defaults: `DRAFT`, `SCHEDULED`, `PUBLISHED`, `EXPIRED`, `DELETED`. Specify allowed transitions in one server module. Scheduled/draft content is visible only to its author and representatives; it must not enter member-visible sync feeds or events early.

Use UTC instants for transport/storage and a class timezone for date-only deadlines and display. Do not infer the class timezone from this development machine's Europe/Berlin setting. Existing `YYYY-MM-DD` deadlines need an explicitly selected class timezone, usually `Asia/Kolkata`, with documented end-of-day conversion.

Use a transaction for content, version checks, change-feed entry, audit entry where required, and outbox insertion. Updates require an expected version (`If-Match`/ETag or one documented body field). A stale edit returns `409`; it must not overwrite a concurrent edit.

POST mutations accept an idempotency key scoped to actor, endpoint, and request content. Store the result transactionally so a retry cannot duplicate a published alert. Personal-state updates are idempotent by user/alert.

## 7. Proposed API contracts

Add new functionality under backend `/api/v1/...`, exposed as `/akatsuki/api/v1/...`. Existing unversioned Akatsuki routes remain compatible. Publish an OpenAPI document and shared example fixtures before building both clients. Define pagination, timestamps, nullable fields, limits, and structured errors centrally.

| Method | New backend route | Purpose |
| --- | --- | --- |
| GET | `/api/v1/classes` | Current user's memberships and available actions |
| Operator CLI | `--campus-bootstrap FILE` | Implemented class/subject creation and first representative assignment; no HTTP bootstrap |
| GET | `/api/v1/classes/:class_id/subjects` | Authorized subject directory |
| POST | `/api/v1/classes/:class_id/join-requests` | Request membership using approved discovery/invite rules |
| GET/PATCH | `/api/v1/classes/:class_id/join-requests[/:id]` | Representative lists/approves/rejects requests; implement explicit routes |
| GET/PATCH | `/api/v1/classes/:class_id/members[/:user_id]` | Representative roster and role/state changes; implement explicit routes |
| POST | `/api/v1/classes/:class_id/invites` | Representative issues a bounded invite |
| GET/POST | `/api/v1/classes/:class_id/alerts` | Authorized paginated feed / create alert |
| GET/PATCH/DELETE | `/api/v1/classes/:class_id/alerts/:id` | Read, versioned edit, soft-delete with role checks |
| POST | `/api/v1/classes/:class_id/alerts/:id/publish` | Publish now or schedule; author/representative authorization |
| GET | `/api/v1/classes/:class_id/alerts/sync?cursor=...&limit=...` | Changes, tombstones, personal-state changes, and next cursor |
| PUT | `/api/v1/classes/:class_id/alerts/:id/state` | Set current user's seen/hidden/trashed state |
| GET | `/api/v1/classes/:class_id/alerts/:id/history` | Author/representative history according to policy |
| PUT/DELETE | `/api/v1/device-installations/:installation_id` | Bind/update/remove current user's push destination, when enabled |

Use the existing `/api/auth/erp-login`, `/api/auth/me`, `/api/jobs`, `/api/applications`, `/api/conversations`, `/api/images`, and `/ws` for Akatsuki initially. Do not assume new refresh, logout, search, message-cursor, or notification-count endpoints exist.

Original content example (actual create body wraps this in `content` alongside `idempotency_key`; see the implemented OpenAPI):

```json
{
  "subject_id": "subject-uuid-or-null",
  "type": "HOMEWORK",
  "topic": "Unit 2 assignment",
  "description": "Submit the assignment before the deadline.",
  "due_at": "2026-11-02T12:30:00Z",
  "publish_at": null
}
```

The author, class access, timestamps, UUID, and initial version come from the server. Return the created canonical entity; Android uses that response rather than inventing server fields.

Example sync envelope:

```json
{
  "changes": [
    {"sequence": 241, "operation": "upsert", "alert": {"id": "alert-uuid", "version": 3}},
    {"sequence": 242, "operation": "delete", "alert_id": "deleted-uuid", "version": 4}
  ],
  "user_state_changes": [],
  "next_cursor": "opaque-server-cursor",
  "has_more": false,
  "server_time": "2026-10-21T12:00:00Z"
}
```

The example alert is abbreviated; document the complete DTO in OpenAPI. Bind cursors to account, class, and feed version; reject altered/wrong-class cursors. Initial snapshots and pagination need a consistent high-water mark so concurrent edits are not skipped. Upserts must contain the latest visible content or produce a subsequent change; never advance a cursor past unprocessed data. Expired cursor retention returns an explicit reset-required response, not an empty successful feed. Personal-state changes need an independent per-user sequence or an equivalently correct combined cursor.

API errors should distinguish authentication, membership, validation, stale version, rate limits, and temporary service failure, with a stable code and safe message. Support cancellation and bounded bodies. Return `404` for cross-class resource lookups where existence should remain private.

## 8. Server alerts manager

### Backend responsibilities

- CRUD and moderation with server-derived authorship, membership checks, validation, and versioning.
- A scheduler scans persisted due work, claims it transactionally, and publishes/expires alerts exactly once at the domain-state level. Restarting after a missed publication time must catch up.
- An outbox worker delivers events at least once with retry/backoff and stable event IDs. Consumers deduplicate. Do not claim exactly-once network delivery.
- Start with one process, matching Akatsuki's current broadcast architecture. Use persisted claims/leases so restart recovery works; document that multiple replicas require a shared event fan-out design.
- Publish class-filtered `alert.created`, `alert.updated`, and `alert.deleted` events only after commit. Filter by current membership, including when membership is removed during an open socket.
- Add metrics/logs for scheduler delay, oldest undelivered outbox row, sync failures, rejected writes, and authorization failures. Logs must redact credentials and private descriptions.

### Web management interface

Extend the existing Akatsuki React workspace with **Class alerts**, not a second unrelated admin login:

- Class selector, subject filter, active/archive/scheduled views.
- Compose/edit form with preview, timezone-aware scheduling, and explicit delete confirmation.
- Author controls and representative moderation controls based on server-returned capabilities.
- Membership requests, roster/invites, and representative role management for authorized users.
- Version conflict UI that reloads current content and preserves the editor's unsent draft.
- Delivery/schedule status from persisted server state; do not equate a queued event with a delivered phone notification.

No need to build a general-purpose control panel, attachment system, or automation engine in the first milestone.

## 9. Android alerts implementation

1. Add a dedicated campus HTTP client with trusted HTTPS base URL and campus Bearer token. Never attach the GitHub PAT or unrelated ERP cookies. Normalize `/akatsuki/api/` URL joining in one place and test it.
2. Replace Gist DTOs/network paths in `AlertRepository` with the server API. Keep Gist reads only in an explicit migration/legacy adapter during the transition.
3. Add a Room migration from the then-current version (currently 14). Scope cached alerts and cursors to server user + class; split canonical content from user state. Include server version, tombstone, publication/due/expiry fields, and sync status.
4. Scope subject badges and all alert queries to the active account/class. Match subjects by server ID with a deliberate local ERP-subject mapping; do not leak old-class counts through a subject-name-only join.
5. Apply each sync page and its cursor in the **same Room transaction**. Preserve local personal state and unapplied outbox edits. Tombstones remove content from active views on every device.
6. Show cached feeds offline. Queue creates/edits/deletes in an account-scoped local outbox with idempotency keys and expected versions. Mark pending/failed/conflicted items visibly; permit retry/cancel. Never retry a `403` or stale edit as though it were a network failure.
7. Reuse the existing alert screen and notification channel where appropriate. Add class switching, author identity, explicit edit actions, scheduling permissions, and separate hide/trash versus delete-for-everyone actions.
8. Retain local reminders but reconcile them on alert edits/deletions/expiry and account/class changes. Define whether a restored personal trash item remains expired instead of immediately being auto-trashed again.
9. Use ViewModel-managed state for loading, drafts, and errors across rotation. UI network operations should be cancellable; repository errors must reach workers/UI instead of returning a misleading zero-new-alert count.

## 10. Akatsuki inside the Android app

Add an **Akatsuki / Campus** entry to home tools. Use native Kotlin screens backed by the existing server APIs; keep a browser fallback to the web workspace for features that have not yet been ported.

Implement in this order:

1. Campus session/profile using saved attendance ERP credentials, with cached read-only profile while offline.
2. Opportunity list and detail: existing search/category/location/department/status filters, loading and pagination, open/closed status, and authenticated description images.
3. Apply flow and **My applications**: pitch, optional resume URL, duplicate/closed-job handling, and actual server application status.
4. **My listings**: create/edit/close/reopen and applicant status controls. The server remains the authority on ownership and allowed transitions.
5. Conversations: inbox, job context, message history, unread counters, send/retry, and foreground WebSocket updates. Closing a job must preserve existing chats.
6. Notification deep links to a specific class alert, job, application, or conversation. Check session and access before navigation; queue the navigation target through sign-in without placing credentials in the URL.

The current message API uses limit/offset and marks returned incoming messages read. Account for overlapping pages and deduplicate by message ID. Do not show delivery/read guarantees beyond the server's actual semantics. If introducing message cursors or idempotent sends, implement and test an additive backend contract first.

Description images use authenticated `/api/images/:id` reads and a separate upload limit. Do not expose private campus images through an unauthenticated image loader or forward a campus token to arbitrary image hosts.

## 11. Synchronization and notifications

### Required first release

- Pull on screen entry/refresh and enqueue unique account-scoped WorkManager sync with network constraints and exponential backoff.
- Keep roughly the existing 30-minute periodic interval initially. Android periodic work is deferred and inexact; it is not instant delivery or an exact scheduler.
- Foreground WebSocket events trigger deduplicated REST sync. Reconnect with backoff; handle expiry and `resync_required`; REST fills gaps after disconnection.
- User notification preferences control notifications, not whether the cache is allowed to synchronize. Check Android notification permission before showing notifications.
- Dedupe notifications by account/resource/event version, so poll, socket, and later push paths cannot announce the same alert repeatedly. Initial migration/snapshot must not notify every historical alert.
- Logout cancels both immediate and periodic work; clear account-specific pending intents and notifications. A worker checks its expected account before applying data or announcing it.

### Optional next phase: push

Add a provider adapter, preferably FCM if the owner wants Google push and provides a Firebase project. The plan does not assume credentials or an existing project. Polling and foreground WSS must work without it.

Store installation tokens server-side, bind them to authenticated users, and invalidate/rebind on account changes. Choose recipients from current memberships and preferences, not client-supplied topics. Push a minimal resource/event reference, then fetch authorized content; no ERP credentials, JWTs, PATs, or private message body on the transport/lock screen by default. Handle token rotation and invalid-token responses.

Use normal priority for ordinary sync; reserve high priority for appropriate time-sensitive, user-visible notifications. Push is best effort and not an exact alarm. Akatsuki messages/applications may reuse the outbox/provider adapter with distinct event types and authorization; do not automatically broadcast private messages as class alerts.

## 12. Migration from Gists

1. Inventory legacy course-to-Gist mappings using read-only reads; create a dry-run report of counts, duplicate IDs, invalid deadlines, unknown subjects, and unmapped authors/classes.
2. Define mappings from legacy course names to new class UUIDs explicitly. A course label alone cannot safely divide historical records by academic year/semester/division.
3. Import idempotently using `(source_gist_id, legacy_alert_id)`. Preserve original timestamp and displayed author as legacy provenance. Map to a server author only when independently verified; never grant ownership merely because a string matches a current roll number.
4. Unmapped legacy records stay read-only/moderatable by representatives. Preserve Room seen/hidden/trash state using the import ID mapping, without treating it as a global deletion.
5. Back up and validate counts/content before cutover. Test a staging database and an upgraded Room database containing realistic local state.
6. Choose a cutover time: stop legacy Gist writes, perform the final import, enable server writes for the pilot class. Avoid uncontrolled dual writes, which reintroduce divergent authorities.
7. Updated clients use server alerts after class mapping/session setup; show an explicit legacy read-only view during transition. Old APKs cannot be redirected by a server flag they do not implement: communicate the required update and the legacy write cutoff.
8. Keep the old Gists and export for a defined rollback/archive period. Do not delete them as part of implementation.
9. GitHub `gist` scope is no longer needed for alerts after migration. Personal `repo` access remains solely for notes/backups.

Rollback after server writes begin requires preserving/exporting those writes. Do not restore an older database over new campus activity or silently switch writers back to Gists. Prefer disabling new mutations while retaining server reads and repairing forward.

## 13. Implementation milestones and acceptance gates

| Milestone | Deliverable | Acceptance gate |
| --- | --- | --- |
| M0: contracts and fixtures | Confirm defaults, class mappings, OpenAPI, sample data, deployment topology | No client-asserted identity/role trust; test fixtures cover two classes and multiple students |
| M1: campus Android session | Protected campus client/store, sign-in/profile, isolated logout | `401`, ERP outage, account switch, and token expiry work without losing existing attendance/notes |
| M2: class foundation | Migrations, membership/invite/representative APIs and bootstrap operation | Cross-class access denied; join/role races tested; production role assignment is audited |
| M3: server alerts | Versioned CRUD, state/sync, scheduler, durable outbox, importer dry run | Concurrent edits do not overwrite; retries do not duplicate; restart catches scheduled work |
| M4: web alerts manager | Extend Akatsuki React workspace with authorized alert/roster views | Member/representative permissions match backend; stale edits preserve unsent content |
| M5: Android server alerts | Room migration, sync/outbox, existing UI replacement, reminders/badges | Offline/reconnect, deletes, state preservation, rotation, account/class switch pass |
| M6: Akatsuki read/apply | Native opportunity, detail, application and profile screens | Contract/pagination/authorization tests; browser fallback is explicit |
| M7: Akatsuki manage/chat | Native listing management, applicants, inbox/chat, WSS/deep links | Owner/participant privacy, closure rules, reconnect gaps and unread behavior pass |
| M8: pilot and cutover | Backups, final import, one-class pilot, monitored rollout | Counts checked, no notification flood, rollback procedure reviewed |
| M9: optional push | Provider adapter and private installation bindings | No dependency on always-running background sockets; duplicate and stale-device tests pass |

Build M2/M3 on the server before replacing Android's Gist writer. M1 can be developed against existing auth immediately. M4 and M5 follow the same published M3 contract. Do not delay reliable alerts solely to finish every native Akatsuki screen or optional push.

## 14. Verification

### Backend integration tests

- ERP fixture validation, unknown/unverified/expired accounts, missing credentials, and existing legacy-account conflicts.
- Two classes, multiple members, one representative per class, an outsider, and a removed member.
- Attempt forged author IDs, altered class/subject IDs, invalid invitations, role escalation, and cross-class UUID lookups.
- Concurrent create/retry, stale edits, delete/edit races, role/membership changes during writes, and uniqueness constraints.
- Scheduled publication/expiry across restart, scheduler clock tests, outbox retries, duplicate event delivery, and no pre-publication leak.
- Snapshot/cursor pagination during concurrent writes; tombstones; personal-state cursors; expired cursor reset; removal of access while a socket is open.
- Gist import rerun, unmapped authors/classes, malformed old dates, and original record preservation.
- Existing Akatsuki jobs/applications/private conversation and image tests must remain green.

### Android and web

- Mock-server contract tests for URL prefixes, auth headers, `401`/`403`/`409`/`429`/`5xx`, cancellation, and pagination.
- Room migration tests with real old schema/state; sync transaction/cursor atomicity; pending outbox replay and account isolation.
- Device tests for rotation, notification permission denial, Doze/background delay, process restart, sign-out during work, reminder changes, and deep-link sign-in/access checks.
- Web tests for class/role switching, edit conflicts, scheduling timezone display, pending membership actions, and existing campus workflows.

Baseline commands (recheck toolchains and existing scripts):

```sh
# Android workspace
ANDROID_HOME=/home/max/android-sdk ./gradlew :app:assembleDebug
/tmp/mit-notes-tests/bin/python -m unittest discover -s tests -v
# Add targeted Room/MockWebServer/instrumentation tasks with the implementation.

# Akatsuki workspace
cargo fmt --check --manifest-path /home/max/akatsuki/backend/Cargo.toml
cargo clippy --locked --manifest-path /home/max/akatsuki/backend/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path /home/max/akatsuki/backend/Cargo.toml
npm --prefix /home/max/akatsuki/frontend test
AKATSUKI_BASE_PATH=/akatsuki/ npm --prefix /home/max/akatsuki/frontend run build
```

The temporary notes-test environment may not exist in a later session; recreate it using the preceding notes handoff if needed. No real ERP passwords or production accounts belong in fixtures.

## 15. Deployment and operations

- Reinspect the installed unit, current route prefixes, existing Nginx site, database version, and health response. Do not assume source files equal deployed files.
- Create a consistent SQLite online backup and test restoration before applying migrations; do not copy only the main file of an active WAL database and assume the backup is complete.
- Preserve existing Akatsuki users, opportunities, chats, image blobs, config, and migration history. Test the new schema against a staging copy first.
- Add health/capability metadata for class alerts, supported API/feed version, and optional push. Clients handle an older backend gracefully.
- Keep production local-auth/demo flags disabled, and preserve existing configured signing secrets. Supply any push credentials through server configuration, never APK BuildConfig or Git.
- Keep one backend process initially. If scaling out, replace process-local socket broadcasting with an appropriate shared broker and test scheduler/outbox ownership first.
- Verify Nginx request limits, CORS for the existing browser origin, WebSocket upgrade forwarding, and safe authentication logging. Native Android is not protected by browser CORS; backend authorization remains required.
- Monitor database disk size (including existing image storage), backups, outbox lag, scheduled publication delay, API errors, and membership failures. Define retention for tombstones/change feeds, delivered outbox rows, audit rows, device bindings, and archived alerts before pruning.
- Deployment is a separate action from this plan. Prepare concrete artifacts, migration reports, backup paths, smoke-test commands, and rollback steps before any live service/database changes.

## 16. Decisions to resolve during implementation

These do not block writing contracts or local tests. Resolve before the affected production feature ships:

- Confirm adding alerts as an Akatsuki module versus a separately operated service; this plan recommends the module.
- Identify initial class definitions, roster source, first representatives, and how new classes are approved.
- Confirm member publishing rights and representative moderation policy; recommended defaults are in section 4.
- Choose exact expiry/date-only semantics, retention periods, and the class timezone.
- Decide whether seven-day sessions are acceptable initially or whether revocable sessions/refresh must precede rollout.
- Confirm scope/order of native Akatsuki screens; the plan provides an incremental order and browser fallback.
- Decide on a push provider and provide its project configuration if faster background delivery is needed. Do not imply polling is instant.
- Agree on the legacy Gist write cutoff and how students with old APKs will be informed.

## 17. Next-session starting prompt

> Read `ALERTS_AKATSUKI_IMPLEMENTATION_PLAN.md` in the Android repository and `docs/student-owned-notes-and-updates.md`. Inspect the dirty working trees in this repository and `/home/max/akatsuki` without resetting them. Implement M0 and M1, then the class/alerts server foundation M2–M3 using the recommended defaults unless I have supplied corrections. Keep personal GitHub notes and GitHub Releases updates working, and leave reviews alone. Use offline fixtures/tests first. Report production decisions separately; do not treat this planning document as authorization to migrate the live database, deploy, or restart services.

Update this plan's status and milestone acceptance results as implementation progresses; record actual API/schema changes instead of leaving stale proposals as facts.

## References

Local implementation details above were inspected directly in both repositories and through a read-only health check. Primary references for the delivery/operations choices:

- [Android WorkManager constraints, periodic work, and backoff](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [FCM Android message priority and background handling](https://firebase.google.com/docs/cloud-messaging/android-message-priority)
- [Android deep links and verified App Links](https://developer.android.com/training/app-links/create-deeplinks)
- [SQLite online backup API and consistent snapshots](https://sqlite.org/backup.html)
