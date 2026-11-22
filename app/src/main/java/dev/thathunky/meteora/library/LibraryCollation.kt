package dev.thathunky.meteora.library

import java.text.CollationKey
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * How library lists sort and which index letter a name goes under. Pure, so the rules are tested
 * on the JVM (whose Collator is not Android's ICU one; the order between scripts is set here,
 * not left to either).
 *
 * - Scripts never interleave: the app language's script first (Cyrillic for Ukrainian and its
 *   neighbours, Latin otherwise), then the other one, then "#" (digits, symbols, anything else).
 * - Cyrillic is always compared with a Cyrillic collator: an English one puts "Іній" after "Яр".
 * - A leading English "The " is ignored. Not "A"/"An" ("A-ha"), not other languages' articles.
 * - Latin letters fold their accents for the index (É under E); Cyrillic letters stay themselves
 *   (Й, Ї, Є, Ґ are letters of their own).
 */
class LibraryCollation(val locale: Locale) {
    private val cyrillicFirst = locale.language in CYRILLIC_LANGUAGES
    private val latin: Collator = collator(if (cyrillicFirst) Locale.ENGLISH else locale)
    private val cyrillic: Collator = collator(if (cyrillicFirst) locale else Locale.forLanguageTag("uk"))

    private fun collator(l: Locale) = Collator.getInstance(l).apply { strength = Collator.SECONDARY }

    /** A name ready to compare: rank of its script, its index letter, then the text itself. */
    class Key internal constructor(
        val rank: Int,
        val letter: String,
        private val letterKey: CollationKey,
        private val textKey: CollationKey,
    ) : Comparable<Key> {
        override fun compareTo(other: Key): Int {
            if (rank != other.rank) return rank.compareTo(other.rank)
            if (letter != other.letter) return letterKey.compareTo(other.letterKey).takeIf { it != 0 } ?: letter.compareTo(other.letter)
            return textKey.compareTo(other.textKey)
        }
    }

    fun key(text: String): Key {
        val s = sortText(text)
        val letter = indexLetter(s)
        val script = scriptOf(letter)
        val rank = when (script) {
            Script.Other -> 2
            Script.Cyrillic -> if (cyrillicFirst) 0 else 1
            Script.Latin -> if (cyrillicFirst) 1 else 0
        }
        val c = if (script == Script.Cyrillic) cyrillic else latin
        return Key(rank, letter, c.getCollationKey(letter), c.getCollationKey(s))
    }

    /** The text that is compared: trimmed, without a leading English "The ". */
    fun sortText(text: String): String {
        val t = text.trim()
        if (t.length > 4 && t.regionMatches(0, "The ", 0, 4, ignoreCase = true)) {
            val rest = t.substring(4).trimStart()
            if (rest.isNotEmpty()) return rest
        }
        return t
    }

    /** The index letter of [sortText]-ed text: an upper-case letter, or "#". */
    fun indexLetter(sorted: String): String {
        // Leading quotes and brackets do not count: "'Til Tuesday" goes under T.
        val i = sorted.indexOfFirst { it.isLetterOrDigit() }
        if (i < 0) return OTHER
        val cp = sorted.codePointAt(i)
        if (!Character.isLetter(cp)) return OTHER
        return when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.LATIN -> {
                val base = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
                base.uppercase(locale).take(1).ifEmpty { OTHER }
            }
            Character.UnicodeScript.CYRILLIC -> String(Character.toChars(cp)).uppercase(locale)
            else -> OTHER
        }
    }

    private enum class Script { Latin, Cyrillic, Other }

    private fun scriptOf(letter: String): Script = when {
        letter == OTHER -> Script.Other
        Character.UnicodeScript.of(letter.codePointAt(0)) == Character.UnicodeScript.CYRILLIC -> Script.Cyrillic
        else -> Script.Latin
    }

    companion object {
        const val OTHER = "#"
        private val CYRILLIC_LANGUAGES = setOf("uk", "ru", "be", "bg", "mk", "sr", "kk", "ky", "tg", "mn")

        /** For search: lower case, accents and case folded, so "beyonce" finds "Beyoncé". */
        fun fold(text: String, locale: Locale): String =
            Normalizer.normalize(text.lowercase(locale), Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

        /** Artist names that mean "no one in particular". */
        fun isVarious(artist: String?): Boolean {
            val a = artist?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return a.isEmpty() || a in VARIOUS
        }

        private val VARIOUS = setOf("various artists", "various", "va", "різні виконавці", "разные исполнители", "unknown artist")
    }
}
