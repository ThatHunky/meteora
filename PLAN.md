# Meteora: plan

An Android remote for **YTMDesktop**. It controls playback on the PC and lets you browse your YouTube Music Home, library and search on the phone, then start any of it on the PC with one tap. It is built with Material 3 Expressive.

> Status: plan agreed 2026‑11‑21, reviewed and revised the same day. No code yet. Next: spikes **S1** (companion recipes) and **S2** (WebView sign‑in) before M0.
> API details: [docs/api-notes.md](docs/api-notes.md)

---

## 1. Decisions

| # | Topic | Decision |
|---|---|---|
| Q1 | Audience | Personal use (adb or sideload) for now. Kept ready for GitHub/F‑Droid. **GPL‑3.0**, no secrets in the repo. |
| Q2 | SDK | minSdk **31**, compile/target **36**. Move to 37 when AGP supports it stably. |
| Q3 | UI | Kotlin, Jetpack Compose and the **latest alpha Material 3 (Expressive)**, versions pinned. We fix API churn when we upgrade. |
| Q4 | YTM login | WebView sign‑in, cookies captured, `SAPISIDHASH` per request. Cookies encrypted with a Keystore key. Sign out wipes them. Remote control and search still work while signed out. |
| Q5 | Connection | One paired PC. mDNS discovery plus manual entry. Remembers the LAN and Tailscale addresses: LAN first, Tailscale as fallback. The data model is keyed per PC. |
| Q6 | v1 screens | Now Playing, Queue (reorder/remove), Home, Library (Liked songs, Playlists), Search, Album/Artist/Playlist pages, Settings/Pairing. |
| Q7 | System | v1: MediaSession notification and lock screen, Share‑to‑Meteora, volume keys control the PC. v1.1: Quick Settings tile, Glance widget. |
| Q8 | Structure | Single `:app` module. Hilt, Ktor+OkHttp, kotlinx.serialization, socket.io-client-java, DataStore, Coil 3. |
| Q9 | Languages | English and Ukrainian, with the per‑app language setting. That system setting exists on Android 13+. Android 12 needs AppCompat's `setApplicationLocales` with `autoStoreLocales` and an in-app picker. |
| Q11 | Test device | OnePlus 7T, **LineageOS 22.2 / Android 15 (SDK 35)**, 1080×2400 @ 420dpi, connected over wireless adb. It reports `model=Pixel` (spoofed), so never branch on the model name. |
| Q12 | Color | Default: a scheme **built from the current album art** (materialkolor). Setting: Album art / System wallpaper / Fixed. |
| Q13/19 | Identity | Name **Meteora**, package `dev.thathunky.meteora`, companion `appId` `meteora`. |
| Q14 | YTM account | Assumed to be the same account as on the PC. Brand‑account picker after login. No multi‑Google‑account switching. |
| Q15 | Background | A foreground service stays connected while the PC is playing. It disconnects after **10 min paused** or when the notification is dismissed. |
| Q16 | Tap behavior | Song tap: play now with song radio. Collection tap: open its page (Play / Shuffle button group). Long press: Play next / Add to queue / Start radio. |
| Q17 | Offline | PC unreachable: browsing still works, play actions are disabled, a banner offers Reconnect. YTM session expired: controls still work, browse screens ask you to sign in again. |
| Q18 | Testing | JVM tests with MockWebServer and recorded fixtures for both APIs, a fake companion server for development, manual end-to-end runs on the OP7T. No screenshot tests in v1. |

Fixed details that came with the decisions above:
- **Command throttling:** a **single shared budget** for all commands, because the server limits `/command` to 2/s per token on top of a global 100 requests/min.
  - **Discrete actions go first:** play/pause, next, like and queue operations always jump ahead.
  - **Continuous inputs** (the seek and volume sliders, held volume keys) update the UI immediately. Only their latest value waits in one slot per kind, and it is sent when the budget allows. The final value is always sent.
  - **Long drags:** during a long drag, send nothing until release, at most about 1/s.
  - **Queue reorder:** a drag sends one `queueMove` when the item is dropped.
  - A `429` response backs off and retries the latest value only.
- **No polling:** `/state` is used once after connecting. After that, state comes only from realtime `state-update`. `/playlists` is not used.

---

## 2. Architecture

```
┌──────────────────────── UI (Compose, M3 Expressive) ────────────────────────┐
│  NowPlaying · Queue · Home · Library · Search · Album/Artist/Playlist · Settings │
└──────────────┬──────────────────────────────────────────────┬───────────────┘
               │ ViewModels (StateFlow, UDF)                   │
      ┌────────▼────────┐                             ┌────────▼────────┐
      │  PlayerRepository│  remote playback state +    │ MusicRepository │  browse / search
      │  (single source) │  command dispatch           │ (YTM data)      │  / library
      └────────┬────────┘                             └────────┬────────┘
      ┌────────▼────────┐  ┌───────────────┐          ┌────────▼────────┐
      │ CompanionClient │  │ ConnectionMgr │          │   YtmClient     │
      │ REST + Socket.IO│◄─┤ mDNS, LAN/TS  │          │ InnerTube + auth│
      └────────┬────────┘  │ fallback,     │          └────────┬────────┘
               │           │ backoff       │                   │
               ▼           └───────────────┘                   ▼
     YTMDesktop on PC :9863                          music.youtube.com/youtubei/v1
               ▲
      ┌────────┴─────────┐
      │ RemotePlayer     │  Media3 SimpleBasePlayer mirroring PC state
      │ + MeteoraSession │  → MediaSessionService → notification, lock screen,
      │   Service        │    BT/headset buttons, Wear media controls (bridged notification)
      └──────────────────┘   (Android Auto would need a MediaLibraryService + browse tree: not planned)
```

### Packages (`dev.thathunky.meteora`)

| Package | Contents |
|---|---|
| `companion` | `CompanionClient` (Ktor REST), `RealtimeClient` (Socket.IO), DTOs, `Command` sealed class, `CommandThrottler`, error mapping |
| `companion.discovery` | `NsdManager` discovery of `_ytmdesktop._tcp`. The PC advertises **an A record for every non-internal IPv4 address**, which on your PC includes the WSL/Hyper‑V `172.19.128.1` and Tailscale. Probe **every** resolved address with `/metadata` in parallel and keep the ones that answer. Also store the instance (host)name and rediscover by it when DHCP changes the IP. |
| `connection` | `ConnectionManager`: holds the paired PC, chooses a route (LAN → Tailscale), exponential backoff, exposes `ConnectionState` |
| `ytm` | `YtmClient` (InnerTube), `YtmAuth` (cookie store, SAPISIDHASH, brand account), parsers → domain models |
| `domain` | `Song`, `Album`, `Artist`, `Playlist`, `Shelf`, `PlaybackState`, `QueueItem`, `PlayIntent` |
| `data` | `PlayerRepository`, `MusicRepository`, `SettingsRepository` (DataStore), `SecureStore` (Keystore AES‑GCM) |
| `playback` | `RemotePlayer` (SimpleBasePlayer), `MeteoraSessionService`, idle‑disconnect timer, volume‑key handling |
| `ui.theme` | Expressive theme, color source switching (album/system/fixed), motion and shape tokens |
| `ui.<feature>` | One package per screen: screen composable + ViewModel |
| `share` | `ShareReceiverActivity`: parses `music.youtube.com` / `youtu.be` / `youtube.com` links → `PlayIntent` |

### Key flows

- **Play anything:** UI → `PlayIntent` (Song/Album/Playlist/Radio) → `PlayerRepository.play(intent)` → `changeVideo` with the recipe from api-notes. The UI shows a "starting…" state until a `state-update` arrives with the new `video.id` or `playlistId`, then confirms. A 5 s timeout shows an error snackbar.
- **Queue ops:** "Play next" → `queueAdd{index = selected+1}`. "Add to queue" → `queueAdd{index = items.length}`. Drag to reorder → `queueMove` on drop, applied to the screen immediately and corrected by the next state update.
  - **Index races:** commands take **indexes**, and the queue can shift underneath them (automix generating, another client). Before sending, check that `items[index].videoId` still matches what the user touched in the latest state; if it doesn't, re-resolve the index or abort with a snackbar.
- **Auth loss:** a `401` from REST, or a realtime connection rejected for its token, means the token was revoked on the PC. Clear it and route to the re-pair flow, with no retry loop.
- **Realtime state:** `state-update` → `PlaybackState` → StateFlow. Progress arrives in ticks. Between ticks the UI interpolates locally while `trackState == Playing`, so the progress bar moves smoothly.
- **Album art color:** the `video.thumbnails` largest image goes to Coil, then a seed color is extracted, then materialkolor builds a `ColorScheme`. That scheme is animated into the theme with `animateColorAsState` on each color role.

---

## 3. Design system: Material 3 Expressive

Goal: an app that looks and moves like Android 16's own apps, the bleeding edge of Material 3 Expressive.

- **Theme:** `MaterialExpressiveTheme` with `MotionScheme.expressive()`. The color scheme comes from the album art, the system wallpaper or a fixed seed, as chosen in Settings. The seed is animated whenever the track changes.
- **Typography:** the M3 Expressive type scale with emphasized styles for titles. Use Roboto Flex with variable weight where it exists. Track titles animate weight and width slightly on track change.
- **Shapes:** `MaterialShapes` (cookie, clover, sunny, pill…).
  - Album art masks morph between shapes on play/pause.
  - Pressed buttons morph their shape (`IconButton` / `Button` with `shapes =`).
- **Components:**
  - `ButtonGroup` for the Play / Shuffle / Radio controls and the album page actions.
  - The split button "Play ▾ (Play next / Add to queue)".
  - Wavy `LinearWavyProgressIndicator` as the seek bar in Now Playing (its amplitude falls to 0 when paused).
  - `LoadingIndicator` (shape‑morphing) for every loading state; no plain spinners.
  - `HorizontalFloatingToolbar` as the mini player, docked above the navigation bar.
  - `ShortNavigationBar` / `WideNavigationRail` chosen by window size class.
  - `LargeFlexibleTopAppBar` on album/artist/playlist pages, collapsing over the hero art.
  - `Carousel` (`HorizontalMultiBrowseCarousel`) for Home shelves.
  - `ModalBottomSheet` for long‑press actions.
  - `SwipeToDismissBox` + drag handles in the Queue.
- **Motion:**
  - Shared element from the mini‑player art to Now Playing art (`SharedTransitionLayout`).
    - Now Playing is therefore **not** a `ModalBottomSheet`: that opens in a separate window, and shared elements can't cross windows.
    - It is an in‑layout expandable player (`AnchoredDraggable` between collapsed and expanded) inside the same `SharedTransitionLayout` as the mini player.
  - Predictive back on every screen: `PredictiveBackHandler` drives the Now Playing → mini player collapse with the gesture's progress.
  - Spring‑based list animations (`animateItem()`).
- **Edge‑to‑edge** everywhere. Album‑art‑tinted gradients behind the system bars on Now Playing.
- **Haptics:** a light tick on seek detents and queue reorder, a confirm buzz on play.
- **Accessibility:** every icon button labelled, plus a minimum touch target of 48dp. Respect the system "remove animations" setting by falling back to `MotionScheme.standard()`.

---

## 4. Screens (v1)

1. **Onboarding & pairing:**
   - Discovered PCs are listed (mDNS), with a "Enter address" fallback.
   - Instructions: "Turn on *Enable companion authorization* in YTMDesktop".
   - Big 4‑digit code, then "Click Allow on your PC" with a 30 s countdown and a morphing loader.
   - Optional "Sign in to YouTube Music" step, which can be skipped.
2. **Now Playing (full sheet):**
   - Hero art in a morphing shape, title/artist/album (artist and album are tappable and open their pages).
   - Wavy seek bar, button group (prev / play‑pause / next), like/dislike, shuffle, repeat, volume slider, queue button.
   - "Playing on <PC name>" chip with connection state.
3. **Mini player:** a floating toolbar with art, title, play/pause and next. Swipe up opens Now Playing; horizontal swipe skips.
4. **Queue:**
   - Lists the PC's queue with the current item highlighted. Drag to reorder (`queueMove`), swipe to remove (`queueRemove`), tap to jump (`playQueueIndex`).
   - Automix items are shown in a separate section.
5. **Home:** the shelves from `FEmusic_home` (Quick picks as a song grid, everything else as carousels). Pull to refresh, with continuation paging.
6. **Library:** tabs for Liked songs and Playlists (Albums and Artists come in v1.1).
7. **Search:** suggestions while typing; results grouped as Top / Songs / Albums / Artists / Playlists, plus filter chips.
8. **Album / Playlist / Artist pages:**
   - A flexible top bar over the hero art, a Play / Shuffle / Radio button group and the track list.
   - Artist pages show sections for top songs, albums and singles.
9. **Settings:**
   - Paired PC (re‑pair / forget / addresses), YTM account (brand account picker / sign out).
   - Color source, language, idle‑disconnect timeout, about/licenses.

All song rows: tap = play with radio, long‑press = action sheet.

---

## 5. Background playback service

- `RemotePlayer extends SimpleBasePlayer`:
  - It maps `PlaybackState` to the player's state, metadata, artwork URI, position and duration.
  - It maps `handleSetPlayWhenReady`, `handleSeek`, `handleSetRepeatMode`, `handleSetShuffleModeEnabled` and next/previous to companion commands.
  - It reports `DeviceInfo.PLAYBACK_TYPE_REMOTE` with volume 0–100, so the **hardware volume keys** and the system volume panel control the **PC volume** through `handleSetDeviceVolume`.
- `MeteoraSessionService extends MediaSessionService`:
  - Starts when the app connects and the PC is playing.
  - Idle timer: after 10 min of `Paused`/`Unknown`, close the socket, stop the foreground service and drop the notification.
  - Dismissing the notification disconnects too.
- **Android 12+ background start limit:** Android 12+ forbids starting a foreground service from the background. Consequences:
  - The service can only be (re)started while Meteora is in the foreground, or from a notification action or media button.
  - After an idle disconnect, **the notification comes back only when you open Meteora**, or (v1.1) tap the QS tile or widget. A PC that resumes on its own does not bring it back.
  - While the service is alive and the PC is merely paused, Media3 may drop foreground status but keep the notification. Verify that behavior on the OP7T in M3.
- The service owns the realtime connection while it runs. The UI binds to the same `PlayerRepository` (an app‑scoped singleton).
- **Volume keys:**
  - While the session is active, they reach the PC through the remote `DeviceInfo` (`handleSetDeviceVolume`).
  - When no session is active but the app is in front, the Activity intercepts `KEYCODE_VOLUME_UP/DOWN` itself and sends the same throttled `setVolume`.
- Permissions:
  - `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
  - `POST_NOTIFICATIONS`, requested at runtime once pairing is done.
  - `ACCESS_NETWORK_STATE`. NsdManager handles multicast itself, so `CHANGE_WIFI_MULTICAST_STATE` isn't needed.
- **Cleartext:** the companion server is plain HTTP, and its address is user/runtime‑provided.
  - Network security config can only allow cleartext **per domain**; it can't take IP ranges. So the base config permits cleartext.
  - `CompanionClient` enforces the rule itself: it only connects to RFC1918, link‑local, `100.64.0.0/10` (Tailscale CGNAT) or `*.local` / MagicDNS hosts.
  - `YtmClient` is HTTPS‑only, and OkHttp is configured for it with `connectionSpecs` that are TLS only.

---

## 6. Security & privacy

- **Companion token and YTM cookies:** encrypted with an AES‑GCM key in the Android Keystore and stored in DataStore. Never logged.
- **Logging:** release builds strip the HTTP body logs, and debug logs redact `Authorization`, `Cookie` and `SAPISID`.
- **Backups:** `android:allowBackup="false"`, with data‑extraction rules that exclude secrets.
- **Sign out:** wipes the stored cookies, the WebView `CookieManager`, WebStorage and the cached Home/library.
- **Forget PC:** deletes the token and addresses. The user must also revoke the token on the PC side ("Authorized companions"); the app explains this.

---

## 7. Build & tooling

- Gradle Kotlin DSL and a version catalog (`gradle/libs.versions.toml`). Kotlin 2.2+ (K2) with the Compose compiler plugin.
- Latest stable AGP that supports compileSdk 36. Target `jvmTarget = 17`. **At M0, check that the chosen Gradle/AGP/Kotlin run on JDK 25.** If not, install a JDK 21 and pin it with the Gradle toolchain.
- **Library names and versions in this section are from memory and not yet verified.** Resolve the real latest versions from Google Maven / Maven Central at M0 (the "alpha" M3 Expressive APIs may have gone stable or been renamed by now), then check every component named in §3 against the resolved version.
- SDK: `Z:\tools\android-sdk` (platforms 36, 37; build-tools 36.0.0).
- Pinned bleeding‑edge libraries: `androidx.compose.material3:material3` (latest alpha with Expressive), `material3-adaptive`, Compose BOM alpha, Navigation 3 (or Navigation Compose with type‑safe routes if Nav3 isn't stable enough on alpha M3), Media3 (`session`), Coil 3, materialkolor, Hilt, Ktor client (OkHttp engine), kotlinx.serialization, socket.io-client-java 2.x, DataStore.
- Quality: ktlint (via Spotless) + detekt, with Android lint warnings treated as errors in CI.
- CI (later, when the repo is on GitHub): GitHub Actions for build + unit tests on each push.

---

## 8. Testing

| Layer | How |
|---|---|
| `YtmClient` parsers | JVM tests on **recorded InnerTube JSON** (`src/test/resources/fixtures/ytm/`), one fixture per endpoint plus edge cases (empty library, missing thumbnails, unknown renderer). Re‑record with a debug-only "dump response" switch when YTM changes. |
| `CompanionClient` | MockWebServer: auth flow, each command's JSON body, 401/429 handling, error‑code mapping. |
| `RealtimeClient` | The fake server emits recorded `state-update` sequences, and the test asserts the `PlaybackState` flow. |
| `CommandThrottler` | Virtual‑time coroutine tests: ≤2 commands/s, last value always sent. |
| `RemotePlayer` | Robolectric or a plain JVM test that state→player mapping and player calls→commands match. |
| Fake companion server | Under `tools/fake-ytmd/`, **outside** the Gradle build. Ktor has no Socket.IO server, so this is a small **Node** script using real `socket.io` v4 and Fastify, the same stack YTMDesktop uses. It implements the v1 REST subset and the realtime namespace, auto‑approves pairing, simulates progress, and replays recorded `state-update` fixtures. Used for UI work without the PC. |
| E2E | Manual on the OP7T over wireless adb against the real YTMDesktop 2.1.0 on `192.168.1.2`. A checklist lives in `docs/e2e-checklist.md`. |

---

## 9. Milestones

| M | Scope | Done when |
|---|---|---|
| **S1** spike | Pair with `curl` against the real PC (you toggle authorization and click Allow). Send every command once. Try each `changeVideo` / `queueAdd` recipe. Capture `/state` and a realtime `state-update` stream (with queue) as fixtures. | Every recipe in api-notes is marked ✅ or replaced; fixtures saved |
| **S2** spike | A throwaway WebView activity on the OP7T: Google sign‑in → `music.youtube.com`, read cookies, one authenticated `browse FEmusic_home` call. Record the fixtures for the parsers. | Sign‑in works, or a fallback from §10 is chosen |
| **M0** | Project skeleton: Gradle, catalog, Hilt, theme with Expressive + dynamic/album color plumbing, nav shell with a placeholder for every screen, en/uk resources, Spotless/detekt | `./gradlew assembleDebug` installs on the OP7T and shows the themed shell |
| **M1** | Companion: discovery, pairing, REST commands, realtime, `ConnectionManager` (LAN→Tailscale, backoff), throttler, fake server. **Capture real `state-update` fixtures.** | Pairing with the real PC works; play/pause/next/seek/volume work from a debug screen |
| **M2** | Now Playing + mini player + Queue with the full Expressive treatment, album‑art theming, shared‑element transition, predictive back | The PC is controllable end to end from polished UI; queue reorder/remove works |
| **M3** | Media session service: notification, lock screen, BT buttons, remote volume keys, idle disconnect | Lock-screen controls work; volume keys move the PC volume; service stops after 10 min paused |
| **M4** | YTM: WebView login, cookie store, SAPISIDHASH, brand‑account picker, Search (works signed‑out), Album/Artist/Playlist pages, play recipes | Search → tap a song starts it on the PC with radio; album Play/Shuffle work |
| **M5** | Home + Library (Liked songs, Playlists), long‑press action sheet, Share‑to‑Meteora | Home Quick picks play on the PC; sharing a link from YT Music on the phone plays it on the PC |
| **M6** | Polish: offline states, error snackbars, a11y pass, haptics, Ukrainian translation review, e2e checklist on the OP7T | The checklist passes; the app feels finished |

**v1.1:** Library Albums/Artists, Quick Settings tile, Glance widget, adaptive layouts (tablet/foldable rail).

**Later / maybe:** multiple PCs; lyrics (InnerTube `next` → lyrics browse); a "Continue on phone" handoff; F‑Droid packaging.

---

## 10. Risks

| Risk | Mitigation |
|---|---|
| InnerTube changes break parsing | Defensive parsers, fixture tests, a debug dump switch, the client version fetched at runtime |
| **Google refuses sign‑in inside a WebView** ("This browser or app may not be secure") | **Spike S2 tests it on the OP7T before any YTM work.** Fallbacks, in order: a mobile Chrome UA without the `; wv` token → a Custom Tab can't share cookies, so not an option → paste a cookie header exported from desktop Chrome (debug/power-user path) → ship v1 signed‑out (search only) |
| Google flags the cookie session | Use the `WEB_REMIX` context with realistic headers, keep request rates low, no background crawling: fetch only on user action plus a cached Home |
| `changeVideo` recipes from memory (`RDAMVM…` radio, `LM`, playlist‑only, `OLAK5uy_…`) | The server only forwards a `watchEndpoint` to the YTM web app's `yt-navigate`, so the IDs must be ones YTM web accepts. **Spike S1 tests each recipe against the real PC.** |
| Alpha Material 3 API churn | Versions pinned; upgrades are a dedicated task; Expressive calls wrapped in thin `ui.components` wrappers |
| YTMDesktop changes the companion API | `/metadata` version check on connect; re‑verify the bundle on each YTMD update (api-notes header); fake server mirrors the verified contract |
| `changeVideo` on the PC needs an active YTM view | Surface `YOUTUBE_MUSIC_UNVAILABLE` / `TIME_OUT` as clear errors |
| 2 commands/s limit | `CommandThrottler` + optimistic UI |
| OP7T OEM quirks | It runs LineageOS, close to AOSP. Still test the foreground-service and multicast behavior on the real device. |
