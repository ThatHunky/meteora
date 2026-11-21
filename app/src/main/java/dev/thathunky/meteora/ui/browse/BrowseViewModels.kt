package dev.thathunky.meteora.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.data.MusicActions
import dev.thathunky.meteora.ytm.ArtistPage
import dev.thathunky.meteora.ytm.CollectionPage
import dev.thathunky.meteora.ytm.MusicItem
import dev.thathunky.meteora.ytm.SearchResults
import dev.thathunky.meteora.ytm.YtmClient
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val error: Throwable) : Load<Nothing>
}

/** Shared item actions for every browse screen. */
@HiltViewModel
class ActionsViewModel @Inject constructor(val actions: MusicActions) : ViewModel()

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(private val ytm: YtmClient) : ViewModel() {
    val query = MutableStateFlow("")
    private val _filter = MutableStateFlow(YtmClient.SearchFilter.All)
    val filter: StateFlow<YtmClient.SearchFilter> = _filter.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _results = MutableStateFlow<Load<SearchResults>?>(null)
    val results: StateFlow<Load<SearchResults>?> = _results.asStateFlow()

    private var searchJob: Job? = null

    /** The query whose results are showing. It gets no suggestions: the list would cover the results. */
    private var submitted: String? = null
    val submittedQuery: String? get() = submitted

    init {
        viewModelScope.launch {
            query.debounce(200).distinctUntilChanged().collectLatest { q ->
                if (q.length < 2 || q == submitted) {
                    _suggestions.value = emptyList()
                    return@collectLatest
                }
                val found = runCatching { ytm.suggestions(q) }.getOrDefault(emptyList())
                // The text may have changed, or been submitted, while the request was out.
                if (query.value == q && q != submitted) _suggestions.value = found
            }
        }
    }

    fun submit(q: String = query.value) {
        if (q.isBlank()) return
        submitted = q
        query.value = q
        _suggestions.value = emptyList()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _results.value = Load.Loading
            _results.value = runCatching { ytm.search(q, _filter.value) }.fold({ Load.Ready(it) }, { Load.Failed(it) })
        }
    }

    fun setFilter(f: YtmClient.SearchFilter) {
        _filter.value = f
        // The query whose results are showing, not whatever has been typed since.
        if (_results.value != null) submitted?.let { submit(it) }
    }

    /** From the failed state: the same query and filter again. */
    fun retry() {
        submitted?.let { submit(it) }
    }
}

@HiltViewModel
class CollectionViewModel @Inject constructor(private val ytm: YtmClient) : ViewModel() {
    private val _page = MutableStateFlow<Load<CollectionPage>>(Load.Loading)
    val page: StateFlow<Load<CollectionPage>> = _page.asStateFlow()
    private var loadedId: String? = null

    fun load(id: String, isAlbum: Boolean, force: Boolean = false) {
        if (loadedId == id && !force) return
        loadedId = id
        viewModelScope.launch {
            _page.value = Load.Loading
            _page.value = runCatching { if (isAlbum) ytm.album(id) else ytm.playlist(id) }
                .fold({ Load.Ready(it) }, { Load.Failed(it) })
        }
    }
}

@HiltViewModel
class ArtistViewModel @Inject constructor(private val ytm: YtmClient) : ViewModel() {
    private val _page = MutableStateFlow<Load<ArtistPage>>(Load.Loading)
    val page: StateFlow<Load<ArtistPage>> = _page.asStateFlow()
    private var loadedId: String? = null

    fun load(id: String, force: Boolean = false) {
        if (loadedId == id && !force) return
        loadedId = id
        viewModelScope.launch {
            _page.value = Load.Loading
            _page.value = runCatching { ytm.artist(id) }.fold({ Load.Ready(it) }, { Load.Failed(it) })
        }
    }
}

/** What a tap on an item does: songs play on the PC, collections open their page. */
fun interface Opener {
    fun open(item: MusicItem)
}
