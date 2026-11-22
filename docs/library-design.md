# Library design: grouping, sorting and selection for long lists

Written 2026‑11‑22. The owner's screenshot of Library → Downloads showed "Songs · 133" and then 133 identical rows, each with a delete icon. "This gotta have grouping, as well as other similar pages (where applicable)."

## What other apps and the guidelines do

- **Sticky subheaders.** Material's subheader guidance says section headers divide a list, are most often "tied to filtering or sorting", and stay pinned until the next one pushes them off ([M1 subheaders](https://m1.material.io/components/subheaders.html), [M2 lists](https://m2.material.io/go/design-lists)). The Compose lists guide builds exactly this, a contacts list with `stickyHeader` per initial, and asks for `key` and `contentType` on every item ([Compose lists](https://developer.android.com/develop/ui/compose/lists)). I tried twice to read the M3 lists pages (m3.material.io and the material-components-android docs) and got back nothing but a title or a 404, so no rule here is credited to M3.
- **One sort and filter header per screen, remembered per screen.** Symfonium puts sort, filter and display mode in "a small header above the content lists", and each screen keeps its own sort ([Symfonium docs](https://docs.symfonium.app/wiki/other/sort-filter-and-configure-display-modes-in-library-views/)). YouTube Music has chips (Playlists, Songs, Albums, Artists), a sort drop-down under them (Recent activity, Recently added, Recently played, A–Z), a Downloaded filter, and remembers the choice ([9to5Google](https://9to5google.com/2023/01/10/youtube-music-library-redesign-final/), [Android Central](https://androidcentral.com/apps-software/youtube-music-library-tab-redesign), [YTM sorting 2026](https://www.androidauthority.com/youtube-music-playlist-sorting-options-3670873/)). Spotify's library has filter chips including "Downloaded", plus a sort menu with Recents, Recently added, Alphabetical and Creator ([Spotify community](https://community.spotify.com/t5/Your-Library/Desktop-New-Your-Library-sidebar/m-p/5575587)). Apple Music offers Sort by title, artist, recently added and recently played, with Downloaded as a filter ([iPhone guide](https://support.apple.com/en-asia/guide/iphone/iphbddea0e5e/16.0/ios)).
- **Users complain about two failures in particular.** Apple's iOS app has no "albums grouped by artist" mode, and albums with odd sort names land under "#" ([Apple discussions](https://discussions.apple.com/thread/256125775)). Poweramp's A–Z bar jumps to the wrong letter ([forum 1](https://forum.powerampapp.com/topic/21470-a-z-bar-not-jumping-to-correct-position-in-albums-view), [forum 2](https://forum.powerampapp.com/topic/23556-library-fast-scroll-letters-dont-work/)). So the index positions must come from the same data as the rows, and the bucket rules must be tested.
- **Drill-down, levels, and extra taps.** Poweramp sorts each level separately (artists by name, their albums by year). Plexamp users complain about the extra taps ([Plex forum](https://forums.plex.tv/t/feature-request-android-browse-library/575438)).
- **Selection.** Long-press enters selection, and taps then toggle items. The app bar becomes a contextual bar that shows the count and the actions, in a different colour so the change of mode is visible ([M2 selection](https://m2.material.io/design/interaction/selection), [top app bar](https://material.io/components/app-bars-top), [Compose dynamic app bar](https://developer.android.com/develop/ui/compose/components/app-bars-dynamic)).
- **Fast scroller accessibility.** None of the sources covers this, so the decision below is my own.

## Decisions

**Shared parts** (`ui/components/Grouping.kt`, logic in `library/`):
- **`GroupHeader`** is a sticky heading (`heading()` semantics) on an opaque page background, so rows never show through. Each look draws it its own way: Terminal `# a`, Retro a serif letter with a 2 dp rule, Flat small capitals, Soft a large rounded title, Expressive an emphasized title. All of these come from look tokens.
- **`GroupRow`** is a collapsed artist or album: artwork, name, then "12 songs · 84 MB". Tapping it expands or collapses the group in place. I chose this over a drill-down page, which would need new routes in the shell.
- **`SortGroupBar`** holds menu buttons such as "Group: Artists" and "Sort: Name". Each look draws them its own way, and Terminal shows `[ group: artists ]`. The choices are remembered per screen in `LibraryPrefs` (SharedPreferences, with pure parsers).
- **`IndexScroller`** is a strip on the right edge, 48 dp wide to touch, listing the letters present. Tapping or dragging jumps to that letter, and a bubble shows the letter under the finger. TalkBack reads it as one adjustable control: the state is the current letter, and swiping up or down moves one letter. It appears only for an alphabetical order, at least 40 rows and at least 5 letters. The list's end padding leaves room for it, so it never sits over a row's overflow button.
- **`SelectionBar`** replaces the sort bar while anything is selected. It holds close, "3 selected" (a polite live region), Select all and Remove. Back leaves selection.
- **Letter rules.** A letter is the first letter of the sort key, upper-cased in the app's locale. A leading "The " is ignored, since it is the one English article that is safe to skip. "A" and "An" are not ignored ("A‑ha"), and no other language's articles are. Latin accents fold (É → E). Cyrillic letters are kept as they are, because Й, Ї, Є and Ґ are letters of their own. Digits and symbols go under "#", which comes last. Scripts never interleave: the app language's script comes first (Cyrillic in Ukrainian, Latin otherwise), then the other script, then "#". Cyrillic is always compared with the Ukrainian collator, because an English collator puts "Іній" after "Яр". Equal keys fall back to the id, so the order is stable.
- **Buckets.** Songs with no album go into "No album", which comes last. A blank artist, "Various Artists" or "Різні виконавці" goes into "Various artists", also last. Recently added uses Today, Yesterday, This week, This month, then one bucket per month ("October 2026"), with unknown dates under "Earlier".

**Library → Downloads**
- **Top of the list** (it scrolls away):
  - a summary line, "133 songs · 1.4 GB";
  - a search field once there are more than 30 songs, which matches title, artist and album and ignores case and accents;
  - the bar: Group, Sort, and a Show menu (All / Mine / Smart), which appears only when some songs are smart downloads.
- **Group: Songs** (the default) has letter headers. Sort: Title, Artist or Size (largest first, for freeing space).
- **Group: Artists** groups by the folder artist, `albumArtist ?: artist`, because the raw artist string ("A & B") would split groups. Artists show as collapsed `GroupRow`s under sticky letter headers, which holds up both when most artists have one song and when one artist has 60. Sort: Name, Most songs or Size. Inside a group, songs are ordered by album, then track number, then title.
- **Group: Albums** works the same way, with "album, artist" on the row. Sort: Title, Artist or Size. Inside, songs go by track number.
- **Group: Recently added** has date headers, newest first, and no sort.
- **Downloaded albums and playlists** keep their own collapsible group at the top in every mode, because that group is what the user asked for by name.
- **Rows.** The delete icon on every row is gone, replaced by one ⋮ overflow menu: Select, Remove download (or a note that the song is kept for its album or playlist), and Try again when the download failed. Smart downloads (`smart && !manual`) say "Smart" in the supporting line. Tapping a song plays the list as shown, from that song. Long-press selects it. Long-press on an artist or album row selects the whole group.
- **Bulk Remove** asks first, then removes the selected songs. Songs that belong to a downloaded album or playlist are skipped and counted: "2 kept for their album or playlist".

**Library → Liked songs.** A Sort menu offers Recently liked (YouTube's order, the default), Title, Artist and Album. Every order except Recently liked has sticky headers (letter, artist or album) and the index scroller once the list is long. Play and Shuffle still play the whole liked list. The list is the first page that `likedSongs()` returns (one browse, no continuation), so the sorts cover what is loaded. That limit is noted in the devlog, and `ytm/` is not touched. Row keys are the video id plus an occurrence number, not the position, so reordering keeps them stable.

**Library → Playlists.** Sort only: Default (YouTube's order) or Title. No grouping: there are usually a few dozen cards, they hold only a free-form subtitle, and a grid does not take headers well.

**Not grouped:**
- **Album pages.** YouTube sends no disc numbers, so there is nothing to separate by.
- **Playlist and artist pages.** Their action headers work, and a sticky action header would change `PageScreens.kt` just for the sake of it.
- **Search and the queue.** Out of scope, and none of the shared parts make them free.

## Smoothness and accessibility

- The rows are built off the main thread, in pure code: headers, items and a map from each letter to its row index. Composition only reads that result.
- Every item has a key and a `contentType`.
- Each song row still reads only its own status flow, and selection is read per row through `derivedStateOf`.
- Headers are headings, so TalkBack can move between them.
- Selection changes are announced.
- Every control is at least 48 dp.
