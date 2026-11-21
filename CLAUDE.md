# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Meteora is an Android app (Kotlin, Compose) that remote-controls YTMDesktop on a PC and can also play YouTube Music on the phone itself. Single `:app` module, package `dev.thathunky.meteora`.

## How to work here

- **Implementation goes to Opus, from a detailed plan.** For a feature or any change bigger than a small fix: write a detailed plan first as a file under `docs/` (what, why, every file to touch, the tests, how to verify), then have an Opus agent implement it from that plan (`Agent` with `model: "opus"`). Review its diff and run the build before reporting. `docs/quality-switch-plan.md` is the model for such a plan.
- **Keep the devlog going.** `docs/devlog.md` tells how the app was built, in order, including what broke and what was not verified. Add to it after each piece of work. It states only what was actually checked, and says so when something was not.
- **Verify on a phone when the change can be seen or heard.** Test phones are reached over wireless adb (`adb devices -l`). The OnePlus 7T reports `model=Pixel`, so never branch on the model name. Wireless adb comes back on a new port after Wi‑Fi drops; `adb mdns services` shows it.
- `PLAN.md` holds the numbered decisions (Q1…Q20) and milestones. `docs/api-notes.md` holds what was verified about the YTMDesktop companion API and YouTube's InnerTube API; read the relevant part before touching `companion/`, `ytm/` or `stream/`.
- **Commit after each step**, on `main` (the repo is local, with no remote): one commit per piece of work that builds and passes its tests, with a message that says what changed and why. Do not let a day's work pile up uncommitted.
- **Send the user the APK** after a build worth trying: copy it to the VPS and run the send script there, which delivers it through the user's Telegram admin bot.
  `scp app/build/outputs/apk/debug/app-debug.apk vps:tmp/meteora-debug.apk` then `ssh vps '~/bin/tg-send-file ~/tmp/meteora-debug.apk "<what is new>"'`.

## Commands

Run Gradle from **PowerShell**. From the Bash tool the Gradle client cannot reach its daemon on this machine ("Could not connect to the Gradle daemon").

```powershell
.\gradlew.bat :app:compileDebugKotlin --console=plain -q      # fastest check that it compiles
.\gradlew.bat :app:assembleDebug --console=plain -q            # APK at app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat :app:testDebugUnitTest --console=plain -q        # all unit tests (JVM)
.\gradlew.bat :app:testDebugUnitTest --tests "dev.thathunky.meteora.stream.InnerTubeStreamsTest"   # one class
adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
```

With `-q` a passing run prints nothing; test counts are in `app\build\test-results\testDebugUnitTest\*.xml`. There is no lint, Spotless or detekt task configured (PLAN.md mentions them; they were never added).

The Rust core (`rust/meteora-core`) is built for arm64 by the Gradle task `cargoBuildArm64` as part of the app build. Its own tests run on the PC:

```powershell
$env:METEORA_PLAYER_JS = "<path to a real YouTube base.js>"   # optional: enables the real-player solver test
cargo test --manifest-path rust\meteora-core\Cargo.toml
```

The Android build of the QuickJS binding needs specific compiler settings; they are in `app/build.gradle.kts` and explained in `docs/rust-core-plan.md` ("Build integration").

When two pieces of work would touch the same files, give each implementing agent its own git worktree (`isolation: "worktree"`), have it commit on its branch, and merge the branches into `main` afterwards. A worktree needs `local.properties` copied in.

Stream timing shows in logcat under the tags `InnerTubeStreams` (resolve time, tier, itag, bitrate) and `LocalEngine` (queue load time, "playing N ms after the tap").

## Toolchain

Deliberately bleeding edge (PLAN.md Q3): AGP 9.5, Kotlin 2.4, Compose BOM alpha, material3 1.6.0‑alpha01 (Expressive), Navigation 3, Media3 1.12 beta, compileSdk 37.2, JDK 25 toolchain. Do not trust memory for these APIs: check how the repo already uses one, or compile. Experimental opt-ins are set once in `app/build.gradle.kts`, not per file.

## Architecture

One player UI drives two playback targets. Everything goes through `data/PlaybackRouter`, which picks the target and exposes a single `StateFlow<PlaybackState?>` (`domain/Playback.kt`) that all player screens read.

- **PC target:** `data/PlayerRepository` owns the connection to YTMDesktop. `companion/CompanionClient` is REST, `companion/RealtimeClient` is the Socket.IO state stream, and `companion/CommandThrottler` keeps to the server's 2 commands per second (discrete commands queue, seek and volume keep only their latest value). The repository is ref-counted with `acquire()` / `release()`.
- **Phone target:** `playback/LocalEngine` wraps ExoPlayer. Queue items are `meteora://<videoId>`; a `ResolvingDataSource` asks `stream/StreamResolver` for the real URL when ExoPlayer opens an item. `StreamResolver` tries `WebMusicStreams` (signed in, High quality only: the 256 kbps formats, whose signature is solved in the Rust core), then `InnerTubeStreams` (one `/player` call as the visionOS client, about 0.1 s), then `YtDlpStreams` (bundled yt-dlp, about 15 s).
- **Rust core:** `core/MeteoraCore` is the JNI door to `rust/meteora-core`. Kotlin does all HTTP; Rust picks formats, parses pages, builds stream URLs and runs YouTube's signature JavaScript on QuickJS. Stream logic is tested with `cargo test`, not in Kotlin.
- **Themes:** `ui/theme/Themes.kt` lists them (seed-based and fixed palettes) and `ThemeStore` keeps the choice; the artwork shape comes from `LocalArtworkShape`.
- **Commands:** the UI sends `companion/Command` values either way. `LocalEngine.handle()` maps them onto ExoPlayer; the PC path serialises them to JSON.
- **Media session:** `playback/MeteoraSessionService` hosts one session and swaps its player when the target changes: `RemotePlayer` (a `SimpleBasePlayer` that plays nothing and mirrors the PC) or the engine's ExoPlayer.
- **Browsing:** `ytm/YtmClient` calls InnerTube as the `WEB_REMIX` client, signed with `SAPISIDHASH` from cookies captured in `ui/signin` and stored encrypted (`ytm/YtmAuthStore`, `data/SecureStore`). `ytm/YtmParser` searches responses for known renderers instead of following fixed paths, because YouTube rearranges its JSON.
- **UI:** `ui/shell/MeteoraShell` is the Navigation 3 shell with the nav bar; `ui/player/PlayerHost` is drawn over it and holds the mini player and the full player in one `SharedTransitionLayout`. The theme is built from the album art (`ui/theme`).

## Things that are easy to break

- **Companion commands:** a `null` id in `changeVideo` wipes the PC's queue, so command JSON is built by hand and absent ids are left out (`companion/Command.kt`). `changeVideo` on the already-loaded video does nothing; send `Play` instead.
- **Stream requests must have an exact byte range.** YouTube throttles an open-ended request to about twice playback speed. `LocalEngine` sets the request length from the file size in the `/player` response; without it the buffer never fills and the next track is not fetched ahead.
- **A track keeps the format it was first opened with** (`pinned` in `LocalEngine`). ExoPlayer reopens a stream on seek or stall, and a different format on the second open would mix two files' bytes. The disk cache key includes the itag for the same reason.
- **Network errors never skip a track.** `LocalEngine.onPlayerError` waits and retries the same track when offline; skipping would run through the whole queue.
- **`PlaybackState` changes several times a second** while the PC plays. Composables high in the tree must read narrow derived flows (`ShellViewModel.hasTrack`), not the whole state, and per-frame values (seek position, shape morph) are read inside draw or layer lambdas.
- **Cleartext HTTP** is allowed app-wide for the companion server only; `companion/HostPolicy` restricts it to private, link-local and tailnet hosts. YouTube clients are built with TLS-only connection specs.

## Text and strings

Every user-facing string exists in English (`res/values/strings.xml`) and Ukrainian (`res/values-uk/strings.xml`). The app is not affiliated with YTMDesktop, YouTube or Google, and says so on the pairing, sign-in and settings screens (`R.string.disclaimer`). It is sideload-only: phone playback is against YouTube's terms.
