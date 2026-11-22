package dev.thathunky.meteora.filter

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One artist in a MusicBrainz search answer, with only what the match needs. */
data class MbCandidate(val name: String, val aliases: List<String>, val score: Int, val country: String?)

/**
 * Reading MusicBrainz `/ws/2/artist?query=…&fmt=json` (shape seen 2026-11-22, anonymous, from the
 * PC; fixtures in `test/resources/fixtures/musicbrainz`). An answer counts only for a candidate
 * scored [MIN_SCORE] or more whose name, or one of its aliases, equals the asked name ignoring
 * case: YouTube Music often shows the Latin alias ("Okean Elzy" for "Океан Ельзи"). Several such
 * candidates with different countries count as unknown.
 */
object MusicBrainzMatch {
    const val MIN_SCORE = 95
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<MbCandidate> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val artists = root["artists"] as? JsonArray ?: return emptyList()
        return artists.mapNotNull { e ->
            val a = e as? JsonObject ?: return@mapNotNull null
            val name = a.string("name") ?: return@mapNotNull null
            MbCandidate(
                name = name,
                aliases = (a["aliases"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("name") },
                score = (a["score"] as? JsonPrimitive)?.intOrNull ?: 0,
                country = a.string("country")?.uppercase()?.takeIf { it.length == 2 },
            )
        }
    }

    /** The country for [name], or null when unknown (no good match, no country, or matches that disagree). */
    fun country(candidates: List<MbCandidate>, name: String): String? {
        val n = name.trim()
        val hits = candidates.filter { c ->
            c.score >= MIN_SCORE && (c.name.trim().equals(n, ignoreCase = true) || c.aliases.any { it.trim().equals(n, ignoreCase = true) })
        }
        if (hits.isEmpty()) return null
        val countries = hits.map { it.country }.distinct()
        return countries.singleOrNull()
    }

    /** A quoted Lucene phrase; every special character escaped (AC/DC, "!!!", quotes). */
    fun query(name: String): String {
        val specials = "+-&|!(){}[]^\"~*?:\\/"
        val escaped = buildString { name.trim().forEach { ch -> if (ch in specials) append('\\'); append(ch) } }
        return "\"$escaped\""
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * Anonymous MusicBrainz search. Sends the artist's name and nothing else: no cookies, no account
 * headers, the User-Agent `Meteora/<version> (Android music player)`. A failed or refused answer
 * (MusicBrainz answers 503 when busy) throws: it is "not answered", never "unknown".
 */
class MusicBrainzClient(private val http: HttpClient, private val userAgent: String) {
    suspend fun country(name: String): String? {
        val res = http.get(URL) {
            parameter("query", MusicBrainzMatch.query(name))
            parameter("fmt", "json")
            parameter("limit", 5)
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "application/json")
        }
        if (!res.status.isSuccess()) throw IOException("MusicBrainz: HTTP ${res.status.value}")
        return MusicBrainzMatch.country(MusicBrainzMatch.parse(res.bodyAsText()), name)
    }

    companion object {
        const val URL = "https://musicbrainz.org/ws/2/artist"
    }
}
