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
- **Throttling (seen on the OP7T, 2026‑11‑21):** an open-ended request (no range, or `bytes=N-`) is served at about twice playback speed: 94 s into a track, 180 s were buffered. A request for an exact range (`bytes=N-M`, with M from the format's `contentLength`) is not throttled: a 4:43 track was fully buffered within 8 s. Tracks over about 10 MB were not tried; yt-dlp uses 10 MB chunks for the same reason.
- **Audio formats in that response** (average bitrate): 251 Opus 127 kbps, 140 AAC 130 kbps, 250 Opus 65 kbps, 249 Opus 49 kbps, 139 HE‑AAC 49 kbps. The top two are labelled `AUDIO_QUALITY_MEDIUM`. The 256 kbps Premium formats are not offered to this signed-out client.
- **Source:** client details come from the yt-dlp 2026.10.26 client table (`yt_dlp/extractor/youtube/_base.py`), where visionOS is the only default client that needs no JavaScript. `android_vr` has returned 403 for all formats since 2026-08-17.

**256 kbps for Premium (probed on the OP7T, 2026‑11‑21, signed in):**
- `/player` as `WEB_REMIX` on `music.youtube.com`, with the session cookies and `SAPISIDHASH`, lists itag 141 (AAC, 257 kbps) and 774 (Opus, 258 kbps), both `AUDIO_QUALITY_HIGH`, next to 140/249/250/251. Every URL is behind `signatureCipher`.
- The same request as `WEB` lists no HIGH formats and no URLs; as `TVHTML5` it is `UNPLAYABLE`; `IOS` and `VISIONOS` with cookies return HTTP 400.
- yt-dlp's client table marks the web music client's PO token as not required for Premium. Not yet confirmed here that a deciphered URL downloads.
- The solver scripts yt-dlp ships (`yt_dlp_ejs/yt/solver/lib.min.js` and `core.min.js`, 0.8.0) solved a signature and an `n` value for player `6b3d0364` under Node: 311 ms the first time, 68 ms after.

**Video formats (probed 2026‑11‑22, anonymous, from the PC; no phone):**
- **Client:** the same visionOS `/player` call as for audio (above, with a fresh visitor id). Checked with two official music videos (`dQw4w9WgXcQ`, `kJQP7kiw5Fk`): `playabilityStatus` `OK`, and `adaptiveFormats` lists the video-only formats next to the audio ones. Every video format has a plain `url`: no `signatureCipher`, no `n` parameter, no PO token. So nothing has to be solved, and the signed-in web music path is not needed for video. `videoDetails` carries no `musicVideoType` for this client; the queue's renderer is where that comes from.
- **Codecs and heights:** every height is offered in three codecs, H.264 (`video/mp4; codecs="avc1.…"`, itags 160/133/134/135/136/137 for 144p…1080p), VP9 (`video/webm; codecs="vp9"`, 278/242/243/244/247/248, plus 271/313 for 1440p/2160p) and AV1 (`video/mp4; codecs="av01.…"`, 394…399, plus 400/401). One video went to 2160p, the other to 1080p; the second also had 608p formats (779/780/788). Each has `contentLength`, `bitrate` and `averageBitrate`, so an exact range can be asked for. 720p averages about 0.7–1.5 Mbit/s, 360p about 0.2–0.5 Mbit/s. No muxed `formats` came back; there is an `hlsManifestUrl` (not used).
- **Throttling:** the audio rule holds, with a size limit. An open-ended request (`bytes=0-`) was served at about 2.0–2.4 Mbit/s, about twice the 720p format's average bitrate. An exact range of 8 MB, 10 000 000 bytes or 10 MiB (10 485 760 bytes) came at full line speed (well over 100 Mbit/s). An exact range of 12 000 000 bytes, 20 MB or the whole 42 MB file was throttled to the same 2.4 Mbit/s as an open-ended one. The limit is per request, not per file position: a 10 MiB range from the middle of the file (`bytes=10485760-20971519`) came at full speed, the same start with 12 MB did not. So a video file has to be fetched in exact ranges of at most 10 MiB each, one after the other (yt-dlp's chunk size). Audio files are almost always below 10 MiB (130 kbps × 10 min ≈ 9.8 MB), so a whole-file range works for them; a very long track would hit the same limit.
- **Song ↔ video counterparts:** an anonymous `next` (WEB_REMIX, with or without `isAudioOnly`) returned no `playlistPanelVideoWrapperRenderer` at all, so the counterpart and its `segmentMap` are only seen signed in, and no response with a `segmentMap` has been recorded. Meteora reads it as `segmentMap.segment[]` with `primaryVideoStartTimeMilliseconds`, `counterpartVideoStartTimeMilliseconds` and `durationMilliseconds` (the names used by other YouTube Music clients). **These field names are not verified.** A map that does not parse means the song plays audio-only in video mode.

**Fallback: yt-dlp** through `youtubedl-android` 0.18.1 (GPL‑3.0).
- **Speed:** about 15 s per track on the OP7T.
- **DNS:** Python's lookups failed intermittently behind Private DNS.
- **Updates:** `updateYoutubeDL(STABLE)` at most once a day.

**RustyPipe, evaluated and not adopted (2026‑11‑21).** `rustypipe` 0.11.4 from crates.io, source read, nothing run against YouTube.
- **Upkeep:** the release is dated 2025‑04‑23. The repository's last code commits are from 2025‑06‑18; the only commit since (2026‑08‑03) changes a licence identifier. Its deobfuscator finds the signature functions with patterns written for 2025 players, so stream URLs from it are unlikely to work today. This alone rules it out.
- **Coverage:** it has YouTube Music search with suggestions, album, playlist and artist pages, liked tracks, the saved library, history, radio and lyrics. It has no Home feed (`FEmusic_home` does not appear in the source) and no like / dislike call.
- **Sign-in:** it accepts cookies and signs with `SAPISIDHASH`.
- **JavaScript:** it embeds QuickJS through `rquickjs` 0.9.
- **PO tokens:** optional, through a separate `rustypipe-botguard` program.
- **Networking:** its own, on `reqwest` and `tokio`.
- **Licence:** GPL‑3.0, compatible with Meteora.

**Like status in a queue response (signed in, probed 2026‑11‑21):** `playlistPanelVideoRenderer` items have no `likeStatus`. Each has a `toggleMenuServiceItemRenderer` whose `defaultIcon.iconType` is `UNFAVORITE` when the track is liked and `FAVORITE` when it is not (signed out, always `FAVORITE`). `playerOverlays.playerOverlayRenderer…likeButtonRenderer` gives `likeStatus` (`LIKE` / `DISLIKE` / `INDIFFERENT`) and `target.videoId` for the current track only. `like/like`, `like/dislike` and `like/removelike` with `{"target":{"videoId":…}}` change it; confirmed by reading the overlay back.

**Queues:** `POST music.youtube.com/youtubei/v1/next` with `{videoId?, playlistId, isAudioOnly, enablePersistentPlaylistPanel}`.
- **Response:** `playlistPanelVideoRenderer` items, with `selected:true` on the starting track.
- **Radio** (`RDAMVM<id>`) returns 50 tracks plus `nextRadioContinuationData`.
- **Playlist-only calls** (`OLAK5uy_…`, `RD…`) work.
- **Collection radio** (probed signed out, 2026‑11‑21): `RDAMPL<playlistId>`, the id without `VL`, returns about 50 tracks plus `nextRadioContinuationData`. Checked for an album (`OLAK5uy_…`), a `PL…` playlist and an `RDCLAK5uy_…` mix. Not checked through YTMDesktop's `changeVideo`.
- **Automix (what follows an album or playlist):** the queue of a playlist-only call ends with a `playlistPanelRenderer.contents[]` item `automixPreviewVideoRenderer.content.automixPlaylistVideoRenderer.navigationEndpoint.watchPlaylistEndpoint` = `{playlistId: "RDAMPL<same id>", params: "wAEB8gECeAE%3D"}` (the params arrive URL-encoded); no continuation. It is there with or without a `videoId` in the request (checked from the first and the fourth track). A `next` call with that playlist id and the params, URL-encoded or not, gave 50 tracks plus a radio continuation; without the params, 49 tracks, of which only 20 were the same. Meteora sends the params as they arrive. Fixture: `fixtures/ytm/next-album.json`.

### Lyrics (probed 2026‑11‑22, anonymous, from the PC; no phone)

- **Lyrics id:** the `next` response (`WEB_REMIX`, signed out, `videoId` + `RDAMVM<id>`) has a `tabRenderer` titled "Lyrics" whose `endpoint.browseEndpoint.browseId` starts with `MPLY` (e.g. `MPLYt_4U7yfKKFZLv-1`), with `pageType` `MUSIC_PAGE_TYPE_TRACK_LYRICS`. The tab was there for every track tried, including instrumentals that have no lyrics, so its presence says nothing. The tab belongs to the track named in the request.
- **Timed lyrics:** `POST music.youtube.com/youtubei/v1/browse` with that id as **`ANDROID_MUSIC`** (`clientVersion` `7.27.52`, `androidSdkVersion` 30, header `X-YouTube-Client-Name: 21`, the app's own User-Agent), **signed out**, returns `contents.elementRenderer.newElement.type.componentType.model.timedLyricsModel.lyricsData` with `timedLyricsData[]` (`lyricLine`, `cueRange.startTimeMilliseconds` / `endTimeMilliseconds` as strings) and `sourceMessage` ("Source: Musixmatch"). `IOS_MUSIC` 7.27.0 returns the same. `ANDROID_MUSIC` 8.47.54 also works; version `5.01` gets HTTP 400, so the version matters.
  - Instrumental gaps arrive as their own lines whose text is `♪`.
  - The same response repeats the lyrics inside `enterLyricsSharingCommand` (the share sheet). Read the first `timedLyricsModel.lyricsData` only, not every `timedLyricsData`.
  - **Locale:** with `hl=uk`, `gl=UA` (what a Ukrainian phone sends) the same three answers come back, timed, unsynced and none; only `sourceMessage` is translated ("Джерело: Musixmatch"). So it is shown as sent.
  - Cookies were not tried with this client (`IOS` / `VISIONOS` gave 400 with cookies on `/player`), so Meteora sends it signed out.
- **Unsynced lyrics in the same shape:** for a track whose lyrics are not timed (`dQw4w9WgXcQ`), the `ANDROID_MUSIC` response is the same `timedLyricsModel`, but the lines have no `cueRange` and `lyricsData.staticLayout` is `true`.
- **No lyrics:** `ANDROID_MUSIC` returns a `musicMessageModel` ("Lyrics not available at this time.") and no `timedLyricsModel`; `WEB_REMIX` returns a `messageRenderer` ("Lyrics not available").
- **Plain lyrics as `WEB_REMIX`:** the same browse gives `musicDescriptionShelfRenderer` with `description.runs[].text` (one string, lines split by `\n`) and `footer.runs` ("Source: Musixmatch"), never timed. Meteora uses it only when the `ANDROID_MUSIC` call fails.
- **Tracks tried:** timed: `J7p4bzqLvCw` (The Weeknd, Blinding Lights), `BSTsnWoslP4` (Queen, Bohemian Rhapsody), `8wWrzr3_46Y` (Океан Ельзи, Обійми, Ukrainian lines). Unsynced: `dQw4w9WgXcQ`. None: `_qkKKwssiqk` (Debussy, Clair de Lune), `uxTdTaNIUxo`, `kAw9xGI8vgk`.
- **Fixtures:** `fixtures/ytm/lyrics-timed.json`, `lyrics-plain.json` (unsynced, `ANDROID_MUSIC`), `lyrics-plain-web.json` (`WEB_REMIX` shelf), `lyrics-none.json`; trimmed to the lyrics data and the first few lines.

### Premium (probed 2026-11-22, anonymous, from the PC; no phone, no Premium account)

Downloads are for Premium accounts only, so Meteora has to tell. Nothing here has been seen for a signed-in account yet.
- **Page config:** the `music.youtube.com` page's `ytcfg` has `"IS_SUBSCRIBER":false` and `"LOGGED_IN":false` signed out (one occurrence of each). `IS_SUBSCRIBER` is the candidate Premium flag; that it becomes `true` for a Premium account is **not verified**. The page is translated (`hl` from the request's region), so nothing is matched by text. `YtmClient.ensureClientVersion()` fetches this page without cookies, so it cannot be used for the check; `PremiumCheck` fetches it with them, redirects off.
- **Account menu:** `POST music.youtube.com/youtubei/v1/account/account_menu` (`WEB_REMIX`) returns `actions[].openPopupAction.popup.multiPageMenuRenderer.sections[].multiPageMenuSectionRenderer.items[].compactLinkRenderer`. Signed out the first item is "Get Music Premium": `icon.iconType` `UNLIMITED`, `navigationEndpoint.browseEndpoint.browseId` `SPunlimited`, `canonicalBaseUrl` `/music_premium`. Expected (not verified) to be absent for Premium. Signed in, the menu's header (`activeAccountHeaderRenderer`) holds the name and handle; the probe never logs it.
- **Rule** (`PremiumMarkers.isPremium`, fail closed): `LOGGED_IN` and `IS_SUBSCRIBER` both `true` on the signed-in page, and the account menu read and offering no upgrade. A missing upsell alone never counts as Premium.
- **Probe:** `adb shell am broadcast -a dev.thathunky.meteora.PROBE_PREMIUM -p dev.thathunky.meteora`, then `adb logcat -s PremiumCheck`. It logs the three markers and the menu's icon types and browse ids.
- **Fixtures:** `fixtures/ytm/account-menu-free.json` (the real signed-out menu, tracking fields removed) and `account-menu-premium.json` (hand-made: the same without the upgrade entry, plus a made-up header).

### Feedback: "Not interested" and "Don't recommend artist" (probed on the phone 2026‑11‑22, signed in, Premium account)

Used by Hide artist and Not interested (`docs/feedback-plan.md`, `filter/YouTubeFeedback.kt`).
- **Where the tokens are:** an item's own `menu.menuRenderer.items[]` in the signed-in `WEB_REMIX` Home browse. `LikeProbe.menus` (`adb shell am broadcast -a dev.thathunky.meteora.PROBE_LIKES -p dev.thathunky.meteora --es menus home`) logged `label x26: REMOVE_CIRCLE "Don't recommend artist" endpoint keys=[feedbackToken, actions]` and `label x38: NOT_INTERESTED "Not interested" endpoint keys=[feedbackToken, actions]`, both `menuServiceItemRenderer` with `serviceEndpoint.feedbackEndpoint`, and 332 `feedbackEndpoint`s with a token on Home in all. A watch queue (`next`) has neither entry. Which item kinds carry them (songs, albums, playlists, artist cards) was not logged.
- **Telling them apart:** by `icon.iconType`, since the labels are translated under `hl=uk`. The library toggles (`toggleMenuServiceItemRenderer`, icons `KEEP`, `BOOKMARK_BORDER`, `LIBRARY_ADD`) also carry `feedbackEndpoint` tokens under `defaultServiceEndpoint` / `toggledServiceEndpoint`; the parser reads only `menuServiceItemRenderer` entries.
- **`actions` on the endpoint:** present on both, not inspected (most likely the toast YouTube shows). No undo token is known, so telling YouTube is one-way from Meteora.
- **The call** (from the open-source clients' notes; **not yet seen answering on the phone**): `POST music.youtube.com/youtubei/v1/feedback?prettyPrint=false` as `WEB_REMIX`, signed in (cookie + `SAPISIDHASH`), body `{"context": {...}, "feedbackTokens": ["<token>"]}`. Expected answer `{"feedbackResponses": [{"isProcessed": true}]}`; Meteora counts only `isProcessed: true` as told. A token is single-use and bound to the response it came from. The app logs only `feedback isProcessed=<bool>` (or `feedback failed: <exception class>`) under the tag `YtmFeedback`, never the token or the answer.
- **Fixture:** `fixtures/ytm/home-feedback.json`, hand-made after the probe's output, with fake tokens.

## 3. MusicBrainz artist search (probed 2026‑11‑22, anonymous, from the PC; no phone)

Used only for the content filter's country rules (`filter/MusicBrainz.kt`).

- **Request:** `GET https://musicbrainz.org/ws/2/artist?query="<name>"&fmt=json&limit=5`, no key, a descriptive User-Agent (Meteora sends `Meteora/<version> (Android music player)`). A query with the `artist:` field matches the main name only (`artist:"Okean Elzy"` gave 0 results); the bare phrase also searches aliases and sort names.
- **Answer:** `artists[]` with `score` (0–100), `name`, `sort-name`, `country` (ISO 3166‑1, may be missing), `area {type, name}`, `aliases[] {name, locale, type}`, `disambiguation`.
- **Examples:** `"Okean Elzy"` → one artist, name `Океан Ельзи`, country `UA`, score 100 through its alias. `"Nirvana"` → the US band at 100, the UK one at 81, then 75 and below. `"Кино"` → `RU` at 100, the rest at 71 and below.
- **Busy:** about half of the first requests got `503` with `{"error": "The MusicBrainz web server is currently busy. Please try again later."}` even a few seconds apart, with `X-RateLimit-*` headers on the successful ones. The app treats a non-200 as "not answered" and backs off; it never caches it.
- **Fixtures:** `fixtures/musicbrainz/artist-okean-elzy.json`, `artist-nirvana.json`, `artist-kino.json`, trimmed to the fields above and five artists.
