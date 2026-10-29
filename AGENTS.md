# Repository instructions

- Do not create, add, or run tests in this repository.
- Do not add test frameworks, test dependencies, or test infrastructure.
- Use build/compile checks and static inspection for validation; do not invoke test tasks.

## Publish APK builds

- After every successful APK build, publish the newly built APK to the local Nginx download directory so it can be installed on the phone. This publishing step is authorized by the repository owner and is part of completing a build.
- Nginx configuration: `/etc/nginx/sites-enabled/default` maps `/apks/` to `/var/www/apks/`. Preserve other apps and the existing index page.
- For debug builds, publish `app/build/outputs/apk/debug/app-debug.apk` as `/var/www/apks/MITAttendance-debug.apk`. For other APK variants, publish the actual new output using a stable, variant-specific filename.
- Publish only after the build succeeds. Copy to a temporary file in `/var/www/apks/` with mode `0644` and owner/group `www-data`, then atomically rename it to the final filename. Use `sudo -n` when necessary.
- Verify the published file matches the build output and that `http://localhost/apks/MITAttendance-debug.apk` (or the corresponding variant URL) serves the same bytes. Report the phone download link: `https://mitasia.in/apks/MITAttendance-debug.apk` for debug builds.
- Publishing does not require running tests. Do not publish a stale APK after a failed build.
