# Plan: downloads and smart downloads

Rewritten 2026‑11‑22 for an implementing agent; it replaces the plan of 2026‑11‑21. What changed: the user wants the files as ordinary music files, "they must go to internal storage, under Music/Meteora/Artist/Album etc", and 256 kbps is now available (it was not when the first plan was written).

## What the user gets

- A **download button** on songs, albums and playlists.
- Files in the phone's shared storage: `Music/Meteora/<Artist>/<Album>/<NN> <Title>.m4a`, visible to any music player and file manager, with title, artist, album, track number and cover inside the file.
- Meteora plays a downloaded song from its file, with no network.
- A **Downloads** tab in Library, a **download quality** setting, a Wi‑Fi‑only switch.
- **Smart downloads**: a set of songs the app keeps downloaded by itself.
- Downloading is **only for YouTube Music Premium accounts**, as in the official app. This keeps Meteora to what the account is entitled to; it does not make the way Meteora fetches audio permitted by YouTube's terms, and the app stays sideload‑only.

## Decisions taken (the user can overrule them)

| Question | Decision |
|---|---|
| Premium ends | No new downloads. Files already in `Music/` are the user's files and are left alone. |
| Premium re‑check | When a download is queued and at each smart refresh; a positive answer is remembered for 30 days. Signed out, check failed or marker missing all mean "not Premium" (fail closed). |
| Smart downloads size | 100 songs by default, 0 to 500. |
| New dependency | WorkManager (downloads and the nightly refresh). Media3's `DownloadManager` is not used: it writes into a cache, not into files. |
| Format | AAC in `.m4a` always (itag 141 at High with Premium, else 140), not Opus: it plays everywhere and can carry tags. |

## Order of work

### D0. Premium signal (may stop the work)

Nothing in the code knows whether the account has Premium. With an anonymous request you cannot see it, and you have no phone, so:

- Add `YtmClient.isPremium()` reading the best candidate markers from responses the app already fetches signed in (the account menu's "Get Music Premium" / upgrade entry being absent; a subscriber flag in the page config that `ensureClientVersion()` downloads). Parse defensively, fail closed.
- Add a debug broadcast like the existing ones in `MeteoraApp.kt` (`…PROBE_PREMIUM`) that logs which markers were found, with no personal fields, so the reviewer can confirm on the phone with the user's Premium account.
- A JVM test over hand‑made fixtures of both shapes. Write what is known and unknown in `docs/api-notes.md`.

The reviewer verifies on the phone. If the marker turns out wrong, the gate is fixed then; do not block the rest on it.

### D1. One song to a tagged file

- **Fetch:** `downloads/Downloader.kt`. Resolve through `StreamResolver` with a new `resolveForDownload(videoId, quality)` that only accepts AAC (extend the Rust `pick_audio` with a container preference, `cargo test` for it; signed in + High goes through `WebMusicStreams` for itag 141 as streaming does). Fetch with the media OkHttp client in ranges of at most 10 MiB (YouTube throttles larger and open‑ended ranges: `docs/api-notes.md`, "Video formats"; reuse the chunk maths in `playback/ChunkedDataSource`). Resolve just before fetching: URLs expire in about six hours and are tied to the IP. On a failed range, re‑resolve once and continue from the byte reached; a different itag on re‑resolve restarts the file.
- **Tags, in Rust:** YouTube's `.m4a` is fragmented MP4 without tags. Add `rust/meteora-core/src/mp4tags.rs`: given the file's `moov` box bytes and the metadata (title, artist, album, album artist, track number, year if known, cover JPEG), return a new `moov` with `udta/meta/ilst` added (`©nam`, `©ART`, `©alb`, `aART`, `trkn`, `©day`, `covr`). In a fragmented file `moov` comes before `sidx` and the fragments, whose offsets are relative, so growing `moov` shifts nothing that is addressed absolutely; verify that claim against the box layout of a real file and say so in the notes (if `sidx` or `tfhd` there use absolute offsets, fix them up). Kotlin streams the rest of the file through unchanged. `cargo test` with a tiny synthetic fMP4 and, if `METEORA_M4A` points at a real file, a test on that. No I/O in Rust, as elsewhere.
- **Write:** through `MediaStore.Audio.Media` with `RELATIVE_PATH = "Music/Meteora/<Artist>/<Album>/"`, `DISPLAY_NAME = "<NN> <Title>.m4a"`, `IS_PENDING = 1` until complete, then 0. No storage permission is needed for files the app creates (minSdk 31). Path parts are sanitised (no `/ \ : * ? " < > |`, no leading dot, trimmed, at most 80 characters, "Unknown artist" / "Singles" when empty). Pure function with JVM tests, including Cyrillic names and two songs that sanitise to the same name.
- **Cover:** the track's artwork at 1080 px, fetched once per album and embedded.
- **Index:** `downloads/DownloadIndex.kt`, a small SQLite table (videoId, content URI, itag, bytes, title, artist, album, track number, artwork URL, duration, state, manual or smart, collection ids). No Room; `SQLiteOpenHelper` is enough.
- **Runner:** WorkManager, one unique work chain, at most two files at a time, a progress notification (foreground info, type `dataSync`; add `FOREGROUND_SERVICE_DATA_SYNC` to the manifest), constraint unmetered when the Wi‑Fi‑only switch is on.
- **Gate:** `downloads/DownloadsGate.kt` from the decisions table; JVM tests.

### D2. Play from the file

- In `LocalEngine`'s resolving lambda, ask the index first: a complete download becomes a `DataSpec` on its `content://` URI (a `ContentDataSource` beside the cached HTTP source, via `DefaultDataSource` or a small delegating source), with no call to `StreamResolver` and no HTTP cache key. Pin the track's format as usual.
- If the file is gone (the user deleted it in a file manager), drop the index row and stream instead.
- Offline, Play on a downloaded song, album or playlist builds the queue from the index (`ytm.watchQueue` needs the network).
- Touch `LocalEngine` as little as possible and keep its rules (CLAUDE.md, "Things that are easy to break").

### D3. Button, collections, Library

- `ui/downloads/DownloadButton.kt`: not downloaded, queued, downloading with a percentage, downloaded, failed (tap retries), fed by a per‑id flow from the index. Places: the long‑press sheet (`ui/browse/ItemActions.kt`: a new `ItemAction.Download` / `RemoveDownload`), album and playlist page headers next to Play and Shuffle, and Now Playing next to like.
- An album or playlist downloads its tracks and remembers the collection; removing it deletes the files no other downloaded collection needs. Reference counting is a pure function with tests. Track numbers come from the collection's order for albums; playlists do not renumber a file that already exists.
- Library gets a **Downloads** tab: collections and songs, from the index. With no network, Home shows an offline note with a button to Downloads instead of an error.
- Without Premium the buttons are not hidden: they open a short sheet saying downloads need YouTube Music Premium.
- Removing a download deletes the file through MediaStore (the app owns it, so no prompt) and empty album and artist folders are left to the system.

### D4. Settings

Settings → Playback (or a new Downloads category in `ui/settings/SettingsCategories.kt`): download quality (Normal about 130 kbps, about 57 MB per hour; High 256 kbps, about 115 MB per hour, Premium), Wi‑Fi only (on by default), space used by downloads and free space, "Remove all downloads" with a confirmation.

### D5. Smart downloads

A switch, a size slider and "Refresh now". Refreshes nightly through WorkManager (charging, unmetered, storage not low). A refresh checks Premium, builds the wanted list below, queues what is missing as smart downloads and removes smart downloads that dropped out. Manual downloads are never touched; a song that is both is manual. It stops adding when free space falls under 500 MB, and stops early with a report if lookups start failing (the yt-dlp fallback is far too slow for this).

**The wanted list** (asked for by the user: "can we get some sort of algo?"). The old rule, recent likes interleaved with "Listen again", ignored what is actually played on this phone.

*Listening log.* New table next to the download index: one row per video id with `plays` (listened past half its length, or 4 minutes), `skips` (left in the first 30 seconds by Next), `lastPlayedMs`, and the track's metadata. Written by `LocalEngine` on media item transitions, phone target only. It never leaves the phone and has a "Clear listening history" row in Settings.

*Score per song.* Everything is a pure function with JVM tests.

- Habit: `plays * 0.5^(daysSinceLastPlayed / 30)`: a play counts half after a month.
- Skips: minus `1.5 * skips * 0.5^(daysSinceLastPlayed / 30)`; a song skipped more often than played scores zero here.
- Liked: plus 3, and plus up to 2 more for being among the 50 most recent likes (linear by position).
- "Listen again" on Home: plus 2 at the first position falling to 0 at the last.

*Three shares of the size*, so the set is not only old favourites:

- 60 % **favourites**: the highest scores.
- 25 % **recent likes**: newest liked songs not already in.
- 15 % **fresh**: from the account's personal mix (the first "mix" or "Supermix" playlist on Home; skip this share and give it to favourites when there is none), songs with no plays on this phone.

A share that cannot be filled passes its room to favourites. At most two songs in a row by one artist in the fresh share.

*Manual downloads are outside the limit.* The size counts smart downloads only. A song the user downloaded by hand (alone or as part of an album or playlist) is left out of the candidates, so it takes no smart slot: with 300 manual songs and a size of 100, the phone holds 400. When a manual download is removed and the song still scores high, it can come back as a smart one at the next refresh; when the user manually downloads a song that is already a smart one, it becomes manual and its slot is refilled.

*Stability*, so the phone does not re-download every night:

- A song already downloaded stays unless its place is taken by one scoring at least 20 % higher.
- At most 20 % of the set is replaced in one refresh.
- Nothing played in the last 14 days is removed.

Settings shows, under the switch, one line of what the last refresh did ("Kept 92, added 8, removed 8") and when.

## Strings

English and Ukrainian for everything shown, added near related strings. Notifications too.

## UI and merge rules

Two reviewers are editing `ui/` on other branches right now. Put UI in new files under `ui/downloads/`, and add only single lines to existing screens (`PageScreens.kt`, `HomeLibrary.kt`, `PlayerHost.kt`, `MusicRows.kt`, `SettingsScreens.kt`). Use the look‑aware wrappers in `ui/components`.

## Tests

JVM: path sanitising, gate rules, index round trip (Robolectric is not available: keep SQL behind an interface and test the logic above it), reference counting, the smart wanted‑list, chunk resume maths, the Premium parser. Rust: `pick_audio` with the AAC preference, `mp4tags`.

## Verify

`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain -q` from PowerShell and `cargo test --manifest-path rust\meteora-core\Cargo.toml`. No phone. The reviewer checks on the OnePlus: the Premium probe; a song downloads to `Music/Meteora/…` and shows title, artist, album and cover in another player; airplane mode playback and seek; an album in order; removal frees the files; Wi‑Fi only; smart refresh.

## Risks

- **The Premium marker is unverified** until the phone check.
- **Tagging fragmented MP4** by hand is the delicate part. If the tagged file does not play or scan, the fallback is the untagged file with artist, album and title in the path, and that must be said in the report.
- **Storage:** 500 songs at High is about 4 GB.
