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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

    private val richSyncCache = ConcurrentHashMap<String, Lyrics.WordByWord>()
    private var currentRichSyncTrackId: String? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
            )
        }.build()

    private var richSyncJob: Job? = null
    private var appleToken: String? = null
    private var mxmToken: String? = null

    private fun JsonElement.safeObj() = if (this is kotlinx.serialization.json.JsonObject) this else null
    private fun JsonElement.safeArray() = if (this is kotlinx.serialization.json.JsonArray) this else null

    private val JsonElement.asString: String?
        get() = try {
            if (this is kotlinx.serialization.json.JsonNull) null else this.jsonPrimitive.content
        } catch (e: Exception) { null }

    private fun findFieldGlobally(element: JsonElement, fieldName: String): String? {
        val obj = element.safeObj() ?: return null
        obj[fieldName]?.asString?.let { return it }
        obj["data"]?.safeObj()?.get(fieldName)?.asString?.let { return it }
        obj["lrc"]?.safeObj()?.get(fieldName)?.asString?.let { return it }
        obj["result"]?.safeObj()?.get(fieldName)?.asString?.let { return it }
        return null
    }

    private fun parseUniversalWordByWord(json: JsonElement?): Lyrics.WordByWord? {
        if (json == null) return null
        val yrc = findFieldGlobally(json, "yrc") ?: findFieldGlobally(json, "romalrc")
        if (!yrc.isNullOrBlank()) {
            parseYrc(yrc)?.let { return it }
        }
        val synced = findFieldGlobally(json, "syncedLyrics")
            ?: findFieldGlobally(json, "lyrics")
            ?: findFieldGlobally(json, "lrc")
        if (!synced.isNullOrBlank()) {
            parseEnhancedLrc(synced)?.let { return it }
        }
        return null
    }

    private fun parseYrc(yrc: String): Lyrics.WordByWord? {
        val lines = mutableListOf<List<Lyrics.Item>>()
        val lineRegex = Regex("""^\[(?:t:)?(\d+),(\d+)\](.*)""")
        val wordRegex = Regex("""\((\d+),(\d+)(?:,\d+)?\)([^(]*)""")

        yrc.lines().forEach { lineStr ->
            val lineMatch = lineRegex.find(lineStr.trim()) ?: return@forEach
            val content = lineMatch.groupValues[3]

            val wordMatches = wordRegex.findAll(content).toList()
            if (wordMatches.isNotEmpty()) {
                val words = mutableListOf<Lyrics.Item>()
                for (match in wordMatches) {
                    val wStartMs = match.groupValues[1].toLong()
                    val wDurMs = match.groupValues[2].toLong()
                    val text = match.groupValues[3].trimEnd()
                    if (text.isNotBlank()) {
                        words.add(Lyrics.Item(text.trim(), wStartMs, wStartMs + wDurMs))
                    }
                }
                if (words.isNotEmpty()) lines.add(words)
            }
        }
        return if (lines.isNotEmpty()) Lyrics.WordByWord(lines, true) else null
    }

    private fun parseEnhancedLrc(lrc: String): Lyrics.WordByWord? {
        val lines = mutableListOf<List<Lyrics.Item>>()
        val lineRegex = Regex("""\[(\d+):(\d+(?:\.\d+)?)\](.*)""")
        val wordRegex = Regex("""<(\d+):(\d+(?:\.\d+)?)>([^<]*)""")

        lrc.lines().forEach { lineStr ->
            val lineMatch = lineRegex.find(lineStr) ?: return@forEach
            val content = lineMatch.groupValues[3]

            val wordMatches = wordRegex.findAll(content).toList()
            if (wordMatches.isNotEmpty()) {
                val words = mutableListOf<Lyrics.Item>()
                for (i in wordMatches.indices) {
                    val match = wordMatches[i]
                    val wMin = match.groupValues[1].toLong()
                    val wSec = match.groupValues[2].toDouble()
                    val wStartMs = (wMin * 60000 + wSec * 1000).toLong()
                    val text = match.groupValues[3].trim()

                    val wEndMs = if (i + 1 < wordMatches.size) {
                        val nextMatch = wordMatches[i + 1]
                        val nMin = nextMatch.groupValues[1].toLong()
                        val nSec = nextMatch.groupValues[2].toDouble()
                        (nMin * 60000 + nSec * 1000).toLong()
                    } else {
                        wStartMs + 1500L
                    }

                    if (text.isNotEmpty()) {
                        words.add(Lyrics.Item(text, wStartMs, wEndMs))
                    }
                }
                if (words.isNotEmpty()) lines.add(words)
            }
        }

        val hasWordSync = lines.any { it.size > 1 }
        return if (lines.isNotEmpty() && hasWordSync) Lyrics.WordByWord(lines, true) else null
    }

    private val kpoeMirrors = listOf(
        "https://api.kpoe.ee",
        "https://kpoe.meowarex.net",
        "https://lyricsp.ibratabian17.com",
        "https://lyricsplus.painfueg0.com",
        "https://lyrics.binimum.net"
    )

    private suspend fun fetchKpoeLyricsPlus(track: Track): Lyrics.WordByWord? {
        val isrc = track.extras["isrc"] ?: track.extras["ISRC"]

        for (mirror in kpoeMirrors) {
            try {
                val searchUrl = if (!isrc.isNullOrBlank()) {
                    "$mirror/v1/songlist/search?keyword=${URLEncoder.encode(isrc, "UTF-8")}"
                } else {
                    "$mirror/v1/songlist/search?keyword=${URLEncoder.encode("${track.title.clean()} ${track.artists.firstOrNull()?.name?.clean() ?: ""}", "UTF-8")}"
                }

                val searchResp = httpClient.newCall(Request.Builder().url(searchUrl).build()).await()
                if (!searchResp.isSuccessful) continue
                val searchJson = searchResp.body?.string()?.toData<JsonElement>()?.getOrNull() ?: continue

                val results: JsonArray? = searchJson.safeObj()?.get("data")?.safeArray()
                    ?: searchJson.safeArray()
                    ?: searchJson.safeObj()?.get("result")?.safeArray()

                val songId = findBestMatch(results, track) ?: continue

                val lyricsUrl = "$mirror/v1/song/lyrics?id=$songId"
                val lyricsResp = httpClient.newCall(Request.Builder().url(lyricsUrl).build()).await()
                if (!lyricsResp.isSuccessful) continue
                val lyricsJson = lyricsResp.body?.string()?.toData<JsonElement>()?.getOrNull() ?: continue

                val result = parseKpoeV2(lyricsJson) ?: parseUniversalWordByWord(lyricsJson)
                if (result != null) return result
            } catch (e: Exception) { continue }
        }
        return null
    }

    private fun findBestMatch(results: JsonArray?, track: Track): String? {
        if (results == null || results.isEmpty()) return null
        val cleanTitle = track.title.clean().lowercase()
        val cleanArtist = track.artists.firstOrNull()?.name?.clean()?.lowercase() ?: ""

        for (item in results) {
            val obj = item.safeObj() ?: continue
            val itemTitle = (obj["title"]?.asString ?: obj["name"]?.asString ?: "").clean().lowercase()
            val itemArtist = (obj["artist"]?.asString ?: obj["artistName"]?.asString ?: "").clean().lowercase()

            if (itemTitle.contains(cleanTitle) || cleanTitle.contains(itemTitle)) {
                if (cleanArtist.isEmpty() || itemArtist.contains(cleanArtist) || cleanArtist.contains(itemArtist)) {
                    return obj["id"]?.asString
                }
            }
        }
        for (item in results) {
            val obj = item.safeObj() ?: continue
            val itemTitle = (obj["title"]?.asString ?: obj["name"]?.asString ?: "").clean().lowercase()
            if (itemTitle.contains(cleanTitle) || cleanTitle.contains(itemTitle)) {
                return obj["id"]?.asString
            }
        }
        return results.firstOrNull()?.safeObj()?.get("id")?.asString
    }

    private fun parseKpoeV2(json: JsonElement): Lyrics.WordByWord? {
        val root = json.safeObj() ?: return null
        val linesArray = root["lines"]?.safeArray()
            ?: root["data"]?.safeObj()?.get("lines")?.safeArray()
            ?: root["content"]?.safeArray()
            ?: return null

        val lines = mutableListOf<List<Lyrics.Item>>()
        for (lineEl in linesArray) {
            val lineObj = lineEl.safeObj() ?: continue
            val wordsArray = lineObj["words"]?.safeArray()
                ?: lineObj["syllables"]?.safeArray()
                ?: continue

            val words = mutableListOf<Lyrics.Item>()
            for (wordEl in wordsArray) {
                val wordObj = wordEl.safeObj() ?: continue
                val text = (wordObj["word"]?.asString
                    ?: wordObj["text"]?.asString
                    ?: wordObj["c"]?.asString
                    ?: "").trim()
                if (text.isBlank()) continue

                val startMs = wordObj["startTime"]?.asString?.toLongOrNull()
                    ?: wordObj["start"]?.asString?.toLongOrNull()
                    ?: ((wordObj["ts"]?.asString?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
                val endMs = wordObj["endTime"]?.asString?.toLongOrNull()
                    ?: wordObj["end"]?.asString?.toLongOrNull()
                    ?: (startMs + 500L)

                words.add(Lyrics.Item(text, startMs, endMs))
            }
            if (words.isNotEmpty()) lines.add(words)
        }
        return if (lines.isNotEmpty() && lines.any { it.size > 1 }) Lyrics.WordByWord(lines, true) else null
    }

    private suspend fun fetchSpicyLyrics(track: Track): Lyrics.WordByWord? {
        return try {
            val title = URLEncoder.encode(track.title.clean(), "UTF-8")
            val artist = URLEncoder.encode(track.artists.firstOrNull()?.name?.clean() ?: "", "UTF-8")

            val url = "https://api.spicylyrics.org/query?title=$title&artist=$artist"
            val req = Request.Builder()
                .url(url)
                .header("Origin", "https://open.spotify.com")
                .build()

            val resp = httpClient.newCall(req).await()
            if (!resp.isSuccessful) return null

            val json = resp.body?.string()?.toData<JsonElement>()?.getOrNull() ?: return null
            val root = json.safeObj() ?: return null
            val type = root["type"]?.asString ?: root["Type"]?.asString ?: ""

            if (type.equals("Syllable", ignoreCase = true)) {
                val lyricsObj = root["lyrics"]?.safeObj() ?: root["Lyrics"]?.safeObj()
                if (lyricsObj != null) {
                    parseKpoeV2(lyricsObj)?.let { return it }
                }
            }

            parseUniversalWordByWord(json)
        } catch (e: Exception) { null }
    }

    private suspend fun fetchLrclib(track: Track): Lyrics.WordByWord? {
        return try {
            val title = URLEncoder.encode(track.title.clean(), "UTF-8")
            val artist = URLEncoder.encode(track.artists.firstOrNull()?.name?.clean() ?: "", "UTF-8")
            val album = URLEncoder.encode(track.album?.title?.clean() ?: "", "UTF-8")
            val duration = track.duration?.let { it / 1000 } ?: 0L

            val getUrl = buildString {
                append("https://lrclib.net/api/get")
                append("?track_name=$title")
                append("&artist_name=$artist")
                if (album.isNotBlank()) append("&album_name=$album")
                if (duration > 0) append("&duration=$duration")
            }

            val getResp = httpClient.newCall(
                Request.Builder().url(getUrl).header("Lrclib-Client", "dev.matteomac81888.echo v1.0").build()
            ).await()

            if (getResp.isSuccessful) {
                val json = getResp.body?.string()?.toData<JsonElement>()?.getOrNull()
                val root = json?.safeObj()
                if (root != null) {
                    val synced = root["syncedLyrics"]?.asString
                    if (!synced.isNullOrBlank()) {
                        parseEnhancedLrc(synced)?.let { return it }
                    }
                }
            }

            val searchUrl = "https://lrclib.net/api/search?track_name=$title&artist_name=$artist"
            val searchResp = httpClient.newCall(
                Request.Builder().url(searchUrl).header("Lrclib-Client", "dev.matteomac81888.echo v1.0").build()
            ).await()

            if (!searchResp.isSuccessful) return null
            val searchJson = searchResp.body?.string()?.toData<JsonElement>()?.getOrNull() ?: return null
            val results = searchJson.safeArray() ?: return null

            val cleanTitle = track.title.clean().lowercase()
            val cleanArtist = track.artists.firstOrNull()?.name?.clean()?.lowercase() ?: ""

            for (item in results) {
                val obj = item.safeObj() ?: continue
                val itemTitle = (obj["trackName"]?.asString ?: "").clean().lowercase()
                val itemArtist = (obj["artistName"]?.asString ?: "").clean().lowercase()
                val hasSynced = !obj["syncedLyrics"]?.asString.isNullOrBlank()

                if (!hasSynced) continue
                if (itemTitle.contains(cleanTitle) || cleanTitle.contains(itemTitle)) {
                    if (cleanArtist.isEmpty() || itemArtist.contains(cleanArtist) || cleanArtist.contains(itemArtist)) {
                        val synced = obj["syncedLyrics"]?.asString ?: continue
                        parseEnhancedLrc(synced)?.let { return it }
                    }
                }
            }
            null
        } catch (e: Exception) { null }
    }

    private suspend fun getAppleToken(): String? {
        if (appleToken != null) return appleToken
        return try {
            val req = Request.Builder().url("https://music.apple.com/us/search").header("Accept", "text/html").build()
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
        if (timeStr.endsWith("s")) {
            return (timeStr.dropLast(1).toDoubleOrNull()?.times(1000))?.toLong() ?: 0L
        }
        val parts = timeStr.split(":")
        var ms = 0L
        when (parts.size) {
            3 -> {
                ms += parts[0].toLong() * 3600000
                ms += parts[1].toLong() * 60000
                val secParts = parts[2].split(".")
                ms += secParts[0].toLong() * 1000
                if (secParts.size > 1) ms += secParts[1].padEnd(3, '0').substring(0, 3).toLong()
            }
            2 -> {
                ms += parts[0].toLong() * 60000
                val secParts = parts[1].split(".")
                ms += secParts[0].toLong() * 1000
                if (secParts.size > 1) ms += secParts[1].padEnd(3, '0').substring(0, 3).toLong()
            }
            1 -> {
                val secParts = parts[0].split(".")
                ms += secParts[0].toLong() * 1000
                if (secParts.size > 1) ms += secParts[1].padEnd(3, '0').substring(0, 3).toLong()
            }
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
                    val text = spanMatch.groupValues[3]
                        .replace(Regex("<[^>]+>"), "")
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
            val term = URLEncoder.encode("${track.title.clean()} ${track.artists.firstOrNull()?.name?.clean() ?: ""}", "UTF-8")

            val searchUrl = "https://amp-api.music.apple.com/v1/catalog/us/search?types=songs&term=$term&limit=5"
            val searchReq = Request.Builder().url(searchUrl)
                .header("Authorization", "Bearer $token")
                .header("Origin", "https://music.apple.com")
                .header("Referer", "https://music.apple.com/")
                .build()

            val searchResp = httpClient.newCall(searchReq).await()
            if (!searchResp.isSuccessful) {
                appleToken = null
                return null
            }

            val searchJson = searchResp.body?.string()?.toData<JsonElement>()?.getOrNull() ?: return null
            val songs = searchJson.safeObj()?.get("results")?.safeObj()
                ?.get("songs")?.safeObj()
                ?.get("data")?.safeArray() ?: return null

            val cleanTitle = track.title.clean().lowercase()
            var songId: String? = null

            for (song in songs) {
                val attrs = song.safeObj()?.get("attributes")?.safeObj() ?: continue
                val songTitle = attrs["name"]?.asString?.clean()?.lowercase() ?: ""
                val hasLyrics = attrs["hasLyrics"]?.asString == "true"
                if (hasLyrics && (songTitle.contains(cleanTitle) || cleanTitle.contains(songTitle))) {
                    songId = song.safeObj()?.get("id")?.asString
                    break
                }
            }
            if (songId == null) {
                songId = songs.firstOrNull { song ->
                    song.safeObj()?.get("attributes")?.safeObj()?.get("hasLyrics")?.asString == "true"
                }?.safeObj()?.get("id")?.asString
            }
            if (songId == null) {
                songId = songs.firstOrNull()?.safeObj()?.get("id")?.asString
            }
            if (songId == null) return null

            val lyricsUrl = "https://amp-api.music.apple.com/v1/catalog/us/songs/$songId/lyrics"
            val lyricsReq = Request.Builder().url(lyricsUrl)
                .header("Authorization", "Bearer $token")
                .header("Origin", "https://music.apple.com")
                .header("Referer", "https://music.apple.com/")
                .build()

            val lyricsResp = httpClient.newCall(lyricsReq).await()
            if (!lyricsResp.isSuccessful) return null

            val lyricsJson = lyricsResp.body?.string()?.toData<JsonElement>()?.getOrNull() ?: return null

            val ttml = lyricsJson.safeObj()?.get("data")?.safeArray()?.firstOrNull()?.safeObj()
                ?.get("attributes")?.safeObj()
                ?.get("ttml")?.asString ?: return null

            return parseAppleTtml(ttml)
        } catch (e: Exception) {
            appleToken = null
            return null
        }
    }

    private suspend fun getMxmToken(): String? {
        mxmToken?.let { return it }
        return try {
            val url = "https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=web-desktop-app-v1.0&format=json"
            val req = Request.Builder()
                .url(url)
                .header("authority", "apic-desktop.musixmatch.com")
                .header("Cookie", "x-mxm-token-guid=")
                .build()
            val resp = httpClient.newCall(req).await()
            val json = resp.body?.string()?.toData<JsonElement>()?.getOrNull() ?: return null
            val token = json.safeObj()?.get("message")?.safeObj()
                ?.get("body")?.safeObj()
                ?.get("user_token")?.asString

            if (!token.isNullOrBlank() && token != "UpgradeNeeded") {
                mxmToken = token
                token
            } else null
        } catch (e: Exception) { null }
    }

    private suspend fun fetchMusixmatchLyrics(track: Track): Lyrics.WordByWord? {
        return try {
            val token = getMxmToken() ?: return null
            val artist = URLEncoder.encode(track.artists.firstOrNull()?.name?.clean() ?: "", "UTF-8")
            val title = URLEncoder.encode(track.title.clean(), "UTF-8")
            val duration = track.duration?.let { it / 1000 } ?: 0L

            val matchUrl = buildString {
                append("https://apic-desktop.musixmatch.com/ws/1.1/matcher.track.get")
                append("?app_id=web-desktop-app-v1.0")
                append("&usertoken=$token")
                append("&format=json")
                append("&q_artist=$artist")
                append("&q_track=$title")
                if (duration > 0) append("&q_duration=$duration")
            }

            val matchReq = Request.Builder()
                .url(matchUrl)
                .header("authority", "apic-desktop.musixmatch.com")
                .build()

            val matchJson = httpClient.newCall(matchReq).await()
                .body?.string()?.toData<JsonElement>()?.getOrNull() ?: return null

            val matchMsg = matchJson.safeObj()?.get("message")?.safeObj() ?: return null
            val statusCode = matchMsg["header"]?.safeObj()?.get("status_code")?.asString?.toIntOrNull() ?: 0

            if (statusCode == 401) {
                mxmToken = null
                return null
            }

            val matchBody = matchMsg["body"]?.safeObj() ?: return null
            val trackObj = matchBody["track"]?.safeObj() ?: return null
            val trackId = trackObj["track_id"]?.asString?.toLongOrNull() ?: return null
            val hasRichsync = trackObj["has_richsync"]?.asString?.toIntOrNull() ?: 0
            if (hasRichsync != 1) return null

            val richUrl = buildString {
                append("https://apic-desktop.musixmatch.com/ws/1.1/track.richsync.get")
                append("?app_id=web-desktop-app-v1.0")
                append("&usertoken=$token")
                append("&format=json")
                append("&track_id=$trackId")
            }

            val richReq = Request.Builder()
                .url(richUrl)
                .header("authority", "apic-desktop.musixmatch.com")
                .build()

            val richJson = httpClient.newCall(richReq).await()
                .body?.string()?.toData<JsonElement>()?.getOrNull() ?: return null

            val richMsg = richJson.safeObj()?.get("message")?.safeObj() ?: return null
            val richBody = richMsg["body"]?.safeObj() ?: return null
            val bodyStr = richBody["richsync"]?.safeObj()?.get("richsync_body")?.asString ?: return null

            val array = bodyStr.toData<JsonArray>().getOrNull() ?: return null
            val lines = mutableListOf<List<Lyrics.Item>>()

            for (el in array) {
                val obj = el.safeObj() ?: continue
                val tsMs = ((obj["ts"]?.asString?.toDoubleOrNull() ?: continue) * 1000).toLong()
                val teMs = ((obj["te"]?.asString?.toDoubleOrNull() ?: (tsMs / 1000.0 + 4.0)) * 1000).toLong()
                val chars = obj["l"]?.safeArray() ?: continue

                val words = mutableListOf<Lyrics.Item>()

                for (i in 0 until chars.size) {
                    val charEl = chars[i].safeObj() ?: continue
                    val c = charEl["c"]?.asString ?: ""
                    if (c.isBlank()) continue

                    val offsetMs = ((charEl["o"]?.asString?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
                    val absoluteTimeMs = tsMs + offsetMs

                    val nextOffsetMs = if (i + 1 < chars.size) {
                        ((chars[i + 1].safeObj()?.get("o")?.asString?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
                    } else {
                        teMs - tsMs
                    }
                    val nextAbsoluteTimeMs = tsMs + nextOffsetMs

                    words.add(Lyrics.Item(c.trim(), absoluteTimeMs, minOf(nextAbsoluteTimeMs, teMs)))
                }
                if (words.isNotEmpty()) lines.add(words)
            }
            if (lines.isEmpty()) null else Lyrics.WordByWord(lines, true)
        } catch (e: Exception) {
            mxmToken = null
            null
        }
    }

    private suspend fun fetchNeteaseYrc(track: Track): Lyrics.WordByWord? {
        return try {
            val keyword = URLEncoder.encode("${track.title.clean()} ${track.artists.firstOrNull()?.name?.clean() ?: ""}", "UTF-8")
            val searchUrl = "https://music.163.com/api/search/get/web?csrf_token=&s=$keyword&type=1&offset=0&total=true&limit=5"
            val searchReq = Request.Builder()
                .url(searchUrl)
                .header("Referer", "https://music.163.com")
                .header("Cookie", "appver=2.0.2")
                .build()

            val searchResp = httpClient.newCall(searchReq).await()
            if (!searchResp.isSuccessful) return null

            val searchJson = searchResp.body?.string()?.toData<JsonElement>()?.getOrNull() ?: return null
            val songs = searchJson.safeObj()?.get("result")?.safeObj()
                ?.get("songs")?.safeArray() ?: return null

            val cleanTitle = track.title.clean().lowercase()
            val cleanArtist = track.artists.firstOrNull()?.name?.clean()?.lowercase() ?: ""

            var songId: Long? = null
            for (song in songs) {
                val obj = song.safeObj() ?: continue
                val songName = (obj["name"]?.asString ?: "").clean().lowercase()
                val artistName = obj["artists"]?.safeArray()?.firstOrNull()?.safeObj()
                    ?.get("name")?.asString?.clean()?.lowercase() ?: ""

                if (songName.contains(cleanTitle) || cleanTitle.contains(songName)) {
                    if (cleanArtist.isEmpty() || artistName.contains(cleanArtist) || cleanArtist.contains(artistName)) {
                        songId = obj["id"]?.asString?.toLongOrNull()
                        break
                    }
                }
            }
            if (songId == null) songId = songs.firstOrNull()?.safeObj()?.get("id")?.asString?.toLongOrNull()
            if (songId == null) return null

            val lyricsUrl = "https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=-1&kv=-1&tv=-1&yv=1"
            val lyricsReq = Request.Builder()
                .url(lyricsUrl)
                .header("Referer", "https://music.163.com")
                .header("Cookie", "appver=2.0.2")
                .build()

            val lyricsResp = httpClient.newCall(lyricsReq).await()
            if (!lyricsResp.isSuccessful) return null

            val lyricsJson = lyricsResp.body?.string()?.toData<JsonElement>()?.getOrNull() ?: return null

            val yrc = lyricsJson.safeObj()?.get("yrc")?.safeObj()?.get("lyric")?.asString
            if (!yrc.isNullOrBlank()) {
                parseYrc(yrc)?.let { return it }
            }
            null
        } catch (e: Exception) { null }
    }

    private fun String.clean() = this
        .replace(Regex("(?i)\\(.*?remaster.*?\\)"), "")
        .replace(Regex("(?i)\\[.*?official.*?]"), "")
        .replace(Regex("(?i)\\(.*?video.*?\\)"), "")
        .replace(Regex("(?i)\\(.*?lyrics.*?\\)"), "")
        .replace(Regex("(?i)\\(.*?feat.*?\\)"), "")
        .replace(Regex("(?i)\\(.*?ft\\..*?\\)"), "")
        .replace(Regex("(?i)\\(.*?radio.*?\\)"), "")
        .replace(Regex("(?i)\\(.*?edit.*?\\)"), "")
        .replace(Regex("(?i) - .*?(version|mix|edit|remaster).*", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun scoreLyrics(lyrics: Lyrics.WordByWord): Int {
        var score = 0
        var penalty = 0

        for (i in lyrics.list.indices) {
            val line = lyrics.list[i]
            if (line.isEmpty()) continue

            if (line.size > 1) {
                score += line.size * 10
                if (line.size >= 5) score += 20
            } else {
                score += 2
            }

            for (j in 1 until line.size) {
                val prev = line[j - 1]
                val curr = line[j]
                if (curr.startTime < prev.startTime) penalty += 50
                if (curr.endTime < curr.startTime) penalty += 30
                if ((curr.endTime - curr.startTime) > 30000) penalty += 20
            }
        }

        val multiWordLines = lyrics.list.count { it.size > 1 }
        if (multiWordLines < lyrics.list.size / 3) penalty += 100

        return maxOf(0, score - penalty)
    }

    fun fetchRichSyncIfNeeded(lyrics: Lyrics.Lyric?, track: Track?) {
        // Se traccia è nulla, pulisci accuratamente le variabili e fermati.
        if (track == null) {
            richSyncJob?.cancel()
            richSyncFlow.value = null
            currentRichSyncTrackId = null
            return
        }

        // FIX: Se peschiamo dalla cache, dobbiamo impostare currentRichSyncTrackId,
        // altrimenti canUseKaraoke() considererà l'id non corrispondente.
        richSyncCache[track.id]?.let {
            currentRichSyncTrackId = track.id
            if (richSyncFlow.value != it) {
                richSyncFlow.value = it
            }
            applyDefaultModeForLyrics(it)
            return
        }

        if (currentRichSyncTrackId == track.id && richSyncJob?.isActive == true) return
        if (currentRichSyncTrackId == track.id && richSyncFlow.value != null) return

        richSyncJob?.cancel()
        richSyncFlow.value = null
        currentRichSyncTrackId = track.id

        if (lyrics is Lyrics.WordByWord) {
            richSyncFlow.value = lyrics
            richSyncCache[track.id] = lyrics
            applyDefaultModeForLyrics(lyrics)
            return
        }

        richSyncJob = viewModelScope.launch(Dispatchers.IO) {
            val trackSnapshot = track
            val deferreds = listOf(
                async { withTimeoutOrNull(9000L) { fetchKpoeLyricsPlus(trackSnapshot) } },
                async { withTimeoutOrNull(7000L) { fetchSpicyLyrics(trackSnapshot) } },
                async { withTimeoutOrNull(6000L) { fetchNeteaseYrc(trackSnapshot) } },
                async { withTimeoutOrNull(9000L) { fetchAppleMusicLyrics(trackSnapshot) } },
                async { withTimeoutOrNull(9000L) { fetchMusixmatchLyrics(trackSnapshot) } },
                async { withTimeoutOrNull(8000L) { fetchLrclib(trackSnapshot) } }
            )

            val results = deferreds.awaitAll().filterNotNull()
            val bestLyrics = results.maxByOrNull { scoreLyrics(it) }
            val bestScore = bestLyrics?.let { scoreLyrics(it) } ?: 0

            if (currentRichSyncTrackId != trackSnapshot.id) return@launch

            withContext(Dispatchers.Main) {
                if (currentRichSyncTrackId != trackSnapshot.id) return@withContext

                if (bestLyrics != null && bestScore > 0) {
                    richSyncCache[trackSnapshot.id] = bestLyrics
                    richSyncFlow.value = bestLyrics
                    applyDefaultModeForLyrics(bestLyrics)
                } else {
                    val cached = richSyncCache[trackSnapshot.id]
                    if (cached != null) {
                        richSyncFlow.value = cached
                        applyDefaultModeForLyrics(cached)
                    } else if (lyricsModeFlow.value == LyricsMode.KARAOKE) {
                        applyDefaultModeForLyrics(lyrics)
                    }
                }
            }
        }
    }

    fun setMode(mode: LyricsMode) {
        val lyrics = (lyricsState.value as? State.Loaded)?.result?.getOrNull()?.lyrics

        lyricsModeFlow.value = when {
            mode == LyricsMode.KARAOKE && !canUseKaraoke(lyrics, currentRichSyncTrackId) -> {
                LyricsMode.SYNCED
            }
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

    fun canUseKaraoke(
        lyrics: Lyrics.Lyric?,
        trackId: String? = currentRichSyncTrackId,
    ): Boolean {
        if (lyrics is Lyrics.WordByWord) {
            return true
        }
        if (trackId == null) {
            return false
        }
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