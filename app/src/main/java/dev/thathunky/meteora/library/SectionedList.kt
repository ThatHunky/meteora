package dev.thathunky.meteora.library

/**
 * A plain list (Liked songs, playlists) sorted and cut into sections. Generic, so it is tested
 * with plain data; [T] is the screen's own item.
 */
object SectionedList {
    sealed interface Row<out T> {
        val key: String
        data class Header(val label: Label) : Row<Nothing> { override val key get() = "h:" + label.id }
        data class Item<T>(val item: T, override val key: String) : Row<T>
    }

    data class View<T>(val rows: List<Row<T>>, val index: LetterIndex, val items: List<T>)

    /**
     * [Default] keeps the given order with no headers. Title, Artist and Album sort by that name
     * under letter headers; a missing album or a "various" artist goes in its own section last.
     * Ties keep the given order, so the result is stable. Keys are [id] plus an occurrence number:
     * a list can hold the same song twice, and a position would change with the sort.
     */
    fun <T> build(
        items: List<T>,
        sort: LibrarySort,
        collation: LibraryCollation,
        id: (T) -> String,
        title: (T) -> String,
        artist: (T) -> String? = { null },
        album: (T) -> String? = { null },
    ): View<T> {
        val seen = HashMap<String, Int>()
        val keyed = items.map { t ->
            val base = id(t)
            val n = seen.merge(base, 1, Int::plus)!!
            t to (if (n == 1) base else "$base#$n")
        }
        if (sort == LibrarySort.Default || sort !in setOf(LibrarySort.Title, LibrarySort.Artist, LibrarySort.Album)) {
            return View(keyed.map { Row.Item(it.first, it.second) }, LetterIndex.Empty, items)
        }
        data class E<T>(val t: T, val key: String, val pos: Int, val name: LibraryCollation.Key?, val title: LibraryCollation.Key, val tail: Label?)
        val entries = keyed.mapIndexed { pos, (t, key) ->
            val titleKey = collation.key(title(t))
            when (sort) {
                LibrarySort.Artist -> {
                    val a = artist(t)
                    if (LibraryCollation.isVarious(a)) E(t, key, pos, null, titleKey, Label.Various) else E(t, key, pos, collation.key(a!!), titleKey, null)
                }
                LibrarySort.Album -> {
                    val a = album(t)?.takeIf { it.isNotBlank() }
                    if (a == null) E(t, key, pos, null, titleKey, Label.NoAlbum) else E(t, key, pos, collation.key(a), titleKey, null)
                }
                else -> E(t, key, pos, titleKey, titleKey, null)
            }
        }
        val named = entries.filter { it.tail == null }
            .sortedWith(compareBy<E<T>> { it.name!! }.thenBy { it.title }.thenBy { it.pos })
        val tails = entries.filter { it.tail != null }
            .sortedWith(compareBy<E<T>> { it.tail!!.id }.thenBy { it.title }.thenBy { it.pos })
        val rows = ArrayList<Row<T>>(entries.size + 40)
        val headers = ArrayList<Pair<Label, Int>>()
        var last: Label? = null
        for (e in named + tails) {
            val label = e.tail ?: Label.Letter(e.name!!.letter)
            if (label != last) {
                headers += label to rows.size
                rows += Row.Header(label)
                last = label
            }
            rows += Row.Item(e.t, e.key)
        }
        return View(rows, LetterIndex.of(headers), (named + tails).map { it.t })
    }
}
