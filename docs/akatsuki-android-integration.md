# Android Akatsuki integration

Current scope (2026-10-23): Android uses the independently deployed Akatsuki at
`https://mitasia.in/akatsuki/api/`, with foreground messages over
`wss://mitasia.in/akatsuki/ws`. Alerts are deferred. This work does not require
updating Akatsuki's backend, database, web build or Nginx configuration.

Open **Akatsuki** in the home tools, then **Opportunities, applications and chat**.
The saved attendance ERP login establishes a separate encrypted Akatsuki session;
expiry/401 permits one reconnect. There is no additional password form.

Implemented native flows:

- Explore/search/filter opportunities, paginate, view details and authenticated images.
- Apply with a pitch/resume link, review My applications and application details.
- Create/edit listings, close/reopen them, review applicants and change their status.
- View the inbox and job conversations, load older messages, send and explicitly
  retry failed messages. Foreground WebSockets trigger REST refreshes.
- Read cached content offline, preserve local message failures across restarts,
  isolate accounts and retain the browser workspace as a fallback.

`AkatsukiRepository` handles Android caching and compatibility without depending on
the alerts repository or class membership. The existing local Room tables are
reused; no new Room migration or server migration is needed for this follow-up.

Application details are resolved through the existing paginated
`GET applications/my` or, for a listing owner, `GET jobs/:id/applications` plus
`GET jobs/:id`. Android does not call the unpublished `GET applications/:id` route.
An owner application link can carry `?job=JOB_ID`; server authorization still
controls the returned list.

Messages use the deployed `POST conversations/:id/messages` contract with
`{content}`. No `message_idempotency` capability is required. Requests are not
automatically retried after connection failures. A lost response/interrupted send
is shown as unconfirmed, and an explicit resend explains that the earlier message
may already have arrived. The client does not claim exactly-once sends on this API.

The Akatsuki entry has no class-alert navigation or push-registration operation.
The earlier alerts code remains separate and unfinished for the new architecture.

Validation: offline `:app:assembleDebug` passed on 2026-10-23. Per the user's
fast-ship preference, no new tests were run. Real-account ERP login and installed-device interaction remain unverified in
this session. Build with:

```sh
ANDROID_HOME=/home/max/android-sdk ./gradlew --offline :app:assembleDebug
```

Artifact: `app/build/outputs/apk/debug/app-debug.apk`.

## Native presentation

Akatsuki shares the app's cream surfaces, rounded cards, Nunito text, outlined inputs,
and pastel actions. Forms and dialogs use the same shared presentation helpers.
Secondary card actions live under More; long content expands through Read more.

Images in opportunity descriptions, alerts, and messages use one compact attachment
row per item. No image is fetched or decoded in a card. Tapping the row opens an
internal full-screen viewer with fit-to-screen sizing, image paging, loading, and
retry states. It uses the existing authenticated image endpoint and checks the
originating account, session generation, and class before displaying an image.
Protected images are not stored in Glide's disk or memory caches. Returning to the
underlying screen leaves its layout independent of image dimensions.
