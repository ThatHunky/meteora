# Plan: hiding music by language, country or artist

Written 2026‑11‑22 for an implementing agent. The user: "can we exclude songs from showing certain languages/countries of origin?" Start it after the review, lyrics and downloads branches are merged: it touches the same screens and `LocalEngine`.

## The limit to be honest about

YouTube Music's responses carry no language and no country for a track or an artist. Nothing here can be exact. Three signals exist, from most to least reliable:

1. **The user's own block list** of artists. Exact.
2. **The artist's country from MusicBrainz** (`https://musicbrainz.org/ws/2/artist?query=…&fmt=json`, no key, one request a second, a descriptive User-Agent required). Good for known artists; missing or wrong for small ones and for names shared by several artists. Sends artist names to a third party, so it is opt-in.
3. **The script and letters of the title and artist name.** Free and offline, but coarse: it tells Cyrillic from Latin, and inside Cyrillic it can tell Russian from Ukrainian only when a telling letter is present (`ы э ъ ё` are Russian-only; `і ї є ґ` are Ukrainian-only; Belarusian has `ў`). A title with none of them is undecided, and a Russian artist with an English title passes.

## What the user gets

- **Hide artist** in the long-press sheet of a song, album or artist, and in Now Playing's menu. Settings → a new **Filters** category lists hidden artists with an undo.
- **Hidden languages:** a list of languages to hide, detected from title and artist text (signal 3). First version: Russian, with the list built so others can be added (a language is a set of telling letters or a script).
- **Hidden countries:** a list of countries; needs the MusicBrainz switch on. An artist with no answer is shown, not hidden.
- **Strictness** for the undecided case (Cyrillic with no telling letter): "Show" (default) or "Hide unless it looks Ukrainian".
- **Where it applies:** Home shelves, radio and autoplay queues (the track is left out of the queue), "more like this" shelves on artist pages, and smart downloads. **Not** to search results, the user's own library, playlists and albums opened on purpose, or anything the user taps directly: asking for it is the override. A shelf left with fewer than three items is dropped.
- A hidden count per screen is not shown; the filter is silent.

## How to build it

- `filter/ContentFilter.kt`: `fun allows(item: MusicItem): Boolean` and the same for a queue song, from the block list, the language rules and the country cache. Pure, with JVM tests on real-looking titles (Ukrainian, Russian, mixed, Latin transliteration, Bulgarian and Serbian Cyrillic, which must not be taken for Russian by default).
- `filter/LanguageGuess.kt`: script and telling-letter rules. Returns Russian / Ukrainian / Belarusian / other Cyrillic undecided / not Cyrillic.
- `filter/FilterStore.kt`: hidden artist ids and names, hidden languages, hidden countries, strictness, the MusicBrainz switch. Preferences file `filters`.
- `filter/ArtistCountries.kt`: MusicBrainz client with a disk cache (artist browse id → country or "unknown", kept 90 days), one request a second, only for artists that appear on screen or in a queue, never blocking the UI: an artist not yet looked up is shown and the answer applies from the next load. Accept a match only when the name is equal ignoring case and the score is 95 or more; several equal names with different countries count as unknown.
- Apply in `ytm` results where shelves and queues are built (`YtmClient.home`, artist page shelves, `watchQueue` consumers in `LocalEngine` for radio and autoplay only) and in the smart downloads wanted list. Not in the parser itself.
- Strings in English and Ukrainian.

## Decisions

- **MusicBrainz: yes** (the user, 2026-11-22: "Yes, use MusicBrainz"). The switch exists in Filters and is on by default once a country is hidden; with no hidden country nothing is looked up.
- **Languages and countries:** assumed Russian and Russia as the first entries; the user has not confirmed. Both lists are editable.
- **Search results:** not filtered, unless the user asks.

## Fully customizable (the user, 2026-11-22: "it should be fully customizable")

Nothing about any language is built into the behaviour: no default exception for Ukrainian or any other. The app ships detection rules; what to do with each result is the user's setting. This replaces the "Strictness" bullet and the fixed "Hidden languages" list above.

- **Languages:** every language the app can detect is a row with three states: **Show** (default), **Hide**, **Always show**. First set, from telling letters or script: Russian, Ukrainian, Belarusian, Bulgarian, Serbian (Cyrillic), Kazakh, Greek, Arabic, Hebrew, Hindi, Chinese, Japanese, Korean, Thai, Turkish, Polish, German, French, Spanish, Portuguese (the Latin ones only when a telling letter is present). Adding a language is adding a rule object, not code in the filter.
- **Scripts, for text no language rule decides:** a row per script (Cyrillic, Latin, Greek, Arabic, Hebrew, Devanagari, Han, Kana, Hangul, Thai) with **Show** (default) or **Hide**. So "hide Cyrillic that is not clearly Ukrainian" is: Cyrillic = Hide, Ukrainian = Always show. Nothing else expresses it.
- **Countries:** any country from the ISO list, each **Hide** or **Always show**; unlisted countries are shown.
- **Artists:** each **Hidden** or **Always show**.
- **Custom rules:** the user's own words or characters; a title or artist name containing one is hidden or always shown, as chosen per rule.
- **Order when rules disagree:** an artist rule wins over a custom rule, then country, then language, then script; inside one level **Always show** wins over **Hide**. One pure function, with tests for each pair.
- **Where it applies** is also a setting, one switch each: Home, radio and autoplay, artist page shelves, smart downloads.
- **Never filtered, with no switch** (the user, 2026-11-22: "if you specifically search for something it should still show"): search results and suggestions, the library, an album, playlist or artist page opened on purpose, and the song the user tapped. A radio started from a tapped song keeps that song and filters only what is added around it.
- A **Test** field in Filters: type or paste a title and artist and see what the filter decides and which rule decided it.
