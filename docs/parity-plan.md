# Plan: closing the gap with the official client

Written 2026‑11‑21. The user asked for what the official YouTube Music app and web player do and Meteora doesn't: starting a radio or mix the way the official one does, autoplay, sharing links, starting an artist, "and everything else". This lists the gap and specifies the first slice, P1, for an implementing agent. Later slices are outlined.

## The gap

Compared against the official Android app's menus as seen on the OnePlus 7T on 2026‑11‑21 (song menu: Play next, Save to playlist, Share, Start mix, Add to queue, Remove from library, Download, Go to album, Go to artist, View song credits, Pin to Speed dial, Dismiss queue, Report, Sleep timer) and against Meteora's code.

| Area | Official | Meteora today | Slice |
|---|---|---|---|
| Song menu | Start radio, Play next, Add to queue, Go to album / artist | all five | has |
| Song menu | Share | missing | P1 |
| Album / playlist menu | Play, Shuffle, Play next, Add to queue | Play next, Add to queue (Play and Shuffle only on the page) | P1 |
| Album / playlist menu | Start radio, Share | missing | P1 |
| Artist menu | Shuffle, Start radio, Share | no actions at all | P1 |
| Now Playing | Start radio from the current track, Share | missing | P1 |
| Now Playing | Artist and album are tappable | plain text | P1 |
| Autoplay | After an album or playlist ends, similar music continues | playback stops (phone); YTMDesktop handles it (PC) | P1 |
| Autoplay | A switch to turn it off | missing | P1 |
| Library | Save / remove album, playlist, song; subscribe to an artist | missing | P2 |
| Playlists | Save to playlist, create, rename, reorder, delete | missing | P2 |
| Library | Albums, artists, history tabs | Liked songs and playlists only | P2 |
| Now Playing | Lyrics, Related | missing | P3 |
| Now Playing | Sleep timer | missing | P3 |
| Home | Mood chips, "more" on a shelf, paging | first page of shelves | P3 |
| Explore | New releases, charts, moods and genres | missing | P3 |
| Song menu | View song credits, Pin to Speed dial | missing | P3 |
| Now Playing | Song / video switch | missing | later |
| Downloads | Download, smart downloads | planned in [downloads-plan.md](downloads-plan.md) | separate |
| Cast | Cast to a device | out of scope: the PC is Meteora's "other device" | no |
| Report, Dismiss queue | present | not planned | no |

## Not building: sharing the audio file

The user also asked for "share with file", to send a track into Telegram as a file. That one is left out. Sharing a link lets the other person play the track from YouTube; sending the audio file hands out a copy of the recording itself, which is redistribution of someone else's music rather than a feature of the official client. P1 shares links, and Telegram shows a playable preview for those.

## P1: radio, autoplay, share, artist actions

Read first: `CLAUDE.md`; `ui/browse/MusicRows.kt` (`ItemActionsSheet`, `ItemAction`), `ui/shell/MeteoraShell.kt` (where the sheet's actions are handled), `ui/browse/PageScreens.kt`, `ui/player/PlayerHost.kt`, `data/MusicActions.kt`, `data/PlaybackRouter.kt`, `playback/LocalEngine.kt` (`maybeExtendRadio`, `load`, `queueFor`), `ytm/YtmClient.kt`, `ytm/YtmParser.kt` (`watchQueue`), `ytm/YtmModels.kt`, and the "Queues" notes in `docs/api-notes.md`.

### 1. Radio ids

| Start radio on | Playlist id to play | Status |
|---|---|---|
| a song | `RDAMVM<videoId>` | verified (api-notes) |
| a playlist or album | `RDAMPL<playlistId>`, where the playlist id has no `VL` prefix (for an album, its `OLAK5uy_…` id) | **not verified**: confirm with one `next` call before relying on it; if it returns no tracks, find the id the web player uses (the "Start radio" entry in the collection's menu carries a `watchPlaylistEndpoint`) |
| an artist | `ArtistPage.radioPlaylistId` (already parsed), shuffle = `shufflePlaylistId` | in use on the artist page |

Add `MusicActions.radio(item: MusicItem)` covering all four types (an album needs its playlist id resolved first, as `withAlbumPlaylist` does; an artist needs its page fetched, as `play(Artist)` does). On the PC it is `changeVideo` with that playlist id; on the phone, `PlayIntent.Collection(id)`.

### 2. Action sheet

Replace the sheet's fixed list with one per type, in this order:

- **Song:** Play next, Add to queue, Start radio, Go to album, Go to artist, Share.
- **Album, playlist:** Play, Shuffle, Play next, Add to queue, Start radio, Share.
- **Artist:** Shuffle, Start radio, Share.

Shuffle for a collection opened from a list has no track list in hand. On the phone use `router.play(PlayIntent.Collection(id), shuffle = true)`; on the PC, play it and turn shuffle on once it has loaded, as `MusicActions.shuffleCollection` already does (factor that out rather than copy it). Shuffle for an artist plays `shufflePlaylistId`.

The sheet currently closes without its hide animation (`onDismiss()` straight after the action). Hide it through the sheet state first.

### 3. Share

- Links:
  - song: `https://music.youtube.com/watch?v=<videoId>`
  - playlist: `https://music.youtube.com/playlist?list=<playlistId>` (no `VL` prefix)
  - album: `https://music.youtube.com/playlist?list=<OLAK5uy_…>` when the playlist id is known, else `https://music.youtube.com/browse/<browseId>`
  - artist: `https://music.youtube.com/channel/<browseId>`
- A pure function `shareUrl(item: MusicItem): String` in `share/ShareLink.kt`, with tests. `ShareLink.parse` must parse every URL it produces back to the same target (a round‑trip test); it already understands all four shapes.
- Send with `Intent.ACTION_SEND`, `text/plain`, `EXTRA_TEXT` = the URL, `EXTRA_TITLE` and `EXTRA_SUBJECT` = "title – artist", through `Intent.createChooser`. Start it from the activity context.
- Meteora's own share target (`ShareReceiverActivity`, "Play on PC") will appear in that chooser. Exclude it with `Intent.EXTRA_EXCLUDE_COMPONENTS`.
- Where: the action sheet (all types), the header of album, playlist and artist pages (an icon button), and Now Playing.

### 4. Now Playing

- An overflow button (three dots) in the top row, next to the queue button, with: Start radio, Share, Go to album, Go to artist. The last two only when the id is known (`Track.albumId`, `Track.artistChannelId`).
- The artist and album in the subtitle line become tappable and open their pages. The line is one marquee text today; split it into two clickable parts without losing the marquee for long names. Opening a page from Now Playing collapses the player first.
- `PlayerHost` has no navigation today. Pass callbacks in from `MeteoraShell` (`onOpenAlbum(browseId)`, `onOpenArtist(browseId)`), the way `ItemActionsSheet` is handled there.
- Keep per-frame reads where they are (see "Things that are easy to break" in CLAUDE.md); do not read the whole playback state in new places.

### 5. Autoplay on the phone

When a queue that came from an album or playlist runs out, the official client carries on with similar music. On the phone today playback just ends.

- The `next` response for a collection describes what comes after it. Look for `automixPreviewVideoRenderer` and, inside it, a `watchPlaylistEndpoint` with a `playlistId` (expected to start with `RDAMPL` or `RDAT`) and `params`. **Not verified**: capture one signed‑out `next` response for an album (`playlistId` = an `OLAK5uy_…` id) into `app/src/test/resources/fixtures/ytm/next-album.json`, find the real keys, and write the parser against the fixture.
- `WatchQueue` gains `automix: AutomixRef?` (playlist id and params). `LocalEngine` keeps it next to `radioContinuation`. In `maybeExtendRadio`, when there is no radio continuation, fewer than four tracks are left, autoplay is on and an automix reference exists, fetch that playlist's queue once and append its tracks (skipping ones already queued), then carry on with its continuation like a radio.
- Repeat‑all must win: do not append when `repeatMode` is all.
- Setting: "Autoplay" under the quality block in Settings, on by default, stored in the `playback` preferences. Strings: `autoplay_title` "Autoplay" / «Автовідтворення», `autoplay_note` "When an album or playlist ends on this phone, keep playing similar music." / «Коли альбом чи плейлист на цьому телефоні закінчується, грати схожу музику далі.»
- The PC needs nothing: YTMDesktop's own autoplay does this.

### 6. Strings (English / Ukrainian)

`action_share` Share / Поділитися · `action_shuffle` already exists as `shuffle` · `action_more` More / Ще · `share_chooser` Share via / Поділитися через. Reuse `action_play`, `action_radio`, `action_go_album`, `action_go_artist`.

### Tests

- `shareUrl` for each type and the round trip through `ShareLink.parse`.
- The automix parser against the new fixture, and `watchQueue` still passing its existing tests.
- Which actions the sheet offers for each item type (make the list a pure function so it can be tested).

### Verify

- Build and test from PowerShell: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain -q` (600000 ms timeout).
- No phone, no adb, no commit. The reviewer checks on the OnePlus: radio from an album and an artist, an album running into autoplay, a shared link opening in Telegram, and the tappable artist line.

### Files

`ui/browse/MusicRows.kt`, `ui/browse/PageScreens.kt`, `ui/shell/MeteoraShell.kt`, `ui/shell/ShellViewModel.kt`, `ui/player/PlayerHost.kt`, `ui/player/PlayerViewModel.kt`, `data/MusicActions.kt`, `data/PlaybackRouter.kt`, `playback/LocalEngine.kt`, `ytm/YtmClient.kt`, `ytm/YtmParser.kt`, `ytm/YtmModels.kt`, `share/ShareLink.kt`, both `strings.xml`, tests and the new fixture. Nothing under `stream/`, `core/`, `rust/` or `app/build.gradle.kts`: another change is in progress there.

## P2 and P3 (outline)

- **P2, library and playlists:** save and remove (albums, playlists, songs), subscribe to artists, "Save to playlist" with a playlist picker, create / rename / reorder / delete playlists, and Albums, Artists and History tabs in the Library. All need signed‑in InnerTube calls that are not yet in `api-notes.md`; start with a probe, as for likes.
- **P3, the rest of Now Playing and browsing:** lyrics and related tabs (the `next` response links to both), sleep timer, Home's mood chips and paging, an Explore tab, song credits.
