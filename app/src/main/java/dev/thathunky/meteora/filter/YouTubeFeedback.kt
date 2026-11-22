package dev.thathunky.meteora.filter

import dev.thathunky.meteora.ytm.ArtistRef
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.Shelf
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** What Hide artist did, for its toast. */
enum class HideOutcome {
    /** Hidden in Meteora only: the switch is off, or the item had no token to send. */
    Local,
    /** Hidden in Meteora, and YouTube answered that it processed "Don't recommend artist". */
    Told,
    /** Hidden in Meteora; the call to YouTube failed, timed out or was not processed. */
    NotTold,
}

/**
 * Hide artist and Not interested, with YouTube's feedback tokens. The local part always happens
 * first and never waits on, or depends on, the network.
 */
object YouTubeFeedback {
    const val TIMEOUT_MS = 15_000L

    /**
     * The token to send when [refs] are what Hide artist offers: only when there is exactly one
     * artist. The token names YouTube's choice of artist, which may not be the one picked from
     * several, and it cannot be taken back.
     */
    fun tokenFor(refs: List<ArtistRef>, token: String?): String? = token?.takeIf { refs.size == 1 }

    /** Hides [artist] with [hide], then, when [tellYouTube] and there is a [token], sends it. */
    suspend fun hideArtist(
        artist: ArtistRef,
        token: String?,
        tellYouTube: Boolean,
        hide: (ArtistRef) -> Unit,
        send: suspend (String) -> Boolean,
        timeoutMs: Long = TIMEOUT_MS,
    ): HideOutcome {
        hide(artist)
        if (!tellYouTube || token == null) return HideOutcome.Local
        return if (trySend(token, send, timeoutMs)) HideOutcome.Told else HideOutcome.NotTold
    }

    /** Removes the item from the screen with [dismiss], then sends its token. True when YouTube processed it. */
    suspend fun notInterested(token: String, dismiss: (String) -> Unit, send: suspend (String) -> Boolean, timeoutMs: Long = TIMEOUT_MS): Boolean {
        dismiss(token)
        return trySend(token, send, timeoutMs)
    }

    private suspend fun trySend(token: String, send: suspend (String) -> Boolean, timeoutMs: Long): Boolean = try {
        withTimeoutOrNull(timeoutMs) { send(token) } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}

/**
 * Items removed with Not interested, by their token, in memory only. A token belongs to one
 * response, so a dismissal lasts until the shelves are loaded again: if YouTube ignored it, the
 * item may come back then.
 */
@Singleton
class Dismissals @Inject constructor() {
    private val _tokens = MutableStateFlow<Set<String>>(emptySet())
    val tokens: StateFlow<Set<String>> = _tokens.asStateFlow()

    fun dismiss(token: String) = _tokens.update { it + token }
}

/**
 * [shelves] without the items whose Not interested token is in [dismissed], and without other
 * copies of the same item in this load. A shelf is dropped only when nothing is left of it.
 */
fun withoutDismissed(shelves: List<Shelf>, dismissed: Set<String>): List<Shelf> {
    if (dismissed.isEmpty()) return shelves
    val gone = shelves.flatMap { it.items }.filter { item -> item.feedback?.notInterested?.let { it in dismissed } == true }.map { it.identity() }.toSet()
    if (gone.isEmpty()) return shelves
    return shelves.mapNotNull { shelf ->
        val kept = shelf.items.filterNot { it.identity() in gone }
        when {
            kept.size == shelf.items.size -> shelf
            kept.isEmpty() -> null
            else -> shelf.copy(items = kept)
        }
    }
}

private fun MusicItem.identity(): String = when (this) {
    is MusicItem.Song -> "song:$videoId"
    is MusicItem.Album -> "album:$browseId"
    is MusicItem.Artist -> "artist:$browseId"
    is MusicItem.Playlist -> "playlist:$browseId"
}
