# Plan: Hide artist also tells YouTube ("Don't recommend artist", "Not interested")

Written 2026‑11‑22 for an implementing agent. The user pointed out that the official app's song menu has **Not interested** and **Don't recommend artist**, and wants Meteora's Hide artist and YouTube's own option to "work together if possible".

## What was verified on the phone (signed in, Premium account, 2026‑11‑22)

`adb shell am broadcast -a dev.thathunky.meteora.PROBE_LIKES -p dev.thathunky.meteora --es menus home` (`ytm/LikeProbe.menus`) logged the menu entries of the `WEB_REMIX` Home response:

- `menuServiceItemRenderer` with `icon.iconType = NOT_INTERESTED` and a `serviceEndpoint.feedbackEndpoint`: present.
- `menuServiceItemRenderer` with `icon.iconType = REMOVE_CIRCLE` and a `serviceEndpoint.feedbackEndpoint`: present. **Assumed** to be "Don't recommend artist"; the probe logs no titles. Confirm it first (below).
- 332 `feedbackEndpoint`s with a `feedbackToken` on Home.
- In a watch queue (`next`): neither entry. Only like, library and queue entries.

So the tokens come with Home items (and probably other browse shelves), not with queue items or Now Playing.

## Step 1 result (done by the reviewer on the phone, 02:50)

`label x26: REMOVE_CIRCLE "Don't recommend artist" endpoint keys=[feedbackToken, actions]` and `label x38: NOT_INTERESTED "Not interested" endpoint keys=[feedbackToken, actions]`. So the assumption holds; both endpoints also carry `actions` (not inspected: most likely the toast YouTube shows, possibly with an undo token; look at their shape in a hand-made fixture only if the reviewer supplies one, otherwise ignore them). Skip the rest of step 1 below and start building; still write the "Feedback" section in `docs/api-notes.md`.

## Step 1: confirm before building (already done, kept for the record)

Extend the probe to log, for the two entries, the entry's `text.runs` text (it is YouTube's own label, not personal data) and whether the endpoint carries anything besides `feedbackToken` (for example `actions` to run on success), and which item kinds have them (song, album, playlist, artist cards). The reviewer runs it on the phone and pastes the output back; write the result in `docs/api-notes.md` under "Feedback". If REMOVE_CIRCLE is not "Don't recommend artist", stop and report.

The call itself, from the open-source clients' notes (verify by the response): `POST music.youtube.com/youtubei/v1/feedback` as `WEB_REMIX`, signed in, body `{ context, feedbackTokens: ["<token>"] }`, answer `feedbackResponses[].isProcessed`. A token is single-use and bound to the response it came from.

## What the user gets

- **Hide artist** (long-press sheet on Home and on any shelf whose items carry the token): hides the artist locally as today **and** sends YouTube "Don't recommend artist" for that item. The toast says which happened: "Hidden here and YouTube was told not to recommend it", or "Hidden here" when the item had no token (Now Playing, queue, search) or the call failed.
- **Not interested**: a new action in the long-press sheet, shown only when the item has that token. It sends the token and removes the item from the screen for now (it may return on the next load if YouTube ignores it). No local rule is created.
- A switch in Settings → Filters: **Also tell YouTube** (on by default). Off = Hide artist stays local only. The note says it changes recommendations for the whole account, in every YouTube Music app.
- **Undo is one-way for YouTube:** removing a local rule does not un-tell YouTube (there is no token for that). Say so in the note. If step 1 finds an undo token in the response to the feedback call, use it for an Undo action on the toast.

## How to build it

- `ytm/YtmModels.kt`: `MusicItem.Song/Album/Playlist/Artist` gain `feedback: ItemFeedback? = null` (`notInterested: String?`, `dontRecommendArtist: String?`). Tokens never go to logs, the index, or saved state: mark the class so (`toString` without tokens).
- `ytm/YtmParser.kt`: read the two tokens from an item's `menuRenderer.items` by icon type in `twoRowItem` and `responsiveItem`. Fixture: a hand-made item with both entries; tokens in fixtures are fake.
- `ytm/YtmClient.kt`: `suspend fun feedback(token: String): Boolean`.
- `filter/`: `ContentFilter.hideArtist(...)` (or the existing entry point used by `rememberHideArtist`) takes the item's token and the setting; `FilterStore` gains `tellYouTube`.
- `ui/filter/`: the Not interested action (`ItemAction.NotInterested`, one line each in `ItemActions.kt`, `MusicRows.kt`, `MeteoraShell.kt`), the switch and its note, the toasts. A locally dismissed item is kept in a small in-memory set in the Home view model so it stays gone until the next load.
- Strings in English and Ukrainian, appended to the `<!-- Filters -->` block.

## Tests

Parser reads both tokens and ignores other feedback entries (library "KEEP" and "BOOKMARK_BORDER" toggles also use `feedbackEndpoint`); the request body; Hide artist with and without a token and with the switch off; a failed call still hides locally.

## Rules

Gradle from PowerShell: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain -q`. No phone, no adb for the agent. Another reviewer is working in `downloads/`, `playback/LocalEngine.kt`, `ytm/PremiumCheck.kt` and the Rust core: do not touch those. Single-line additions in shared screens. Never log tokens.
