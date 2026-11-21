package dev.thathunky.meteora.lyrics

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.thathunky.meteora.domain.Lyrics
import dev.thathunky.meteora.domain.Track
import dev.thathunky.meteora.ytm.YtmClient
import javax.inject.Inject
import javax.inject.Singleton

/** One place lyrics can come from. Null: it has none for the track. Throws when it cannot be asked. */
fun interface LyricsSource {
    suspend fun find(track: Track): Lyrics?
}

/** YouTube's own lyrics: the lyrics id from `next`, then the lyrics browse. */
class YouTubeLyrics @Inject constructor(private val ytm: YtmClient) : LyricsSource {
    override suspend fun find(track: Track): Lyrics? = ytm.lyricsId(track.videoId)?.let { ytm.lyrics(it) }
}

/**
 * Lyrics for a track, asked only when the lyrics page wants them. YouTube first. Answers, "none"
 * included, are kept for the last [CACHE_SIZE] tracks so a track is not asked twice; a failure is
 * not kept, so an offline open is tried again next time.
 */
class LyricsRepository(private val youtube: LyricsSource) {

    private class Entry(val lyrics: Lyrics?)

    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > CACHE_SIZE
    }

    /** Null: the track has no lyrics. Throws when they could not be fetched. */
    suspend fun lyrics(track: Track): Lyrics? {
        val key = track.videoId
        synchronized(cache) { cache[key] }?.let { return it.lyrics }
        val found = youtube.find(track)
        synchronized(cache) { cache[key] = Entry(found) }
        return found
    }

    companion object {
        const val CACHE_SIZE = 30
    }
}

/** One repository for the app, so the cache outlives a lyrics page. */
@Module
@InstallIn(SingletonComponent::class)
object LyricsModule {
    @Provides @Singleton
    fun lyricsRepository(youtube: YouTubeLyrics): LyricsRepository = LyricsRepository(youtube)
}
