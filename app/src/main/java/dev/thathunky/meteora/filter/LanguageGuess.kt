package dev.thathunky.meteora.filter

import java.text.Normalizer
import java.util.Locale

/** The writing systems the filter tells apart. Anything else (digits, punctuation, other scripts) is ignored. */
enum class Script { Cyrillic, Latin, Greek, Arabic, Hebrew, Devanagari, Han, Kana, Hangul, Thai }

/**
 * One detectable language. Text is taken as this language when it contains [script], at least one
 * of [signals] (each a group of letters that must all be present; none means the script alone is
 * enough), none of [excludes], and no letter of [excludeScripts]. Adding a language is adding one
 * of these to [LanguageGuess.RULES]; the filter has no code of its own for any language.
 */
data class LanguageRule(
    val id: String,
    val script: Script,
    val signals: List<String> = emptyList(),
    val excludes: String = "",
    val excludeScripts: Set<Script> = emptySet(),
)

/**
 * A guess from letters alone: YouTube Music says nothing about a track's language. It is coarse
 * by nature: a Russian artist with an English title passes, and Cyrillic with no telling letter
 * is left to the script rules.
 */
object LanguageGuess {

    private const val CYRILLIC_OTHERS_UK = "ыэёъўәғқңөұүһђјљњћџ"
    private const val KAZAKH = "әғқңөұүһ"
    private const val SERBIAN = "ђјљњћџ"
    private const val MACEDONIAN = "ѓќѕ"

    val RULES: List<LanguageRule> = listOf(
        // Cyrillic. The plan's "telling letters" overlap (ъ is everyday Bulgarian; ы э ё are also
        // Belarusian, and Kazakh), so each rule also says what rules it out.
        LanguageRule("uk", Script.Cyrillic, listOf("ї", "є", "ґ", "і"), excludes = CYRILLIC_OTHERS_UK),
        LanguageRule("be", Script.Cyrillic, listOf("ў", "іы", "іэ", "іё"), excludes = "їєґъ$KAZAKH$SERBIAN"),
        LanguageRule("ru", Script.Cyrillic, listOf("ы", "э", "ё"), excludes = "іїєґў$KAZAKH$SERBIAN"),
        LanguageRule("bg", Script.Cyrillic, listOf("ъ", "ѝ"), excludes = "ыэёіїєґў$KAZAKH$SERBIAN"),
        LanguageRule("sr", Script.Cyrillic, SERBIAN.map { it.toString() }, excludes = "$MACEDONIAN" + "ыэёъіїєґў"),
        LanguageRule("kk", Script.Cyrillic, KAZAKH.map { it.toString() }, excludes = "їєґў$SERBIAN"),
        // Whole scripts.
        LanguageRule("el", Script.Greek),
        // Persian and Urdu letters rule Arabic out.
        LanguageRule("ar", Script.Arabic, excludes = "پچژگکیٹڈڑںے"),
        LanguageRule("he", Script.Hebrew),
        LanguageRule("hi", Script.Devanagari),
        LanguageRule("zh", Script.Han, excludeScripts = setOf(Script.Kana, Script.Hangul)),
        LanguageRule("ja", Script.Kana),
        LanguageRule("ko", Script.Hangul),
        LanguageRule("th", Script.Thai),
        // Latin: only with a telling letter. Plain ASCII is never a language.
        LanguageRule("tr", Script.Latin, listOf("ğ", "ı", "İ", "ş"), excludes = "əäßãõñ"),
        LanguageRule("pl", Script.Latin, listOf("ą", "ę", "ł", "ś", "ź", "ż", "ń"), excludes = "čřšěůđėįųū"),
        LanguageRule("de", Script.Latin, listOf("ß", "ä", "ö", "ü"), excludes = "ğışåõőűøæáéíóúàèçñã"),
        LanguageRule("fr", Script.Latin, listOf("œ", "ÿ", "ë", "î", "ï", "û", "è", "ù", "ç"), excludes = "ãõñáíóúìòğış"),
        LanguageRule("es", Script.Latin, listOf("ñ", "¿", "¡", "á", "í", "ó", "ú"), excludes = "ãõçêâôàèùœëîïûąęłśźżńěřůčšžőűåäöüß"),
        LanguageRule("pt", Script.Latin, listOf("ã", "õ"), excludes = "ñăđơư"),
    )

    val LANGUAGE_IDS: List<String> = RULES.map { it.id }

    fun script(codePoint: Int): Script? {
        if (!Character.isLetter(codePoint)) return null
        return when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.CYRILLIC -> Script.Cyrillic
            Character.UnicodeScript.LATIN -> Script.Latin
            Character.UnicodeScript.GREEK -> Script.Greek
            Character.UnicodeScript.ARABIC -> Script.Arabic
            Character.UnicodeScript.HEBREW -> Script.Hebrew
            Character.UnicodeScript.DEVANAGARI -> Script.Devanagari
            Character.UnicodeScript.HAN -> Script.Han
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Script.Kana
            Character.UnicodeScript.HANGUL -> Script.Hangul
            Character.UnicodeScript.THAI -> Script.Thai
            else -> null
        }
    }

    /** NFC, so a letter sent as base + combining mark (й, ї, ё) is one character. */
    private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC)

    fun scripts(text: String): Set<Script> {
        val n = normalize(text)
        val out = HashSet<Script>()
        var i = 0
        while (i < n.length) {
            val cp = n.codePointAt(i)
            script(cp)?.let(out::add)
            i += Character.charCount(cp)
        }
        return out
    }

    /**
     * The languages [text] looks like, by [RULES]. When two languages of one script both match
     * (which the exclusions should prevent), neither counts: the text is undecided and is left
     * to the script rules.
     */
    fun languages(text: String): Set<String> {
        val n = normalize(text)
        val scripts = scripts(n)
        if (scripts.isEmpty()) return emptySet()
        // The original letters too: "İ" lowercases to i + a combining dot.
        val letters = (n + n.lowercase(Locale.ROOT)).toSet()
        val matched = RULES.filter { r ->
            r.script in scripts &&
                r.excludeScripts.none { it in scripts } &&
                r.excludes.none { it in letters } &&
                (r.signals.isEmpty() || r.signals.any { group -> group.all { it in letters } })
        }
        return matched.groupBy { it.script }.values.filter { it.size == 1 }.flatten().map { it.id }.toSet()
    }
}
