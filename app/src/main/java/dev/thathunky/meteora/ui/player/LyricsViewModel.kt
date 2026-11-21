package dev.thathunky.meteora.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.domain.Lyrics
import dev.thathunky.meteora.domain.Track
import dev.thathunky.meteora.lyrics.LyricsRepository
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LyricsUi {
    data object Loading : LyricsUi
    data class Found(val lyrics: Lyrics) : LyricsUi
    data object None : LyricsUi
    data object Failed : LyricsUi
}

/** Lyrics for the track on the lyrics page; asked when the page opens or its track changes. */
@HiltViewModel
class LyricsViewModel @Inject constructor(private val repository: LyricsRepository) : ViewModel() {

    private val _ui = MutableStateFlow<Pair<String, LyricsUi>?>(null)
    /** The videoId the answer is for, and the answer. */
    val ui: StateFlow<Pair<String, LyricsUi>?> = _ui.asStateFlow()

    private var job: Job? = null
    private var lastKey: String? = null

    /** Asks again only for another track, after a failure, or when the LRCLIB setting changed. */
    fun load(track: Track, force: Boolean = false) {
        val key = repository.answerKey(track)
        if (!force && key == lastKey && _ui.value?.second != LyricsUi.Failed) return
        lastKey = key
        job?.cancel()
        // Asking again for the same track (the setting or the length changed): keep what is shown meanwhile.
        if (_ui.value?.first != track.videoId || _ui.value?.second == LyricsUi.Failed) _ui.value = track.videoId to LyricsUi.Loading
        job = viewModelScope.launch {
            val result = try {
                repository.lyrics(track)?.takeIf { it.lines.isNotEmpty() }?.let(LyricsUi::Found) ?: LyricsUi.None
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LyricsUi.Failed
            }
            _ui.value = track.videoId to result
        }
    }
}
