# Plan: in-app updater

Written 2026‑11‑22 for an implementing agent. The user: "we also gotta build an autoupdater for the app". Meteora is sideload-only, published as alpha pre-releases at github.com/ThatHunky/meteora, each with one APK attached.

## What the user gets

- The app checks GitHub for a newer release: once a day when it is opened, and from **Check for updates** in Settings → About.
- When there is one: a card on Home's top and a row in Settings → About, "Version 0.1.0 alpha 3 is available", with the release notes, **Download and install**, and **Skip this version**.
- Download with progress (in the card and as a notification), then Android's install confirmation. Nothing is installed without the user's tap in this first version.
- Settings → About: **Check automatically** (on), **Include alpha releases** (on while only alphas exist), the installed version, when it last checked.

## Versions

Today `versionName = "0.1.0"` and `versionCode = 1`, and the first release is tagged `v0.1.0-alpha.1`. From now on:

- `versionName` carries the pre-release part: `0.1.0-alpha.2`. The tag is `v` + `versionName`.
- `versionCode` goes up by one with every published build. Set the next build to `versionName = "0.1.0-alpha.2"`, `versionCode = 2` in `app/build.gradle.kts`.
- `update/Version.kt`: parse and compare semver with pre-release identifiers by the semver rules (`0.1.0-alpha.2 < 0.1.0-alpha.10 < 0.1.0-beta.1 < 0.1.0`). Pure, with JVM tests, including tags that do not parse (ignored).

## Checking

- `GET https://api.github.com/repos/ThatHunky/meteora/releases?per_page=10`, no authentication, `Accept: application/vnd.github+json`, User-Agent `Meteora/<versionName>`. No cookies, nothing about the user. TLS only. The unauthenticated limit is 60 requests an hour per IP; one check a day is nowhere near it. A 403 or 429 means "try tomorrow", silently.
- Pick the highest version among releases that are not drafts, that have an asset ending in `.apk`, and (unless "Include alpha releases" is on) are not pre-releases. Newer than the installed `versionName` → an update.
- Check the real response shape with an anonymous `curl` first and save a trimmed fixture. Use the asset's `browser_download_url`, `size`, and `digest` (`sha256:…`) when the API gives one.
- `update/UpdateStore.kt` (preferences file `updates`): last check time, skipped version, the two switches.

## Downloading and installing

- Download with OkHttp to `cacheDir/updates/<tag>.apk`, resumable with a Range request, deleted after a successful install and on the next start if older than the installed version. Follow redirects (GitHub serves assets from another host); HTTPS only.
- **Verify before offering to install**, and refuse with a clear message otherwise:
  1. size equals the API's `size`, and SHA-256 equals `digest` when present;
  2. `PackageManager.getPackageArchiveInfo` on the file: package name equals ours, `versionCode` higher than installed, and the **signing certificate equals the installed app's** (`GET_SIGNING_CERTIFICATES`, compare the certificate bytes). This is what stops a tampered or differently signed file; Android would refuse it too, but the app should say why instead of failing in the installer.
- Install with a `PackageInstaller` session (`MODE_FULL_INSTALL`, `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`; Android still asks unless Meteora installed the current version itself, which is fine). Handle `STATUS_PENDING_USER_ACTION` by launching the confirmation intent, and report the failure statuses in words.
- Manifest: `REQUEST_INSTALL_PACKAGES`, and the receiver for the session's status. The first time, Android sends the user to "Allow from this source": explain that in the card before it happens.
- Progress notification on its own channel; reuse the notification permission the app already asks for.
- A debug build can only update to builds signed with the same key. Releases so far are debug-signed on one PC, so they update over each other; if the certificate differs, the message says to uninstall and install the new build by hand, with a link to the release page.

## UI

- New files under `ui/update/`: the card (shown on Home through a one-line call, like the connection banner), the About rows, a dialog with the release notes (Markdown shown as plain text is enough; links not needed).
- States: idle, checking, up to date, available, downloading with percent, verifying, ready, failed with the reason and Retry.
- Look-aware wrappers from `ui/components`. English and Ukrainian strings in one `<!-- Updates -->` block at the end of each `strings.xml`.
- Single-line additions only in `HomeLibrary.kt` and `SettingsScreens.kt`; another agent is working in `filter/`, `ui/filter/`, `ytm/` and `MusicRows.kt` / `ItemActions.kt` / `MeteoraShell.kt`.

## Release procedure (write it into CLAUDE.md, replacing the current release sentence)

1. Bump `versionName` and `versionCode`. 2. Build and test. 3. Commit, push. 4. `gh release create v<versionName> <apk named meteora-<versionName>.apk> --prerelease --target main` with notes. Add `scripts/release.ps1` that does 2 and 4 and refuses when the tag exists, the tree is dirty, or `versionCode` was not raised since the last tag.

## Tests (JVM)

Version parsing and ordering; picking the release from the fixture (drafts, no APK, pre-release switch, skipped version, older and equal versions); the once-a-day rule; the verification decision as a pure function over (expected size, digest, package, versionCode, certificate) with each mismatch.

## Rules

Gradle from PowerShell: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain -q`. No phone, no adb. Do not create signing keys or keystores and do not change signing configuration. Do not publish releases or push. Commit in steps; add a devlog section.

## Not in this version

Silent background installs, delta updates, a release (non-debug) signing key. The last one is the owner's decision: see the report.
