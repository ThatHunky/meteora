# Plan: audio quality switch for phone playback

Written 2026‑11‑21 for an implementing agent. Phone playback only: on the PC, YTMDesktop picks its own quality.

## What YouTube gives us

Meteora asks `/player` as the signed‑out visionOS client. The saved response (`app/src/test/resources/fixtures/ytm/player-visionos.json`) lists five audio formats:

| itag | Codec | Average bitrate | `audioQuality` |
|---|---|---|---|
| 251 | Opus | 127 kbps | MEDIUM |
| 140 | AAC | 130 kbps | MEDIUM |
| 250 | Opus | 65 kbps | LOW |
| 249 | Opus | 49 kbps | LOW |
| 139 | HE‑AAC | 49 kbps | LOW |

The official app's top tier is 256 kbps (AAC or Opus), for Premium members only. Those formats are not in this response. Getting them would need a signed‑in `/player` request from a client that serves Premium formats, which is out of scope here. The switch therefore chooses among the formats above, and "High" means "the best format YouTube returns", so it picks up a 256 kbps format by itself if one ever appears.

## Behaviour

- Three tiers, `StreamQuality { DataSaver, Balanced, High }`.
- Two settings: quality on Wi‑Fi and quality on mobile data. Both default to `High`, which is today's behaviour.
- "Mobile data" means `ConnectivityManager.isActiveNetworkMetered` is true at the moment a track is first opened.
- A change applies from the next track that is opened. A track that is already open keeps its format (see "Pinning").

## Picking a format (`InnerTubeStreams.pickAudio`)

Change the signature to `pickAudio(root: JsonElement, quality: StreamQuality = StreamQuality.High): Picked?` and add `bitrate: Int` to `Picked`.

1. Candidates: `adaptiveFormats` whose `mimeType` starts with `audio/` and that have a plain `url` (as now).
2. Drop loudness‑compressed and dubbed variants when an alternative exists: prefer formats where `isDrc` is not `true`, and where `audioTrack.audioIsDefault` is not `false`. If filtering would leave nothing, keep the unfiltered list.
3. Bitrate of a format = `averageBitrate`, else `bitrate`, else 0.
4. Ceiling per tier, on that bitrate: `DataSaver` 56 000, `Balanced` 96 000, `High` none.
5. Among candidates at or under the ceiling, take the highest score, where score = bitrate × 1.3 for Opus (`codecs="opus"` in `mimeType`) and bitrate × 1.0 otherwise. Opus sounds better per bit, so it wins ties and near‑ties; a 256 kbps AAC still beats a 127 kbps Opus.
6. If nothing is under the ceiling, take the candidate with the lowest bitrate.

Expected with the fixture: `High` → 251, `Balanced` → 250, `DataSaver` → 249. Delete `PREFERRED_ITAGS`.

`resolve(videoId, quality)` passes the tier through, logs itag and bitrate, and returns an `AudioStream` whose `cacheKey` stays `"$videoId/$itag"` (already there: it keeps formats apart in the disk cache).

## Files

Touch only these. Other files are being edited or tested in parallel.

1. **New `stream/StreamQuality.kt`**
   - `enum class StreamQuality { DataSaver, Balanced, High }`.
   - `@Singleton class StreamQualityStore @Inject constructor(@ApplicationContext context: Context)`:
     - SharedPreferences file `"playback"` (the one `PlaybackRouter` uses), keys `quality_wifi` and `quality_mobile`, stored as enum names, unknown or missing → `High`.
     - `val wifi: StateFlow<StreamQuality>`, `val mobile: StateFlow<StreamQuality>`, `fun setWifi(q)`, `fun setMobile(q)`.
     - `fun current(): StreamQuality`: `mobile.value` when the active network is metered, else `wifi.value`. Use `context.getSystemService(ConnectivityManager::class.java)`. `ACCESS_NETWORK_STATE` is already in the manifest.
2. **`stream/AudioStream.kt`**: add `val quality: StreamQuality = StreamQuality.High`.
3. **`stream/InnerTubeStreams.kt`**: as above.
4. **`stream/YtDlpStreams.kt`**: `resolve(videoId, quality)`. Format selector per tier, keeping the current one as the tail fallback:
   - `High`: unchanged.
   - `Balanced`: `bestaudio[abr<=96][protocol^=http]/` + the current selector.
   - `DataSaver`: `bestaudio[abr<=56][protocol^=http]/` + the current selector.
5. **`stream/StreamResolver.kt`**
   - Inject `StreamQualityStore`; expose it as `val quality`.
   - `resolve(videoId: String, quality: StreamQuality = this.quality.current())`.
   - Key the in‑memory `cache` and `inFlight` maps by `"$videoId/${quality.name}"`. `cached(videoId, quality)` likewise.
   - `invalidate(videoId)` removes the entries of every tier for that video.
   - `warm(videoId)` uses the current tier. Keep the lazy `async` in the app scope exactly as it is: it is what stops one cancelled caller from failing the others.
6. **`playback/LocalEngine.kt`**: pinning, see below. Nothing else in this file.
7. **`ui/shell/ShellViewModel.kt`**: inject `StreamQualityStore`; expose `qualityWifi`, `qualityMobile` and `setQualityWifi`, `setQualityMobile`.
8. **`ui/shell/MeteoraShell.kt`**: in `SettingsScreen`, under the PC / This phone selector, add an "Audio quality on this phone" block:
   - a small title, then two rows, each a label ("On Wi‑Fi", "On mobile data") above a three‑segment connected `ToggleButton` group. Copy the structure of `TargetSelector` in the same file (`ButtonGroupDefaults.connectedLeadingButtonShapes()`, `connectedMiddleButtonShapes()`, `connectedTrailingButtonShapes()`); check that the middle‑shape function exists in material3 1.6.0‑alpha01 before using it.
   - a `bodySmall` note in `onSurfaceVariant` under them (string `quality_note`).
   - `SettingsScreen` already takes many parameters. Pass the two values and two setters as four more; do not restructure the screen.
9. **Strings**, `res/values/strings.xml` and `res/values-uk/strings.xml`:

   | Name | English | Ukrainian |
   |---|---|---|
   | `quality_title` | Audio quality on this phone | Якість звуку на цьому телефоні |
   | `quality_wifi` | On Wi‑Fi | Через Wi‑Fi |
   | `quality_mobile` | On mobile data | Через мобільний інтернет |
   | `quality_high` | High | Висока |
   | `quality_balanced` | Balanced | Збалансована |
   | `quality_data_saver` | Data saver | Економія |
   | `quality_note` | High is the best YouTube offers Meteora, about 130 kbps. Balanced is about 65 kbps and Data saver about 50 kbps. A change applies from the next track. | «Висока» — найкраще, що YouTube віддає Meteora, приблизно 130 кбіт/с. «Збалансована» — приблизно 65 кбіт/с, «Економія» — приблизно 50 кбіт/с. Зміна діє з наступного треку. |

10. **Tests**, `app/src/test/java/dev/thathunky/meteora/stream/InnerTubeStreamsTest.kt`:
    - fixture: `High` → 251, `Balanced` → 250, `DataSaver` → 249;
    - a hand‑written response that also has itag 141 (AAC, 256 000) and 774 (Opus, 256 000): `High` → 774; without 774, `High` → 141;
    - a response where the first 251 has `"isDrc": true` and a second 251 does not: the second one is picked (assert on the URL);
    - a response with only a 130 000 AAC format: `DataSaver` still returns it;
    - the existing tests keep passing (`cipheredFormatsAreSkipped` relies on the fallback to a format with a plain URL).

## Pinning (the one subtle part)

ExoPlayer opens a track's stream more than once: at the start, and again for a seek or after a stall, each time through the `ResolvingDataSource` lambda in `LocalEngine.build()`. If the tier changed in between (the user flips the switch, or the phone moves from Wi‑Fi to mobile data), the second open would get a different file and the player would read bytes of one format into a stream of another. So the tier is fixed per track when it is first opened:

- In `LocalEngine`, add `private val pinned = ConcurrentHashMap<String, StreamQuality>()`.
- In the resolving lambda: `val q = pinned.getOrPut(id) { resolver.quality.current() }`, then `resolver.resolve(id, q)`. The lambda runs on ExoPlayer's loader thread, hence the concurrent map.
- Clear `pinned` in `apply()` (a new queue).
- On `EVENT_MEDIA_ITEM_TRANSITION`, keep only the current item's id and the next item's id (`pinned.keys.retainAll(...)`). The next track may already be buffered in its pinned format.

## Verify

- Build and test from **PowerShell**, not the Bash tool (Bash here cannot reach the Gradle daemon):
  `.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --console=plain -q`
- Do not install the app or drive a phone: the phones are in use for other checks. Device verification happens after you report.
- Do not commit.

## Report back

The files changed, the test result (count passed/failed, copied from the output), anything in this plan you had to do differently and why, and anything you could not verify.
