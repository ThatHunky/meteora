# Plan: switchable themes and a gentler artwork shape

Written 2026‑11‑21 for an implementing agent. Two requests from the user:

1. **Themes you can switch in the app**, including a few good-looking fixed ones. A friend of the user (N7Art) asked for one in particular: background `#000000`, text `#eeeeee` and `#aaaaaa`, `#ffffff` as the accent, "vim style", and named the vim colour scheme **moonfly** as a reference.
2. **The artwork cut-out.** While a track plays, the album cover is clipped to a 12‑sided cookie. The user: it "cuts way too much of the album cover". The same goes for the cookie on album and playlist pages.

## What exists

- `ui/theme/Theme.kt`: `MeteoraTheme(colorSource, albumSeed, darkTheme)` with `enum ColorSource { AlbumArt, System, Fixed }`. Nothing ever passes a `colorSource`, so the app is always album art. The scheme goes through `ColorScheme.animated()` (`AnimatedColorScheme.kt`), so any change of scheme already animates.
- `MainActivity` calls `MeteoraTheme(albumSeed = seed)`.
- Artwork shapes: `ui/player/PlayerHost.kt` (full player: a `Morph` between `MaterialShapes.Square` and `Cookie12Sided`; mini player: `Cookie9Sided`), `ui/browse/MusicRows.kt` (album rows: `Cookie9Sided`), `ui/browse/PageScreens.kt` (`Hero`: `Cookie12Sided` for albums and playlists).

## Themes

A theme is either **dynamic** (built from a seed with materialkolor, as now) or **fixed** (a hand-picked palette).

| Id | Name | Kind | Notes |
|---|---|---|---|
| `album` | Album art | dynamic | today's behaviour; the default |
| `system` | System colours | dynamic | Material You wallpaper colours |
| `meteora` | Meteora | dynamic | the fixed seed `MeteoraSeed` |
| `mono` | Mono | fixed, dark | N7Art's request: background `#000000`, text `#eeeeee`, secondary text `#aaaaaa`, accent `#ffffff`. No colour at all except the error red. Settings shows "by N7Art" under its name. |
| `moonfly` | Moonfly | fixed, dark | the vim scheme, on black |
| `catppuccin` | Catppuccin Mocha | fixed, dark | |
| `gruvbox` | Gruvbox | fixed, dark | |
| `nord` | Nord | fixed, dark | |
| `rosepine` | Rosé Pine | fixed, dark | |
| `tokyonight` | Tokyo Night | fixed, dark | |
| `latte` | Catppuccin Latte | fixed, light | so there is a fixed light theme too |

**Palettes must come from each scheme's own published source, not from memory.** You may fetch them anonymously (the projects' repositories or sites: bluz71/vim-moonfly-colors, catppuccin/catppuccin, morhetz/gruvbox, nordtheme/nord, rose-pine/rose-pine-theme, folke/tokyonight.nvim). For each, take: background, one or two raised-surface tones if the scheme has them, foreground, a dimmed foreground, three accents and a red. Put the source URL in a comment next to each palette. If a source cannot be reached, leave that theme out and say so in the report rather than guessing its colours.

### From a palette to a Material scheme

One pure function, `fixedScheme(palette): ColorScheme`, so every fixed theme is mapped the same way:

- `background`, `surface`, `surfaceDim`, `surfaceContainerLowest` = the background.
- `surfaceContainerLow` … `surfaceContainerHighest`, `surfaceBright`, `surfaceVariant`: the scheme's raised tones if it has them, otherwise the background blended towards the foreground in even steps (about 4, 7, 10, 13 and 16 %).
- `onBackground`, `onSurface` = foreground. `onSurfaceVariant`, `outline` = dimmed foreground. `outlineVariant` = dimmed foreground at half strength over the background.
- `primary` = accent 1, `secondary` = accent 2, `tertiary` = accent 3, `error` = red. Each `on…` = whichever of background and foreground contrasts better with it.
- Each `…Container` = that accent at 24 % over the background; each `on…Container` = the accent itself (lightened towards the foreground if its contrast on the container is under 4.5).
- `inverseSurface` = foreground, `inverseOnSurface` = background, `inversePrimary` = accent 1 darkened, `surfaceTint` = accent 1, `scrim` = black.
- The "fixed" roles (`primaryFixed` and the rest): set them from the same accents; do not leave library defaults, which are purple.
- For `mono`, accent 1 is `#ffffff`, accents 2 and 3 are `#eeeeee` and `#aaaaaa`.

### Modes and options

- **Mode:** System / Light / Dark. It applies to dynamic themes. A fixed theme is the mode it was designed for and ignores the setting; Settings greys the mode selector out while a fixed theme is selected.
- **Pure black:** a switch for dynamic themes in dark mode. It replaces `background`, `surface`, `surfaceDim` and `surfaceContainerLowest` with `#000000` and darkens the other surface containers (blend each 60 % towards black). Greyed out for fixed themes and in light mode.
- **Status and navigation bar icons** must follow the app's theme, not the system's: a dark theme on a phone in light mode needs light icons. Re-apply `enableEdgeToEdge` with matching `SystemBarStyle`s from `MainActivity` whenever the effective darkness changes.

## Artwork shape

A setting, **Artwork shape**, with two values:

- **Rounded** (new default). The whole cover is shown.
  - Full player: a rounded square (28 dp corners). Play / pause is shown by size instead of by cutting: the art is at full size while playing and springs to 88 % while paused, using `MaterialTheme.motionScheme.slowSpatialSpec()`. Apply the scale in the existing `graphicsLayer` block so it stays off the composition path.
  - Mini player: a rounded square (10 dp). Album rows: a rounded square (10 dp), like songs. `Hero`: a rounded square (28 dp); artists stay circular.
- **Scalloped**: exactly what the app does today.

Provide it as a composition local (`LocalArtworkShape`) set next to the theme in `MainActivity`, so the four call sites read it without new parameters. The shared-element transition between mini player and full player must keep working in both settings.

## Storage and wiring

- `ui/theme/ThemeStore.kt`: `@Singleton`, SharedPreferences file `appearance`; `StateFlow`s for theme id, mode, pure black and artwork shape; unknown stored values fall back to the defaults (`album`, System, off, Rounded).
- `ui/theme/Themes.kt`: the theme list, the palettes, `fixedScheme`. Replace `ColorSource` (it has no other users).
- `MeteoraTheme` takes the chosen theme, mode, pure-black flag and album seed, and still ends in `.animated()` so switching themes cross-fades.
- `MainActivity` collects the store through a small `AppearanceViewModel` (`@HiltViewModel`, new, in `ui/settings/`) and passes the values to `MeteoraTheme`.

## Settings UI

A new file, `ui/settings/AppearanceSettings.kt`, with one composable `AppearanceSection()` that gets its own `AppearanceViewModel` through `hiltViewModel()`. `SettingsScreen` in `ui/shell/MeteoraShell.kt` calls it once, as the first block of the screen, and is otherwise untouched: another change is editing that function, so keep the diff there to that one line and do not change its parameters.

- **Theme:** a horizontally scrolling row of preview cards, one per theme. Each card shows the theme's own background with three accent dots and a line of text in its foreground, the name underneath, and a clear selected state. Dynamic themes preview with the colours they would have right now. Tapping applies it at once.
- **Mode:** a three-segment connected toggle (System / Light / Dark), copied from the pattern of `QualitySelector` in `MeteoraShell.kt`.
- **Pure black:** a switch row.
- **Artwork shape:** a two-segment toggle (Rounded / Scalloped).
- Everything is labelled for TalkBack and the cards are at least 48 dp tall.

## Strings (English / Ukrainian)

Add them as one block at the very end of each `strings.xml`, just before `</resources>`, under a comment `<!-- Appearance -->`, so they merge cleanly with other work adding strings elsewhere.

| Name | English | Ukrainian |
|---|---|---|
| `appearance_title` | Appearance | Вигляд |
| `appearance_theme` | Theme | Тема |
| `appearance_mode` | Mode | Режим |
| `mode_system` | System | Як у системі |
| `mode_light` | Light | Світла |
| `mode_dark` | Dark | Темна |
| `appearance_pure_black` | Pure black background | Чисто чорне тло |
| `appearance_artwork` | Artwork shape | Форма обкладинки |
| `artwork_rounded` | Rounded | Заокруглена |
| `artwork_scalloped` | Scalloped | Фігурна |
| `theme_album` | Album art | Обкладинка альбому |
| `theme_system` | System colours | Кольори системи |
| `theme_by` | by %1$s | від %1$s |

Fixed themes' names are proper names and stay untranslated in code.

## Tests (JVM)

- For every fixed theme: `onPrimary` on `primary`, `onSurface` on `surface`, `onSurfaceVariant` on `surface` and `onPrimaryContainer` on `primaryContainer` each reach a contrast ratio of at least 4.5. Compute contrast from the colours' ARGB values with a small helper in the test, so nothing needs the Android framework.
- `mono` has exactly the four requested colours in the roles named above.
- `ThemeStore` parsing: unknown values fall back to the defaults (test the pure parsing function, not SharedPreferences).

## Files

New: `ui/theme/Themes.kt`, `ui/theme/ThemeStore.kt`, `ui/settings/AppearanceSettings.kt` (section and view model), tests. Changed: `ui/theme/Theme.kt`, `MainActivity.kt`, `ui/shell/MeteoraShell.kt` (one line), `ui/player/PlayerHost.kt` (the artwork blocks of the mini and full player only), `ui/browse/MusicRows.kt` (the album row's clip only), `ui/browse/PageScreens.kt` (`Hero` only), both `strings.xml`. Nothing under `stream/`, `core/`, `rust/`, `playback/`, `ytm/`, `data/` or the build files.

## Verify

- From PowerShell: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain -q` (600000 ms timeout).
- No phone, no adb. The reviewer checks on the OnePlus: each theme applied, the mode and pure-black switches, status bar icons on a dark theme, the rounded artwork while playing and paused, and the mini-to-full transition.
