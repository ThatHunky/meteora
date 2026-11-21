# API notes

Two backends. Meteora talks to both:

1. **YTMDesktop Companion Server**: controls playback on the PC.
2. **YouTube Music InnerTube** (unofficial): browse, search and library, called directly from the phone.

---

## 1. YTMDesktop Companion Server (v1)

Verified against **YTMDesktop 2.1.0** (`app.asar` → `.vite/main/index.cjs`, extracted 2026‑11‑21).
Reverify on every YTMDesktop update by grepping the bundle for `` `/command` `` and the command schema union.

### Transport

| | |
|---|---|
| Port | `9863`, bound to `0.0.0.0` (LAN + Tailscale both reachable) |
| HTTP framework | Fastify, `@fastify/cors`, `@fastify/rate-limit` |
| Prefix | `/api/v1` |
| Realtime | Socket.IO **v4** (`EIO=4`), namespace `/api/v1/realtime` |
| Discovery | mDNS service `_ytmdesktop._tcp.local`, instance `<hostname>._ytmdesktop._tcp.local`, answers PTR/SRV/TXT(empty)/A. **A records = every non‑internal IPv4, computed once at server start** (yours: `172.19.128.1` WSL, `192.168.1.2` LAN, `100.95.146.127` Tailscale). Probe each. The server only starts if Electron `safeStorage` is available. |
| Auth header | `Authorization: <token>` (**raw token, no `Bearer ` prefix**) |
| **mDNS in practice** | **Doesn't work on V's PC** (2026‑11‑21): `main.log` shows `mdns: received _ytmdesktop._tcp.local query` for every phone query, but no reply reaches the phone, and a query from the PC itself gets none either. Most likely multicast egress picks the wrong adapter (WSL/Hyper‑V/Tailscale). Meteora therefore falls back to a /24 sweep of `/metadata` after 2.5 s (`LanScanner`), which finds the PC in about 2 s. |

Toggles in YTMDesktop → Settings → Integrations:
- **Companion server**: must be on.
- **Enable companion authorization**: must be on *only while pairing*. It switches itself off after a successful pairing or after 5 minutes.
- **Allow browser communication**: CORS for websites. Not needed by Meteora. Leave off.

### Endpoints

| Method | Path | Auth | Rate limit | Notes |
|---|---|---|---|---|
| GET | `/metadata` | — | global | `{"apiVersions":["v1"]}`. Use as reachability probe. |
| POST | `/api/v1/auth/requestcode` | — | 5 / min | Body `{appId, appName, appVersion}` → `{code}` (4 digits) |
| POST | `/api/v1/auth/request` | — | 5 / min | Body `{appId, code}` → `{token}`. **Blocks** until the user clicks Allow/Deny on the PC (30 s timeout). |
| GET | `/api/v1/state` | ✔ | 1 / 5 s | Snapshot; prefer realtime |
| GET | `/api/v1/playlists` | ✔ | 1 / 30 s | Only `[{id,title}]` from the "Add to playlist" menu; needs a loaded video. **Not used by Meteora.** |
| POST | `/api/v1/command` | ✔ | **2 / s** | Returns `204` on success |

Global limit: 100 requests / minute per client.

Rate-limit keys are `authId || ip`, so the limits apply per token.

Validation of the pairing request body:
- `appId`: `^[a-z0-9_\-]{2,32}$`. Meteora uses `meteora`.
- `appName`: 2–48 characters. Meteora uses `Meteora`.
- `appVersion`: must be valid semver (e.g. `0.1.0`, `0.1.0-alpha.3`). It is shown in the "Authorized companions" table.

### ⚠ YTMDesktop 2.1.0 bug: authorization left on for 5 minutes crashes the app

`memoryStoryListenerCallback` is registered unbound (`onStateChanged(this.memoryStoryListenerCallback)`), so its 5‑minute auto‑disable timer runs with the wrong `this`. If "Enable companion authorization" stays on for 5 minutes without a successful pairing, YTMDesktop throws `TypeError: this.getService is not a function`. The main window becomes "Error" and the companion server stops responding, so the app has to be restarted. Seen on V's PC at 19:52:04 on 2026‑11‑21. A successful pairing turns the toggle off and avoids the crash, so the pairing UI tells the user to enable it only when ready and to click Allow promptly.

### Pairing flow

```
user enables "Enable companion authorization" on PC
phone  POST /api/v1/auth/requestcode {appId:"meteora", appName:"Meteora", appVersion}
     ← {code:"1234"}                 → show code big on phone
phone  POST /api/v1/auth/request {appId:"meteora", code:"1234"}   (long-poll, ≤30 s)
PC     pops "Authorize companion" window showing the same code → user clicks Allow
     ← {token:"…"}                   → store encrypted, per PC
```

At most 5 authorization windows can be open at once (`AUTHORIZATION_TOO_MANY`).

### Commands (`POST /api/v1/command`)

Body: `{"command": "<name>", "data": <payload?>}`

Schema detail: optional fields are TypeBox `Optional(String)`. **Omit** an unused `videoId`/`playlistId`; don't send `null`, because `null` fails validation. Configure kotlinx.serialization with `explicitNulls = false`.

Under the hood: `changeVideo` → `yt-navigate` with `{watchEndpoint:{videoId, playlistId}}` in the YTM web view, and `queueAdd` → a `queueAddEndpoint{queueTarget}` service request whose result is spliced into the local queue at `index`. Both behave exactly like the YTM web app would with those IDs.

| command | data | Notes |
|---|---|---|
| `playPause` / `play` / `pause` | — | |
| `volumeUp` / `volumeDown` | — | |
| `setVolume` | int 0–100 | |
| `mute` / `unmute` | — | |
| `seekTo` | number, seconds, 0..duration | |
| `next` / `previous` | — | |
| `repeatMode` | `0` None · `1` All · `2` One | |
| `shuffle` | — | Toggles shuffle |
| `playQueueIndex` | int ≥ 0 | |
| `changeVideo` | `{videoId?, playlistId?}` | At least one is required. Navigates the PC to `watchEndpoint{videoId, playlistId}`. **This is how we start any song, album, playlist or radio.** |
| `toggleLike` / `toggleDislike` | — | |
| `queueAdd` | `{videoId? XOR playlistId?, index}` | **Exactly one** of videoId/playlistId. `index` ≤ queue length. Use index `selected+1` for "Play next" and `items.length` for "Add to queue". *New in 2.x.* |
| `queueRemove` | int index | *New in 2.x.* |
| `queueMove` | `{fromIndex, toIndex}` | *New in 2.x.* |

`changeVideo` / `queueAdd` recipes, **verified by spike S1 on 2026‑11‑21 against YTMDesktop 2.1.0**:

| Intent | data | Result |
|---|---|---|
| Play one song + radio | `{videoId, playlistId: "RDAMVM" + videoId}` | ✅ plays it; queue = 50‑item radio; `playlistId` = `RDAMVM…` |
| Play one song only | `{videoId}` | ✅ but the queue holds **just that song** (`playlistId` null). Not what a "song tap" should do. |
| Play album from top | `{playlistId: "OLAK5uy_…"}` | ✅ queue = album tracks + `automixItems` (50) |
| Play track N of album/playlist | `{videoId, playlistId}` | ✅ |
| Liked songs | `{playlistId: "LM"}` | ✅ |
| Shuffle | `shuffle` | ✅ a **toggle** of `shuffleEnabled` (it reorders the queue in place) |
| Play next | `queueAdd {videoId, index: selected+1}` | ✅ inserted at that index |
| Add playlist/album to queue | `queueAdd {playlistId, index}` | ✅ all tracks spliced in at `index` |
| Reorder / remove | `queueMove {fromIndex,toIndex}` / `queueRemove i` | ✅ |

**Caveat:** `changeVideo` with the video that is **already loaded** is effectively a no‑op. The queue regenerates, but `playlistId` doesn't change and playback doesn't start. If the user taps the current song, send `playQueueIndex`/`play` instead.

Other verified behavior:
- `volumeUp` / `volumeDown` step by **10**. `mute`, `unmute`, `repeatMode` 0/1/2, `seekTo` and `toggleLike` (likeStatus 1↔2) all show up in state within about 1 s.
- `next` / `previous` / `playQueueIndex` ✅. After them, `trackState` passes through `-1 → 2 → 1`, and `video.id` changes about 100 ms *after* `selectedItemIndex`.
- **⚠ `null` is dangerous:** `{"videoId": null}` is **accepted (204)**. Ajv coerces it to `""`, and YTM navigates to an empty watch endpoint, which **wipes the queue and stops playback**. Never serialize nulls (`explicitNulls = false`).
- Errors: `{}` → 400 `INVALID_CHANGE_REQUEST`. Both ids in `queueAdd` → 400 `INVALID_QUEUE_ADD_REQUEST`. Out of range → 400 `FST_ERR_VALIDATION` with a long `anyOf` message (map it by `statusCode` and treat the message as opaque).
- Rate limit confirmed: the 3rd command within 1 s → 429 `"Rate limit exceeded, retry in 1 second"`.

### Error codes

`INVALID_VOLUME`, `INVALID_REPEAT_MODE`, `INVALID_SEEK_POSITION`, `INVALID_QUEUE_INDEX`, `INVALID_CHANGE_REQUEST`, `INVALID_QUEUE_ADD_REQUEST`, `UNAUTHENTICATED`, `AUTHORIZATION_DISABLED`, `AUTHORIZATION_INVALID`, `AUTHORIZATION_TIME_OUT`, `AUTHORIZATION_DENIED`, `AUTHORIZATION_TOO_MANY`, `YOUTUBE_MUSIC_UNVAILABLE` (sic), `YOUTUBE_MUSIC_TIME_OUT`.

Missing or invalid token → HTTP 401 `{"error":"UNAUTHORIZED"}`.

### Realtime

Connect to `http://<host>:9863/api/v1/realtime` with Socket.IO v4, `auth: { token }`. A bad token is rejected in middleware, and the client gets a `connect_error`. Treat that as auth loss and re-pair.

- The **default namespace `/` disconnects every client immediately**. Connect only to `/api/v1/realtime`. socket.io v3+ clients don't auto-join `/`.
- Use `transports = ["websocket"]`: no long‑polling fallback, which wastes the rate budget and battery.
- **The server sends no state on connect.** It only emits on changes. After (re)connecting, call `GET /state` once to seed. That endpoint allows 1 call per 5 s, so debounce reconnect storms.

| Event | Payload |
|---|---|
| `state-update` | The **full** state object (below) on every store change. Measured: **4–7 updates/s while playing, 10–15/s during track changes**, each **~50 KB** (up to 79 KB with a 50‑item queue × 6 thumbnails). That is roughly 250–750 KB/s. |
| `playlist-created` | playlist info |
| `playlist-deleted` | playlist id |

Client library: `io.socket:socket.io-client` 2.x (Java, supports server v3/v4).

### State object

```jsonc
{
  "player": {
    "trackState": -1 | 0 | 1 | 2,        // Unknown | Paused | Playing | Buffering
    "videoProgress": 73.4,               // seconds
    "volume": 0..100,
    "muted": false,
    "adPlaying": false,
    "queue": {                           // nullable
      "autoplay": true,
      "items": [ /* queue items: thumbnails, title, author, duration, selected, videoId, counterparts… */ ],
      "automixItems": [ ... ],
      "isGenerating": false,
      "isInfinite": false,
      "repeatMode": -1 | 0 | 1 | 2,      // Unknown | None | All | One
      "selectedItemIndex": 3,
      "shuffleEnabled": false
    }
  },
  "video": {                             // nullable
    "author": "Radiohead", "channelId": "…",
    "title": "Nude", "album": "In Rainbows", "albumId": "MPREb_…",
    "likeStatus": -1 | 0 | 1 | 2,        // Unknown | Dislike | Indifferent | Like
    "thumbnails": [{ "url", "width", "height" }],
    "durationSeconds": 256, "id": "<videoId>",
    "isLive": false,
    "videoType": -1 | 0 | 1 | 2 | 3,     // Unknown | MusicAudio | MusicVideo | MusicUploaded | PodcastEpisode
    "metadataFilled": true
  },
  "playlistId": "RDAMVM…"
}
```

Queue item (verified): `{thumbnails[], title, author, duration: "3:39" (string!), selected: bool, videoId, counterparts: null | [...]}`. When `videoType` is MusicVideo, `counterparts` holds the audio/video twin.

**Consequences for the client:**
- Parse off the main thread.
- **Conflate**: keep the latest state, and recompute the queue UI only when the list of `videoId`s or `selected` changes.
- Ignore transient states during track changes (`qlen 0`, `sel -1`, `trackState -1`) for about 300 ms before showing them.
- The socket must be closed when nothing needs it, which is the battery reason behind the idle disconnect.

Fixtures (state changes only, deduplicated) live in `app/src/test/resources/fixtures/companion/`: `state-paused-unknown.json` and `realtime-{next,album,queueAdd-next,queueMove,volume,shuffle}.json` (each `[{dtMs, state}]`).

---

## 2. YouTube Music InnerTube (phone side)

This API is unofficial and can change without notice. Everything here sits behind `YtmClient` and is covered by fixture tests (see PLAN.md › Testing).

> **⚠ This whole section is from memory (ytmusicapi / Metrolist conventions), not verified.** In particular `account/accounts_list`, the library browse IDs and the header requirements. Spike S2 confirms or corrects it, and it records the first fixtures.
Prior art to read, not copy: ytmusicapi (Python), Metrolist / InnerTune (Kotlin).

### Request basics

- Base: `https://music.youtube.com/youtubei/v1/<endpoint>?prettyPrint=false`
- Client context: `clientName: "WEB_REMIX"`, `clientVersion: "1.<yyyymmdd>.01.00"`. Fetch the current version from the `ytcfg` of `music.youtube.com` at runtime and cache it. Hard-code a fallback.
- `hl` / `gl` from the app locale (`uk`/`en`, `UA`).
- `Origin` / `Referer`: `https://music.youtube.com`.

### Auth (cookie)

- WebView login at `https://accounts.google.com/ServiceLogin?continue=https://music.youtube.com`. Once the WebView lands on `music.youtube.com`, read `CookieManager.getCookie("https://music.youtube.com")`.
- Required cookies include `SAPISID` (or `__Secure-3PAPISID`), plus the whole cookie header.
- Per request: `Authorization: SAPISIDHASH <ts>_<sha1("<ts> <SAPISID> https://music.youtube.com")>`, `Cookie: <all>`, `X-Goog-AuthUser: 0`.
- Channel (brand) account: send `X-Goog-PageId: <pageId>`. Get the list from `account/accounts_list`.
- Storage: encrypted with an Android Keystore AES‑GCM key inside DataStore. "Sign out" wipes the stored cookies and the WebView `CookieManager`.

### Endpoints we use

| Feature | Endpoint | Key params |
|---|---|---|
| Home | `browse` | `browseId: "FEmusic_home"`; continuations for more shelves |
| Liked songs | `browse` | `browseId: "VLLM"` |
| Library playlists | `browse` | `browseId: "FEmusic_liked_playlists"` |
| Library albums *(v1.1)* | `browse` | `browseId: "FEmusic_liked_albums"` |
| Library artists *(v1.1)* | `browse` | `browseId: "FEmusic_library_corpus_track_artists"` |
| Playlist page | `browse` | `browseId: "VL" + playlistId` |
| Album page | `browse` | `browseId: "MPREb_…"`. The response contains the `OLAK5uy_` audio playlist id. |
| Artist page | `browse` | `browseId: "UC…"` |
| Search | `search` | `query`, `params` filter (songs/albums/artists/playlists) |
| Suggestions | `music/get_search_suggestions` | `input` |
| Accounts | `account/accounts_list` | — |

Parsing: renderers such as `musicCarouselShelfRenderer`, `musicTwoRowItemRenderer`, `musicResponsiveListItemRenderer`, `musicShelfRenderer` and `musicImmersiveHeaderRenderer`/`musicResponsiveHeaderRenderer` change often. Parse defensively: unknown renderer → skip; missing field → null.

---

## Phone playback: getting audio URLs (verified 2026-11-21)

**Fast path: InnerTube `/player`, visionOS client.**
- **Request:** `POST https://www.youtube.com/youtubei/v1/player`.
- **Body:** `{"context":{"client":{"clientName":"VISIONOS","clientVersion":"1.02","deviceMake":"Apple","deviceModel":"RealityDevice17,1","osName":"visionOS","osVersion":"26.5.23O471","visitorData":<vd>}},"videoId":…,"contentCheckOk":true,"racyCheckOk":true}`.
- **Headers:** `X-YouTube-Client-Name: 101`, `X-YouTube-Client-Version: 1.02`, `X-Goog-Visitor-Id: <vd>`, and the Safari UA from yt-dlp.
- **Visitor ID:** from `GET https://www.youtube.com/sw.js_data`, which is `)]}'` followed by JSON, with the ID at `[0][2][0][0][13]`. Without it the response is `LOGIN_REQUIRED` "Sign in to confirm you're not a bot".
- **Response:** `streamingData.adaptiveFormats` contains audio itags 139/140/249/250/251, each with a plain `url`: no `signatureCipher`, no n‑parameter, no PO token. `expiresInSeconds` is about 21600.
- **Behaviour:** ranged reads and mid-file seeks return 206. URLs are tied to the requesting IP, so the phone resolves its own.
- **Source:** client details come from the yt-dlp 2026.10.26 client table (`yt_dlp/extractor/youtube/_base.py`), where visionOS is the only default client that needs no JavaScript. `android_vr` has returned 403 for all formats since 2026-08-17.

**Fallback: yt-dlp** through `youtubedl-android` 0.18.1 (GPL‑3.0).
- **Speed:** about 15 s per track on the OP7T.
- **DNS:** Python's lookups failed intermittently behind Private DNS.
- **Updates:** `updateYoutubeDL(STABLE)` at most once a day.

**Queues:** `POST music.youtube.com/youtubei/v1/next` with `{videoId?, playlistId, isAudioOnly, enablePersistentPlaylistPanel}`.
- **Response:** `playlistPanelVideoRenderer` items, with `selected:true` on the starting track.
- **Radio** (`RDAMVM<id>`) returns 50 tracks plus `nextRadioContinuationData`.
- **Playlist-only calls** (`OLAK5uy_…`, `RD…`) work.
