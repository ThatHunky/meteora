# Plan: themes that change the look, not just the colours

Written 2026‑11‑22 for an implementing agent. The themes added on 2026‑11‑21 ([theming-plan.md](theming-plan.md)) swap colour schemes only. The user: "The themes must change the look dramatically, not only the color." This plan adds a second axis, the **look**: type, corner shapes, how surfaces are drawn, the progress bar, the navigation bar, section headers, spacing and motion.

## Idea

- A **look** is a small bundle of tokens plus a few component variants. There are five.
- Every theme names its look, so picking "Mono" changes everything at once.
- Settings also gets a **Style** selector, "From theme" by default, so any colours can be combined with any look (album-art colours with the Terminal look, for example).
- The five looks must be tellable apart at a glance in a screenshot with the colours removed. If two of them end up looking alike, that is a failure of this work.

## The looks

| | Expressive | Terminal | Flat | Retro | Soft |
|---|---|---|---|---|---|
| Used by | Album art, System colours, Meteora | Mono, Moonfly | Nord, Tokyo Night | Gruvbox | Catppuccin Mocha and Latte, Rosé Pine |
| Feels like | today's app: Material 3 Expressive | a text-mode program, vim | calm, dense, modern | chunky, 90s desktop | rounded, pastel, roomy |
| Type | system sans, emphasized titles (as now) | **monospace everywhere** (JetBrains Mono, bundled) | system sans, lighter weights, tighter titles | **serif titles** (system serif), sans body, labels in capitals | system sans, medium weights, slightly larger body |
| Corners | as now (pills, 20–28 dp) | **0 dp everywhere** | 8 dp, never pills | 2 dp | pills and 32 dp |
| Surfaces | tonal fills | **no fills: 1 dp outline** in the outline colour; selected = inverted (foreground fill, background text) | tonal fills, no outlines, no gradients | fills with a **2 dp outline and a hard offset shadow** (3 dp down-right, no blur) | tonal fills, larger padding |
| Gradients (Now Playing, page headers) | yes | none: flat background | none | none | yes, softer (half the strength) |
| Progress / seek bar | wavy | **a row of block characters** drawn as text, e.g. `████████░░░░░░░░`, in the mono font | thin straight line (2 dp) with a small round thumb | thick straight bar (8 dp) with an outline, square thumb | wavy, thicker |
| Loading | shape-morphing `LoadingIndicator` | a text spinner cycling `|/-\` | thin circular indicator | three bouncing squares, or the plain circular indicator if that is simpler | `LoadingIndicator` |
| Section headers | emphasized title | `# listen again`: lowercase, `#` prefix, in the primary colour | small capitals label, letter-spaced, onSurfaceVariant | serif title with a 2 dp rule under it | large rounded title |
| Navigation bar | `ShortNavigationBar` with pill indicator | **a status line**: one row of text labels, no icons, the selected one inverted, 32 dp tall plus insets | icons only with a 2 dp line above the selected one, no labels | icons and labels in outlined boxes, the selected one filled | pill indicator, larger |
| Mini player | floating pill toolbar | a status line above the nav line: `▶ title - artist` left, `0:25/4:20` right, no artwork, full width, no float | a flat full-width bar, 56 dp, square artwork | outlined box with hard shadow, square artwork | floating pill, larger artwork |
| Buttons | filled, shape-morph on press | text in square brackets, `[ play ]`; primary action inverted | filled 8 dp, no morph | outlined with hard shadow; pressed = shadow gone and shifted 3 dp | filled pills |
| Artwork (rows, cards, hero, player) | from the Artwork shape setting | square, 1 dp outline | 8 dp corners | 2 dp corners, 2 dp outline | 32 dp corners; Scalloped allowed |
| Player artwork on pause | 88 % size (Rounded) | stays full size; the status line shows `▶` / `‖` | dims to 60 % opacity | stays full size | 88 % size |
| Spacing | as now | tighter: list rows 48 dp, screen padding 12 dp | tighter: list rows 56 dp | as now | roomier: screen padding 20 dp, row gaps +4 dp |
| Motion | `MotionScheme.expressive()` | `MotionScheme.standard()`, no shape morphs | `MotionScheme.standard()` | `MotionScheme.standard()` | `MotionScheme.expressive()` |
| Icons | filled / outlined Material icons | where a control has a text form, use text: `prev`, `next`, `like`, `queue`; keep icons only where text would not fit | outlined icons | filled icons | rounded icons if available, else as now |

Where a cell says "as now", do not change that aspect for that look.

## How to build it

### Tokens

`ui/theme/Look.kt`:

- `enum class LookId { Expressive, Terminal, Flat, Retro, Soft }`.
- `data class Look(...)` with the token values: `fontFamily`, `titleFontFamily`, `capsLabels`, corner sizes (`cornerSmall`, `cornerMedium`, `cornerLarge`, `pills: Boolean`), `outline: Dp`, `hardShadow: Dp`, `fills: Boolean`, `gradients: Float` (0, 0.5 or 1), `rowHeight`, `screenPadding`, `progress: ProgressStyle`, `nav: NavStyle`, `mini: MiniStyle`, `header: HeaderStyle`, `pauseArt: PauseArt`, `textControls: Boolean`, `expressiveMotion: Boolean`.
- `val LocalLook = staticCompositionLocalOf { Looks.Expressive }`.
- `MeteoraTheme` picks the look (theme's own, or the Style override), provides `LocalLook`, and passes `typography`, `shapes` and `motionScheme` built from it to `MaterialExpressiveTheme`. That alone restyles text and the components that honour `MaterialTheme.shapes`.
- Each `AppTheme` gains `val look: LookId`. `ThemeStore` gains the Style override (`look`, stored as an id, `""` = from theme).

### Look-aware components

Many components in this Material version take their shape from their own defaults, not from `MaterialTheme.shapes`, and the code passes `shapes = ButtonDefaults.shapes()` or a literal `RoundedCornerShape(...)` in about forty places. Replace those call sites with a small set of wrappers in `ui/components/`, each reading `LocalLook`:

- `MButton`, `MTonalButton`, `MOutlinedButton`, `MIconButton`, `MFilledIconButton`: the five button kinds the app uses.
- `MSurface(selected: Boolean = false, …)`: a card or container drawn as fill, outline, or outline with hard shadow.
- `SectionHeader(text)`.
- `MProgress(progress: () -> Float, playing: Boolean, …)`: wavy, block text, thin line or thick bar. Keep the lambda form of `progress`: the seek position is read at draw time and must stay off the composition path (CLAUDE.md, "Things that are easy to break").
- `MLoading()`.
- `ArtworkFrame(model, size or modifier, kind: song / album / artist / hero / player, …)`: clip, outline and pause treatment in one place. It replaces the `LocalArtworkShape` reads; the Artwork shape setting keeps applying in the looks that allow it.
- `SegmentedChoice(options, selected, onSelect)`: the connected toggle rows in Settings (three copies exist today: `TargetSelector`, `QualitySelector` and the ones in `AppearanceSettings.kt`). Fold them into this.
- `MeteoraNavBar(tabs, current, onSelect)` and the mini player variants.

Do not fork whole screens per look. A screen calls the wrappers; the wrappers branch.

### Screens to convert

`ui/shell/MeteoraShell.kt` (nav bar, Settings, banner), `ui/player/PlayerHost.kt` (mini player, Now Playing, seek bar, controls), `ui/player/QueueSheet.kt`, `ui/browse/HomeLibrary.kt`, `PageScreens.kt`, `SearchScreen.kt`, `MusicRows.kt`, `ui/pairing/PairingScreen.kt`, `ui/signin/SignInScreen.kt`, `ui/settings/AppearanceSettings.kt`.

Specifics that are easy to get wrong:

- **Shared elements.** The mini player and Now Playing share the artwork and the title (`sharedElement`, `sharedBounds` in `PlayerHost.kt`). The Terminal mini player has no artwork: there, fade the artwork in instead of sharing it, and keep sharing the title.
- **Mini player space.** `MiniPlayerSpace` in `MeteoraShell.kt` reserves room under scrolling content. It must follow the look's mini player height, including the Terminal one, which is not floating.
- **Status line content.** The Terminal status line shows live position text. Derive it once a second (as the seek bar's time label already does with `derivedStateOf`), not every frame.
- **Insets.** A custom nav bar must still pad for the system navigation bar.
- **Touch targets.** Text controls and the status-line tabs must still be at least 48 dp tall in their touch area, even when drawn smaller.
- **Capitals and prefixes** are presentation: transform at draw time (`SectionHeader`), never in `strings.xml`. Lowercasing and uppercasing must use the app's locale, since the app is also in Ukrainian.

### Font

Bundle JetBrains Mono (SIL Open Font License 1.1): regular and bold, from the project's official release (https://github.com/JetBrains/JetBrainsMono). Put the `.ttf` files in `app/src/main/res/font/` with lowercase names, and the licence text in `app/src/main/assets/licenses/JetBrainsMono-OFL.txt`. Check that the font has Cyrillic (it does, but verify: titles here are often Ukrainian). Use `FontFamily.Serif` for Retro titles; no second bundled font.

### Settings

In `AppearanceSection`: the theme cards preview their look as well as their colours (card corner, an outline or fill, and the sample text in the look's font). Under them a **Style** row: From theme, Expressive, Terminal, Flat, Retro, Soft, as a horizontally scrolling chip row. The Artwork shape row is disabled, with its current value still shown, while the active look fixes the shape.

Strings (English / Ukrainian), added inside the existing `<!-- Appearance -->` block: `appearance_style` Style / Стиль · `style_from_theme` From theme / Як у темі · `style_expressive` Expressive / Експресивний · `style_terminal` Terminal / Термінал · `style_flat` Flat / Плаский · `style_retro` Retro / Ретро · `style_soft` Soft / М’який. Text controls for the Terminal look reuse the existing action strings, lowercased at draw time.

## Tests (JVM)

- Every theme maps to a look, and the stored Style override parses back, with unknown values meaning "from theme".
- The block progress text: 0 %, 50 % and 100 % of a given width give the expected strings, and the string length never changes with progress (the bar must not jitter).
- The look table itself: no two looks have the same combination of font family, corner size and surface style.

## Verify

- From PowerShell: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain -q` (600000 ms timeout).
- No phone, no adb. The reviewer checks all five looks on the OnePlus with screenshots of Home, an album page, Now Playing and Settings, the mini-to-full transition in each, and Ukrainian text in the mono font.

## Files

New: `ui/theme/Look.kt`, `ui/components/*.kt`, `res/font/*`, the font licence, tests. Changed: `ui/theme/Theme.kt`, `Themes.kt`, `ThemeStore.kt`, `MainActivity.kt`, and the screens listed above, both `strings.xml`. Nothing under `stream/`, `core/`, `rust/`, `playback/`, `ytm/`, `data/`, `companion/` or the build files (adding `res/font` needs no build change).

## Order of work, so there is something whole at each step

1. Tokens, `LocalLook`, typography / shapes / motion wired into the theme, the Style setting. Build.
2. The wrappers, with the Expressive variant only, and every screen converted to them. The app must look exactly as before. Build.
3. Terminal, complete, including nav and mini player. Build.
4. Flat, Retro, Soft. Build and test.

If time or context runs short, stop after a completed step and say which looks are finished, rather than leaving all five half done.
