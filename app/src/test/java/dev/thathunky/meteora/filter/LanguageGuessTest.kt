package dev.thathunky.meteora.filter

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageGuessTest {
    private fun lang(text: String) = LanguageGuess.languages(text)

    @Test fun `Ukrainian by its own letters`() {
        assertEquals(setOf("uk"), lang("Стефанія"))
        assertEquals(setOf("uk"), lang("Обійми"))
        assertEquals(setOf("uk"), lang("Їхали козаки"))
        assertEquals(setOf("uk"), lang("Океан Ельзи — Без бою, є ще"))
    }

    @Test fun `Russian by its own letters`() {
        assertEquals(setOf("ru"), lang("Выхода нет"))
        assertEquals(setOf("ru"), lang("Это здорово"))
        assertEquals(setOf("ru"), lang("Ёлка"))
    }

    @Test fun `Cyrillic with no telling letter is undecided`() {
        assertEquals(emptySet<String>(), lang("Звезда по имени Солнце"))
        assertEquals(emptySet<String>(), lang("Шум"))
        assertEquals(emptySet<String>(), lang("Луда по тебе"))
        assertEquals(setOf(Script.Cyrillic), LanguageGuess.scripts("Шум"))
    }

    @Test fun `Bulgarian and Serbian are not taken for Russian`() {
        assertEquals(setOf("bg"), lang("Българийо"))
        assertEquals(setOf("bg"), lang("Сънят ти"))
        assertEquals(setOf("sr"), lang("Ђурђевдан"))
        assertEquals(setOf("sr"), lang("Јутро"))
    }

    @Test fun `Belarusian by its short u or i with Russian-looking letters`() {
        assertEquals(setOf("be"), lang("Жоўты лісток"))
        assertEquals(setOf("be"), lang("Мой родны кут, як ты мне мілы"))
    }

    @Test fun `Kazakh by its own letters, even with i`() {
        assertEquals(setOf("kk"), lang("Қаражорға"))
        assertEquals(setOf("kk"), lang("Әлдиім"))
    }

    @Test fun `Latin transliteration is no language`() {
        assertEquals(emptySet<String>(), lang("Stefania"))
        assertEquals(emptySet<String>(), lang("Vykhoda net"))
        assertEquals(setOf(Script.Latin), LanguageGuess.scripts("Stefania (Kalush Orchestra)"))
    }

    @Test fun `whole scripts`() {
        assertEquals(setOf("el"), lang("Σ' αγαπώ"))
        assertEquals(setOf("ar"), lang("حبيبي"))
        assertEquals(setOf("he"), lang("שלום"))
        assertEquals(setOf("hi"), lang("तुम ही हो"))
        assertEquals(setOf("ko"), lang("봄날"))
        assertEquals(setOf("th"), lang("ขอใจเธอแลกเบอร์โทร"))
    }

    @Test fun `Persian letters rule Arabic out`() = assertEquals(emptySet<String>(), lang("زندگی"))

    @Test fun `Han alone is Chinese, with kana Japanese`() {
        assertEquals(setOf("zh"), lang("月亮代表我的心"))
        assertEquals(setOf("ja"), lang("残酷な天使のテーゼ"))
        assertEquals(setOf(Script.Han, Script.Kana), LanguageGuess.scripts("残酷な天使のテーゼ"))
    }

    @Test fun `Latin languages only with a telling letter`() {
        assertEquals(setOf("tr"), lang("Şımarık"))
        assertEquals(setOf("tr"), lang("İstanbul"))
        assertEquals(setOf("pl"), lang("Przeżyj to sam"))
        assertEquals(setOf("de"), lang("Über den Wolken"))
        assertEquals(setOf("fr"), lang("Ça plane pour moi"))
        assertEquals(setOf("es"), lang("Corazón"))
        assertEquals(setOf("es"), lang("Niña"))
        assertEquals(setOf("pt"), lang("Coração"))
        assertEquals(emptySet<String>(), lang("Du hast"))
        assertEquals(emptySet<String>(), lang("Je t'aime moi non plus"))
        // Italian é: none of the listed languages.
        assertEquals(emptySet<String>(), lang("Perché"))
    }

    @Test fun `metal umlauts read as German, a known limit`() = assertEquals(setOf("de"), lang("Motörhead"))

    @Test fun `decomposed letters count`() {
        val decomposed = Normalizer.normalize("Їхали", Normalizer.Form.NFD)
        assertEquals(setOf("uk"), lang(decomposed))
    }

    @Test fun `digits and punctuation have no script`() {
        assertEquals(emptySet<Script>(), LanguageGuess.scripts("1999 — !!!"))
        assertEquals(emptySet<String>(), lang("1999"))
    }

    @Test fun `every rule has a unique id`() =
        assertEquals(LanguageGuess.RULES.size, LanguageGuess.LANGUAGE_IDS.toSet().size)
}
