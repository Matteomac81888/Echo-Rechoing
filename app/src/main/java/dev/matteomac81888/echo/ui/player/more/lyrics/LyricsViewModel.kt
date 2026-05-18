package dev.matteomac81888.echo.ui.player.more.lyrics

import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.paging.cachedIn
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.matteomac81888.echo.di.App
import dev.matteomac81888.echo.extensions.ExtensionLoader
import dev.matteomac81888.echo.extensions.ExtensionUtils.getExtension
import dev.matteomac81888.echo.extensions.ExtensionUtils.isClient
import dev.matteomac81888.echo.extensions.cache.Cached
import dev.matteomac81888.echo.extensions.builtin.lyrics.DefaultLyricsExtension
import dev.matteomac81888.echo.playback.MediaItemUtils.extensionId
import dev.matteomac81888.echo.playback.MediaItemUtils.isLoaded
import dev.matteomac81888.echo.playback.MediaItemUtils.track
import dev.matteomac81888.echo.playback.PlayerState
import dev.matteomac81888.echo.ui.common.PagedSource
import dev.matteomac81888.echo.ui.extensions.list.ExtensionListViewModel
import dev.matteomac81888.echo.utils.CacheUtils.getFromCache
import dev.matteomac81888.echo.utils.CacheUtils.saveToCache
import dev.matteomac81888.echo.utils.CoroutineUtils.combineTransformLatest
import dev.matteomac81888.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

enum class LyricsMode { UNSYNCED, SYNCED, KARAOKE }

class LyricsViewModel(
    private val app: App,
    extensionLoader: ExtensionLoader,
    playerState: PlayerState,
) : ExtensionListViewModel<Extension<*>>() {

    private val refreshFlow = MutableSharedFlow<Unit>()
    override val currentSelectionFlow = MutableStateFlow<Extension<*>?>(null)

    val queryFlow = MutableStateFlow("")
    val selectedTabIndexFlow = MutableStateFlow(-1)
    val lyricsState = MutableStateFlow<State>(State.Initial)
    val lyricsModeFlow = MutableStateFlow(LyricsMode.SYNCED)

    // Esito della richiesta Global WordByWord Engine
    val richSyncFlow = MutableStateFlow<Lyrics.WordByWord?>(null)

    // Client HTTP per API Esterne
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
            )
        }.build()

    private var richSyncJob: Job? = null
    private var appleToken: String? = null
    private var mxmToken: String? = null

    /* -------------------------------------------------------------------------
     * CORE ENGINE: APPLE MUSIC API (PRIORITY 1)
     * -----------------------------------------------------------------------*/
    private suspend fun getAppleToken(): String? {
        if (appleToken != null) return appleToken
        return try {
            val req = Request.Builder().url("https://music.apple.com/us/search").build()
            val resp = httpClient.newCall(req).await()
            val html = resp.body?.string() ?: return null
            val regex = Regex("name=\"desktop-music-app/config/environment\" content=\"([^\"]+)\"")
            val match = regex.find(html) ?: return null
            val decoded = java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
            val tokenRegex = Regex("\"token\":\"([^\"]+)\"")
            appleToken = tokenRegex.find(decoded)?.groupValues?.get(1)
            appleToken
        } catch (e: Exception) { null }
    }

    private fun parseTtmlTime(timeStr: String): Long {
        val parts = timeStr.split(":")
        var ms = 0L
        if (parts.size == 3) {
            ms += parts[0].toLong() * 3600000
            ms += parts[1].toLong() * 60000
            val secParts = parts[2].split(".")
            ms += secParts[0].toLong() * 1000
            if (secParts.size > 1) ms += secParts[1].padEnd(3, '0').substring(0, 3).toLong()
        } else if (parts.size == 2) {
            ms += parts[0].toLong() * 60000
            val secParts = parts[1].split(".")
            ms += secParts[0].toLong() * 1000
            if (secParts.size > 1) ms += secParts[1].padEnd(3, '0').substring(0, 3).toLong()
        } else if (parts.size == 1) {
            val secParts = parts[0].split(".")
            ms += secParts[0].toLong() * 1000
            if (secParts.size > 1) ms += secParts[1].padEnd(3, '0').substring(0, 3).toLong()
        }
        return ms
    }

    private fun parseAppleTtml(ttml: String): Lyrics.WordByWord? {
        val lines = mutableListOf<List<Lyrics.Item>>()
        val pRegex = Regex("<p[^>]*begin=\"([^\"]+)\"[^>]*end=\"([^\"]+)\"[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
        val spanRegex = Regex("<span[^>]*begin=\"([^\"]+)\"[^>]*end=\"([^\"]+)\"[^>]*>(.*?)</span>", RegexOption.DOT_MATCHES_ALL)

        val pMatches = pRegex.findAll(ttml)
        for (pMatch in pMatches) {
            val pBegin = parseTtmlTime(pMatch.groupValues[1])
            val pEnd = parseTtmlTime(pMatch.groupValues[2])
            val innerHtml = pMatch.groupValues[3]

            val spanMatches = spanRegex.findAll(innerHtml)
            val words = mutableListOf<Lyrics.Item>()

            if (spanMatches.none()) {
                val text = innerHtml.replace(Regex("<[^>]+>"), "").trim()
                if (text.isNotEmpty()) words.add(Lyrics.Item(text, pBegin, pEnd))
            } else {
                for (spanMatch in spanMatches) {
                    val sBegin = parseTtmlTime(spanMatch.groupValues[1])
                    val sEnd = parseTtmlTime(spanMatch.groupValues[2])
                    val text = spanMatch.groupValues[3].replace(Regex("<[^>]+>"), "")
                        .replace("&amp;", "&").replace("&apos;", "'")
                        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                    if (text.trim().isNotEmpty()) words.add(Lyrics.Item(text, sBegin, sEnd))
                }
            }
            if (words.isNotEmpty()) lines.add(words)
        }
        return if (lines.isNotEmpty()) Lyrics.WordByWord(lines, true) else null
    }

    private suspend fun fetchAppleMusicLyrics(track: Track): Lyrics.WordByWord? {
        try {
            val token = getAppleToken() ?: return null
            val artist = URLEncoder.encode(track.artists.firstOrNull()?.name ?: "", "UTF-8")
            val title = URLEncoder.encode(track.title, "UTF-8")

            val searchUrl = "https://amp-api.music.apple.com/v1/catalog/us/search?types=songs&term=$artist+$title&limit=1"
            val searchReq = Request.Builder().url(searchUrl)
                .header("Authorization", "Bearer $token")
                .header("Origin", "https://music.apple.com")
                .build()

            val searchResp = httpClient.newCall(searchReq).await()
            val searchJson = searchResp.body?.string()?.toData<JsonElement>()?.getOrNull() ?: return null

            val songId = searchJson.jsonObject["results"]?.jsonObject
                ?.get("songs")?.jsonObject
                ?.get("data")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("id")?.jsonPrimitive?.contentOrNull ?: return null

            val lyricsUrl = "https://amp-api.music.apple.com/v1/catalog/us/songs/$songId/lyrics"
            val lyricsReq = Request.Builder().url(lyricsUrl)
                .header("Authorization", "Bearer $token")
                .header("Origin", "https://music.apple.com")
                .build()

            val lyricsResp = httpClient.newCall(lyricsReq).await()
            val lyricsJson = lyricsResp.body?.string()?.toData<JsonElement>()?.getOrNull() ?: return null

            val ttml = lyricsJson.jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("attributes")?.jsonObject
                ?.get("ttml")?.jsonPrimitive?.contentOrNull ?: return null

            return parseAppleTtml(ttml)
        } catch (e: Exception) {
            return null
        }
    }

    /* -------------------------------------------------------------------------
     * CORE ENGINE: MUSIXMATCH API (PRIORITY 2)
     * -----------------------------------------------------------------------*/
    private suspend fun getMxmToken(): String? {
        mxmToken?.let { return it }
        return try {
            val url = "https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=web-desktop-app-v1.0&format=json"
            val req = Request.Builder().url(url).header("authority", "apic-desktop.musixmatch.com").build()
            val resp = httpClient.newCall(req).await()
            val json = resp.body?.string()?.toData<JsonElement>()?.getOrNull() ?: return null
            val token = json.jsonObject["message"]?.jsonObject?.get("body")?.jsonObject?.get("user_token")?.jsonPrimitive?.contentOrNull
            if (!token.isNullOrBlank() && token != "UpgradeNeeded") {
                mxmToken = token; token
            } else null
        } catch (e: Exception) { null }
    }

    // .jsonObject lancia IllegalArgumentException se il nodo è un primitivo (es. "body": false).
    // Questa helper restituisce null in sicurezza invece di crashare.
    private fun JsonElement.safeObj() = if (this is kotlinx.serialization.json.JsonObject) this else null

    private suspend fun fetchMusixmatchLyrics(track: Track): Lyrics.WordByWord? {
        return try {
            val token = getMxmToken() ?: return null
            val artist = URLEncoder.encode(track.artists.firstOrNull()?.name ?: "", "UTF-8")
            val title = URLEncoder.encode(track.title, "UTF-8")
            val dur = track.duration?.div(1000) ?: 0L

            val matchUrl = "https://apic-desktop.musixmatch.com/ws/1.1/matcher.track.get?app_id=web-desktop-app-v1.0&usertoken=$token&format=json&q_artist=$artist&q_track=$title${if (dur > 0) "&q_duration=$dur" else ""}"
            val matchReq = Request.Builder().url(matchUrl).header("authority", "apic-desktop.musixmatch.com").build()

            val matchJson = httpClient.newCall(matchReq).await().body?.string()?.toData<JsonElement>()?.getOrNull() ?: return null
            val matchMsg = matchJson.safeObj()?.get("message")?.safeObj() ?: return null
            val matchBody = matchMsg["body"]?.safeObj() ?: return null
            val trackObj = matchBody["track"]?.safeObj() ?: return null
            val trackId = trackObj["track_id"]?.jsonPrimitive?.longOrNull ?: return null
            val hasRichsync = trackObj["has_richsync"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            if (hasRichsync != 1) return null

            val richUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.richsync.get?app_id=web-desktop-app-v1.0&usertoken=$token&format=json&track_id=$trackId"
            val richReq = Request.Builder().url(richUrl).header("authority", "apic-desktop.musixmatch.com").build()
            val richJson = httpClient.newCall(richReq).await().body?.string()?.toData<JsonElement>()?.getOrNull() ?: return null
            val richMsg = richJson.safeObj()?.get("message")?.safeObj() ?: return null
            val richBody = richMsg["body"]?.safeObj() ?: return null
            val bodyStr = richBody["richsync"]?.safeObj()?.get("richsync_body")?.jsonPrimitive?.contentOrNull ?: return null

            val array = bodyStr.toData<JsonArray>().getOrNull() ?: return null
            val lines = mutableListOf<List<Lyrics.Item>>()

            for (el in array) {
                val obj = el.safeObj() ?: continue
                val tsMs = ((obj["ts"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue) * 1000).toLong()
                val teMs = ((obj["te"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: (tsMs / 1000.0 + 4.0)) * 1000).toLong()
                val chars = obj["l"]?.jsonArray ?: continue

                val words = mutableListOf<Lyrics.Item>()
                var currentWordText = StringBuilder()
                var currentWordStart = tsMs

                for (i in 0 until chars.size) {
                    val charEl = chars[i].safeObj() ?: continue
                    val c = charEl["c"]?.jsonPrimitive?.contentOrNull ?: ""
                    val offsetMs = ((charEl["o"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
                    val absoluteTimeMs = tsMs + offsetMs
                    val nextOffsetMs = if (i + 1 < chars.size) {
                        ((chars[i + 1].safeObj()?.get("o")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
                    } else { teMs - tsMs }
                    val nextAbsoluteTimeMs = tsMs + nextOffsetMs

                    if (c.isBlank()) {
                        if (currentWordText.isNotEmpty()) {
                            words.add(Lyrics.Item(currentWordText.toString(), currentWordStart, absoluteTimeMs))
                            currentWordText.clear()
                        }
                        currentWordStart = nextAbsoluteTimeMs
                    } else {
                        if (currentWordText.isEmpty()) { currentWordStart = absoluteTimeMs }
                        currentWordText.append(c)
                    }
                }
                if (currentWordText.isNotEmpty()) words.add(Lyrics.Item(currentWordText.toString(), currentWordStart, teMs))
                if (words.isNotEmpty()) lines.add(words)
            }
            if (lines.isEmpty()) null else Lyrics.WordByWord(lines, true)
        } catch (e: Exception) { null }
    }

    /* -------------------------------------------------------------------------
     * ENGINE PRINCIPALE: GERARCHIA DI RICERCA
     * -----------------------------------------------------------------------*/
    fun fetchRichSyncIfNeeded(lyrics: Lyrics.Lyric?, track: Track?) {
        richSyncJob?.cancel()
        richSyncFlow.value = null

        if (lyrics is Lyrics.WordByWord) {
            richSyncFlow.value = lyrics
            return
        }

        if (track != null) {
            richSyncJob = viewModelScope.launch(Dispatchers.IO) {
                // 1. Tenta Apple Music API
                var wbw = fetchAppleMusicLyrics(track)

                // 2. Fallback su Musixmatch API
                if (wbw == null) {
                    wbw = fetchMusixmatchLyrics(track)
                }

                if (wbw != null) {
                    richSyncFlow.value = wbw
                } else {
                    // Fallimento: nessuna info WordByWord trovata, rimuove Karaoke
                    withContext(Dispatchers.Main) {
                        if (lyricsModeFlow.value == LyricsMode.KARAOKE) {
                            applyDefaultModeForLyrics(lyrics)
                        }
                    }
                }
            }
        }
    }

    fun setMode(mode: LyricsMode) {
        val lyrics = (lyricsState.value as? State.Loaded)?.result?.getOrNull()?.lyrics
        lyricsModeFlow.value = when {
            mode == LyricsMode.KARAOKE && !canUseKaraoke(lyrics) -> LyricsMode.SYNCED
            else -> mode
        }
    }

    fun applyDefaultModeForLyrics(lyrics: Lyrics.Lyric?) {
        lyricsModeFlow.value = when (lyrics) {
            is Lyrics.WordByWord -> LyricsMode.KARAOKE
            is Lyrics.Timed      -> LyricsMode.SYNCED
            is Lyrics.Simple     -> LyricsMode.UNSYNCED
            null                 -> LyricsMode.SYNCED
        }
    }

    fun canUseKaraoke(lyrics: Lyrics.Lyric?): Boolean =
        lyrics is Lyrics.WordByWord || richSyncFlow.value != null


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
            val id = app.context.getFromCache<String>(media, "lyrics_ext")
                ?: app.settings.getString(LAST_LYRICS_KEY, null)
                ?: DefaultLyricsExtension.metadata.id
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

    private val cachedFeed = combineTransformLatest(
        currentSelectionFlow, mediaFlow, queryFlow, refreshFlow
    ) {
        emit(null)
        val extension = it[0] as Extension<*>? ?: return@combineTransformLatest
        val item = it[1] as MediaItem? ?: return@combineTransformLatest
        val query = it[2] as String
        val result = Cached.getLyricsFeed(app, extension.id, item.extensionId, item.track, query)
        emit(result)
    }.stateIn(viewModelScope, Eagerly, null)

    private val loadedFeed = combineTransformLatest(
        currentSelectionFlow, mediaFlow, queryFlow, refreshFlow
    ) {
        emit(null)
        val extension = it[0] as Extension<*>? ?: return@combineTransformLatest
        val item = it[1] as MediaItem? ?: return@combineTransformLatest
        val query = it[2] as String
        val result = Cached.loadLyricsFeed(app, extension, item.extensionId, item.track, query)
        emit(result)
    }.stateIn(viewModelScope, Eagerly, null)

    private val feedFlow = loadedFeed.combine(cachedFeed) { loaded, cache ->
        cache to loaded
    }.stateIn(viewModelScope, Eagerly, null to null)

    val tabsFlow = feedFlow.map { (cached, loaded) ->
        val state = (loaded?.getOrNull() ?: cached?.getOrNull()) ?: return@map listOf()
        state.tabs
    }

    private suspend fun getData(feed: Result<Feed<Lyrics>>?, index: Int) =
        withContext(Dispatchers.IO) {
            feed?.mapCatching {
                it.getPagedData(it.tabs.run { getOrNull(index) ?: firstOrNull() }).pagedData
            }
        }

    private val cachedDataFlow =
        cachedFeed.combineTransformLatest(selectedTabIndexFlow) { feed, tab ->
            emit(null)
            if (feed == null) return@combineTransformLatest
            emit(getData(feed, tab))
        }.stateIn(viewModelScope, Lazily, null)

    private val loadedDataFlow =
        loadedFeed.combineTransformLatest(selectedTabIndexFlow) { feed, tab ->
            emit(null)
            if (feed == null) return@combineTransformLatest
            emit(getData(feed, tab))
        }.stateIn(viewModelScope, Lazily, null)

    private val dataFlow = loadedDataFlow.combine(cachedDataFlow) { loaded, cache ->
        cache to loaded
    }.stateIn(viewModelScope, Lazily, null to null)

    val shouldShowEmpty = dataFlow.map { (cached, loaded) ->
        val data = loaded?.getOrNull() ?: cached?.getOrNull()
        data != null
    }.stateIn(viewModelScope, Lazily, false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingFlow = dataFlow.transformLatest { (cached, loaded) ->
        emitAll(PagedSource(loaded, cached).flow)
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
            lyricsState.value =
                State.Loaded(Cached.loadLyrics(app, extension, lyricsItem).map { it.fillGaps() })
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