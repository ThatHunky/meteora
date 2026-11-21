# Plan: 256 kbps playback on the phone for Premium accounts

Written 2026‑11‑21 for an implementing agent. Read [quality-switch-plan.md](quality-switch-plan.md) first: this builds on the quality switch it describes, which is already in the code.

## What was found (all on 2026‑11‑21)

- **The formats exist for this account.** A debug probe (`stream/StreamProbe.kt`, run on the OnePlus 7T with the user signed in) asked `/player` as several clients:

  | Client | Result |
  |---|---|
  | `WEB_REMIX` (web music), signed in | 140 AAC 129k, **141 AAC 257k HIGH**, 249 Opus 47k, 250 Opus 63k, 251 Opus 127k, **774 Opus 258k HIGH**. Every URL is behind `signatureCipher`. |
  | `WEB`, signed in | 140, 249, 250, 251 only, with no URLs at all |
  | `TVHTML5`, signed in | `UNPLAYABLE` |
  | `IOS`, `VISIONOS` with cookies | HTTP 400 (these clients do not take cookies) |

- **No PO token for Premium.** yt-dlp's client table (2026.10.26, the copy on the phone) marks the web music client's token as `not_required_for_premium`. What is needed is the JavaScript step: decipher the signature, and transform the `n` parameter.
- **The solver is already on hand.** yt-dlp ships its challenge solver as two scripts, `lib.min.js` (151 KB) and `core.min.js` (7 KB), version 0.8.0, licence Unlicense (it bundles meriyah, ISC, and astring, MIT). Both are now in `app/src/main/assets/ejs/`.
- **The solver works on today's player.** Run under Node against `https://music.youtube.com/s/player/6b3d0364/player_es6.vflset/uk_UA/base.js` (2.7 MB): first solve 311 ms, later solves 68 ms, both a signature and an `n` value came back. The player's signature timestamp was found with `(?:signatureTimestamp|sts)\s*:\s*(\d{5})` → `20774`.
- **Not yet shown:** that a deciphered 141/774 URL actually downloads (HTTP 206) for this account. That is the first thing this work must prove on the phone (step 7).

## Solver protocol

Evaluate, once per JavaScript context:

```js
<lib.min.js>
Object.assign(globalThis, lib);
<core.min.js>
```

Then call `jsc(input)`; it returns an object.

- First call for a player: `{type: "player", player: <base.js text>, requests: [...], output_preprocessed: true}`. The result has `preprocessed_player` (a 3.8 MB string).
- Later calls: `{type: "preprocessed", preprocessed_player: <that string>, requests: [...]}`.
- `requests`: `[{type: "sig", challenges: ["<s value>"]}, {type: "n", challenges: ["<n value>"]}]`.
- Result: `{type: "result", responses: [{type: "result", data: {"<challenge>": "<solved>"}}, ...]}`, one response per request in order. A failure is `{type: "error", error: "..."}` at either level.

## Design

### Tiers match the official app

Rename the three tiers so they mean what YouTube Music's own setting means:

| Enum (new name) | Label | Ceiling on average bitrate | Today's format |
|---|---|---|---|
| `Low` (was `DataSaver`) | Low | 56 000 | 249, about 50 kbps |
| `Normal` (was `Balanced`) | Normal | 160 000 | 251, about 130 kbps |
| `High` | High | none | 774, about 256 kbps, when signed in with Premium; otherwise the same as Normal |

- Defaults: Wi‑Fi `High`, mobile data `Normal`.
- Stored values: read old names too (`DataSaver` → `Low`, `Balanced` → `Normal`).
- Update `quality_note` (both languages): "High is about 256 kbps and needs YouTube Music Premium with you signed in; otherwise it is the same as Normal, about 130 kbps. Low is about 50 kbps. A change applies from the next track." Ukrainian: «Висока» — приблизно 256 кбіт/с, потрібні YouTube Music Premium і вхід в обліковий запис; інакше вона така сама, як «Звичайна», приблизно 130 кбіт/с. «Низька» — приблизно 50 кбіт/с. Зміна діє з наступного треку.
- Labels: `quality_high` High / Висока, `quality_normal` Normal / Звичайна, `quality_low` Low / Низька. Remove the two old label strings.
- Order in the selector: Low, Normal, High.
- Update the existing tests and the yt-dlp selectors (`Normal` → `abr<=160`, `Low` → `abr<=56`).

### When the web music path is used

Only when the tier is `High` **and** the user is signed in. `Low` and `Normal` gain nothing from it, and the visionOS path is anonymous, proven and fast, so they stay on it. In `StreamResolver.lookup`:

1. tier `High` and signed in → `WebMusicStreams.resolve`. If it fails with anything but a plain network `IOException`, log it and go on to 2.
2. `InnerTubeStreams.resolve` (visionOS), as now.
3. yt-dlp, as now.

A non‑Premium signed‑in account gets no HIGH formats, or gets URLs that fail without a token. Handle the first by returning whatever the picker picks (it may be 251); the second shows up as an HTTP 403 at playback, where `LocalEngine` already invalidates and retries once. Make that retry skip the web path: add `StreamResolver.avoidWebMusic(videoId)` (an in‑memory set, cleared with `invalidate` only for other reasons) and call it from the `retried.add(id)` branch of `onPlayerError` when the failed stream's `source` was `"web_music"`. Simplest: `invalidate(videoId, failedSource)`.

### New pieces (all in `stream/`)

1. **`JsSolver`** (interface) with `suspend fun solve(playerId: String, playerJs: suspend () -> String, sig: List<String>, n: List<String>): Solved` where `Solved` holds two maps, challenge → solved. An interface so everything around it can be unit‑tested with a fake.

2. **`WebViewJsSolver`** (the real one, `@Singleton`, injects `@ApplicationContext`).
   - One headless `android.webkit.WebView`, created lazily on the main thread from the application context, JavaScript on, never attached to a window, never loads a network page (`loadDataWithBaseURL("about:blank", "<html></html>", "text/html", "utf-8", null)`; wait for `onPageFinished` before the first evaluation).
   - No new dependency: this is the JavaScript engine the app already has.
   - Every WebView call on `Dispatchers.Main`; bridge `evaluateJavascript` with `suspendCancellableCoroutine`. Serialise calls with a `Mutex`.
   - Load the solver scripts from, in order: the yt‑dlp zip on the device, if it exists (`File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")`, entries `yt_dlp_ejs/yt/solver/lib.min.js` and `core.min.js`; yt‑dlp updates itself daily, so this copy tracks YouTube's changes), else the assets `ejs/lib.min.js`, `ejs/core.min.js`. If a solve fails with the zip's scripts, retry once with the assets, and the other way round.
   - Keep the preprocessed player **inside the WebView** (`globalThis.__players[playerId] = out.preprocessed_player`), not in Kotlin: passing 3.8 MB across `evaluateJavascript` on every call would be slow. Per call, send only the challenges.
   - Passing `base.js` in: build the script text with the player as a JSON string literal (`Json.encodeToString(String.serializer(), js)`), one evaluation per new player id.
   - `evaluateJavascript` returns a JSON‑encoded value. Have the script return `JSON.stringify(result)`, so the callback gets a JSON string containing JSON: decode twice.
   - Wrap the evaluation in `try { ... } catch (e) { return JSON.stringify({type: "error", error: String(e)}) }` inside the script.
   - Timeout 15 s per solve (`withTimeout`).

3. **`WebMusicStreams`** (`class WebMusicStreams(http: OkHttpClient, auth: YtmAuthProvider, solver: JsSolver)`).
   - `suspend fun resolve(videoId: String, quality: StreamQuality): AudioStream`, on `Dispatchers.IO` for the HTTP.
   - **Player info**, cached in memory and refreshed at most every 6 hours or after a failure: `GET https://music.youtube.com/` with the desktop user agent `YtmClient.USER_AGENT` and the auth headers. From the page: `"jsUrl":"(/s/player/[^"]+?/base\.js)"` → player URL, player id = the path segment after `/s/player/`; `"INNERTUBE_CLIENT_VERSION":"([0-9.]+)"` → client version (fall back to `YtmClient.FALLBACK_CLIENT_VERSION`). Then `GET https://music.youtube.com<jsUrl>`; keep the text in memory and on disk at `cacheDir/player/<playerId>.js` (delete other files in that folder). From the text: signature timestamp with the regex above.
   - **`/player` request:** `POST https://music.youtube.com/youtubei/v1/player?prettyPrint=false`, body `{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":<v>,"hl":"en"}},"videoId":<id>,"contentCheckOk":true,"racyCheckOk":true,"playbackContext":{"contentPlaybackContext":{"signatureTimestamp":<sts>}}}`. Headers: `User-Agent` desktop, `Origin: https://music.youtube.com`, `X-YouTube-Client-Name: 67`, `X-YouTube-Client-Version: <v>`, plus everything from `auth.headers()`. `StreamProbe.probe` shows the same request without the timestamp.
   - **Pick:** reuse `InnerTubeStreams.pickAudio` by adding a parameter `allowCipher: Boolean = false`; with it, a format counts as a candidate if it has `url` **or** `signatureCipher`. Add `cipher: String?` to `Picked` (and make `url` nullable or empty in that case; keep existing callers working).
   - **Build the URL** (pure function, unit‑tested): `signatureCipher` is a query string with `s` (the scrambled signature), `sp` (the parameter name to use, default `signature`) and `url`. Final URL = `url` + `&<sp>=<solved s, URL‑encoded>`. Then, if the URL has an `n` query parameter, replace its value with the solved one. A format with a plain `url` still needs its `n` solved. Ask the solver for both in one call.
   - **Result:** `AudioStream(videoId, url, headers = mapOf("User-Agent" to desktop UA, "Origin" to "https://music.youtube.com", "Referer" to "https://music.youtube.com/"), staleAtMs as InnerTubeStreams computes it, source = "web_music", cacheKey = "$videoId/$itag", quality, contentLength)`.
   - Never log URLs, cookies, signatures or `n` values. Log: video id, time taken, tier, itag, bitrate, and whether the player came from cache.
   - Throw `StreamException` for anything that is not a plain network failure.

4. **`StreamResolver`**: build `WebMusicStreams` from its existing `okHttp` (TLS only, like `innerTube`), inject `YtmAuthProvider` and `JsSolver`. Bind `JsSolver` to `WebViewJsSolver` in `di/AppModule.kt`. Signed in = `auth.headers() != null`. `prewarm()` also loads the player info and preprocesses the player when signed in and either tier is `High`, so the first High track does not pay for it.

5. **`StreamProbe`**: after the existing lines, also run `WebMusicStreams.resolve(videoId, High)` and then fetch bytes `0-1048575` of the result with the stream's headers. Log: itag, bitrate, HTTP status, bytes received, milliseconds. This is the proof for step 7. `MeteoraApp.ProbeDeps` needs whatever the probe now requires (take the `StreamResolver` or the pieces from the Hilt graph).

### Things to get right

- **Pinning and the disk cache key are unchanged** and already cover this: a track keeps the tier it was first opened with, and the key is `videoId/itag`.
- **Exact byte range:** `contentLength` must be set from the format, as on the visionOS path, or YouTube throttles the download (see `docs/api-notes.md`).
- **Threads:** the resolver is called with `runBlocking` on ExoPlayer's loader thread, and the solver needs the main thread. That is fine as long as nothing on the main thread waits for the loader; do not call `resolve` from the main thread.
- **Do not send the user's cookies anywhere but `music.youtube.com`.** The stream host (`googlevideo.com`) gets no cookies.

## Tests (JVM)

- `signatureCipher` parsing and URL building with a fake solver: cipher with and without `sp`; URL with and without `n`; values needing URL‑encoding.
- Player page parsing: `jsUrl`, player id, client version from a small HTML snippet; signature timestamp from a JS snippet (`signatureTimestamp:20774` and `sts:20774`).
- `pickAudio(root, High, allowCipher = true)` on a hand‑written response with ciphered 141 and 774 → 774; with `allowCipher = false` the same response → falls back to a plain‑URL format or null.
- Tier rename: old stored names map to the new ones; `Normal` picks 251 and `Low` picks 249 from the fixture (do not print or grep the fixture file, it is one enormous line).
- `StreamResolver` routing with fakes is optional; skip it if it needs Android classes.

## Verify

- Build and test from PowerShell: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain -q` (timeout 600000 ms).
- Do not install, run adb or touch a phone, and do not commit. Device checks are done by the reviewer with the probe: `adb shell am broadcast -a dev.thathunky.meteora.PROBE -p dev.thathunky.meteora --es v <videoId>`, then playing a track on High and reading the `InnerTubeStreams` / `WebMusicStreams` log lines.

## Files

New: `stream/JsSolver.kt` (interface, `WebViewJsSolver`), `stream/WebMusicStreams.kt`, tests. Changed: `stream/StreamQuality.kt`, `stream/InnerTubeStreams.kt`, `stream/YtDlpStreams.kt`, `stream/StreamResolver.kt`, `stream/StreamProbe.kt`, `MeteoraApp.kt`, `di/AppModule.kt`, `playback/LocalEngine.kt` (only the `invalidate` call in `onPlayerError`), `ui/shell/MeteoraShell.kt` (`QualitySelector` labels and order), both `strings.xml`, `InnerTubeStreamsTest.kt`. Nothing else.

## Risks to report, not to solve

- YouTube can change the player so that solver 0.8.0 fails. The fallback to the visionOS path keeps playback working at Normal quality; say so in the log.
- These are signed‑in requests from an app that is not the real web player. The account could be challenged ("confirm you're not a bot"). If `/player` returns `LOGIN_REQUIRED` or a bot check, stop using the web path for the rest of the process and fall back.
