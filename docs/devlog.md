# Meteora devlog

How Meteora got built, in order, with what broke along the way. Everything happened on 2026‑11‑21, and every change was tried on a OnePlus 7T (LineageOS, Android 15) against YTMDesktop 2.1.0 on the PC. Times are commit times.

---

## The question

It started with a screenshot of YTMDesktop's *Integrations* page and one question: could an Android app start *any* song on the PC, not just press play and pause? The companion server in YTMDesktop 2.1.0 has a `changeVideo` command, and its source shows the command passes a `watchEndpoint` straight to the YouTube Music web app. So yes: any video, any playlist, any radio. The server can't browse anything, though, so the phone would have to talk to YouTube Music itself to show your Home and library.

## Planning (18:44)

The plan came out of a long grilling session: 19 numbered decisions in [PLAN.md](../PLAN.md). The main ones:

- **Name:** Meteora, after the album.
- **Platform:** Kotlin and Compose, built on the newest alpha of Material 3 Expressive on purpose.
- **Data:** a single module, with WebView sign‑in to YouTube Music.
- **Playback:** a media session, so the lock screen and Bluetooth buttons drive the PC.

Two spikes came before any app code, because each one could kill an approach:

- **S1:** do the `changeVideo` recipes actually work?
- **S2:** will Google let you sign in inside a WebView?

## S1: poking the real PC (18:50)

S1 paired with the PC using `curl`, sent every command once and recorded the realtime stream. Findings, all now in [api-notes.md](api-notes.md):

- **Recipes:** radio is `RDAMVM` + videoId, albums are their `OLAK5uy_…` playlist, liked songs are `LM`, and every one works.
- **A null ID wipes your queue.** The server turns `null` into `""`, and an empty watch endpoint clears the PC's queue. Command JSON is therefore built by hand, and absent IDs are left out.
- **Same track is ignored.** `changeVideo` on the track that's already loaded does nothing, so the app sends *play* instead.
- **State is heavy.** Updates are about 50 KB each, at 4–15 per second. The client keeps only the latest one and parses it off the main thread.

## Skeleton to remote control (18:56 → 19:11)

The build sits on the newest tools: AGP 9.5, Kotlin 2.4, Compose BOM alpha, material3 1.6.0‑alpha01, Navigation 3. Coil and Compose alpha needed compileSdk **37.2**, a minor SDK level that had to be installed first.

M1 added:
- **Pairing:** the code is shown on both screens and you click Allow on the PC.
- **Command throttler:** the server allows 2 commands/s, so play, pause and queue edits jump the line, while seek and volume keep only their latest value.
- **Realtime connection:** a Socket.IO client.

Device bug: **mDNS replies never reach the phone** on this network. Discovery now falls back to a sweep of the /24 subnet, 64 probes at a time, and finds the PC in about two seconds.

M2 and M3 added the UI and the media session:
- **Mini player:** a floating toolbar.
- **Full player:** artwork that morphs between a square and a 12‑sided cookie shape on play/pause, and a wavy progress bar.
- **Colour:** a theme built from the album art.
- **Queue:** drag to reorder.
- **Media session:** a Media3 player that plays nothing itself. It mirrors the PC and has *remote* volume, so the hardware keys change the PC's volume.

## Browsing YouTube Music (19:18)

The YouTube Music calls copy what the web player sends (InnerTube, `WEB_REMIX` client). YouTube rearranges its JSON often, so the parser doesn't follow fixed paths: it looks for the renderers it knows anywhere in the response. Search, album, artist and playlist pages worked signed‑out the first time. Share‑to‑Meteora came next: share a link from the YouTube Music app and it plays on the PC.

## First real use (19:56–19:59)

Pairing on the real phone failed: the user didn't see the Allow prompt.

- **Cause on our side:** a connection timeout during pairing crashed the app, and pairing silently restarted. The fix catches the timeout, keeps the screen on while waiting, and gets a fresh code automatically when the 30‑second Allow window runs out.
- **Bug in YTMDesktop, not ours:** while we waited, YTMDesktop crashed with `this.getService is not a function`. If "Enable companion authorization" stays on for five minutes without a pairing, it throws. That's now in api-notes. After a restart, pairing worked first time.

The full player's title came out almost black on a dark background. The full player isn't inside a `Surface`, so nothing set the text colour; providing `LocalContentColor` fixed it.

## Signing in (20:01–20:03, then S2)

Sign‑in uses a WebView with `; wv` removed from its user agent. When it lands on `music.youtube.com` with a `SAPISID` cookie, the cookies are encrypted with a Keystore key, and each later request is signed with `SAPISIDHASH`. The mini player was drawn over Google's sign‑in page, so the sign‑in screen now hides it.

The user signed in on the phone; Meteora never touched the password. Google didn't block it, and Home switched to the personal "Listen again" shelf, with liked songs in Library. S2 ✅. Playing liked songs *on the PC* is not confirmed: the screen was asleep when I tapped Play, and the PC then showed a different track.

## "Can this be a standalone client?" (21:09 →)

The next question was whether Meteora could play on the phone itself. YouTube Music's browse data gives titles and IDs but no audio, so the app has to get stream URLs itself. The user suggested yt-dlp.

### Spike: yt-dlp on the phone

`youtubedl-android` bundles Python and yt-dlp. Getting it running on the phone:

- **Native libs:** it runs `libpython.so` from the app's native library folder, so the libraries have to be extracted at install.
- **Size:** limited to arm64, since it's ~15 MB per CPU type.
- **Version:** yt-dlp updated itself on first run to 2026.10.26, and the first track played and seeked fine.

It was also far too slow, and unreliable on this phone:

| Run | Result |
|---|---|
| cold | 15.2 s |
| warm | 15.4 s, 17.1 s, 18.1 s |
| later | `[Errno 7] No address associated with hostname`, while ping from the same phone worked |

The DNS failures come from Python's own lookups and happen often. My guess is the phone's AdGuard Private DNS, but I haven't confirmed it. A 15‑second wait after tapping a song isn't usable.

### Doing it the way YouTube's own apps do

The user's follow-up: if yt-dlp is too slow, do what the YouTube Music web player does. The web player itself is the hard case: its stream URLs are scrambled by JavaScript, and YouTube increasingly demands "PO tokens". So instead of guessing, I read the client table in the yt-dlp copy installed on the phone. That shows what works *right now*:

- **`android_vr`**, the usual workaround, has been fully blocked since 2026‑08‑17.
- **`visionos`** is yt-dlp's only default client that needs no JavaScript.

One `/player` request as the visionOS client, with a visitor ID from `sw.js_data`, returned plain audio URLs. Without the visitor ID you get "Sign in to confirm you're not a bot". The returned URLs passed every check:

| Check | Result |
|---|---|
| ranged read | 206 |
| seek into the middle | 206 in 0.08 s |
| full file | 6 MB in 0.13 s |

On the phone it takes **113–515 ms** instead of 15 s. yt-dlp stays as the fallback for the day YouTube changes this.

### The engine

- **Queue:** ExoPlayer owns it. Each item is `meteora://<videoId>`, turned into a real URL only when ExoPlayer opens it, so the next track's URL is fetched while the current one plays.
- **Building queues:** InnerTube's `next` endpoint is the same call the web player uses to fill its "Up next", so radio, albums and playlists come back as exactly what YouTube would queue. Radio keeps going: more tracks are fetched near the end.
- **Shuffle:** really reorders the queue, the way YouTube Music does, so the queue sheet shows the true play order.
- **One player UI:** a router chooses **PC** or **This phone**. The existing player screens didn't change; they read the same state type either way.
- **Switching:** it moves the queue and position and pauses the other side. It's in Settings and in the "Playing on …" chip in the player.
- **System integration:** the media session swaps its player when you switch, volume keys go to the phone's own volume in phone mode, and pairing can be skipped with "Use without a PC".

### Checked on the OP7T (21:30–21:40)

- **PC → phone:** the PC was paused on *The Chain* at 0:49. After switching to *This phone* and pressing play, the phone resolved the track in 515 ms and played from 0:49.
- **Next track:** resolved in 113 ms. Playback continued with the screen off for 45 s, with a foreground media notification.
- **Search:** "nothing else matters", tap the top result: the Metallica radio played on the phone in 121 ms, and the full player showed "Playing on this phone".
- **Liked songs on the phone:** played in order from "End of It" (the signed-in `next` call with `LM`). Seeking near the end rolled over to the next track by itself, and a `next` sent through the media session, as the notification does, skipped again.
- **Phone → PC:** pause, then switch back. The media session went back to the PC player with remote volume 0–100.

### A second phone, on mobile data (21:42–21:45)

Testing mobile data on the OP7T would have cut my adb link, so the user lent me a Pixel 9 Pro (Android 37, lifecell SIM):

- **Pairing over wireless adb:** with the pairing code from the Pixel's Wireless debugging screen.
- **Install and setup:** the debug APK went on. Meteora found the PC by itself, but I chose "Use without a PC", so this phone plays on its own.
- **Sign-in:** the user signed in to Google on the Pixel. Again no block, and "Drive" by Deftones was playing within a minute.

Turning Wi‑Fi off kills adb, so a script on the Pixel ran the test itself and wrote a log:

1. Turn Wi‑Fi off; the only network left is LTE.
2. Wait 30 s while "Drive" keeps playing.
3. Press the media *next* key.
4. Wait 15 s, save the log, turn Wi‑Fi back on.

Results:

- **On LTE:** "Drive" kept playing with no errors, and the user heard it carry on.
- **Next track:** loaded over LTE in **889 ms**, against 0.1–0.5 s on Wi‑Fi.
- **Not exercised:** a Wi‑Fi stream link failing on mobile data mid-song. Its link may be tied to the Wi‑Fi IP, but ExoPlayer buffers about 50 s ahead, which likely covered the 30‑second gap. The app's recovery (fetch a fresh link once, then skip) didn't run.
- **Script gap:** my lines that read the playback status came back empty on this Pixel, so the 889 ms log line is the evidence the skip happened.
- **Lesson:** after Wi‑Fi comes back, wireless debugging returns on a new port (39939 → 38693). My watcher kept knocking on the old one; `adb mdns services` shows the new port.

## Review pass: design, animations, streaming (21:50 → 22:25)

The user asked for a review of the design, the animations in particular, then the rest of the app, and partway through added "optimize streaming to perfection", a quality switch, a "not affiliated" notice and a plan for downloads. I read the UI, player, theme, pairing, playback and stream code, plus the stores and the share receiver. Not read line by line: the companion REST and Socket.IO clients and the YouTube Music parser.

### What the review found and fixed

- **Search suggestions covered the results.** Seen on the OP7T before changing anything: type "deft", tap the suggestion "deftones change", and four seconds later the screen shows suggestions for "deftones change" instead of results. Setting the field's text counted as typing. The view model now remembers the submitted query and gives it no suggestions. Re-checked on the phone with "slipk" → "slipknot duality": results stay.
- **Settings couldn't scroll** and ignored the mini player. On the OP7T the old content happened to end above the mini player, so nothing was hidden yet; with the quality block added below, the last rows would have been unreachable. It scrolls now and leaves room at the bottom, which I saw on the phone.
- **Everything recomposed on every PC update.** `MainActivity` and the shell read the whole playback state, which changes 4–15 times a second, only to learn whether a track is loaded. They now read a `hasTrack` flow. I did not measure the difference.
- **Seek bar and artwork recomposed the player every frame.** The live position and the shape-morph progress are now read where they are drawn. The time label updates once a second.
- **Drags snapped.** A short pull-down on the full player, or a cancelled back gesture, jumped back to rest. Both now settle with the theme's spring, and a pull that collapses the player leaves from where the finger let go. Dragging up first no longer eats the drag back down.
- **Swipe distances were in pixels** on the mini player (60 and 120), so they were shorter on denser screens. Now 24 dp and 48 dp, about what they were on the OP7T.
- **Artwork couldn't shrink.** It was always the full width, which on a short or landscape screen pushes the controls off the bottom. It now takes the space the controls leave.
- **A failed page stayed failed** until the app restarted: Home, Library, album, playlist and artist pages had no retry. They have a "Try again" button now.
- **Colour roles that snapped.** The animated colour scheme left out the error, scrim and "fixed" roles. Added.
- **Small ones:** labels for the mini player's play button and the collapse button, a haptic on long-press for cards and track rows, a full player that no longer comes back expanded after the track went away, a host comparison in place of a URL prefix check on the sign-in screen, and dead code removed.

### Streaming

Three changes came from reading the code, two from measuring on the phone.

- **No more racing through the queue offline.** A second playback error on a track skipped it, so with no network the player would skip to the end of the queue. Network errors now retry the same track after 1, 2, 4… seconds; other errors still get one fresh URL and then a skip, and after two skips in a row the player pauses instead of skipping again.
- **A stalled download can fail.** The shared HTTP client never times out a read, because it also holds the PC's websocket. Audio now uses a 12-second read timeout, so a stream that dies on a network change errors out and is retried.
- **Lookups survive a cancelled caller.** One lookup per video was shared, but it ran in the first caller's coroutine; tapping another song cancelled it for everyone waiting. It now runs in the app's scope. yt-dlp is also no longer tried when the phone is simply offline, where it could only fail 15 seconds later.
- **A disk cache** (256 MB, least recently used, in the cache directory) so replays and seeks back cost no network. The key includes the format, so Opus and AAC bytes of one video can't mix.
- **YouTube throttles open-ended requests.** I raised the buffer to 10 minutes expecting each track to download in seconds. On the phone, 94 seconds into a track only 180 seconds of it were buffered: about twice playback speed, and the next track was never fetched ahead. The fix was to ask for an exact byte range, using the file size from the `/player` response. After that the whole 4:43 track was buffered within 8 seconds and the next two tracks were resolved one and two seconds after the start.
- **Start before the queue arrives.** The log showed where the time went on a tap: 114 ms to resolve the stream, 795 ms for YouTube to return the queue, then about 740 ms to start. The tapped song now starts by itself straight away and its queue is put around it when it arrives.

Measured on the OP7T over Wi‑Fi, from the app's own log ("playing N ms after the tap"):

| Case | Before starting ahead of the queue | After |
|---|---|---|
| Tap a song that was not fetched before | 1535 ms | 736 ms and 905 ms (the 905 included creating the player) |
| Tap a song that was already fetched ahead | not measured | 136 ms |
| Skip to the next track | not measured | new track playing at the first check, 156 ms after the tap |

I have no number for the build as it was this morning: I only added the timing log partway through.

### Is the official app's quality higher?

It can be, for Premium accounts, and only when set to High. (Meteora reached that tier later the same evening; see "256 kbps after all".) Google's help page gives the official app's tiers as up to 48, 128 and 256 kbps (Low, Normal, High). Meteora's `/player` call as the signed-out visionOS client returns five audio formats, and the best is itag 251: Opus at about 127 kbps, which YouTube labels medium quality. So Meteora plays the Normal tier. The 256 kbps formats would need a signed-in request from a client that serves Premium formats; I did not attempt that.

The user installed the official app on the OP7T and signed in, so I looked at its settings (and changed nothing):

- **The account has Premium:** the account menu says "YouTube Premium".
- **Streaming quality** offers Low, Normal, High and Always High, and is set to **Normal** on both Wi‑Fi and mobile data. As configured, the official app and Meteora play the same tier.
- **Download quality** is also Normal, with "Download over Wi‑Fi only" and a smart downloads option ("Recently played songs").
- **Not measured:** the bitrate the official app actually decodes. Android's codec statistics showed Opus and AAC decoders at 48 kHz but no bitrate, and I could not tie the entries to the app.

### Quality switch

The user asked for the switch to be implemented by Opus from a detailed plan. The plan is [quality-switch-plan.md](quality-switch-plan.md); an Opus agent implemented it and I reviewed the diff.

- **Tiers:** High (the best format returned), Balanced (about 65 kbps) and Data saver (about 50 kbps), chosen separately for Wi‑Fi and for mobile data, in Settings.
- **Picking:** by bitrate under a ceiling per tier, with Opus weighted up because it sounds better per bit. Loudness-compressed and dubbed variants are skipped when there is an alternative.
- **A track keeps its format** from its first open, because ExoPlayer reopens a stream on seek and a different format would mix two files' bytes. A change applies from the next track.
- **Checked on the phone:** with Wi‑Fi set to Data saver, the next four lookups logged itag 249 at 48–54 kbps. Not checked: the mobile-data setting (needs a metered network) and the yt-dlp format selectors.

### Also added

- **"Not affiliated" notice** on the pairing screens, the sign-in screen and Settings: Meteora is an independent app, not affiliated with YTMDesktop, YouTube or Google. Seen in Settings; the other two screens were not opened.
- **[CLAUDE.md](../CLAUDE.md)** with the build commands, the architecture, and the working rule the user set: a detailed plan first, then implementation by Opus.
- **[downloads-plan.md](downloads-plan.md):** a plan, not code, for a download button, smart downloads and a download quality setting, Premium accounts only, stored in internal storage. Its first step is a spike, because nothing yet tells the app whether an account has Premium.

### Found, not fixed

- **Tabs forget their state.** Switching tabs clears the back stack, so Search loses its query and results. Seen on the phone.
- **Queue sheet:** a row swiped away stays swiped if the removal fails, and the list isn't re-synced after a drag if the PC's queue changed meanwhile.
- **Accessibility:** the seek bar has no seek action for TalkBack. A UI dump taken with the full player open listed the screen underneath and none of the player's buttons. That may be the dump tool rather than the app: the same dump still showed the previous track's title, so it was stale. Not checked with TalkBack itself.
- **Plan items not built:** the colour source setting (the theme supports three sources, nothing sets one), the reduced-motion fallback, title weight animation, carousels, the collapsing top bar.
- **Theme re-tint** rebuilds the colour scheme every frame while it animates, so everything that reads a colour recomposes for its duration. Not measured.
- **Small:** `LocalEngine`'s like map is written off the main thread; a PC found by the subnet sweep is named by its IP address; the shell writes the bottom bar height during composition.

### Not verified in this pass

- Gestures, driven with `adb shell input swipe` on the final build: a swipe up on the mini player opened the full player, a 250‑pixel pull-down left it open and back at rest, and a 1000‑pixel pull-down collapsed it. I saw the end states in screenshots, not the motion in between. The cancelled back gesture and the sideways skip swipe were not tried.
- Switching PC → This phone from the chip inside the full player. The player now waits 1.5 s before collapsing when the track goes away, because a hand-off has no track for a moment; not tried.
- The artwork on a short or landscape screen.
- The "Try again" button and the offline retry logic, which need the network off.
- The PC target after these changes. The PC was paused throughout, and the OP7T is back on "PC".
- Unit tests: 40 pass, including four new ones for format picking.

## 256 kbps after all (22:35 → 23:15)

I had written that the 256 kbps tier "would need a signed-in request from a client that serves Premium formats" and left it. The user pushed back: the web player has High, YTMDesktop plays it, so it can be done. They were right.

### Finding the formats

- **A probe in the app.** The session cookies never leave the phone, so the test had to run there: a debug-only broadcast that asks `/player` as several clients with the user's sign-in and logs only itags, bitrates and whether each URL is plain. As the web music client the answer included itag 141 (AAC, 257 kbps) and 774 (Opus, 258 kbps), both marked high quality, every URL behind a signature. The plain web client listed no high formats, the TV client was unplayable, and the iOS and visionOS clients reject cookies with HTTP 400.
- **No PO token for Premium.** yt-dlp's client table on the phone marks the web music client's token as not required for Premium accounts. That left the JavaScript step: decipher the signature and transform the `n` parameter.
- **The solver was already on the phone.** yt-dlp ships its challenge solver as two scripts. Under Node, against that day's player script, they solved both challenges: 311 ms the first time, 68 ms after.

### Building it

The plan is [high-quality-plan.md](high-quality-plan.md); an Opus agent implemented it. The solver runs in a hidden WebView, the one JavaScript engine the app already has. Tiers were renamed to match the official app: Low (about 50 kbps), Normal (about 130), High (about 256 with Premium and a sign-in, otherwise the same as Normal). The web path is used only for High and falls back to the anonymous path on any failure.

### What broke on the phone

The probe passed first time: itag 774, HTTP 206, 1 MB in 290 ms. Real playback did not. Every track fell back to 130 kbps, with two errors alternating in the log.

- **yt-dlp's file is not a plain zip.** It is a zip with a `#!` line in front, and Java's `ZipFile` refuses it. The solver now finds the first zip entry and reads forward from there.
- **The page forgot everything after each solve.** The agent's wrapper loaded the solver once and kept the preprocessed player inside the page. After the first solve `jsc` was undefined. My guess, not confirmed: the player's code runs with a real `window` in a WebView, unlike in Node, and replaces the document. I rewrote the wrapper so nothing lives in the page between calls: each call carries the solver, the input and the answer, and the preprocessed player is kept in Kotlin.

After that, on the OP7T over Wi‑Fi: every track resolved to itag 774 at 238–262 kbps. A solve takes 450–980 ms, and a tap to sound on High takes 1.3–1.7 s, against 0.7–0.9 s on Normal.

### What it costs

`dumpsys meminfo` while playing on High: the app at 246 MB (76 MB of it Java heap), plus a WebView renderer process of 152 MB. Earlier in the evening, idle in the background and without the solver, the app was at 60 MB. Those two are not the same situation, so the difference is not all the solver's, but each solve builds a script of several megabytes and the renderer is there only for this.

### Rust

The user asked for the backend in Rust. I said it would not make playback faster, since the time is network wait, and that it would lower memory in two places: this solver, and the PC's state updates. They chose to do it anyway. The plan is [rust-core-plan.md](rust-core-plan.md): Rust does no networking, and the first slice moves format picking and the solver (on QuickJS) into a library built into the APK. The numbers above are the baseline it has to beat.

RustyPipe, a Rust client for YouTube Music, looked like a shortcut. It is not: its last code change is from June 2025, it has no Home feed and no like button. Notes in [api-notes.md](api-notes.md).

### Also

- **Commits.** Nothing had been committed all evening. The user asked for a commit per step; the four steps so far had touched the same files, so they went in as one commit, and it is one per step from here.
- **APKs by Telegram.** A script on the VPS sends a file through the user's admin bot. The first build went out at 23:15.
- **README.** A short placeholder until the user writes the real one.

## Likes weren't synced (23:35 → 23:55)

The user: "I think likes aren't synced." In phone mode the like button always started as "not liked", whatever YouTube knew. It was on the gaps list from the afternoon; pressing it did reach YouTube, but nothing was ever read back.

- **Where the status is.** A second debug probe asked for a signed-in queue (`next`) response and logged only counts and key paths. No queue item carries a `likeStatus`. The player overlay does, for the current track only. What every item does have is a liked-songs toggle in its menu, and its default icon is the action on offer: `UNFAVORITE` ("remove from liked songs") means the track is liked. In the Duality radio, 23 of 78 items were in that state.
- **The fix.** The queue parser now returns a like status per track from those toggles, with the overlay's answer on top for the current track, since it is the only place a dislike shows. The player takes those when a queue loads, when a radio extends and on a hand-off from the PC. A track rated in Meteora keeps Meteora's value until the app restarts, so an older answer from YouTube can't undo it.
- **Checked on the OP7T.** Duality, liked on YouTube, opens with the like button filled. Un-liking it in Meteora and asking YouTube again gave `INDIFFERENT`; liking it again gave `LIKE`.
- **Still missing:** a dislike on a track that is not the first of its queue is not known until it is rated here. Lists (search, album pages) show no like state at all.

Seen along the way: after tapping a search result the search field keeps focus, so the keyboard comes back over the mini player when the app returns to the front. Not fixed.

## Radio, autoplay, share, artist actions (P1 of docs/parity-plan.md)

- **Radio for anything.** The long-press sheet now has a list per type (song; album or playlist; artist) and Start radio on all of them. A collection's radio is `RDAMPL<playlistId>`, an artist's is the one its page already offers. The collection id was checked with signed-out `next` calls from the PC for an album, a `PL…` playlist and an `RDCLAK…` mix (about 50 tracks and a continuation each), not through YTMDesktop.
- **Autoplay on the phone.** An album's `next` response ends with an `automixPreviewVideoRenderer` pointing at `RDAMPL<id>`, saved as a fixture. When a queue with no continuation gets within three tracks of its end, the phone fetches that once and carries on with its radio. Off under Settings → Autoplay, and repeat-all wins.
- **Share** from the sheet, the album, playlist and artist pages and Now Playing, as music.youtube.com links. Meteora's own "Play on PC" entry is left out of the chooser. Sharing the audio file itself was asked for and left out (see the plan).
- **Now Playing** has an overflow menu (Start radio, Share, Go to album, Go to artist), and the artist and album in the subtitle open their pages.
- **Checked:** build and unit tests only. Nothing here was tried on a phone yet.

## Three agents at once (23:20 → 00:00)

The user kept asking for things faster than one line of work could deliver them, so three Opus agents ran side by side, each from its own plan: the Rust core in the main checkout, and the parity slice and theming each in a separate git worktree so their half-finished edits couldn't break each other's builds. I merged them in turn and checked each on the OP7T.

### Rust core, first slice

Plan: [rust-core-plan.md](rust-core-plan.md). Format picking, URL handling and the signature solver now live in `rust/meteora-core`, built for arm64 by a Gradle task. Rust does no networking.

- **Getting QuickJS to build for Android** took five tries before the agent started. The `rquickjs` crate ships no Android bindings; generating them failed on a missing `stdbool.h`, then on "Unversioned target triples are not supported". `cargo-ndk` was dropping the extra compiler arguments, so the recipe uses plain `cargo` with the NDK paths set by hand. It is written into the plan.
- **First result on the phone: no faster.** A solve took about 450 ms, the same as the WebView. The solver's entry point re-evaluates the whole preprocessed player, about 4 MB of JavaScript, on every call. I changed the glue to evaluate it once and keep the two functions it defines, which is what the solver does internally anyway.
- **After that**, on the OP7T on High quality:

  | | WebView solver | Rust solver |
  |---|---|---|
  | One solve | 450–980 ms | 5–9 ms |
  | Stream lookup | 600–1200 ms | 127–154 ms |
  | Tap to sound | 1.3–1.7 s | about 1.0 s |
  | App memory while playing (PSS) | 246 MB | 232 MB |
  | WebView renderer process | 152 MB | none |

- **The cost:** preparing a new player script takes 6.3 s on the phone, once per app start, in the background. A High track tapped inside that window waits for it. Keeping the prepared script on disk would remove that; not done.
- The library is 1.8 MB. The plan had guessed 0.5 MB from a one-function test.

### Closer to the official client

Plan: [parity-plan.md](parity-plan.md). The first slice added Start radio for albums, playlists and artists, a long-press menu for every kind of item, share links, autoplay after an album or playlist, and an overflow menu and tappable artist in Now Playing.

- The agent checked the two things the plan had marked unverified, with anonymous requests: `RDAMPL<playlistId>` is the radio of a collection, and a collection's queue response names what plays after it, one level deeper than I had written.
- **Checked on the phone:** the album menu lists Play, Shuffle, Play next, Add to queue, Start radio, Share; Start radio on an album loaded a 62‑track queue and played; Share opened the system sheet with the link and "title – artist", without Meteora's own "Play on PC" target.
- **Not checked:** autoplay at the end of an album, the Settings switch for it, radio through YTMDesktop on the PC, and the tappable artist line.
- **Left out on purpose:** the user also asked to share a track as a file into Telegram. That sends out a copy of the recording, so only links are shared.

### Themes, and less of the cover cut off

Plan: [theming-plan.md](theming-plan.md). The user forwarded a friend's request from a group chat (N7Art): black background, `#eeeeee` and `#aaaaaa` text, white accents, "vim style", with the moonfly scheme as the reference. And the user's own complaint: the cookie shape "cuts way too much of the album cover".

- **Themes:** Album art (still the default), System colours and Meteora are built from a seed as before. Eight are fixed palettes: Mono (N7Art's, credited in Settings), Moonfly on black, Catppuccin Mocha and Latte, Gruvbox, Nord, Rosé Pine and Tokyo Night. The agent took every palette from the scheme's own repository, not from memory, and the source is noted next to each.
- **Options:** light / dark / system for the seed-based themes, a pure black background, and status bar icons that follow the app's theme.
- **Artwork:** the default is now a rounded square showing the whole cover; play and pause show as size (full while playing, 88 % paused). The old cookie is still there as "Scalloped".
- **Checked on the phone:** the Appearance section, Mono applied (black, white accents, mode selector greyed out), and the rounded cover in Now Playing. Not checked: the other fixed themes one by one, light mode, and the status bar icons on a light phone.

### Two small ones from the user's own testing

- **"Sometimes you think it's an album and the song starts playing."** Cards and search rows now say Song or Video in front, as the official client does; albums, singles and playlists already named their kind.
- **"Notice how the list jumps"**, with a screen recording. Frames from it showed Home's rows shifting up and down while a carousel scrolled sideways: a card with a two-line title is taller, and a carousel is as tall as its tallest visible card. Cards now always reserve two title lines. Four swipes on the phone left the headers below at the same position.

Unit tests after the merges: 62 in Kotlin, 31 in Rust.

### Looks: themes that change more than colour

Plan: [looks-plan.md](looks-plan.md). The user: "The themes must change the look dramatically, not only the color." Every theme now names a look, and Settings has a Style row to combine any colours with any look:

- **Expressive** (album art, System, Meteora): Material 3 Expressive as before.
- **Terminal** (Mono, Moonfly): JetBrains Mono everywhere, bundled with its OFL licence (the font has Cyrillic); square corners; 1 dp outlines instead of fills; controls written as `[ next ]`; a `████░░░░` seek bar; `# section` headers; a `|/-\` spinner; 48 dp rows.
- **Flat** (Nord, Tokyo Night): light, tight type; 8 dp corners; no gradients; a 2 dp seek line; icon-only tabs; the cover dims on pause.
- **Retro** (Gruvbox): serif titles and capital labels; 2 dp corners with a 2 dp outline and a hard offset shadow that buttons sink into when pressed; a thick outlined seek bar; bouncing squares while loading.
- **Soft** (Catppuccin, Rosé Pine): 32 dp corners, fat pills, a thicker wave, rows on rounded tonal cards, roomier padding and a taller tab bar.

Screens call wrappers in `ui/components` and the wrappers branch on the look. Two more requests came in while this was being built:

- **Settings** is now a short list (Appearance, Playback, YouTube Music account, Paired PC, About), each opening its own sub-screen.
- **A bottom dock.** The user disliked the floating mini player and the nav bar. The mini player is now an opaque full-width row on top of the tabs, with a 2 dp progress line, and the selected tab is marked by colour and a small line instead of a large pill. Terminal's dock is two text status lines.

Carousel cards keep their fixed height but put the subtitle right under the title.

- **Not seen on a phone.** Nothing in this section was installed or looked at; it was only built and unit-tested. The likely trouble spots are the Terminal transport row in English (`[ previous ]` and the rest may not fit a 360 dp screen), the mini-to-full transition in each look, the dock's progress line near the rounded corners, and Retro's shadow colour on Gruvbox.
- Unit tests: 75 in Kotlin. Two of them are from other work committed meanwhile.
- **Then checked on the OP7T:** every look on Settings → Appearance, Home and Now Playing, with a track playing from Home. They are clearly different from each other. Terminal's transport row fits on the 1080 px screen, its dock shows the two status lines with the live position, and Ukrainian renders in the mono font. Not checked: the mini-to-full animation frame by frame, light mode, the fixed palettes one by one.

### Two queue fixes from the user

- **"Some songs say Video."** Home cards were all marked as videos in the parser. They now read YouTube's video type; on the phone, a real live video says Video and the songs next to it say Song.
- **"It includes the audio, then its video clip."** Signed in, YouTube sends a song together with its music video as a counterpart (the official app's Song/Video switch), and the parser queued both. Counterparts are skipped now. Tested with a JVM test shaped like the signed-in response; the saved fixtures are signed out and have none. Not yet seen in a radio queue on the phone.

### Known gaps

- **Solver:** preparing a new player script takes about 6 s after each app start.
- **High without Premium:** a signed-in account without Premium was not tried. It may pay one failed open per track before falling back.
- **Web path after a failure:** a video that fails once on the web path stays on the anonymous path until the app restarts, and a "confirm you're not a bot" answer turns the web path off for the session.
- **Untested:** handing off to the PC *while the phone is playing*, which would have started music on the PC. Review found that switching back on the same track would have done nothing (`changeVideo` ignores the loaded track), so it now sends play.
- **Release build:** not built yet.
- **Signed-out streams:** audio URLs are fetched without your cookies, so age-restricted or private tracks may not play.

- **Share sheet:** PC only.
- **Debug APK:** 104 MB, mostly the unshrunk debug build with the full icon set; yt-dlp adds ~18 MB. A release build will be much smaller.
- **License:** yt-dlp comes through `youtubedl-android`, which is GPL‑3.0. That matches the plan's GPL‑3.0 choice.
- **Terms:** phone playback is against YouTube's terms, so Meteora stays sideload‑only.

## Watching the music video (2026-11-22, docs/video-plan.md)

The user asked for the official app's Song / Video switch. One agent built it in its own worktree while another restyled the UI, so the new UI is in new files (`ui/player/VideoPanel.kt`, `FullscreenVideo.kt`, `VideoViewModel.kt`, `ui/settings/VideoQualitySettings.kt`) and `PlayerHost.kt` only gained three calls.

### What YouTube gives (checked from the PC, anonymous)

- **Same client as audio:** the visionOS `/player` call lists video-only formats in H.264, VP9 and AV1, up to 1080p or 2160p, all with plain URLs. No signature, no `n`, no PO token.
- **A new throttling limit:** an exact range is fast only up to 10 MiB. 12 MB, 20 MB or a whole 42 MB file came at about 2.4 Mbit/s, as slow as an open-ended request; a 10 MiB range from the middle of the file was fast. So reads now go in 10 MiB requests (`ChunkedDataSource`). Audio files are almost always smaller and pass through unchanged.
- **Counterparts:** signed out, `next` has none, so the `segmentMap` field names were taken from other clients and are not verified.

### How it works

- **Sources:** in video mode a song with a picture is its usual audio source merged with a video source. The sound does not change. An audio track (ATV) borrows its counterpart's picture only when the segment map gives one constant shift; otherwise it plays audio-only.
- **Mode:** the switch is for the session. Switching restarts the current item at its position and rebuilds the next two.
- **Failures:** a picture that cannot be resolved falls back to audio at the same position; the track is never skipped for it.
- **Data:** the video track is on only while a view shows it. The view is attached while the activity is started, so in the background, with the screen off or with Now Playing closed only audio loads. Each video-mode track still opens its video file once to read the header.
- **Quality:** 720p on Wi‑Fi, 360p on mobile data, in the phone's hardware codec order. The settings section exists but is not on the Playback screen yet (that file belonged to the looks work).
- **Full screen:** landscape, system bars hidden, with play/pause, a seek bar and a way back.

### Checked

Build, 89 Kotlin unit tests and 37 Rust tests, all passing (`pick_video`, the queue parser keeping counterparts, the picture-source rules, the chunk boundaries). **Nothing was run on a phone:** whether the picture plays, keeps in sync, survives a mode switch, full screen and rotation, and that no video loads with the screen off are all unchecked.
### Video, checked on the OP7T (00:55)

Merged into `main` after the looks work; the quality setting went onto Settings → Playback and the Song | Video switch now uses the look-aware `SegmentedChoice`.

- **Seen working:** a user-upload video from Home ("Corey Taylor - Lovesong"). The switch appears; Video put a moving picture in the artwork slot within a few seconds, at the same position (1:30 → 1:43 on screen). Logcat: `itag 247, vp9 720p`, looked up in about 100 ms. The full-screen button shows on tap; full screen is landscape with seek and play/pause, and Back returns to portrait. Screen off logged "video track off" while the session stayed PLAYING; waking logged "video track on".
- **Not checked:** an audio track that borrows its counterpart's video (the `segmentMap` names are still unverified), sync by ear, the mobile-data ceiling, seeking in a long track with the 10 MiB chunked fetching, other looks, and the picture in the letterboxed square looks small: a 16:9 slot in video mode would use the space better.

### Video controls, quality picker and stats (01:15)

The user: full screen was "missing more controls", then "Quality, etc. also 'stats for nerds' would be nice."

- **Full screen** gained the title and artist, like, previous / next, 10‑second jumps as buttons and as a double tap on either half.
- **A gear on the picture** (inline and full screen): 1080p / 720p / 480p / 360p and a Stats for nerds switch. Picking a quality applies to the playing track at once and is stored as the setting for the network in use (Wi‑Fi or mobile data). Video items carry a revision number in their URI so ExoPlayer reloads them; the sound's source is untouched.
- **Stats** are read from the player once a second: ids, decoded resolution and the limit, codec, itag, average bitrate from the file size, dropped frames, audio format and where it came from, buffer length. The labels are English only, as technical field names.
- **Checked on the OP7T:** the full-screen layout; the gear menu inline; stats showing `1280x720, vp9, itag 247` and, after picking 360p, `640x360, itag 243` within a few seconds, with the log line "video quality Low". Not checked: each full-screen button, the double tap, the gear menu in full screen.
## Lyrics, synced where available (2026-11-22)

The user asked for synced lyrics. Built from `docs/lyrics-plan.md` on a branch, without a phone.

### What YouTube gives (checked from the PC, anonymous)

- **Timed lyrics are there signed out:** the `MPLY…` id from the lyrics tab in `next`, then `browse` as `ANDROID_MUSIC` 7.27.52, returns `timedLyricsModel` with a start and end time per line and "Source: Musixmatch". `IOS_MUSIC` gives the same; an old Android version (5.01) gets HTTP 400. The web client only ever gives plain text.
- **Unsynced and none:** an unsynced track comes in the same model without times (`staticLayout: true`); a track with no lyrics gets a "not available" message. Instrumental gaps are their own `♪` lines.
- Details, the test tracks and the trimmed fixtures are in `docs/api-notes.md` › Lyrics.

### How it works

- **Fetching:** only when the lyrics page opens or its track changes: one `next` for the lyrics id, one signed-out Android `browse`, and the web client's plain text if that call fails. The Android client never gets the account's cookies.
- **Cache:** the answers for the last 30 tracks, "none" included, per source. Failures are not kept, so an offline open tries again.
- **Page:** a Lyrics button next to the queue opens it over Now Playing. Timed lines follow the interpolated position (PC or phone): the current line sits in the upper third, past lines are dimmed, a tap seeks. A hand scroll pauses the follow for 4 s. Unsynced lyrics are static with a note. The source line sits at the bottom. Terminal marks the current line with `>`.
- **LRCLIB:** Settings → Playback → "Find synced lyrics on LRCLIB", off by default. When it is on and YouTube has no timed lyrics, the title, artist, album and rounded length go to `lrclib.net/api/get` from a TLS-only client with the User-Agent `Meteora/<version>`. A result counts only if it is timed and its length is within 2 s of the track's, because LRCLIB answered a request 10 s off. Turning the switch on asks LRCLIB for tracks YouTube already answered.

### Checked

Build, and 123 Kotlin unit tests passing (34 new: the parser on the four fixtures, `currentLine`, LRC parsing, the LRCLIB duration rule and request, the repository order and cache, and the lyrics browse going out without the account's headers and falling back to the web client). With `hl=uk` YouTube gives the same lyrics and translates only the source line. LRCLIB has timed lyrics for `dQw4w9WgXcQ` (unsynced on YouTube), so that track shows the switch working. **Nothing was run on a phone:** following the music and seeking on tap on both targets, the auto-follow pause, back closing only the lyrics page, the Terminal top row with the extra text button (also in Ukrainian), Ukrainian lyrics, and the LRCLIB switch are all unchecked, and so is whether a signed-in `next` still carries the lyrics tab.

- **Lyrics, then checked on the OP7T (01:30):** "Blinding Lights" from search, the Lyrics button in Now Playing: the list opened on the current line, and ten seconds later the highlight had moved down three lines with the list scrolled to keep it in place. A tap on a lower line moved the playback position forward. Not checked: the PC target, unsynced and missing lyrics, Ukrainian lyrics, the Terminal look, the LRCLIB switch.

## Two reviewers on the UI (01:20 → 01:50)

The user asked for `/code-review --fix` "with a pair of Opus subagents to improve UI/UX too". Each took half of `ui/` in its own worktree: one the player, queue, video views and dock, the other browsing, Settings, pairing and the look wrappers. Neither had a phone.

- **Bugs fixed, the ones a user would meet:** switching tabs cleared the back stack, so Home reloaded and an open album was lost (now one saved stack per tab); full-screen video controls faded mid-seek and the seek was lost; Video mode showed a grey box until the first frame; Home's quick picks cut their fourth row in Terminal; segmented labels were cut mid-letter in Ukrainian; a duplicated song on an artist page or two PCs with one name crashed a list; the Flat and Retro seek bars could throw on a zero width; a swiped queue row could stay as an empty red band.
- **Accessibility:** the seek bar had no semantics at all and now steps and announces; queue rows have Move up / Move down; the video's controls can be reached with TalkBack.
- **UX:** in Video mode the artwork slot takes the picture's shape instead of letterboxing it in a square; a buffering loader; empty and failed states with Try again on Home, Library and Search; Library uses the look's own toggle; Appearance wraps the Style chips, fades the theme row's edge and says why a row is disabled; the search field gives up focus; Soft moved further from Expressive (rounder, taller rows).
- **Mine, on merging:** `configChanges` on the activity, so turning to landscape for full-screen video no longer recreates it.
- **Left open:** Retro's tab labels do not fit a 360 dp screen in Ukrainian (needs a shorter label or no icon); Terminal's queue shows no drag feedback; artist and album links in Now Playing are still under 48 dp tall.
- **Checked on the OP7T:** the video slot in Song and Video mode with the gear and full-screen buttons on a scrim; Home keeps its scroll position across a tab switch; Library's new header and toggle; Appearance with wrapped Style chips. Everything else in the two reports is unchecked on a phone, including all TalkBack work, rotation, the other four looks and the search keyboard.
## Downloads and smart downloads (2026-11-22, docs/downloads-plan.md)

Built by one agent in its own worktree, in the plan's order, one commit per step. **Nothing was run on a phone**: no adb, no install. Everything below that is not a unit test or a PC check is unverified.

- **D0, Premium.** `ytm/PremiumCheck` fetches `music.youtube.com` with the session cookies (its own OkHttp client, redirects off) and reads `LOGGED_IN` and `IS_SUBSCRIBER` from the page config, then asks `account/account_menu` and looks for the "Get Music Premium" entry by `browseId SPunlimited` / `UNLIMITED` icon. Premium only when the page says both and the menu offers no upgrade; anything unknown is "not Premium". Seen signed out from the PC only (`IS_SUBSCRIBER:false`, the upgrade entry present); that `IS_SUBSCRIBER` turns true for Premium is a guess until the probe runs on the user's account: `adb shell am broadcast -a dev.thathunky.meteora.PROBE_PREMIUM -p dev.thathunky.meteora`, then `adb logcat -s PremiumCheck`. Details in api-notes, "Premium".
- **D1, a tagged file.** Rust `pick_audio_in` picks within a container (AAC for downloads: 141 at High through the signed-in web client, else 140). Rust `mp4tags` adds or merges `moov/udta/meta/ilst` (title, artist, album, album artist, track, year, cover). A real itag 140 file fetched anonymously on the PC (`dQw4w9WgXcQ`) showed the layout the plan expected: `ftyp`, `moov` (empty `stco`, `mvex`, and a `udta/meta/ilst` with `©too Google` already in it), `sidx` v0 with first offset 0, then 22 `moof`/`mdat` pairs whose `tfhd` uses default-base-is-moof; no `mfra`. So `moov` grows and nothing after it moves. Tagged on the PC with a cover: `ffprobe` read all six text and number tags and the cover as an attached picture, the duration stayed 213.09 s, `ffmpeg -i … -f null -` decoded it without errors, and every byte after `moov` matched the original. Itag 141's layout was not seen (it needs Premium); the code refuses any file with `stco` entries, an explicit `tfhd` base offset or an `mfra`, and then writes the file untagged. Kotlin: `StreamResolver.resolveForDownload` (no cache, no yt-dlp), `downloads/Downloader` (10 MiB ranges into `filesDir`, one re-resolve, MediaStore pending until complete), `DownloadIndex` over SQLite, the gate, and `DownloadManager` with a WorkManager run (2.12.0; two at a time; `dataSync` foreground; unmetered when Wi‑Fi only).
- **D2, play from the file.** `LocalEngine`'s resolving lambda asks the index first and returns a `content://` spec, which a small `SchemeDataSource` sends to `ContentDataSource` instead of the HTTP cache. File or stream is pinned per track like the format. A file deleted in a file manager is forgotten and the track streams. Offline, a downloaded song or collection is queued from the index.
- **D3/D4, UI.** New files under `ui/downloads/`: the button (five states), the Downloads tab and page, the offline note on Home, the Premium dialog, the settings section. Existing screens got one-line hooks.
- **D5, smart downloads** from the revised plan: a listens table written when the phone leaves a track, the scores, the three shares, manual downloads outside the size, the stability rules, nightly and "Refresh now" work. One addition to the rules: the 20 % replacement cap is at least one song, or a set of four or fewer could never change.

### Checked

`cargo test`: 46 passing (new: 3 for the AAC preference, 6 for `mp4tags`, one of them on the real file when `METEORA_M4A` is set). Gradle build, and 163 Kotlin unit tests passing (new: Premium parser, paths, gate, ranges, reference counting and renumbering, index round trip, listen counting, scores, shares, manual downloads outside the limit, fresh-share artist rule, stability). **Not checked at all:** the Premium markers on a Premium account, any download on the phone, MediaStore writing, the notification, Wi‑Fi only, playback from a file, airplane mode, the UI, the nightly refresh, the "Listen again" and mix shelf titles signed in.

### Downloads on the OP7T (02:10)

Brought onto `main` by cherry-pick, not merge: the history had just been rewritten to the no-reply address for the public repo, and the agent's branch started from the old one. Two conflicts, both with the UI review's changes (Library's toggle row gained a third choice, Downloads; the shell's per-tab back stacks gained the Downloads route).

- **Premium probe** with the user's account: `loggedIn=true subscriber=true upgradeOffered=false → premium=true`. So the markers hold for a Premium account; a non-Premium one is still unseen.
- **One song** from Home's long-press sheet: `itag 141, 8394021 bytes, tagged`, at `Music/Meteora/Radiohead/Singles/If You Say the Word.m4a`. Android's media scanner reads title, artist and album from it, so the tags written in Rust work on a real 256 kbps file and the JNI byte-array call runs on a device.
- **Playing it** started the AAC decoder (streams at High are Opus), 462 ms after the tap: it is read from the file. The engine still warms a network lookup for it first, which is wasted.
- **Wrong:** the album folder is "Singles" though the track is on an album: a Home card carries no album, so the file was named without one. A download should look the album up before naming the file.
- **Not checked:** airplane mode (it would drop wireless adb), albums and playlists, removal, Wi‑Fi only, the Downloads tab, smart downloads and the listening log, another player's view of the cover.
- **"Singles" fixed (02:20).** Before downloading, a song without an album now asks the watch queue for it and reads the album page for the track number and year. The first try put the file under an artist called "6.4M plays": on an album page the track rows' second column is the play count, and the album artist was being taken from there, for whole-album downloads too. It now comes from the page header. On the phone the same song landed at `Music/Meteora/Radiohead/If You Say the Word/01 If You Say the Word.m4a` with album artist, track 1 and year 2021 in its tags; removing a download deleted the file.

## Content filter: hiding music by language, country or artist (02:00 →)

The user asked whether music could be hidden by language or country of origin, and then that it be "fully customizable". Built from `docs/filter-plan.md` (its later sections override the first bullets) in a worktree, while downloads were being built on another branch, so existing files got only single-line changes: added lines, plus three modified ones (the continuation fetch in `LocalEngine.maybeExtendRadio`, the action list in the long-press sheet, and the `SettingsCategory` enum line).

### What was built

- **Rules, all off by default:** artists (Hide / Always show, by YouTube id or by name), the user's own words, countries (any ISO country), 20 languages (Show / Hide / Always show) and 10 scripts (Show / Hide). Precedence: artist, word, country, language, script; inside one level Always show beats Hide; a rule left at Show has no opinion. "Hide Cyrillic that is not clearly Ukrainian" is Cyrillic = Hide plus Ukrainian = Always show.
- **Language guess:** from the letters of the title and the artists' names only (not subtitles, which carry YouTube's Cyrillic labels under `hl=uk`). Each language is a rule object: its script, its telling letters, and the letters that rule it out. The plan's letters overlap (Bulgarian writes ъ, Belarusian and Kazakh use ы э ё і), so the exclusions matter; text two rules of one script claim is left to the script rules.
- **Where:** Home shelves, the "Fans might also like" row of artist pages, and the phone's radio and autoplay queues (the tapped song always stays, and a page the filter empties is followed by up to three more). Each has a switch, and so do smart downloads, which do not exist on this branch: they should call `ContentFilter.allows(song, Place.SmartDownloads)`. Search, the library, albums, playlists and anything opened or tapped are never filtered. A shelf the filter thins below three items is dropped. The PC builds its own radio, so radio on the PC is not filtered.
- **Countries:** looked up on MusicBrainz only while a country rule exists and its switch is on; one request a second at most, anonymous, TLS only, cached on the phone for 90 days. A match needs a score of 95 and an equal name or alias; equal names with different countries are unknown, and an unknown artist is shown. An artist is shown until its answer arrives; the answer applies from the next load.
- **Settings → Filters:** every list editable, a Test field that says what the filter does with a title and artist and which rule decided, and Hide artist in the long-press sheet (songs, albums with a linked artist, artists) and Now Playing's menu, with a toast saying where to undo it.

### Checked

Build, and 187 Kotlin unit tests passing (64 new: the language guess on real-looking Ukrainian, Russian, Belarusian, Bulgarian, Serbian, Kazakh, Latin and other titles; each precedence pair both ways; shelves and queues; the settings codec; album artists from the search fixture; MusicBrainz matching on three real answers, the request it sends, the 503, and the cache). MusicBrainz was asked from the PC with curl to learn the answer's shape (`docs/api-notes.md` › MusicBrainz). **Nothing was run on a phone:** the Filters screen in any look or in Ukrainian, Home and artist pages with rules set, radio and autoplay filtering, Hide artist and its toast and dialog, and lookups from the phone are all unchecked.

### Known limits

A Russian artist with an English title passes; Cyrillic with no telling letter is undecided; Finnish, Hungarian without ő ű, and metal umlauts ("Motörhead") read as German; Italian è reads as French; Portuguese with only á é í ó ú reads as Spanish; Japanese titles in kanji only read as Chinese. The Test field shows each of these.

### The filter on the OP7T (02:30)

Cherry-picked onto `main` like downloads (the agent's branch started from the old history). Conflicts were all "both added a line here": the engine's constructor, the item actions, the strings. Then the piece the agent could not do on its branch: smart downloads pass every source (listening log, likes, "Listen again", the mix) through `ContentFilter.allows(…, Place.SmartDownloads)`.

- **Checked:** Settings shows Filters with "Nothing hidden"; the screen has the Test field, the four place switches, artists, words, countries with the MusicBrainz switch, and the Languages and Scripts sections. Long-press on a Radiohead song → Hide artist: the card left Home at once and Settings said "1 rule"; Remove in Filters brought it back and the row read "Nothing hidden" again.
- **Not checked:** every language and script rule, the Test field (adb cannot type Cyrillic), country rules and the MusicBrainz requests, radio and autoplay filtering, artist pages, smart downloads with a rule set, Ukrainian, the other looks.

## Second pair of reviewers: downloads and lyrics (02:35 → 02:45)

`/code-review --fix` again with two Opus reviewers, on the code written that night: one on the downloads engine (files, queue, index, Premium gate, playing from the file, the listening log, the Rust tag writer), one on lyrics and the downloads screens.

- **Engine, the ones that would have hurt:** a download cancelled right after its file was written left an orphan file and a second copy later; a process death mid-write left a hidden pending file; a resumed download matched its part by itag alone and could join two different files; the tag writer could overflow on a hostile box size and recurse without limit (a stack overflow kills the app, it cannot be caught); a failed network check erased the remembered Premium answer; the listening log counted the engine's own skips, and Previous, as the user's skips, and a skip refreshed "last played"; a smart refresh with one failed lookup still replaced a fifth of the set; the Premium probe logged the account's channel id.
- **Lyrics and screens:** a fallback plain-text answer was cached as final (and could go to LRCLIB); the current line's heavier weight made the list jump; a seek to the start left the list where it was; the LRC parser ignored `[offset:]`; the Downloads list deleted on one tap and a running download could not be stopped; every progress tick redrew the whole tab; the offline note blocked pull-to-refresh. The lyrics header gained play/pause and a "back to the current line" button.
- **Left open by the reviewers:** a queued playlist keeps downloading after sign-out; Stop on a running download still fetches the file to the end before deleting it; the free-space stop is silent; switching smart downloads off leaves its files; files from before a reinstall are invisible to the new install; the lyrics page still gets the whole playback state; the Downloads route has no back button; "Refresh now" shows no progress.
- **Checked on the OP7T:** the probe logs `browseIds=[other, FE…]` with no channel id and still says `premium=true`; the downloaded song started in 493 ms with no lookup for itself in the log; Library → Downloads lists it with its count and a delete button; its lyrics page shows the new header. Everything else in both reports is unchecked on a phone.
## Hide artist tells YouTube too; Not interested (2026-11-22, docs/feedback-plan.md)

The user pointed at the official app's **Not interested** and **Don't recommend artist** and asked that Hide artist and YouTube's option work together. The reviewer's probe on the phone confirmed both menu entries and their tokens on Home (`docs/api-notes.md` › Feedback); the rest was built in a worktree with no phone, while another reviewer worked in downloads and the engine. The branch was first fast-forwarded to `main`, which had the plan.

### What was built

- **Tokens:** `MusicItem`s gain `feedback: ItemFeedback?` (the two tokens), read from the item's own menu by icon in `twoRowItem` and `responsiveItem`; the library toggles that also use `feedbackEndpoint` are skipped. `ItemFeedback.toString()` says only which tokens are present, so an item or shelf printed anywhere does not carry them. They are not saved anywhere.
- **The call:** `YtmClient.feedback(token)` POSTs `feedbackTokens: [token]` signed in as `WEB_REMIX`; true only on `isProcessed: true`, false (never a throw) signed out, on an HTTP error or a broken answer. Logcat `YtmFeedback` has only booleans, counts and status codes: `hide artist: token=… artists=… tellYouTube=…`, then `feedback isProcessed=<bool>` or `feedback failed: <class> HTTP <status>`.
- **Hide artist:** hides locally first, then, with **Also tell YouTube** on (Settings → Filters, under the artist rules, on by default) and a token on the item, sends "Don't recommend artist" with a 15 s limit. Three toasts: told, not told, or the old "Undo it in Settings → Filters" when nothing was sent. The switch's note says it changes recommendations for the whole account in every YouTube Music app and cannot be undone from Meteora.
- **Not interested:** a long-press action shown only when the item has that token. The item leaves Home and artist shelves at once (an in-memory set of dismissed tokens, so it lasts until the next load), then the token is sent; the toast says whether YouTube took it. No local rule.

### Deviations from the plan

- The token goes only when Hide artist has exactly one artist to offer. With several, the user picks one in the dialog, but the token names YouTube's choice of artist; sending it could tell YouTube about the wrong one, and it cannot be taken back.
- Dismissed items are kept in a `@Singleton` (`filter/Dismissals`) applied inside `rememberFilteredShelves`, not in the Home view model, so `HomeLibrary.kt` is untouched. Keyed by token, they still expire with the load they came from; a song that shows twice in one load goes from both shelves.
- In `MeteoraShell.kt` the Hide artist branch was changed (one line) to pass the item's token; the rest are added lines.

### Checked

Build, and 256 unit tests passing (17 new: both tokens parsed and the toggles ignored, no token in `toString`, the request's path, headers and exact body, the answer's cases, signed out sending nothing; Hide artist with and without a token, with the switch off, and with a failing, refused or stuck call still hiding locally; the single-artist rule; old saved settings reading the switch as on; dismissals across shelves; Not interested offered only with its token).

**Not checked, no phone:** that YouTube answers `isProcessed: true` to the call at all, that the artist then stops being recommended, the toasts, the switch and its note in any look or in Ukrainian, Not interested on Home, and which item kinds carry the tokens.

- **On the OP7T (03:05):** the long-press sheet of a Home song now ends with Hide artist and Not interested, and Settings → Filters has "Also tell YouTube" with its note. Nothing was sent: which artist to tell YouTube about is the user's choice, and it cannot be taken back, so whether YouTube answers `isProcessed: true` is for the user's first real use (logcat tag `YtmFeedback` shows it).
- **Release build.** The minified `release` build type was built for the first time: 25.5 MB against the debug build's 124 MB, and it runs on the phone (sign-in kept, playback and the Rust core work). It reads its signing key from a git-ignored `keystore.properties`; the key itself is the owner's to create.

### "Queue is totally buggy" (03:10)

The user's screenshot, in the Terminal look: every queue row solid red with a delete icon, one row hanging half swiped. The rows sit in a swipe-to-dismiss box whose red background is always drawn behind them, and Terminal's rows have no fill of their own, so the red showed through all of them. The other looks hid it by accident, with their row fill. Rows are now opaque in every look and the red background is drawn only while a row is being swiped. On the OP7T in Terminal: normal rows at rest, red only under the row being swiped, and the swiped track left the queue.
