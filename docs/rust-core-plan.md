# Plan: a Rust core for Meteora

Written 2026‑11‑21 for an implementing agent. The user asked for the app's non‑UI logic ("the backend") to be written in Rust, for speed and memory. This plan covers the first slice, R1, in full, and outlines R2 and R3.

## What to expect, honestly

- **Speed:** the app's own logic takes milliseconds; the time on a tap is network wait and Android's native audio decoder (see the devlog's measurements). Rust will not shorten that.
- **Memory:** most of the app's memory is the Android runtime, Compose, images, ExoPlayer's audio buffer and, since the 256 kbps work, a hidden WebView used only as a JavaScript engine. Two places where Rust can lower it for real:
  1. **The signature solver.** A WebView brings a renderer process with it. QuickJS embedded in a Rust library is a few hundred kilobytes of code and a small heap. This is R1.
  2. **PC state updates.** YTMDesktop sends about 50 KB of JSON 4–15 times a second, and Kotlin builds a full object tree for each one. Parsing in Rust and handing Kotlin only what changed would cut that garbage. This is R2.
- The reviewer measures before and after with `adb shell dumpsys meminfo dev.thathunky.meteora` and writes the numbers in the devlog, whichever way they come out.

## Boundary: Rust does no I/O

HTTP, TLS, cookies and the connection pool stay in Kotlin (OkHttp). A second network stack in Rust would add megabytes to the APK and to memory and would need its own cookie and certificate handling. Kotlin fetches bytes; Rust decides what they mean. Rust is therefore pure logic plus the embedded JavaScript engine, which also makes it testable on the PC with `cargo test`.

## Layout

```
rust/meteora-core/
  Cargo.toml          # crate-type = ["cdylib", "rlib"]; lib name meteora_core
  src/lib.rs          # module list
  src/jni.rs          # the only file that knows about JNI
  src/streams.rs      # format picking, cipher parsing, URL building, page parsing
  src/solver.rs       # QuickJS + the yt-dlp solver scripts
  tests/              # host tests, reading the fixtures in app/src/test/resources/fixtures
```

- Kotlin side: `app/src/main/java/dev/thathunky/meteora/core/MeteoraCore.kt`, an `object` with `external fun`s and `System.loadLibrary("meteora_core")` in a guarded `init` (catch `UnsatisfiedLinkError`, expose `val available: Boolean`).
- Interface style: every native function takes and returns `String` (JSON where structured). No object handles, no callbacks from Rust into Kotlin. Results are `{"ok":true,...}` or `{"ok":false,"error":"..."}`.
- Never let a panic cross JNI: wrap each entry point in `std::panic::catch_unwind` and turn a panic into the error result. Keep `panic = "unwind"`.
- Crates: `jni` for the bindings, `serde` / `serde_json` for JSON. Check the current versions and APIs in their documentation before writing against them; do not rely on memory.
- Release profile: `opt-level = 3`, `lto = "fat"`, `codegen-units = 1`, `strip = true`.

## Build integration

- Tools already installed on this PC: Rust 1.98.1 with the `aarch64-linux-android` target, `cargo-ndk`, and NDK `30.0.16248370` under the SDK (`sdk.dir` in `local.properties`, `<sdk>/ndk/30.0.16248370`). If one is missing, say so in the report rather than installing something else.
- Add a Gradle `Exec` task in `app/build.gradle.kts`, `cargoBuildArm64`, that runs in `rust/meteora-core`:
  `cargo ndk -t arm64-v8a -o <app build dir>/rustJniLibs build --release`
  with `ANDROID_NDK_HOME` set from the SDK path. Declare inputs (`rust/meteora-core/src`, `Cargo.toml`, `Cargo.lock`) and outputs so Gradle skips it when nothing changed.
- Add `<app build dir>/rustJniLibs` to `android.sourceSets["main"].jniLibs.srcDirs` and make the JNI‑libs merge tasks depend on `cargoBuildArm64`. The app is arm64 only (`abiFilters`), so one target is enough.
- The `.so` is a build output: nothing under `app/src/main/jniLibs` is committed. Add `rust/meteora-core/target/` to `.gitignore`. Commit `Cargo.lock`.
- JVM unit tests cannot load an Android `.so`. Logic that moves to Rust is tested with `cargo test`, and its Kotlin tests are ported, not kept (see "Tests").

## R1: stream logic and the signature solver

Read the current Kotlin first: `stream/InnerTubeStreams.kt`, `stream/WebMusicStreams.kt`, `stream/JsSolver.kt` (interface and `WebViewJsSolver`), `stream/StreamResolver.kt`, `stream/StreamQuality.kt`, and [high-quality-plan.md](high-quality-plan.md) for the solver protocol and the URL rules. R1 moves the logic of those files into Rust and leaves the HTTP where it is.

### Functions (all in `streams.rs`, exposed through `jni.rs`)

| Function | In | Out |
|---|---|---|
| `pick_audio` | `/player` response text, tier (`Low` / `Normal` / `High`), `allow_cipher` | status, and the picked format: itag, bitrate, plain `url` or `cipher`, `content_length`, `expires_in_sec` |
| `parse_visitor_data` | `sw.js_data` body | the visitor id |
| `parse_player_page` | the `music.youtube.com` HTML | `js_url`, `player_id`, `client_version` |
| `signature_timestamp` | `base.js` text | the number |
| `stream_challenges` | a picked format | the `s` value (if ciphered) and the `n` value (if present) to solve |
| `build_stream_url` | a picked format, solved `s`, solved `n` | the final URL |

Same rules as the Kotlin has today, including: ceilings 56 000 / 160 000 / none on average bitrate, Opus weighted × 1.3, skip `isDrc` and non‑default audio tracks when there is an alternative, fall back to the lowest bitrate when nothing fits. Port them exactly; the Kotlin tests say what "exactly" means.

### Solver (`solver.rs`)

- Embed QuickJS through the `rquickjs` crate. Read its current documentation for the build requirements on Android (it compiles C with the NDK's clang, and may need bindings for `aarch64-linux-android`).
- One JavaScript runtime, owned by one dedicated thread inside the library; JNI calls send work to it over a channel and wait. QuickJS is not thread‑safe.
- Functions: `solver_init(lib_js, core_js)` evaluates the two scripts once (protocol in high-quality-plan.md); `solver_has_player(player_id)`; `solver_prepare(player_id, player_js)` runs the first solve with `output_preprocessed` and keeps the preprocessed player in a JavaScript global, not in Rust or Kotlin; `solver_solve(player_id, sig_json_array, n_json_array)` returns the two challenge → solved maps.
- Limits: a memory limit on the runtime (start at 256 MB and report the peak you observe; the preprocessed player alone is a 3.8 MB string and the first solve parses a 2.7 MB script) and an interrupt handler that aborts a solve after 15 s.
- Kotlin: `NativeJsSolver : JsSolver` loads the scripts from the yt‑dlp zip on the device or from the assets, exactly as `WebViewJsSolver` does, and calls the functions above. Bind `JsSolver` to a small chooser: native when `MeteoraCore.available` and the last native solve did not fail, otherwise `WebViewJsSolver`. The WebView must not be created unless it is actually used.
- If `rquickjs` cannot be made to build for Android after a real attempt, try in this order and say which you used and why: another maintained QuickJS binding; `boa_engine` (pure Rust, no C toolchain, but check that it runs the solver and how long a solve takes on the PC); or leave the WebView solver in place, finish the rest of R1, and report the build errors verbatim.

### Kotlin after R1

- `InnerTubeStreams` and `WebMusicStreams` keep their HTTP and call `MeteoraCore` for every decision. Delete the Kotlin implementations of the ported functions; do not keep two copies.
- If the native library fails to load, phone playback cannot pick a format. That is acceptable for a library shipped inside the APK; make the failure a clear `StreamException("native core unavailable")`, not a crash.
- Nothing else changes: `StreamResolver` routing, pinning, the cache key, the exact byte range, the logging rules (no URLs, cookies, signatures or `n` values in logs, in Rust either).

### Tests

- `cargo test` in `rust/meteora-core`, on the PC:
  - every case in `app/src/test/java/dev/thathunky/meteora/stream/InnerTubeStreamsTest.kt` and the web‑music tests, ported, reading the same fixture files by relative path;
  - the solver against a real player script. A copy of today's is at `C:\Users\THATHU~1\AppData\Local\Temp\claude\afdb55a2746f8bf0\223d85d6-418a-48ab-8f18-760d27fa45bf\scratchpad\ytdlp\base.js` (2.7 MB; do not commit it and do not print it). Make this test read the path from the environment variable `METEORA_PLAYER_JS` and skip when it is unset. Expected, from Node with the same scripts: signature challenge `ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz01` → `10zyxwvutsrqponmlkjihgfedcbaZYXWVUTSRQPONMLKJIHGFEDCBA_B9876A43210zyxwvutsr-ponmlkjihgfedcbaZY5WVUTSRQPONMLKJIHGFEDCq`; `n` challenge `ZdZIqFPQK-Ty8wId` → `uk0U1apoQu2r`. Report the solve times you see.
- Remove the Kotlin tests whose subject moved to Rust; keep `durations` and anything still testing Kotlin. `:app:testDebugUnitTest` must still pass.

### Verify

- From PowerShell (the Bash tool cannot reach the Gradle daemon): `cargo test` in `rust\meteora-core` (set `METEORA_PLAYER_JS` for the solver test), then in the repo root `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain -q` with a 600000 ms timeout. Confirm `lib/arm64-v8a/libmeteora_core.so` is inside the APK and report its size.
- Do not install, run adb or touch a phone; the reviewer does that. Do not commit. Do not edit `docs/devlog.md` or `CLAUDE.md`.

### Report

Files created and changed; `cargo test` and Gradle test counts; the `.so` size and the APK size before and after if you have them; which JavaScript engine ended up in use and its solve times on the PC; every deviation from this plan with the reason; what only a phone can verify.

## R2 and R3 (outline only, not for this round)

- **R2, PC state:** `state_update(json)` parses YTMDesktop's state in Rust, keeps the previous one, and returns a compact result: unchanged, progress only, or a full new state. `RealtimeClient` and `PlayerRepository` then stop building a `StateDto` tree 4–15 times a second. Needs its own measurements first (allocation rate in Android Studio's profiler or `dumpsys meminfo` over a minute of playback).
- **R3, YouTube Music parsing:** `ytm/YtmParser` (290 lines of renderer search over large JSON) ported to Rust, returning the app's item lists as JSON. The biggest port and the smallest gain; do it last or not at all. Before starting it, do the evaluation below: an existing crate may make the port unnecessary.

## Before R3: evaluate RustyPipe (done 2026‑11‑21: leave it)

**Outcome:** do not adopt it. Its last code change is from June 2025 and its last release (0.11.4) from April 2025, and YouTube has changed its player many times since. It also has no Home feed and no like / dislike. Details and evidence are in `docs/api-notes.md`. Two things are worth taking from it: it runs its JavaScript on `rquickjs`, the binding R1 plans to use, and its YouTube Music response models are a useful reference if R3 is ever done by hand. The questions it was checked against are kept below.

[RustyPipe](https://docs.rs/rustypipe) is a Rust client for the YouTube and YouTube Music InnerTube APIs, inspired by NewPipe. It may be able to replace the hand-written `YtmParser`, and possibly the Python yt‑dlp fallback (about 18 MB of the APK). Nothing below is verified yet; the evaluation is a spike that ends in a written answer, not in code in the app.

Questions to answer, each from the crate's source or a small test program on the PC, with the crate version noted:

1. **Coverage:** does it return what Meteora shows: Home shelves, search with filters and suggestions, album, playlist and artist pages, liked songs, library playlists, the "up next" queue with radio continuation, and like / dislike?
2. **Signed in:** can it send the user's cookies with `SAPISIDHASH`, so Home and the library are personal? Meteora captures cookies in a WebView; the crate must accept them as given.
3. **Streams:** which clients does it use for audio URLs, does it decipher the signature and `n` itself, and with what JavaScript engine? Can it return the 256 kbps formats (itag 141 / 774) for a signed-in Premium account?
4. **PO tokens:** its docs say tokens come from a separate command-line program, `rustypipe-botguard`. An Android app cannot run that as is. Is it needed for the paths Meteora would use?
5. **Networking:** it does its own HTTP. That breaks this plan's "Rust does no I/O" rule, so measure the cost: the `.so` size and memory with its HTTP and TLS stack included, and check whether it can be given responses fetched by Kotlin instead.
6. **Android:** does it build for `aarch64-linux-android` with the NDK, and what does it need at run time (a cache directory, a clock, certificates)?
7. **Upkeep and licence:** how quickly did it follow YouTube's last few breaking changes, and is its licence compatible with GPL‑3.0?

Outcome, one of: adopt it for browsing and drop R3; adopt it for streams as well and drop the yt‑dlp fallback; use it only as a reference for our own code; or leave it. Write the answer and the evidence in `docs/api-notes.md`.
