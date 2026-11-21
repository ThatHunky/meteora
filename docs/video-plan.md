# Plan: watching the music video

Written 2026‑11‑22 for an implementing agent. The user: "Watching a video function would be great!" The official app has a **Song / Video** switch at the top of Now Playing. Meteora plays audio only.

Start this only after the looks work is merged: both change `ui/player/PlayerHost.kt`.

## What the user gets

- In Now Playing, when the track has a video, a **Song | Video** switch above the artwork (a `SegmentedChoice` from `ui/components`, so it follows the look).
- Video mode: the artwork area shows the video, same size and corners as the artwork (16:9 inside it, letterboxed on the theme background). Tap the video to show a fullscreen button; fullscreen is landscape, edge to edge, with play/pause, seek and the switch back.
- The choice is remembered for the session, not per track: once on Video, following tracks that have a video play as video; tracks without one show their artwork.
- Phone target only. On the PC target the switch is hidden (YTMDesktop shows the video itself).
- When the app goes to the background or the screen turns off, the video track is turned off and only audio keeps playing, so no data is spent on pictures nobody sees. Coming back turns it on again.

## Facts to rely on, and the ones to check first

Already known (`docs/api-notes.md`, commit `58a50b4`):

- Signed in, a queue item that has a video counterpart comes as `playlistPanelVideoWrapperRenderer { primaryRenderer, counterpart: [{ counterpartRenderer: { playlistPanelVideoRenderer }, segmentMap }] }`. The parser now skips counterparts (`YtmParser.queueItems`). It must instead **keep the counterpart's videoId** on the song: add `videoCounterpartId: String?` to `MusicItem.Song`, filled from the counterpart, and `segmentMap` if present (pairs of start times in the audio and the video version).
- `musicVideoType`: `MUSIC_VIDEO_TYPE_ATV` is an audio track (artwork only); `OMV` (official video), `UGC` (upload) and others are videos. A song plays as video from its own id if it is not ATV, from `videoCounterpartId` if it is ATV and has one, and not at all otherwise.

Check before building (anonymous `curl` from the PC is enough; no phone):

1. Which client in `InnerTubeStreams` / `WebMusicStreams` returns **video** adaptive formats (`mimeType` starting `video/`) with usable URLs for an OMV id, and whether they need a signature / `n` solve like the 256 kbps audio. Write the answer in `docs/api-notes.md` under a new "Video formats" heading.
2. Which codecs come back (`avc1`, `vp9`, `av01`) and up to which height.
3. Whether the video-only formats are also throttled without an exact byte range (expected: yes, same rule as audio).

If no client the app already uses gives video formats without a PO token, stop after step 1 and report: do not add a new client or a PO token generator without the user.

## How to build it

### Rust (`rust/meteora-core/src/streams.rs`)

- `pick_video(player: &str, max_height: u32, codecs: &str) -> String`: from `adaptiveFormats`, video only, height ≤ `max_height`, codec in the given preference order (the phone passes the ones it decodes in hardware), highest bitrate within that. Same JSON-out style as `pick_audio`, including `contentLength`, so the request can have an exact range.
- JNI wrapper in `jni.rs`, Kotlin side in `core/MeteoraCore.kt`. `cargo test` cases with a fixture `/player` response: height ceiling, codec order, ciphered formats when `allow_cipher` is false.

### Kotlin streams

- `stream/VideoStream.kt` (like `AudioStream`: url, itag, contentLength, height, codec, `cacheKey`).
- `StreamResolver.resolveVideo(videoId, quality)`: same caching and fallback shape as `resolve`, but without the yt-dlp fallback (15 s is too slow to wait for a picture; fail to audio-only instead).
- Video quality in `StreamQuality.kt`: a separate ceiling, **Wi‑Fi 720p, mobile data 360p** by default, chosen in Settings → Playback next to the audio quality (Low 360p / Normal 480p / High 720p / Highest 1080p). Hardware codecs from `MediaCodecList`, checked once.
- Disk cache: same `SimpleCache`, key `videoId/itag` (it already includes the itag, so audio and video files never mix).

### Playback (`playback/LocalEngine.kt`)

- In video mode, an item's media source is a `MergingMediaSource` of the video (`meteora-video://<id>`) and the audio (`meteora://<id>`), both through the existing `ResolvingDataSource`, which learns the new scheme. The audio part stays exactly what audio mode plays (same pinned format), so switching modes never changes the sound's quality.
- For an ATV song with a counterpart, the video comes from the counterpart id and the audio from the song's own id. The two versions may differ in length (intros, outros): map the position with `segmentMap` when present; without one, keep audio-only for that track rather than show a picture out of sync. Keep this mapping in a small pure function with JVM tests.
- Switching mode replaces the current item's source at the current position (`setMediaSource` + `seekTo`), and rebuilds the following items lazily, not the whole queue at once.
- Background / screen off: disable the video renderer with track selection parameters (`setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)`), not by rebuilding sources. `MeteoraSessionService` already knows when the activity stops.
- Pins (`pinned`) keep holding per stream: a pinned video itag per track, same reason as audio.
- LoadControl: the buffer sizes are tuned for audio. With video, cap the buffer by bytes (about 50 MB) so a 10‑minute back buffer of 720p cannot exhaust memory.

### UI

- `ui/player/PlayerHost.kt`: the switch, and the video surface in the artwork slot (`PlayerSurface` from `media3-ui-compose` if it is in the dependencies; otherwise an `AndroidView` with `SurfaceView`). The shared-element transition stays on the artwork frame, with the video inside it.
- Fullscreen: a new route or a dialog-level composable that locks landscape while shown and hides system bars; back returns to Now Playing.
- `PlaybackState` gains `hasVideo: Boolean` and `videoMode: Boolean`; read them through narrow derived flows, not the whole state (CLAUDE.md, "Things that are easy to break").
- Strings, English and Ukrainian: `mode_song` Song / Пісня, `mode_video` Video / Відео, `action_fullscreen` Full screen / На весь екран, `video_quality` Video quality / Якість відео, plus the four quality labels.

## Tests (JVM and Rust)

- Parser: a wrapped queue item keeps one entry, with `videoCounterpartId` and the segment map (extend `WatchQueueTest.counterpartIsNotQueued`).
- Which id plays as video: ATV with counterpart, ATV without, OMV, UGC.
- Position mapping with a segment map: start, inside a segment, past the end.
- Rust `pick_video` cases above.

## Verify

- `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain -q` and `cargo test --manifest-path rust\meteora-core\Cargo.toml`.
- No phone, no adb for the agent. The reviewer checks on the OnePlus: an OMV track and an ATV track with counterpart in video mode, switching mid-track keeps the position, fullscreen, the screen-off audio continuing with no video traffic (logcat), mobile-data ceiling, and tap-to-picture time.

## Files

New: `stream/VideoStream.kt`, fullscreen composable, tests. Changed: `rust/meteora-core/src/{streams,jni}.rs`, `core/MeteoraCore.kt`, `stream/{StreamResolver,StreamQuality,InnerTubeStreams,WebMusicStreams}.kt`, `ytm/{YtmModels,YtmParser}.kt`, `playback/{LocalEngine,MeteoraSessionService}.kt`, `domain/Playback.kt`, `ui/player/PlayerHost.kt`, Settings → Playback, both `strings.xml`, `docs/api-notes.md`.

## Order of work

1. The format check, written into `api-notes.md`. Stop here if the answer is no.
2. Parser keeps counterparts; Rust `pick_video`; `resolveVideo`. Build and test.
3. Engine video mode and the switch in Now Playing (no fullscreen yet). Build.
4. Background / screen-off, quality setting, fullscreen. Build and test.
