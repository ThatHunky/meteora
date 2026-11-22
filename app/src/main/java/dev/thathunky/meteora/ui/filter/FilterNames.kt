package dev.thathunky.meteora.ui.filter

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.thathunky.meteora.R
import dev.thathunky.meteora.filter.Decision
import dev.thathunky.meteora.filter.Level
import dev.thathunky.meteora.filter.Script
import dev.thathunky.meteora.filter.Verdict
import java.util.Locale

/** The name of each [dev.thathunky.meteora.filter.LanguageGuess.RULES] id, in English and Ukrainian. */
internal val LANGUAGE_NAMES: Map<String, Int> = mapOf(
    "ru" to R.string.filters_lang_ru,
    "uk" to R.string.filters_lang_uk,
    "be" to R.string.filters_lang_be,
    "bg" to R.string.filters_lang_bg,
    "sr" to R.string.filters_lang_sr,
    "kk" to R.string.filters_lang_kk,
    "el" to R.string.filters_lang_el,
    "ar" to R.string.filters_lang_ar,
    "he" to R.string.filters_lang_he,
    "hi" to R.string.filters_lang_hi,
    "zh" to R.string.filters_lang_zh,
    "ja" to R.string.filters_lang_ja,
    "ko" to R.string.filters_lang_ko,
    "th" to R.string.filters_lang_th,
    "tr" to R.string.filters_lang_tr,
    "pl" to R.string.filters_lang_pl,
    "de" to R.string.filters_lang_de,
    "fr" to R.string.filters_lang_fr,
    "es" to R.string.filters_lang_es,
    "pt" to R.string.filters_lang_pt,
)

@StringRes
internal fun scriptName(s: Script): Int = when (s) {
    Script.Cyrillic -> R.string.filters_script_cyrillic
    Script.Latin -> R.string.filters_script_latin
    Script.Greek -> R.string.filters_script_greek
    Script.Arabic -> R.string.filters_script_arabic
    Script.Hebrew -> R.string.filters_script_hebrew
    Script.Devanagari -> R.string.filters_script_devanagari
    Script.Han -> R.string.filters_script_han
    Script.Kana -> R.string.filters_script_kana
    Script.Hangul -> R.string.filters_script_hangul
    Script.Thai -> R.string.filters_script_thai
}

@Composable
internal fun languageName(id: String): String = LANGUAGE_NAMES[id]?.let { stringResource(it) } ?: id

/** A country's name in the app's language, from the platform's own list. */
@Composable
internal fun countryName(code: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return countryName(code, locale)
}

internal fun countryName(code: String, locale: Locale): String =
    runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(locale) }.getOrNull()?.ifBlank { null } ?: code

/** Every ISO 3166-1 country, sorted by its name in [locale]. */
internal fun allCountries(locale: Locale): List<Pair<String, String>> =
    Locale.getISOCountries().map { it to countryName(it, locale) }.sortedBy { it.second.lowercase(locale) }

/** "Hidden by the language rule for Russian", for the Test field. */
@Composable
internal fun describe(d: Decision): String {
    val v = d.verdict ?: return stringResource(R.string.filters_test_shown)
    val reason = d.reason.orEmpty()
    val rule = when (d.level) {
        Level.Artist -> stringResource(R.string.filters_rule_artist, reason)
        Level.Custom -> stringResource(R.string.filters_rule_custom, reason)
        Level.Country -> stringResource(R.string.filters_rule_country, countryName(reason))
        Level.Language -> stringResource(R.string.filters_rule_language, languageName(reason))
        Level.Script -> stringResource(R.string.filters_rule_script, runCatching { Script.valueOf(reason) }.getOrNull()?.let { stringResource(scriptName(it)) } ?: reason)
        null -> reason
    }
    return stringResource(if (v == Verdict.Hide) R.string.filters_test_hidden else R.string.filters_test_always, rule)
}
