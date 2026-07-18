package dev.matteomac81888.echo.ui.player.more.lyrics

import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.paging.cachedIn
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.LyricsSearchClient
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.Tab
import dev.matteomac81888.echo.di.App
import dev.matteomac81888.echo.extensions.ExtensionLoader
import dev.matteomac81888.echo.extensions.ExtensionUtils.getAs
import dev.matteomac81888.echo.extensions.ExtensionUtils.getExtension
import dev.matteomac81888.echo.extensions.ExtensionUtils.isClient
import dev.matteomac81888.echo.extensions.builtin.lyrics.DefaultLyricsExtension
import dev.matteomac81888.echo.extensions.cache.Cached
import dev.matteomac81888.echo.playback.MediaItemUtils.extensionId
import dev.matteomac81888.echo.playback.MediaItemUtils.isLoaded
import dev.matteomac81888.echo.playback.MediaItemUtils.track
import dev.matteomac81888.echo.playback.PlayerState
import dev.matteomac81888.echo.ui.common.PagedSource
import dev.matteomac81888.echo.ui.extensions.list.ExtensionListViewModel
import dev.matteomac81888.echo.utils.CacheUtils.getFromCache
import dev.matteomac81888.echo.utils.CacheUtils.saveToCache
import dev.matteomac81888.echo.utils.CoroutineUtils.combineTransformLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.Eagerly
import kotlinx.coroutines.flow.SharingStarted.Companion.Lazily
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LyricsMode { UNSYNCED, SYNCED, KARAOKE }

class LyricsViewModel(
    val app: App,
    extensionLoader: ExtensionLoader,
    playerState: PlayerState,
) : ExtensionListViewModel<Extension<*>>() {

    private val refreshFlow = MutableSharedFlow<Unit>()
    override val currentSelectionFlow = MutableStateFlow<Extension<*>?>(null)

    val queryFlow = MutableStateFlow("")
    val selectedTabIndexFlow = MutableStateFlow(-1)
    val lyricsState = MutableStateFlow<State>(State.Initial)
    val lyricsModeFlow = MutableStateFlow(LyricsMode.SYNCED)

    val richSyncFlow = MutableStateFlow<Lyrics.WordByWord?>(null)
    private var currentRichSyncTrackId: String? = null
    private var richSyncJob: Job? = null

    fun fetchRichSyncIfNeeded(lyrics: Lyrics.Lyric?, track: Track?) {
        // Annulliamo sempre l'eventuale ricerca karaoke in background già in corso per il
        // brano precedente: non ha senso continuarla se l'utente è passato ad un altro brano.
        richSyncJob?.cancel()
        richSyncJob = null

        if (track == null) {
            richSyncFlow.value = null
            currentRichSyncTrackId = null
            return
        }
        currentRichSyncTrackId = track.id

        if (lyrics is Lyrics.WordByWord) {
            // Il testo caricato è già parola-per-parola: nessuna ricerca aggiuntiva necessaria.
            richSyncFlow.value = lyrics
        } else {
            richSyncFlow.value = null
            // Il testo mostrato per ora è solo "riga per riga" (o assente): continuiamo a
            // cercare in background una versione parola-per-parola da usare per il Karaoke,
            // senza bloccare quello che l'utente sta già leggendo in modalità Sincronizzata.
            scheduleBackgroundRichSyncSearch(track)
        }
        applyDefaultModeForLyrics(lyrics)
    }

    private fun scheduleBackgroundRichSyncSearch(track: Track) {
        val extension = currentSelectionFlow.value ?: return
        val trackId = track.id
        richSyncJob = viewModelScope.launch(Dispatchers.IO) {
            // La ricerca del Karaoke in background funziona solo con il motore integrato
            // (DefaultLyricsExtension), che espone searchRichSyncOnly(). Per le altre estensioni
            // di terze parti il cast fallisce silenziosamente e non viene fatto nulla di più.
            val result = extension.getAs<DefaultLyricsExtension, Lyrics.WordByWord?> {
                searchRichSyncOnly(track)
            }.getOrNull()

            if (result == null || currentRichSyncTrackId != trackId) return@launch

            richSyncFlow.value = result

            // Passiamo automaticamente alla modalità Karaoke solo se è quello che l'utente
            // ha impostato come modalità predefinita, ed è ancora in modalità Sincronizzata
            // (se nel frattempo ha scelto manualmente Unsynced o è già in Karaoke non forziamo nulla).
            val preferredMode = app.settings.getString("default_lyrics_mode", "SYNCED")
            if (preferredMode == "KARAOKE" && lyricsModeFlow.value == LyricsMode.SYNCED) {
                lyricsModeFlow.value = LyricsMode.KARAOKE
            }
        }
    }

    fun prefetchLyrics(track: Track?) {
        if (track == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val ext = currentSelectionFlow.value
            if (ext != null && ext.isClient<LyricsClient>()) {
                val mediaId = track.id
                val cached = Cached.getLyricsFeed(app, ext.id, mediaId, track, "").getOrNull()
                if (cached == null) {
                    runCatching { Cached.loadLyricsFeed(app, ext, mediaId, track, "") }
                }
            }
        }
    }

    fun setMode(mode: LyricsMode) {
        val lyrics = (lyricsState.value as? State.Loaded)?.result?.getOrNull()?.lyrics
        lyricsModeFlow.value = when {
            mode == LyricsMode.KARAOKE && !canUseKaraoke(lyrics, currentRichSyncTrackId) -> LyricsMode.SYNCED
            else -> mode
        }
    }

    fun applyDefaultModeForLyrics(lyrics: Lyrics.Lyric?) {
        val prefModeStr = app.settings.getString("default_lyrics_mode", "SYNCED")
        val preferredMode = when (prefModeStr) {
            "KARAOKE" -> LyricsMode.KARAOKE
            "UNSYNCED" -> LyricsMode.UNSYNCED
            else -> LyricsMode.SYNCED
        }

        lyricsModeFlow.value = when (preferredMode) {
            LyricsMode.KARAOKE -> if (canUseKaraoke(lyrics, currentRichSyncTrackId)) LyricsMode.KARAOKE
            else if (lyrics is Lyrics.Timed) LyricsMode.SYNCED
            else LyricsMode.UNSYNCED
            LyricsMode.SYNCED -> if (lyrics is Lyrics.Timed || lyrics is Lyrics.WordByWord) LyricsMode.SYNCED
            else LyricsMode.UNSYNCED
            LyricsMode.UNSYNCED -> LyricsMode.UNSYNCED
        }
    }

    fun canUseKaraoke(lyrics: Lyrics.Lyric?, trackId: String? = currentRichSyncTrackId): Boolean {
        if (lyrics is Lyrics.WordByWord) return true
        if (trackId == null) return false
        return richSyncFlow.value != null && currentRichSyncTrackId == trackId
    }

    private val mediaFlow = playerState.current.map { current ->
        current?.mediaItem?.takeIf { it.isLoaded }
    }.distinctUntilChanged().stateIn(viewModelScope, Eagerly, null)

    override val extensionsFlow = extensionLoader.lyrics.combine(mediaFlow) { lyrics, mediaItem ->
        val trackExtension = mediaItem?.extensionId?.let { id ->
            extensionLoader.music.getExtension(id)?.takeIf { it.isClient<LyricsClient>() }
        }
        listOfNotNull(trackExtension) + lyrics
    }.onEach { extensions ->
        currentSelectionFlow.value = null
        lyricsState.value = State.Initial
        queryFlow.value = ""
        val media = mediaFlow.value?.track?.id
        currentSelectionFlow.value = media?.let {
            val id = app.context.getFromCache<String>(media, "lyrics_ext") ?: app.settings.getString(LAST_LYRICS_KEY, null) ?: DefaultLyricsExtension.metadata.id
            extensions.find { it.id == id } ?: extensions.firstOrNull()
        }
        refreshFlow.emit(Unit)
    }.stateIn(viewModelScope, Eagerly, emptyList())

    override fun onExtensionSelected(extension: Extension<*>) {
        app.settings.edit { putString(LAST_LYRICS_KEY, extension.id) }
        val media = mediaFlow.value?.track?.id ?: return
        app.context.saveToCache<String>(media, extension.id, "lyrics_ext")
    }

    fun reloadCurrent() = viewModelScope.launch { refreshFlow.emit(Unit) }

    private val cachedFeed = combineTransformLatest(currentSelectionFlow, mediaFlow, queryFlow, refreshFlow) {
        emit(null)
        val extension = it[0] as Extension<*>? ?: return@combineTransformLatest
        val item = it[1] as MediaItem? ?: return@combineTransformLatest
        val query = it[2] as String
        val result = Cached.getLyricsFeed(app, extension.id, item.extensionId, item.track, query)
        emit(result)
    }.stateIn(viewModelScope, Eagerly, null)

    private val loadedFeed = combineTransformLatest(currentSelectionFlow, mediaFlow, queryFlow, refreshFlow) {
        emit(null)
        val extension = it[0] as Extension<*>? ?: return@combineTransformLatest
        val item = it[1] as MediaItem? ?: return@combineTransformLatest
        val query = it[2] as String
        val result = Cached.loadLyricsFeed(app, extension, item.extensionId, item.track, query)
        emit(result)
    }.stateIn(viewModelScope, Eagerly, null)

    private val feedFlow = loadedFeed.combine(cachedFeed) { loaded, cache -> cache to loaded }.stateIn(viewModelScope, Eagerly, null to null)

    val tabsFlow = feedFlow.map { (cached, loaded) ->
        val state = (loaded?.getOrNull() ?: cached?.getOrNull()) ?: return@map listOf()
        state.tabs
    }

    private suspend fun getData(feed: Result<Feed<Lyrics>>?, index: Int) = withContext(Dispatchers.IO) {
        feed?.mapCatching { it.getPagedData(it.tabs.run { getOrNull(index) ?: firstOrNull() }).pagedData }
    }

    private val cachedDataFlow = cachedFeed.combineTransformLatest(selectedTabIndexFlow) { feed, tab ->
        emit(null)
        if (feed == null) return@combineTransformLatest
        emit(getData(feed, tab))
    }.stateIn(viewModelScope, Lazily, null)

    private val loadedDataFlow = loadedFeed.combineTransformLatest(selectedTabIndexFlow) { feed, tab ->
        emit(null)
        if (feed == null) return@combineTransformLatest
        emit(getData(feed, tab))
    }.stateIn(viewModelScope, Lazily, null)

    private val dataFlow = loadedDataFlow.combine(cachedDataFlow) { loaded, cache -> cache to loaded }.stateIn(viewModelScope, Lazily, null to null)

    val shouldShowEmpty = dataFlow.map { (cached, loaded) ->
        val data = loaded?.getOrNull() ?: cached?.getOrNull()
        data != null
    }.stateIn(viewModelScope, Lazily, false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingFlow = dataFlow.flatMapLatest { (cached, loaded) ->
        PagedSource(loaded, cached).flow
    }.flowOn(Dispatchers.IO).cachedIn(viewModelScope)

    sealed interface State {
        data object Initial : State
        data object Loading : State
        data object Empty : State
        data class Loaded(val result: Result<Lyrics>) : State
    }

    fun onLyricsSelected(lyricsItem: Lyrics?) = viewModelScope.launch(Dispatchers.IO) {
        lyricsState.value = State.Loading
        if (lyricsItem == null) lyricsState.value = State.Empty else {
            val extension = currentSelectionFlow.value ?: return@launch
            lyricsState.value = State.Loaded(Cached.loadLyrics(app, extension, lyricsItem).map { it.fillGaps() })
        }
    }

    private fun Lyrics.fillGaps(): Lyrics {
        return when (val l = this.lyrics) {
            is Lyrics.Timed -> {
                if (!l.fillTimeGaps) return this
                val new = mutableListOf<Lyrics.Item>()
                var last = 0L
                l.list.forEach {
                    if (it.startTime > last) new.add(Lyrics.Item("", last, it.startTime))
                    new.add(it)
                    last = it.endTime
                }
                this.copy(lyrics = Lyrics.Timed(new))
            }
            is Lyrics.WordByWord -> {
                if (!l.fillTimeGaps) return this
                val new = mutableListOf<List<Lyrics.Item>>()
                var last = 0L
                l.list.forEach { line ->
                    val lineStart = line.firstOrNull()?.startTime ?: return@forEach
                    if (lineStart > last) new.add(listOf(Lyrics.Item("", last, lineStart)))
                    new.add(line)
                    last = line.lastOrNull()?.endTime ?: last
                }
                this.copy(lyrics = Lyrics.WordByWord(new))
            }
            else -> this
        }
    }

    init {
        reloadCurrent()
        viewModelScope.launch(Dispatchers.IO) {
            dataFlow.collectLatest { (cached, loaded) ->
                if (lyricsState.value != State.Initial) return@collectLatest
                runCatching {
                    val cachedLyrics = cached?.getOrNull()?.loadAll()?.firstOrNull()
                    val loadedData = loaded?.getOrNull()
                    if (loadedData != null) {
                        lyricsState.value = State.Loading
                        onLyricsSelected(loadedData.loadPage(null).data.firstOrNull())
                    } else if (cachedLyrics != null) {
                        onLyricsSelected(cachedLyrics)
                    }
                }
            }
        }
    }

    companion object {
        const val LAST_LYRICS_KEY = "last_lyrics_client"
    }
}