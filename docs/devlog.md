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

### Known gaps

- **Untested:** handing off to the PC *while the phone is playing*, which would have started music on the PC. Review found that switching back on the same track would have done nothing (`changeVideo` ignores the loaded track), so it now sends play. Mobile data is untested too, because turning Wi‑Fi off cuts the adb link.
- **Release build:** not built yet.
- **Signed-out streams:** audio URLs are fetched without your cookies, so age-restricted or private tracks may not play.
- **Like buttons:** they show "not liked" until you press them; the current like status isn't read from YouTube yet.
- **Share sheet:** PC only.
- **Debug APK:** 104 MB, mostly the unshrunk debug build with the full icon set; yt-dlp adds ~18 MB. A release build will be much smaller.
- **License:** yt-dlp comes through `youtubedl-android`, which is GPL‑3.0. That matches the plan's GPL‑3.0 choice.
- **Terms:** phone playback is against YouTube's terms, so Meteora stays sideload‑only.
