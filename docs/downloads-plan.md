# Plan: downloads and smart downloads

Written 2026‑11‑21. A plan only: nothing here is built yet. It follows the shape of [quality-switch-plan.md](quality-switch-plan.md) and is meant to be handed to an implementing agent one milestone at a time.

## Scope

- A **download button** for songs, albums and playlists, for listening without a network.
- **Smart downloads**: the app keeps a set of songs downloaded by itself and refreshes it.
- A **download quality** setting, separate from the streaming quality.
- Files live in the app's **internal storage**, never on shared or external storage.
- The feature is **only available to YouTube Music Premium accounts**.

One thing to be straight about: the official app lets Premium members download, so gating on Premium keeps Meteora to what the account is entitled to. It does not make Meteora's way of fetching audio permitted by YouTube's terms; that stays as the devlog says, and the app stays sideload‑only.

## Decisions for you

These change the build, so they are worth settling before D1.

| # | Question | Proposed |
|---|---|---|
| 1 | What happens to downloads when the account stops being Premium? | Downloads stop playing offline at once, the files stay for 30 days in case Premium returns, then they are deleted. |
| 2 | How often is Premium re‑checked? | When downloads are switched on, at every smart refresh, and at least every 30 days. Offline for more than 30 days: downloads pause until one check succeeds (the official app has the same 30‑day rule). |
| 3 | Smart downloads default size | 100 songs, adjustable from 0 to 500. |
| 4 | New dependency | WorkManager, for the nightly smart refresh. Nothing else new: Media3 already ships the download engine. |

## D0: spike, find a reliable Premium signal

Nothing in the code knows whether the signed‑in account has Premium, and I have not verified a marker for it. Do this first, because the gate depends on it.

- With the user's signed‑in session, capture the InnerTube `account/account_menu` response and the `browse` response for `FEmusic_home` (both as `WEB_REMIX`, through `YtmClient`). Save them, with personal fields removed, as fixtures under `app/src/test/resources/fixtures/ytm/`.
- Find what differs for a Premium account: candidates are an "upgrade" / "Get Music Premium" entry in the account menu (present only without Premium) and a subscriber flag in the page config that `YtmClient.ensureClientVersion()` already downloads.
- Ideally compare against a non‑Premium account. If only one account is available, record that the marker is confirmed for one side only.
- Write the result into `docs/api-notes.md`.
- **Fail closed:** signed out, check failed, or marker missing all mean "not Premium".

Done when: `YtmClient.isPremium(): Boolean` exists with a parser test over the fixtures.

## Storage

- Directory: `context.filesDir/downloads`. Internal, private to the app, not cleared by Android when space runs low (unlike `cacheDir`, where the streaming cache lives), removed on uninstall.
- Engine: Media3's `DownloadManager` with its own `SimpleCache` over that directory and a `NoOpCacheEvictor`, so nothing is evicted behind the user's back. This is a second cache, separate from the 256 MB streaming cache in `LocalEngine`.
- Index: Media3's `DefaultDownloadIndex` (SQLite through the `StandaloneDatabaseProvider` already in use). Each `DownloadRequest` carries, in its `data` bytes, a small JSON blob: title, artist, album, artwork URL, duration, the quality tier, the itag, the file size, whether it is a manual or a smart download, and which collection it came from.
- Artwork: downloaded art is copied to `filesDir/downloads/art/<videoId>.jpg` so the lock screen and lists have it offline.
- Settings shows the space used and the space free, with "Remove all downloads".

## Playing a downloaded song

Today every open of a track resolves a URL first (`ResolvingDataSource` in `LocalEngine.build()`), which needs the network. Offline playback needs a path that skips it.

- In the resolving lambda, ask the download index first. If the video is fully downloaded, return a `DataSpec` whose key is the download's cache key and whose length is the stored file size, without calling `StreamResolver`.
- Data source order becomes: downloads cache (read only) → streaming cache → network.
- The format is fixed by the download, so the "pinning" rule in `LocalEngine` is satisfied by construction. Record the download's tier in `pinned` anyway so a partly downloaded track does not switch format between opens.
- The queue itself (`ytm.watchQueue`) also needs the network. Offline, "play" on a downloaded song, album or playlist builds the queue from the index instead.

## Download quality

- New setting next to the streaming quality block in Settings: "Download quality" with the same three tiers (`StreamQuality`): High (about 130 kbps, about 57 MB per hour), Balanced (about 65 kbps, about 29 MB per hour), Data saver (about 50 kbps, about 22 MB per hour). Default: High.
- Stored in `StreamQualityStore` as a third value (`quality_download`).
- The tier is read once when a download is queued and saved in the request, so a download that resumes later uses the same tier. If the resumed lookup returns a different itag than the one recorded, the download restarts from zero rather than appending to another format's bytes.
- Changing the setting affects new downloads only. A "Re‑download in the new quality" action can come later.
- The 256 kbps tier of the official app is not available to Meteora (see the quality switch plan), so "High" here is about 130 kbps.

## The download button

- **Where:** the long‑press action sheet for songs, albums and playlists; the header of album and playlist pages, next to Play and Shuffle; the full player, next to like and dislike.
- **States:** not downloaded, queued, downloading (with a percentage), downloaded, failed (tap to retry). One composable, `DownloadButton`, fed by a `StateFlow` per video id from the download manager's listener.
- **Collections:** downloading an album or playlist downloads its tracks and remembers the collection, so the Library can show it as one item and "remove" removes the tracks no other downloaded collection still needs.
- **Library:** a third tab, "Downloads", listing downloaded collections and songs. With no network the app opens here and the Home tab shows an offline note instead of a load error.
- **Network rule:** a "Download over Wi‑Fi only" switch, on by default, mapped to Media3 `Requirements.NETWORK_UNMETERED`.
- **Service:** Media3 `DownloadService`, a foreground service of type `dataSync` (needs `FOREGROUND_SERVICE_DATA_SYNC` in the manifest) with a progress notification. At most two downloads at a time.

## Smart downloads

- A switch and a size slider in Settings (0 to 500 songs), only shown for Premium.
- **What it keeps:** the most recent Liked songs and the songs on Home's "Listen again" shelf, interleaved, without duplicates, up to the size. Both lists come from calls `YtmClient` already makes (`likedSongs()`, `home()`). Mixes can be added as a source later.
- **When it refreshes:** once a night through WorkManager, only when charging, on an unmetered network and with storage not low. Also from a "Refresh now" button.
- **What a refresh does:** check Premium; build the wanted list; queue what is missing as smart downloads; remove smart downloads that are no longer wanted. Manual downloads are never touched, and a song that is both is treated as manual.
- **Budget:** stop adding when the size limit is reached or free space falls under 500 MB.

## Premium gate

- One class, `DownloadsGate`, answers "may downloads be used right now?" from: signed in, last successful Premium check, and how long ago that was.
- Without Premium the download buttons are not hidden: they open a short sheet saying downloads need YouTube Music Premium. Hidden buttons would read as a bug.
- Offline playback of downloads asks the gate too, so a lapsed account does not keep its library (decision 1).

## Milestones

| M | Scope | Done when |
|---|---|---|
| **D0** | Premium signal spike | `isPremium()` with fixtures and a test; notes in `api-notes.md` |
| **D1** | Download cache, manager, service, `DownloadsGate`, download button for single songs | A song downloads on the phone, survives an app restart, and the button shows each state |
| **D2** | Offline playback path | Airplane mode on: a downloaded song plays, seeks, and shows its artwork |
| **D3** | Albums and playlists, Library "Downloads" tab, offline start | A downloaded album plays in order offline; removing it frees its space |
| **D4** | Download quality, Wi‑Fi‑only switch, storage row in Settings | A new download at Data saver is about 40 % of the size of the same song at High |
| **D5** | Smart downloads with WorkManager | After "Refresh now" the wanted songs are downloaded; lowering the slider removes the extra ones |

Each milestone gets unit tests where logic can run on the JVM (gate rules, wanted‑list building, index metadata round trip, collection reference counting) and a check on a phone, recorded in the devlog.

## Risks

- **The Premium marker is unverified.** D0 may find none that is dependable. Then the choice is a weaker signal or no gate, and that is the user's call, not the implementer's.
- **Stream URLs expire** after about six hours and are tied to the phone's IP. A long download queue must resolve each track just before fetching it, which the `meteora://` scheme already does.
- **YouTube may change the visionOS client.** Downloads then fail like streaming does; the yt-dlp fallback is too slow (about 15 s per track) for a 500‑song refresh, so smart downloads should stop early and report rather than grind through it.
- **Storage:** 500 songs at High is roughly 2 GB.
