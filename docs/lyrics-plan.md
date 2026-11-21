# Plan: lyrics, synced where available

Written 2026‑11‑22 for an implementing agent. The user: "Can we do synced lyrics?" The official app shows lyrics in Now Playing; for many tracks they are timed line by line and the current line is highlighted, and a tap on a line seeks to it.

## What the user gets

- A **Lyrics** button in Now Playing (next to the queue button). It opens a lyrics page over the player: the lines in a scrolling list, the current line emphasized and kept near the upper third of the screen, past lines dimmed. Tapping a line seeks there. Scrolling by hand pauses the auto-follow for a few seconds.
- Tracks with only plain lyrics show them as static text. Tracks with none say so.
- The source line YouTube sends ("Source: Musixmatch" and the like) is shown at the bottom, as the official app does.
- Works for both targets: the lyrics come from YouTube by videoId, the position from `PlaybackState`, the seek through the usual `Command`.
- Every look: text uses the theme's typography, so Terminal gets mono lines; in Terminal mark the current line with a `>` prefix as well as colour.

## Facts to rely on, and the ones to check first

Believed, from the open-source `ytmusicapi` (check, do not trust):

- The `next` response for a video has a tab whose `browseEndpoint.browseId` starts with `MPLY`. That is the lyrics id.
- `browse` with that id as `WEB_REMIX` returns plain text in `musicDescriptionShelfRenderer` (`description.runs`, `footer.runs` for the source).
- The same `browse` as a mobile music client (`ANDROID_MUSIC` or `IOS_MUSIC`) returns `timedLyricsModel.lyricsData.timedLyricsData[]` with `lyricLine` and `cueRange.startTimeMilliseconds` / `endTimeMilliseconds`, plus `sourceMessage`, when the track has timed lyrics.

Step 1, before any app code: with anonymous `curl` from the PC, confirm the three points on two or three well-known tracks, find which client name and version return timed lyrics, whether it works signed out, and whether a track without timed lyrics falls back to the plain shape in the same response. Write the result in `docs/api-notes.md` under "Lyrics", and save trimmed responses as fixtures under `app/src/test/resources/fixtures/ytm/` (`lyrics-timed.json`, `lyrics-plain.json`, with the lyric text cut to a few lines).

If timed lyrics cannot be had from YouTube at all, still build the feature with plain lyrics, and report.

### Optional second source: LRCLIB

Many tracks have no timed lyrics on YouTube. LRCLIB (`https://lrclib.net/api/get?track_name=…&artist_name=…&album_name=…&duration=…`, no key, returns `syncedLyrics` in LRC format) covers a lot of them. Using it sends the track's title and artist to a third party, so:

- It is a **setting, off by default**: Settings → Playback → "Find synced lyrics on LRCLIB", with a note saying what is sent (title, artist, album, length; nothing about the account).
- Used only when YouTube has no timed lyrics for the track and the setting is on. No cookies, no account headers, a plain TLS client with a generic User-Agent (`Meteora`, no version details beyond the app version).
- Accept a result only when its duration is within 2 s of the track's; otherwise treat it as not found.
- Show "Source: LRCLIB" at the bottom.

## How to build it

- `ytm/YtmClient`: `lyricsId(videoId)` (from `next`; reuse the queue's `next` response if the engine already has it, otherwise one call) and `lyrics(browseId)` with the client found in step 1. `ytm/YtmParser.lyrics(root): Lyrics?`.
- `domain/Lyrics.kt`: `data class Lyrics(lines: List<LyricLine>, synced: Boolean, source: String?)`, `LyricLine(startMs: Long?, text: String)`. A pure `currentLine(lines, positionMs): Int` (binary search; -1 before the first line) with tests. Empty-text timed lines are instrumental gaps: keep them, draw them as `♪`.
- `lyrics/LrcParser.kt`: LRC text → lines (`[mm:ss.xx]`, several tags on one line, skip metadata tags). JVM tests.
- `lyrics/LrcLibClient.kt` and `lyrics/LyricsRepository.kt`: YouTube first, LRCLIB second if enabled; an in-memory LRU of about 30 tracks keyed by videoId, including "none" results so a track is not asked twice. Fetch when the lyrics page opens or the track changes while it is open, not for every track played.
- `ui/player/LyricsViewModel.kt`, `ui/player/LyricsScreen.kt`: new files. The current line index is derived from the position at most a few times a second (`derivedStateOf` / a narrow flow), never by reading the whole `PlaybackState` high in the tree (CLAUDE.md, "Things that are easy to break"). On the PC target the position arrives about once a second: interpolate from the last update while playing, as the seek bar already does.
- `ui/player/PlayerHost.kt`: only the button and the call that shows `LyricsScreen`. Use the look-aware wrappers in `ui/components` (`MIconButton`, `MLoading`).
- Settings → Playback (`ui/shell/SettingsScreens.kt`): the LRCLIB switch; store it next to autoplay (`playback/AutoplayStore.kt` is the model).
- Strings in English and Ukrainian: `action_lyrics` Lyrics / Текст · `lyrics_none` No lyrics for this track / Для цього треку немає тексту · `lyrics_not_synced` These lyrics are not synced / Цей текст не синхронізований · `lyrics_source` Source: %s / Джерело: %s · `lrclib_title` Find synced lyrics on LRCLIB / Шукати синхронізований текст на LRCLIB · `lrclib_note` Sends the track's title, artist, album and length to lrclib.net when YouTube has no synced lyrics. / Надсилає назву, виконавця, альбом і тривалість треку на lrclib.net, коли на YouTube немає синхронізованого тексту.

## Tests (JVM)

Parser on both fixtures; `currentLine` (before the first line, exactly on a start, between lines, after the last, with gaps); LRC parsing; the LRCLIB duration rule; the repository order (YouTube timed wins, plain YouTube kept when LRCLIB is off or misses).

## Verify

`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain -q` from PowerShell. No phone, no adb. The reviewer checks on the OnePlus: a track with timed lyrics follows the music and seeks on tap, a plain one, one with none, Ukrainian lyrics, the Terminal look, and the LRCLIB switch.

## Files

New: `domain/Lyrics.kt`, `lyrics/*`, `ui/player/LyricsScreen.kt`, `ui/player/LyricsViewModel.kt`, fixtures, tests. Changed: `ytm/YtmClient.kt`, `ytm/YtmParser.kt`, `ui/player/PlayerHost.kt` (a few lines), `ui/shell/SettingsScreens.kt`, both `strings.xml`, `docs/api-notes.md`, `docs/devlog.md`. Nothing under `stream/`, `rust/`, `playback/LocalEngine.kt`.

## Order of work

1. The API check, notes and fixtures. Commit.
2. Models, parser, `currentLine`, repository with YouTube only, tests. Commit.
3. The lyrics page and the button. Commit.
4. LRCLIB behind its setting, devlog. Commit.
