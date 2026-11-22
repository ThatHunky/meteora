package dev.thathunky.meteora.downloads

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** One song's row in the listening log (kept on the phone only). */
data class Listen(
    val videoId: String,
    val plays: Int,
    val skips: Int,
    val lastPlayedMs: Long,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumId: String? = null,
    val artworkUrl: String? = null,
    val durationSec: Double = 0.0,
)

/** What leaving a track counts as. */
enum class ListenOutcome { Play, Skip, Nothing }

object ListenRules {
    const val PLAY_CAP_MS = 4 * 60_000L
    const val SKIP_WITHIN_MS = 30_000L

    /**
     * Left at [positionMs] of a track [durationMs] long. A play: past half its length or 4 minutes.
     * A skip: left in the first 30 seconds by Next ([byUser]). Anything else counts as nothing.
     */
    fun outcome(positionMs: Long, durationMs: Long, byUser: Boolean): ListenOutcome {
        val needed = if (durationMs > 0) minOf(durationMs / 2, PLAY_CAP_MS) else PLAY_CAP_MS
        return when {
            positionMs >= needed -> ListenOutcome.Play
            byUser && positionMs < SKIP_WITHIN_MS -> ListenOutcome.Skip
            else -> ListenOutcome.Nothing
        }
    }

    /** How the player moved away from a position (Media3's discontinuity reasons, reduced to what matters here). */
    enum class Jump { Auto, Seek, Remove, Other }

    /**
     * Whether a jump leaves the track, and if so whether the user moved on to a later one (only
     * that can be a skip). Null: the track was not left (a seek inside it, a rearranged queue).
     * Reaching the end, repeat-one included, is never a skip; nor is a new queue replacing the
     * track, going back with Previous or to an earlier queue entry, or the player itself moving
     * past a track that failed to play ([skippedForError]).
     */
    fun leftByUser(jump: Jump, sameItem: Boolean, forward: Boolean, skippedForError: Boolean): Boolean? = when (jump) {
        Jump.Auto -> false
        Jump.Other -> null
        Jump.Remove -> if (sameItem) null else false
        Jump.Seek -> if (sameItem) null else forward && !skippedForError
    }

    fun apply(old: Listen?, song: Listen, outcome: ListenOutcome, nowMs: Long): Listen? {
        if (outcome == ListenOutcome.Nothing) return null
        val base = old?.copy(title = song.title, artist = song.artist, album = song.album ?: old.album, albumId = song.albumId ?: old.albumId, artworkUrl = song.artworkUrl ?: old.artworkUrl, durationSec = if (song.durationSec > 0) song.durationSec else old.durationSec)
            ?: song.copy(plays = 0, skips = 0, lastPlayedMs = 0)
        return when (outcome) {
            ListenOutcome.Play -> base.copy(plays = base.plays + 1, lastPlayedMs = nowMs)
            // A skip is not a play: it must not refresh the decay of old plays (which would raise
            // the score) nor put the song under the 14-day protection.
            else -> base.copy(skips = base.skips + 1)
        }
    }
}

/** A song that smart downloads could keep, with what it needs to be downloaded. */
data class SmartSong(val videoId: String, val title: String, val artist: String, val album: String?, val artworkUrl: String?, val durationSec: Double)

/**
 * Smart downloads' wanted list (docs/downloads-plan.md, D5): a score per song from the
 * listening log, likes and Home's "Listen again", then three shares of the size, then the
 * stability rules. All pure.
 */
object SmartRules {
    private const val DAY_MS = 24 * 60 * 60 * 1000.0
    const val HALF_LIFE_DAYS = 30.0
    const val FAVOURITES = 0.60
    const val LIKES = 0.25
    const val RECENT_LIKES = 50
    const val BETTER_BY = 1.2
    const val MAX_REPLACED = 0.2
    const val PROTECT_DAYS = 14

    fun decay(lastPlayedMs: Long, nowMs: Long): Double {
        val days = max(0.0, (nowMs - lastPlayedMs) / DAY_MS)
        return 0.5.pow(days / HALF_LIFE_DAYS)
    }

    /** Plays minus skips, both fading by half a month: never below zero. */
    fun habit(l: Listen?, nowMs: Long): Double {
        if (l == null) return 0.0
        val d = decay(l.lastPlayedMs, nowMs)
        return max(0.0, l.plays * d - 1.5 * l.skips * d)
    }

    /** +3 for a liked song, +2 more at the newest like falling to 0 after [RECENT_LIKES]. [likePosition]: 0 is the newest. */
    fun likeBonus(likePosition: Int?): Double {
        if (likePosition == null || likePosition < 0) return 0.0
        val recent = if (likePosition < RECENT_LIKES) 2.0 * (RECENT_LIKES - likePosition) / RECENT_LIKES else 0.0
        return 3.0 + recent
    }

    /** +2 at the first "Listen again" place, falling to 0 at the last. */
    fun listenAgainBonus(position: Int?, count: Int): Double {
        if (position == null || position < 0 || count <= 0) return 0.0
        if (count == 1) return 2.0
        return 2.0 * (count - 1 - position) / (count - 1)
    }

    fun score(l: Listen?, likePosition: Int?, listenAgainPosition: Int?, listenAgainCount: Int, nowMs: Long): Double =
        habit(l, nowMs) + likeBonus(likePosition) + listenAgainBonus(listenAgainPosition, listenAgainCount)

    data class Inputs(
        val size: Int,
        val listens: Map<String, Listen>,
        /** Newest first. */
        val likes: List<SmartSong>,
        val listenAgain: List<SmartSong>,
        /** The account's mix, in its order; empty when Home has none. */
        val mix: List<SmartSong>,
        /** Downloaded by hand (alone or with an album or playlist): never candidates. */
        val manual: Set<String>,
        val nowMs: Long,
    )

    data class Wanted(val songs: List<SmartSong>, val scores: Map<String, Double>)

    /** The wanted list, best first: 60 % favourites, 25 % recent likes, 15 % fresh; unfilled room goes to favourites. */
    fun wanted(i: Inputs): Wanted {
        val songs = LinkedHashMap<String, SmartSong>()
        (i.likes + i.listenAgain + i.mix).forEach { songs.putIfAbsent(it.videoId, it) }
        i.listens.values.forEach { l -> songs.putIfAbsent(l.videoId, SmartSong(l.videoId, l.title, l.artist, l.album, l.artworkUrl, l.durationSec)) }
        i.manual.forEach(songs::remove)
        val likePos = i.likes.withIndex().associate { it.value.videoId to it.index }
        val againPos = i.listenAgain.withIndex().associate { it.value.videoId to it.index }
        val scores = songs.keys.associateWith { score(i.listens[it], likePos[it], againPos[it], i.listenAgain.size, i.nowMs) }
        if (i.size <= 0) return Wanted(emptyList(), scores)

        val likesShare = (i.size * LIKES).roundToInt()
        val freshShare = if (i.mix.isEmpty()) 0 else i.size - (i.size * FAVOURITES).roundToInt() - likesShare
        val favShare = i.size - likesShare - freshShare
        // Favourites: positive scores only, best first; ties keep the order songs were seen in.
        val ranked = songs.values.filter { (scores[it.videoId] ?: 0.0) > 0 }.sortedByDescending { scores[it.videoId] }
        val picked = LinkedHashMap<String, SmartSong>()
        ranked.take(favShare).forEach { picked[it.videoId] = it }
        i.likes.filter { it.videoId in songs && it.videoId !in picked }.take(likesShare).forEach { picked[it.videoId] = it }
        val freshCandidates = i.mix.filter { it.videoId in songs && it.videoId !in picked && (i.listens[it.videoId]?.plays ?: 0) == 0 }
        fresh(freshCandidates, freshShare).forEach { picked[it.videoId] = it }
        // What a share could not fill goes to favourites.
        ranked.filter { it.videoId !in picked }.take(i.size - picked.size).forEach { picked[it.videoId] = it }
        return Wanted(picked.values.toList(), scores)
    }

    /** Up to [n] of [mix] in order, never three in a row by one artist. */
    fun fresh(mix: List<SmartSong>, n: Int): List<SmartSong> {
        val out = ArrayList<SmartSong>()
        val waiting = ArrayDeque<SmartSong>()
        fun fits(s: SmartSong) = out.size < 2 || !(out[out.size - 1].artist == s.artist && out[out.size - 2].artist == s.artist)
        for (s in mix) {
            if (out.size >= n) break
            if (fits(s)) {
                out += s
                // A song held back may fit now that another artist came between.
                while (out.size < n) {
                    val w = waiting.firstOrNull { fits(it) } ?: break
                    waiting.remove(w)
                    out += w
                }
            } else {
                waiting += s
            }
        }
        return out
    }

    data class Plan(val keep: List<String>, val add: List<SmartSong>, val remove: List<String>)

    /**
     * From the smart downloads there are ([current]) to the next set. A song that stays wanted is
     * kept; one that is not stays unless a newcomer scores at least 20 % higher, at most 20 % of
     * the set is replaced in one refresh, and nothing played in the last 14 days is removed. Free
     * room (the set is smaller than the size) is filled without counting as replacement. A set
     * larger than the size (the size was lowered) loses its lowest scores first, whatever the rules.
     */
    fun plan(current: List<String>, wanted: Wanted, size: Int, listens: Map<String, Listen>, nowMs: Long): Plan {
        val scoreOf = { id: String -> wanted.scores[id] ?: 0.0 }
        val wantedIds = wanted.songs.map { it.videoId }.toSet()
        val protectedIds = listens.values.filter { nowMs - it.lastPlayedMs < PROTECT_DAYS * DAY_MS }.map { it.videoId }.toSet()
        val keep = current.distinct().toMutableList()
        val remove = ArrayList<String>()
        // The size was lowered: the surplus goes, lowest scores first.
        if (keep.size > size) {
            val surplus = keep.sortedWith(compareBy<String> { it in wantedIds }.thenBy { scoreOf(it) }).take(keep.size - size.coerceAtLeast(0))
            keep -= surplus.toSet()
            remove += surplus
        }
        val newcomers = ArrayDeque(wanted.songs.filter { it.videoId !in keep && it.videoId !in remove })
        val add = ArrayList<SmartSong>()
        while (keep.size + add.size < size && newcomers.isNotEmpty()) add += newcomers.removeFirst()
        // 20 % of the set, but at least one song, so a small set is not frozen.
        var budget = if (size > 0) maxOf(1, (size * MAX_REPLACED).toInt()) else 0
        val victims = keep.filter { it !in wantedIds && it !in protectedIds }.sortedBy { scoreOf(it) }.toMutableList()
        // Strongest newcomers first, each against the weakest song that may go.
        for (n in newcomers.sortedByDescending { scoreOf(it.videoId) }) {
            if (budget <= 0 || victims.isEmpty()) break
            val v = victims.first()
            if (scoreOf(n.videoId) <= 0 || scoreOf(n.videoId) < BETTER_BY * scoreOf(v)) continue
            victims.removeAt(0)
            keep -= v
            remove += v
            add += n
            budget--
        }
        return Plan(keep, add, remove)
    }
}
