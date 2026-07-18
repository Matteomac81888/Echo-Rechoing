// START OF FILE main/java/dev/matteomac81888/echo/extensions/builtin/lyrics/DefaultLyricsExtension.kt
package dev.matteomac81888.echo.extensions.builtin.lyrics

import dev.brahmkshatriya.echo.common.clients.LyricsSearchClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toResourceImageHolder
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.matteomac81888.echo.BuildConfig
import dev.matteomac81888.echo.R
import dev.matteomac81888.echo.utils.Serializer.toData
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.net.URLEncoder
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Motore lyrics che interroga in parallelo tutte le fonti karaoke/sincronizzate note e restituisce
 * un elenco ordinato di candidati (dal più preciso al meno preciso), esattamente secondo questa
 * tabella di priorità:
 *
 *  1  Better Lyrics            Syllable
 *  2  Unison                   Syllable
 *  3  BiniLyrics                Syllable
 *  4  Better Lyrics Portato    Word
 *  5  Musixmatch               Word
 *  6  Better Lyrics            Line
 *  7  Unison                   Line
 *  8  YouTube Captions         Line
 *  9  BiniLyrics                Line
 * 10  LRCLib                   Line
 * 11  Better Lyrics Legato     Line
 * 12  Musixmatch               Line
 * 13  YouTube                  Unsynced
 * 14  Unison                   Unsynced
 * 15  LRCLib                   Unsynced
 *
 * "Portato" e "Legato" sono derivati localmente (senza chiamate di rete aggiuntive) a partire
 * dallo stesso payload TTML sillaba-per-sillaba restituito da Better Lyrics: il primo raggruppa
 * le sillabe in parole, il secondo fonde l'intera riga in un unico elemento.
 */
class DefaultLyricsExtension : LyricsSearchClient {

    init { Logger.getLogger("org.jaudiotagger").level = Level.OFF }

    companion object {
        const val MIN_BETTER_LYRICS_SCORE = 40
        const val LINE_SYNC_TIMEOUT_MS = 6000L // Tempo per raccogliere le fonti principali (incluso YouTube)
        const val RICHSYNC_BACKGROUND_TIMEOUT_MS = 9000L

        val metadata = Metadata(
            className = "DefaultLyricsExtension", path = "", importType = ImportType.BuiltIn,
            type = ExtensionType.LYRICS, id = "echo-default-lyrics", name = "Testi (Auto + Karaoke)",
            description = "Motore Supremo: Integra Better Lyrics, Unison, BiniLyrics, Musixmatch, LRCLib e YouTube (sottotitoli + descrizione) con matching rigoroso.",
            version = "v${BuildConfig.VERSION_CODE}", author = "Echo x Spicy",
            icon = R.drawable.ic_queue_music.toResourceImageHolder(), isEnabled = true
        )
    }

    private val httpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    private var mxmToken: String? = null
    private var mxmCookie: String? = null
    private var unisonKeyId: String? = null

    private val MXM_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Rappresenta una singola opzione di testo restituita da un provider/tipo di sincronizzazione. */
    private data class LyricCandidate(val provider: String, val syncType: String, val lyric: Lyrics.Lyric)
    private data class LrclibResult(val synced: Lyrics.Timed?, val plain: Lyrics.Simple?)
    private data class YoutubeWatchResult(val captions: Lyrics.Timed?, val description: Lyrics.Simple?)

    private suspend fun ensureMxmToken(): String? {
        if (mxmToken != null && mxmCookie != null) return mxmToken
        val req = Request.Builder()
            .url("https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=web-desktop-app-v1.0&format=json")
            .header("User-Agent", MXM_UA)
            .header("Cookie", "x-mxm-token-guid=")
            .build()
        val resp = callWithRetry(req) ?: return null
        mxmCookie = resp.headers("Set-Cookie").joinToString("; ") { it.substringBefore(";") }.ifBlank { null }
        mxmToken = resp.body?.string()?.toData<JsonElement>()?.getOrNull()?.safeObj()?.get("message")?.safeObj()?.get("body")?.safeObj()?.get("user_token")?.asString
        return mxmToken
    }

    private fun getUnisonKeyId(): String {
        if (unisonKeyId != null) return unisonKeyId!!
        unisonKeyId = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        return unisonKeyId!!
    }

    private fun mxmRequest(url: String): Request {
        val builder = Request.Builder().url(url).header("User-Agent", MXM_UA)
        mxmCookie?.let { builder.header("Cookie", it) }
        return builder.build()
    }

    private fun JsonElement?.safeObj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.safeArray(): JsonArray? = this as? JsonArray
    private val JsonElement?.asString: String? get() = try { if (this is JsonNull) null else this?.jsonPrimitive?.contentOrNull } catch (e: Exception) { null }

    private suspend fun callWithRetry(request: Request, maxRetries: Int = 1, baseDelayMs: Long = 200L): Response? {
        var attempt = 0
        while (true) {
            try {
                val resp = httpClient.newCall(request).await()
                if ((resp.code == 429 || resp.code == 503) && attempt < maxRetries) {
                    resp.close(); delay(baseDelayMs); attempt++; continue
                }
                return resp
            } catch (e: Exception) {
                if (attempt >= maxRetries) return null
                delay(baseDelayMs); attempt++
            }
        }
    }

    private fun <T> Deferred<T?>.safeResult(): T? =
        if (isCompleted && !isCancelled) runCatching { getCompleted() }.getOrNull() else null

    private fun String.clean(): String {
        var s = this; var prev: String; var iter = 0
        do {
            prev = s
            s = s.replace(Regex("(?i)[\\(\\[][^\\)\\]]*?(feat\\.?|ft\\.?|featuring)\\s+[^\\)\\]]*?[\\)\\]]"), "")
                .replace(Regex("(?i)\\s+(feat\\.?|ft\\.?|featuring)\\s+.+$"), "")
                .replace(Regex("(?i)[\\(\\[][^\\)\\]]*?(re-?master(ed)?)(\\s*\\d{4})?[^\\)\\]]*?[\\)\\]]"), "")
                .replace(Regex("(?i)[\\(\\[][^\\)\\]]*?(deluxe|bonus track|expanded|anniversary|special edition)[^\\)\\]]*?[\\)\\]]"), "")
                .replace(Regex("(?i)[\\(\\[][^\\)\\]]*?(radio edit|single version|album version|extended mix|clean version|explicit version)[^\\)\\]]*?[\\)\\]]"), "")
                .replace(Regex("(?i)[\\(\\[][^\\)\\]]*?(official\\s*(music\\s*)?video|lyric video|visualizer|\\bhd\\b|\\bhq\\b)[^\\)\\]]*?[\\)\\]]"), "")
                .replace(Regex("(?i)[\\(\\[][^\\)\\]]*?(live(\\s+(at|in|from))?.*?|acoustic(\\s+version)?)[^\\)\\]]*?[\\)\\]]"), "")
                .replace(Regex("(?i)\\s*-\\s*(re-?master(ed)?(\\s*\\d{4})?|live(\\s+(at|in|from))?.*|acoustic(\\s+version)?|radio edit|single version|deluxe.*)\\s*$"), "")
                .replace(Regex("\\s{2,}"), " ").trim()
            iter++
        } while (s != prev && s.isNotBlank() && iter < 3)
        return s.ifBlank { this.trim() }
    }

    private fun getSearchableString(s: String): String {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "") // Rimuove accenti
            .replace(Regex("[^a-zA-Z0-9]"), " ") // Mantiene solo alfanumerici
            .lowercase()
            .replace(Regex("\\s+"), " ") // Rimuove spazi doppi
            .trim()
    }

    /**
     * Algoritmo di matching infallibile: Evita i falsi positivi incrociando parole chiave.
     */
    private fun isStrictMatch(candidateTitle: String, candidateArtist: String, queryTitle: String, queryArtist: String): Boolean {
        val candT = getSearchableString(candidateTitle.clean())
        val queryT = getSearchableString(queryTitle.clean())

        if (candT.isBlank() || queryT.isBlank()) return false

        // Il titolo deve essere quasi identico o contenuto
        var tMatch = candT == queryT ||
                (candT.contains(queryT) && candT.length - queryT.length < 6) ||
                (queryT.contains(candT) && queryT.length - candT.length < 6)

        if (!tMatch) {
            val cWords = candT.split(" ")
            val qWords = queryT.split(" ")
            val overlap = cWords.intersect(qWords.toSet()).size
            val maxLen = maxOf(cWords.size, qWords.size)
            if (maxLen > 0 && (overlap.toFloat() / maxLen) >= 0.70f) {
                tMatch = true
            }
        }

        if (!tMatch) return false

        val candA = getSearchableString(candidateArtist.clean())
        val queryA = getSearchableString(queryArtist.clean())

        if (candA.isBlank() || queryA.isBlank()) return true

        val cAWords = candA.split(" ").filter { it.length > 2 }
        val qAWords = queryA.split(" ").filter { it.length > 2 }

        if (cAWords.isEmpty() || qAWords.isEmpty()) {
            return candA.contains(queryA) || queryA.contains(candA)
        }

        return cAWords.any { qAWords.contains(it) } || qAWords.any { cAWords.contains(it) }
    }

    private fun parseEnhancedLrc(lrc: String): Lyrics.WordByWord? {
        val lines = mutableListOf<List<Lyrics.Item>>()
        val lineRegex = Regex("""\[(\d+):(\d+(?:\.\d+)?)\](.*)""")
        val wordRegex = Regex("""<(\d+):(\d+(?:\.\d+)?)>([^<]*)""")
        lrc.lines().forEach { lineStr ->
            val content = lineRegex.find(lineStr)?.groupValues?.get(3) ?: return@forEach
            val wordMatches = wordRegex.findAll(content).toList()
            if (wordMatches.isNotEmpty()) {
                val words = mutableListOf<Lyrics.Item>()
                for (i in wordMatches.indices) {
                    val wStartMs = (wordMatches[i].groupValues[1].toLong() * 60000 + wordMatches[i].groupValues[2].toDouble() * 1000).toLong()
                    val text = wordMatches[i].groupValues[3].trim()
                    val wEndMs = if (i + 1 < wordMatches.size) (wordMatches[i+1].groupValues[1].toLong() * 60000 + wordMatches[i+1].groupValues[2].toDouble() * 1000).toLong() else wStartMs + 1500L
                    if (text.isNotEmpty()) words.add(Lyrics.Item(text, wStartMs, wEndMs))
                }
                if (words.isNotEmpty()) lines.add(words)
            }
        }
        return if (lines.isNotEmpty() && lines.any { it.size > 1 }) Lyrics.WordByWord(lines) else null
    }

    private fun parseSimpleLrc(lrc: String): Lyrics.Timed? {
        if (!lrc.contains("[")) return null
        val items = mutableListOf<Lyrics.Item>()
        val regex = Regex("""\[(\d{1,3}):(\d{2})[:.](\d{2,3})\](.*)""")
        val lines = lrc.split("\n")
        for (i in lines.indices) {
            val m = regex.find(lines[i]) ?: continue
            val (min, sec, ms, text) = m.destructured
            val startMs = min.toLong() * 60000 + sec.toLong() * 1000 + ms.toLong() * (if (ms.length == 2) 10 else 1)
            var endMs = startMs + 4000
            for (j in i + 1 until lines.size) {
                val nm = regex.find(lines[j]) ?: continue
                endMs = nm.groupValues[1].toLong() * 60000 + nm.groupValues[2].toLong() * 1000 + nm.groupValues[3].toLong() * (if (nm.groupValues[3].length == 2) 10 else 1)
                break
            }
            items.add(Lyrics.Item(text.trim(), startMs, endMs))
        }
        return if (items.isNotEmpty()) Lyrics.Timed(items) else null
    }

    private fun parseTtml(ttml: String): Lyrics.WordByWord? {
        val cleanTtml = ttml.replace(Regex("""<(/?)[a-zA-Z0-9-]+:([a-zA-Z0-9-]+)"""), "<$1$2")

        val lines = mutableListOf<List<Lyrics.Item>>()
        val pRegex = Regex("<p([^>]*)>(.*?)</p>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val spanRegex = Regex("<span([^>]*)>(.*?)</span>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        fun parseTimeAttr(attrString: String, key: String): Long? {
            val match = Regex("""$key=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrString)?.groupValues?.get(1) ?: return null
            val offsetMatch = Regex("""^([\d.,]+)(h|m|s|ms)$""").find(match)
            if (offsetMatch != null) {
                val value = offsetMatch.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0
                return when(offsetMatch.groupValues[2]) {
                    "h" -> (value * 3600000).toLong()
                    "m" -> (value * 60000).toLong()
                    "s" -> (value * 1000).toLong()
                    "ms" -> value.toLong()
                    else -> 0L
                }
            }
            val parts = match.replace(Regex("[^0-9.:]"), "").split(":")
            return try {
                when (parts.size) {
                    1 -> (parts[0].toDouble() * 1000).toLong()
                    2 -> (parts[0].toLong() * 60000) + (parts[1].toDouble() * 1000).toLong()
                    3 -> (parts[0].toLong() * 3600000) + (parts[1].toLong() * 60000) + (parts[2].toDouble() * 1000).toLong()
                    else -> null
                }
            } catch(e: Exception) { null }
        }

        pRegex.findAll(cleanTtml).forEach { pMatch ->
            val pAttrs = pMatch.groupValues[1]
            val pContent = pMatch.groupValues[2]

            if (pAttrs.contains("role=\"x-bg\"")) return@forEach

            val pBegin = parseTimeAttr(pAttrs, "begin") ?: 0L
            val pEnd = parseTimeAttr(pAttrs, "end") ?: (pBegin + 5000L)

            val spanMatches = spanRegex.findAll(pContent).toList()
            val words = mutableListOf<Lyrics.Item>()

            if (spanMatches.isEmpty()) {
                val text = pContent.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"").trim()
                if (text.isNotEmpty()) words.add(Lyrics.Item(text, pBegin, pEnd))
            } else {
                for (i in spanMatches.indices) {
                    val sMatch = spanMatches[i]
                    val sAttrs = sMatch.groupValues[1]
                    val sBegin = parseTimeAttr(sAttrs, "begin") ?: pBegin
                    var sEnd = parseTimeAttr(sAttrs, "end")

                    if (sEnd == null) {
                        sEnd = if (i + 1 < spanMatches.size) {
                            parseTimeAttr(spanMatches[i+1].groupValues[1], "begin") ?: pEnd
                        } else {
                            pEnd
                        }
                    }

                    val text = sMatch.groupValues[2].replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"").trim()
                    if (text.isNotEmpty()) words.add(Lyrics.Item(text, sBegin, sEnd))
                }
            }
            if (words.isNotEmpty()) lines.add(words)
        }
        return if (lines.isNotEmpty() && lines.any { it.size > 1 }) Lyrics.WordByWord(lines) else null
    }

    /**
     * Come [parseTtml], ma invece di considerare ogni <span> come un'unità a sé stante, raggruppa
     * le sillabe consecutive in parole intere. Il confine di parola viene rilevato osservando lo
     * spazio bianco finale non ripulito (rimosso invece dentro [parseTtml]): esattamente come
     * documentato dall'API di Better Lyrics, uno spazio finale nel testo di uno <span> indica la
     * fine della parola corrente.
     */
    private fun parseTtmlAsWords(ttml: String): Lyrics.WordByWord? {
        val cleanTtml = ttml.replace(Regex("""<(/?)[a-zA-Z0-9-]+:([a-zA-Z0-9-]+)"""), "<$1$2")

        val lines = mutableListOf<List<Lyrics.Item>>()
        val pRegex = Regex("<p([^>]*)>(.*?)</p>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val spanRegex = Regex("<span([^>]*)>(.*?)</span>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        fun parseTimeAttr(attrString: String, key: String): Long? {
            val match = Regex("""$key=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrString)?.groupValues?.get(1) ?: return null
            val offsetMatch = Regex("""^([\d.,]+)(h|m|s|ms)$""").find(match)
            if (offsetMatch != null) {
                val value = offsetMatch.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0
                return when (offsetMatch.groupValues[2]) {
                    "h" -> (value * 3600000).toLong()
                    "m" -> (value * 60000).toLong()
                    "s" -> (value * 1000).toLong()
                    "ms" -> value.toLong()
                    else -> 0L
                }
            }
            val parts = match.replace(Regex("[^0-9.:]"), "").split(":")
            return try {
                when (parts.size) {
                    1 -> (parts[0].toDouble() * 1000).toLong()
                    2 -> (parts[0].toLong() * 60000) + (parts[1].toDouble() * 1000).toLong()
                    3 -> (parts[0].toLong() * 3600000) + (parts[1].toLong() * 60000) + (parts[2].toDouble() * 1000).toLong()
                    else -> null
                }
            } catch (e: Exception) { null }
        }

        fun decodeEntities(s: String) = s.replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"")

        pRegex.findAll(cleanTtml).forEach { pMatch ->
            val pAttrs = pMatch.groupValues[1]
            val pContent = pMatch.groupValues[2]
            if (pAttrs.contains("role=\"x-bg\"")) return@forEach

            val pBegin = parseTimeAttr(pAttrs, "begin") ?: 0L
            val pEnd = parseTimeAttr(pAttrs, "end") ?: (pBegin + 5000L)

            val spanMatches = spanRegex.findAll(pContent).toList()
            if (spanMatches.isEmpty()) return@forEach

            val words = mutableListOf<Lyrics.Item>()
            var bufferText = StringBuilder()
            var bufferStart: Long? = null
            var bufferEnd: Long = pBegin

            fun flushBuffer() {
                val text = bufferText.toString().trim()
                val start = bufferStart
                if (text.isNotEmpty() && start != null) words.add(Lyrics.Item(text, start, bufferEnd))
                bufferText = StringBuilder()
                bufferStart = null
            }

            for (i in spanMatches.indices) {
                val sMatch = spanMatches[i]
                val sAttrs = sMatch.groupValues[1]
                val sBegin = parseTimeAttr(sAttrs, "begin") ?: pBegin
                var sEnd = parseTimeAttr(sAttrs, "end")
                if (sEnd == null) {
                    sEnd = if (i + 1 < spanMatches.size) {
                        parseTimeAttr(spanMatches[i + 1].groupValues[1], "begin") ?: pEnd
                    } else pEnd
                }

                val rawText = decodeEntities(sMatch.groupValues[2].replace(Regex("<[^>]+>"), ""))
                if (rawText.isEmpty()) continue

                if (bufferStart == null) bufferStart = sBegin
                bufferText.append(rawText)
                bufferEnd = sEnd

                if (rawText.last().isWhitespace()) flushBuffer()
            }
            flushBuffer()

            if (words.isNotEmpty()) lines.add(words)
        }
        return if (lines.isNotEmpty()) Lyrics.WordByWord(lines) else null
    }

    /** Fonde ogni riga di un [Lyrics.WordByWord] (sillabe o parole) in un'unica riga sincronizzata. */
    private fun deriveLineFromWordByWord(wbw: Lyrics.WordByWord): Lyrics.Timed? {
        val lines = wbw.list.mapNotNull { line ->
            if (line.isEmpty()) return@mapNotNull null
            val text = line.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim()
            if (text.isBlank()) return@mapNotNull null
            Lyrics.Item(text, line.first().startTime, line.last().endTime)
        }
        return if (lines.isNotEmpty()) Lyrics.Timed(lines) else null
    }

    /** Appiattisce un qualunque risultato sincronizzato in puro testo (non sincronizzato). */
    private fun derivePlainFromLyric(lyric: Lyrics.Lyric): Lyrics.Simple? = when (lyric) {
        is Lyrics.WordByWord -> {
            val text = lyric.list.mapNotNull { line ->
                line.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim().ifBlank { null }
            }.joinToString("\n")
            text.ifBlank { null }?.let { Lyrics.Simple(it) }
        }
        is Lyrics.Timed -> {
            val text = lyric.list.mapNotNull { it.text.trim().ifBlank { null } }.joinToString("\n")
            text.ifBlank { null }?.let { Lyrics.Simple(it) }
        }
        is Lyrics.Simple -> lyric.takeIf { it.text.isNotBlank() }
        else -> null
    }

    private suspend fun fetchBiniLyrics(track: Track): Lyrics.WordByWord? {
        try {
            val title = URLEncoder.encode(track.title.clean(), "UTF-8")
            val artist = URLEncoder.encode(track.artists.firstOrNull()?.name?.clean() ?: "", "UTF-8")
            val url = "https://lyrics-api.binimum.org/lyrics?song=$title&artist=$artist"
            val req = Request.Builder().url(url).build()
            val resp = callWithRetry(req) ?: return null
            val json = resp.body?.string()?.toData<JsonElement>()?.getOrNull()?.safeObj() ?: return null
            val ttml = json["lyrics"]?.asString ?: return null
            return parseTtml(ttml)
        } catch (e: Exception) {}
        return null
    }

    private suspend fun fetchUnisonLyrics(track: Track): Lyrics.Lyric? {
        try {
            val title = track.title.clean()
            val artist = track.artists.firstOrNull()?.name?.clean() ?: ""
            val videoId = URLEncoder.encode(track.id, "UTF-8")
            val tEnc = URLEncoder.encode(title, "UTF-8")
            val aEnc = URLEncoder.encode(artist, "UTF-8")
            val dur = track.duration?.let { it / 1000 } ?: 0L

            val url = "https://unison.boidu.dev/lyrics?v=$videoId&song=$tEnc&artist=$aEnc&duration=$dur"
            var req = Request.Builder().url(url).header("x-key-id", getUnisonKeyId()).build()
            var resp = callWithRetry(req)
            var json = resp?.body?.string()?.toData<JsonElement>()?.getOrNull()?.safeObj()

            if (json == null || json["success"]?.jsonPrimitive?.booleanOrNull != true) {
                val q = URLEncoder.encode("$title $artist".trim(), "UTF-8")
                val searchUrl = "https://unison.boidu.dev/lyrics/search?q=$q"
                val searchReq = Request.Builder().url(searchUrl).header("x-key-id", getUnisonKeyId()).build()
                val searchResp = callWithRetry(searchReq)
                val searchJson = searchResp?.body?.string()?.toData<JsonElement>()?.getOrNull()?.safeObj()
                val entries = searchJson?.get("data")?.safeArray()

                var lyricsId: Long? = null
                if (entries != null) {
                    for (item in entries) {
                        val obj = item.safeObj() ?: continue
                        val cTitle = obj["song"]?.asString ?: ""
                        val cArtist = obj["artist"]?.asString ?: ""
                        if (isStrictMatch(cTitle, cArtist, title, artist)) {
                            lyricsId = obj["id"]?.jsonPrimitive?.longOrNull
                            break
                        }
                    }
                }

                if (lyricsId != null) {
                    val idUrl = "https://unison.boidu.dev/lyrics/$lyricsId"
                    val idReq = Request.Builder().url(idUrl).header("x-key-id", getUnisonKeyId()).build()
                    val idResp = callWithRetry(idReq)
                    json = idResp?.body?.string()?.toData<JsonElement>()?.getOrNull()?.safeObj()
                }
            }

            if (json != null && json["success"]?.jsonPrimitive?.booleanOrNull == true) {
                val data = json["data"]?.safeObj() ?: return null
                val format = data["format"]?.asString
                val lyricsRaw = data["lyrics"]?.asString ?: return null

                return when (format) {
                    "ttml" -> parseTtml(lyricsRaw)
                    "lrc" -> parseEnhancedLrc(lyricsRaw) ?: parseSimpleLrc(lyricsRaw)
                    "plain" -> Lyrics.Simple(lyricsRaw)
                    else -> null
                }
            }
        } catch (e: Exception) {}
        return null
    }

    private suspend fun fetchMusixmatchLyrics(track: Track): Lyrics.WordByWord? {
        try {
            val token = ensureMxmToken() ?: return null
            val cleanTitle = track.title.clean()

            val titleEnc = URLEncoder.encode(cleanTitle, "UTF-8")
            val artistEnc = URLEncoder.encode(track.artists.firstOrNull()?.name?.clean() ?: "", "UTF-8")
            var searchUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.search?app_id=web-desktop-app-v1.0&usertoken=$token&format=json&q_track=$titleEnc&q_artist=$artistEnc&f_has_lyrics=1&page_size=10&s_track_rating=desc"
            var searchResp = callWithRetry(mxmRequest(searchUrl))
            var trackList = searchResp?.body?.string()?.toData<JsonElement>()?.getOrNull()?.safeObj()?.get("message")?.safeObj()?.get("body")?.safeObj()?.get("track_list")?.safeArray()

            if (trackList.isNullOrEmpty()) {
                val searchQ = URLEncoder.encode("$cleanTitle ${track.artists.firstOrNull()?.name?.clean() ?: ""}".trim(), "UTF-8")
                searchUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.search?app_id=web-desktop-app-v1.0&usertoken=$token&format=json&q=$searchQ&f_has_lyrics=1&page_size=10&s_track_rating=desc"
                searchResp = callWithRetry(mxmRequest(searchUrl))
                trackList = searchResp?.body?.string()?.toData<JsonElement>()?.getOrNull()?.safeObj()?.get("message")?.safeObj()?.get("body")?.safeObj()?.get("track_list")?.safeArray()
            }

            if (trackList.isNullOrEmpty()) return null

            for (entry in trackList) {
                val t = entry.safeObj()?.get("track")?.safeObj() ?: continue
                val id = t["track_id"]?.asString ?: continue
                if (t["has_richsync"]?.asString != "1") continue
                val cTitle = t["track_name"]?.asString ?: ""
                val cArtist = t["artist_name"]?.asString ?: ""

                if (!isStrictMatch(cTitle, cArtist, cleanTitle, track.artists.firstOrNull()?.name ?: "")) continue

                val richReq = mxmRequest("https://apic-desktop.musixmatch.com/ws/1.1/track.richsync.get?app_id=web-desktop-app-v1.0&usertoken=$token&format=json&track_id=$id")
                val richResp = callWithRetry(richReq) ?: continue
                val bodyStr = richResp.body?.string()?.toData<JsonElement>()?.getOrNull()?.safeObj()?.get("message")?.safeObj()?.get("body")?.safeObj()?.get("richsync")?.safeObj()?.get("richsync_body")?.asString ?: continue

                val array = try {
                    dev.matteomac81888.echo.utils.Serializer.json.parseToJsonElement(bodyStr).jsonArray
                } catch (e: Exception) { null } ?: continue

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
                        val absStart = tsMs + offsetMs
                        val nextOffsetMs = if (i + 1 < chars.size) ((chars[i + 1].safeObj()?.get("o")?.asString?.toDoubleOrNull() ?: 0.0) * 1000).toLong() else (teMs - tsMs)
                        words.add(Lyrics.Item(c.trim(), absStart, minOf(tsMs + nextOffsetMs, teMs)))
                    }
                    if (words.isNotEmpty()) lines.add(words)
                }
                if (lines.isNotEmpty() && lines.any { it.size > 1 }) return Lyrics.WordByWord(lines)
            }
        } catch (e: Exception) {}
        return null
    }

    private suspend fun fetchMusixmatchSubtitleLyrics(track: Track): Lyrics.Timed? {
        try {
            val token = ensureMxmToken() ?: return null
            val cleanTitle = track.title.clean()
            val titleEnc = URLEncoder.encode(cleanTitle, "UTF-8")
            val artistEnc = URLEncoder.encode(track.artists.firstOrNull()?.name?.clean() ?: "", "UTF-8")

            var searchUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.search?app_id=web-desktop-app-v1.0&usertoken=$token&format=json&q_track=$titleEnc&q_artist=$artistEnc&f_has_subtitle=1&page_size=10&s_track_rating=desc"
            var searchResp = callWithRetry(mxmRequest(searchUrl))
            var trackList = searchResp?.body?.string()?.toData<JsonElement>()?.getOrNull()?.safeObj()?.get("message")?.safeObj()?.get("body")?.safeObj()?.get("track_list")?.safeArray()

            if (trackList.isNullOrEmpty()) {
                val searchQ = URLEncoder.encode("$cleanTitle ${track.artists.firstOrNull()?.name?.clean() ?: ""}".trim(), "UTF-8")
                searchUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.search?app_id=web-desktop-app-v1.0&usertoken=$token&format=json&q=$searchQ&f_has_subtitle=1&page_size=10&s_track_rating=desc"
                searchResp = callWithRetry(mxmRequest(searchUrl))
                trackList = searchResp?.body?.string()?.toData<JsonElement>()?.getOrNull()?.safeObj()?.get("message")?.safeObj()?.get("body")?.safeObj()?.get("track_list")?.safeArray()
            }
            if (trackList.isNullOrEmpty()) return null

            for (entry in trackList) {
                val t = entry.safeObj()?.get("track")?.safeObj() ?: continue
                val id = t["track_id"]?.asString ?: continue
                if (t["has_subtitles"]?.asString != "1") continue
                val cTitle = t["track_name"]?.asString ?: ""
                val cArtist = t["artist_name"]?.asString ?: ""

                if (!isStrictMatch(cTitle, cArtist, cleanTitle, track.artists.firstOrNull()?.name ?: "")) continue

                val subReq = mxmRequest("https://apic-desktop.musixmatch.com/ws/1.1/track.subtitle.get?app_id=web-desktop-app-v1.0&usertoken=$token&format=json&track_id=$id&subtitle_format=lrc")
                val subResp = callWithRetry(subReq) ?: continue
                val lrc = subResp.body?.string()?.toData<JsonElement>()?.getOrNull()?.safeObj()?.get("message")?.safeObj()?.get("body")?.safeObj()?.get("subtitle")?.safeObj()?.get("subtitle_body")?.asString ?: continue
                val parsed = parseSimpleLrc(lrc)
                if (parsed != null) return parsed
            }
        } catch (e: Exception) {}
        return null
    }

    /**
     * Provider "Better Lyrics" (https://github.com/better-lyrics/better-lyrics), tramite la sua
     * API pubblica https://lyrics-api.boidu.dev (endpoint TTML predefinito: sillaba-per-sillaba).
     * Restituisce il TTML grezzo così com'è: da qui vengono derivate anche le varianti
     * "Portato" (parola) e "Legato" (riga), senza ulteriori chiamate di rete.
     */
    private suspend fun fetchBetterLyricsTtmlRaw(track: Track): String? {
        try {
            val title = track.title.clean()
            val artist = track.artists.firstOrNull()?.name?.clean() ?: ""
            val album = track.album?.title?.clean() ?: ""
            val dur = track.duration?.let { it / 1000 } ?: 0L

            val url = buildString {
                append("https://lyrics-api.boidu.dev/getLyrics?s=${URLEncoder.encode(title, "UTF-8")}&a=${URLEncoder.encode(artist, "UTF-8")}")
                if (album.isNotBlank()) append("&al=${URLEncoder.encode(album, "UTF-8")}")
                if (dur > 0) append("&d=$dur")
            }
            val resp = callWithRetry(Request.Builder().url(url).build()) ?: return null
            if (!resp.isSuccessful) return null
            val json = resp.body?.string()?.toData<JsonElement>()?.getOrNull()?.safeObj() ?: return null

            val score = json["score"]?.jsonPrimitive?.longOrNull
                ?: json["score"]?.jsonPrimitive?.doubleOrNull?.toLong()
            if (score != null && score < MIN_BETTER_LYRICS_SCORE) return null

            return json["ttml"]?.asString?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {}
        return null
    }

    /** Provider "Better Lyrics" via l'endpoint Kugou (riga-per-riga, formato LRC). */
    private suspend fun fetchBetterLyricsKugou(track: Track): Lyrics.Timed? {
        try {
            val title = track.title.clean()
            val artist = track.artists.firstOrNull()?.name?.clean() ?: ""
            val album = track.album?.title?.clean() ?: ""
            val dur = track.duration?.let { it / 1000 } ?: 0L

            val url = buildString {
                append("https://lyrics-api.boidu.dev/kugou/getLyrics?s=${URLEncoder.encode(title, "UTF-8")}&a=${URLEncoder.encode(artist, "UTF-8")}")
                if (album.isNotBlank()) append("&al=${URLEncoder.encode(album, "UTF-8")}")
                if (dur > 0) append("&d=$dur")
            }
            val resp = callWithRetry(Request.Builder().url(url).build()) ?: return null
            if (!resp.isSuccessful) return null
            val json = resp.body?.string()?.toData<JsonElement>()?.getOrNull()?.safeObj() ?: return null
            val lrc = json["lyrics"]?.asString ?: return null
            return parseSimpleLrc(lrc)
        } catch (e: Exception) {}
        return null
    }

    private suspend fun fetchLrclibBoth(track: Track): LrclibResult {
        var syncedResult: Lyrics.Timed? = null
        var plainResult: Lyrics.Simple? = null
        try {
            val rawTitle = track.title.clean()
            val rawArtist = track.artists.firstOrNull()?.name?.clean() ?: ""
            val title = URLEncoder.encode(rawTitle, "UTF-8")
            val artist = URLEncoder.encode(rawArtist, "UTF-8")
            val album = URLEncoder.encode(track.album?.title?.clean() ?: "", "UTF-8")
            val dur = track.duration?.let { it / 1000 } ?: 0L

            val getUrl = buildString { append("https://lrclib.net/api/get?track_name=$title&artist_name=$artist"); if (album.isNotBlank()) append("&album_name=$album"); if (dur > 0) append("&duration=$dur") }
            val getResp = callWithRetry(Request.Builder().url(getUrl).header("Lrclib-Client", "Echo v1.0").build())
            if (getResp != null && getResp.isSuccessful) {
                val json = getResp.body?.string()?.toData<JsonElement>()?.getOrNull()
                val synced = json?.safeObj()?.get("syncedLyrics")?.asString
                if (!synced.isNullOrBlank()) syncedResult = parseSimpleLrc(synced)
                json?.safeObj()?.get("plainLyrics")?.asString?.takeIf { it.isNotBlank() }?.let { plainResult = Lyrics.Simple(it) }
            }

            if (syncedResult == null || plainResult == null) {
                val searchUrl = "https://lrclib.net/api/search?track_name=$title&artist_name=$artist"
                val searchResp = callWithRetry(Request.Builder().url(searchUrl).header("Lrclib-Client", "Echo v1.0").build())
                val results = searchResp?.body?.string()?.toData<JsonElement>()?.getOrNull()?.safeArray()
                if (results != null) {
                    for (item in results) {
                        val obj = item.safeObj() ?: continue
                        val cTitle = obj["trackName"]?.asString ?: ""
                        val cArtist = obj["artistName"]?.asString ?: ""
                        if (!isStrictMatch(cTitle, cArtist, rawTitle, rawArtist)) continue

                        if (syncedResult == null) {
                            obj["syncedLyrics"]?.asString?.takeIf { it.isNotBlank() }?.let { syncedResult = parseSimpleLrc(it) }
                        }
                        if (plainResult == null) {
                            obj["plainLyrics"]?.asString?.takeIf { it.isNotBlank() }?.let { plainResult = Lyrics.Simple(it) }
                        }
                        if (syncedResult != null && plainResult != null) break
                    }
                }
            }
        } catch (e: Exception) {}
        return LrclibResult(syncedResult, plainResult)
    }

    private suspend fun fetchYoutubeWatchHtml(videoId: String): String? {
        try {
            val url = "https://www.youtube.com/watch?v=$videoId&hl=en&persist_hl=1"
            val req = Request.Builder().url(url)
                .header("User-Agent", MXM_UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "CONSENT=YES+1; PREF=hl=en")
                .build()
            val resp = callWithRetry(req) ?: return null
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        } catch (e: Exception) {}
        return null
    }

    /** Estrae una porzione di JSON (oggetto o array) bilanciata, a partire dall'indice indicato. */
    private fun extractBalancedJson(s: String, startIdx: Int): String? {
        if (startIdx >= s.length) return null
        val openChar = s[startIdx]
        val closeChar = when (openChar) { '{' -> '}'; '[' -> ']'; else -> return null }
        var depth = 0
        var inString = false
        var escape = false
        for (i in startIdx until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                openChar -> depth++
                closeChar -> { depth--; if (depth == 0) return s.substring(startIdx, i + 1) }
            }
        }
        return null
    }

    private fun extractJsonField(html: String, fieldMarker: String): String? {
        val idx = html.indexOf(fieldMarker)
        if (idx == -1) return null
        var valueStart = idx + fieldMarker.length
        while (valueStart < html.length && html[valueStart].isWhitespace()) valueStart++
        if (valueStart >= html.length) return null
        return extractBalancedJson(html, valueStart)
    }

    /** Estrae un valore stringa JSON grezzo (incluse le virgolette), per campi come "shortDescription". */
    private fun extractJsonStringField(html: String, fieldMarker: String): String? {
        val idx = html.indexOf(fieldMarker)
        if (idx == -1) return null
        var i = idx + fieldMarker.length
        while (i < html.length && html[i].isWhitespace()) i++
        if (i >= html.length || html[i] != '"') return null
        val start = i
        i++
        var escape = false
        while (i < html.length) {
            val c = html[i]
            if (escape) { escape = false; i++; continue }
            if (c == '\\') { escape = true; i++; continue }
            if (c == '"') return html.substring(start, i + 1)
            i++
        }
        return null
    }

    /**
     * Recupera in un'unica richiesta HTML sia i sottotitoli/caption ufficiali di YouTube
     * (provider "YouTube Captions", riga-per-riga) sia il testo grezzo eventualmente presente
     * nella descrizione del video (provider "YouTube", non sincronizzato).
     */
    private suspend fun fetchYoutubeWatchLyrics(track: Track): YoutubeWatchResult {
        val videoId = track.id
        if (videoId.isBlank()) return YoutubeWatchResult(null, null)
        val html = fetchYoutubeWatchHtml(videoId) ?: return YoutubeWatchResult(null, null)

        val captions = try {
            val tracksJson = extractJsonField(html, "\"captionTracks\":")
            val tracksArray = tracksJson?.toData<JsonElement>()?.getOrNull()?.safeArray()
            val chosen = tracksArray?.let { arr ->
                arr.firstOrNull { t -> (t.safeObj()?.get("languageCode")?.asString ?: "").startsWith("en") }?.safeObj()
                    ?: arr.firstOrNull()?.safeObj()
            }
            val baseUrl = chosen?.get("baseUrl")?.asString
            if (baseUrl != null) {
                val fullUrl = if (baseUrl.contains("fmt=")) baseUrl else "$baseUrl&fmt=json3"
                val capResp = callWithRetry(Request.Builder().url(fullUrl).header("User-Agent", MXM_UA).build())
                val capJson = capResp?.takeIf { it.isSuccessful }?.body?.string()?.toData<JsonElement>()?.getOrNull()?.safeObj()
                val events = capJson?.get("events")?.safeArray()
                val items = mutableListOf<Lyrics.Item>()
                events?.forEach { ev ->
                    val obj = ev.safeObj()
                    val start = obj?.get("tStartMs")?.jsonPrimitive?.longOrNull
                    val dur = obj?.get("dDurationMs")?.jsonPrimitive?.longOrNull ?: 3000L
                    val segs = obj?.get("segs")?.safeArray()
                    if (start != null && segs != null) {
                        val text = segs.joinToString("") { seg -> seg.safeObj()?.get("utf8")?.asString ?: "" }.trim()
                        if (text.isNotBlank()) items.add(Lyrics.Item(text, start, start + dur))
                    }
                }
                if (items.isNotEmpty()) Lyrics.Timed(items) else null
            } else null
        } catch (e: Exception) { null }

        val description = try {
            val descLiteral = extractJsonStringField(html, "\"shortDescription\":")
            val text = descLiteral?.toData<String>()?.getOrNull()?.trim()
            if (!text.isNullOrBlank()) {
                val meaningfulLines = text.lines().count { it.isNotBlank() }
                if (meaningfulLines >= 6 && text.length >= 100) Lyrics.Simple(text) else null
            } else null
        } catch (e: Exception) { null }

        return YoutubeWatchResult(captions, description)
    }

    /**
     * Raccoglie in parallelo (SupervisorScope) tutte le fonti native, poi assembla l'elenco
     * ordinato dei 15 candidati provider/tipo di sincronizzazione, scartando quelli assenti.
     */
    private suspend fun fetchAllLyricCandidates(track: Track, timeoutMs: Long): List<LyricCandidate> = supervisorScope {
        val betterTtmlDef = async { runCatching { fetchBetterLyricsTtmlRaw(track) }.getOrNull() }
        val unisonDef = async { runCatching { fetchUnisonLyrics(track) }.getOrNull() }
        val biniDef = async { runCatching { fetchBiniLyrics(track) }.getOrNull() }
        val mxmWordDef = async { runCatching { fetchMusixmatchLyrics(track) }.getOrNull() }
        val mxmLineDef = async { runCatching { fetchMusixmatchSubtitleLyrics(track) }.getOrNull() }
        val betterKugouDef = async { runCatching { fetchBetterLyricsKugou(track) }.getOrNull() }
        val youtubeDef = async { runCatching { fetchYoutubeWatchLyrics(track) }.getOrNull() }
        val lrclibDef = async { runCatching { fetchLrclibBoth(track) }.getOrNull() }

        val allJobs = listOf(betterTtmlDef, unisonDef, biniDef, mxmWordDef, mxmLineDef, betterKugouDef, youtubeDef, lrclibDef)
        withTimeoutOrNull(timeoutMs) { allJobs.joinAll() }
        allJobs.forEach { if (it.isActive) it.cancel() }

        val betterTtmlRaw = betterTtmlDef.safeResult()
        val unison = unisonDef.safeResult()
        val bini = biniDef.safeResult()
        val mxmWord = mxmWordDef.safeResult()
        val mxmLine = mxmLineDef.safeResult()
        val betterKugou = betterKugouDef.safeResult()
        val youtube = youtubeDef.safeResult()
        val lrclib = lrclibDef.safeResult()

        val betterSyllable = betterTtmlRaw?.let { parseTtml(it) }
        val betterPortato = betterTtmlRaw?.let { parseTtmlAsWords(it) }
        val betterLegato = betterSyllable?.let { deriveLineFromWordByWord(it) }

        val candidates = mutableListOf<LyricCandidate>()

        // 1. Better Lyrics — Syllable
        betterSyllable?.let { candidates += LyricCandidate("Better Lyrics", "Syllable", it) }
        // 2. Unison — Syllable
        (unison as? Lyrics.WordByWord)?.let { candidates += LyricCandidate("Unison", "Syllable", it) }
        // 3. BiniLyrics — Syllable
        bini?.let { candidates += LyricCandidate("BiniLyrics", "Syllable", it) }
        // 4. Better Lyrics Portato — Word
        betterPortato?.let { candidates += LyricCandidate("Better Lyrics Portato", "Word", it) }
        // 5. Musixmatch — Word
        mxmWord?.let { candidates += LyricCandidate("Musixmatch", "Word", it) }
        // 6. Better Lyrics — Line
        betterKugou?.let { candidates += LyricCandidate("Better Lyrics", "Line", it) }
        // 7. Unison — Line
        when (unison) {
            is Lyrics.WordByWord -> deriveLineFromWordByWord(unison)?.let { candidates += LyricCandidate("Unison", "Line", it) }
            is Lyrics.Timed -> candidates += LyricCandidate("Unison", "Line", unison)
            else -> {}
        }
        // 8. YouTube Captions — Line
        youtube?.captions?.let { candidates += LyricCandidate("YouTube Captions", "Line", it) }
        // 9. BiniLyrics — Line
        bini?.let { deriveLineFromWordByWord(it) }?.let { candidates += LyricCandidate("BiniLyrics", "Line", it) }
        // 10. LRCLib — Line
        lrclib?.synced?.let { candidates += LyricCandidate("LRCLib", "Line", it) }
        // 11. Better Lyrics Legato — Line
        betterLegato?.let { candidates += LyricCandidate("Better Lyrics Legato", "Line", it) }
        // 12. Musixmatch — Line
        mxmLine?.let { candidates += LyricCandidate("Musixmatch", "Line", it) }
        // 13. YouTube — Unsynced
        youtube?.description?.let { candidates += LyricCandidate("YouTube", "Unsynced", it) }
        // 14. Unison — Unsynced
        unison?.let { derivePlainFromLyric(it) }?.let { candidates += LyricCandidate("Unison", "Unsynced", it) }
        // 15. LRCLib — Unsynced
        lrclib?.plain?.let { candidates += LyricCandidate("LRCLib", "Unsynced", it) }

        candidates
    }

    /**
     * Usato in background dalla UI (vedi LyricsViewModel.scheduleBackgroundRichSyncSearch) per
     * ottenere una versione Karaoke (sillaba o parola) quando il testo mostrato è solo "riga per
     * riga". Restituisce il primo candidato utile secondo l'ordine di priorità 1..5.
     */
    suspend fun searchRichSyncOnly(track: Track): Lyrics.WordByWord? =
        fetchAllLyricCandidates(track, RICHSYNC_BACKGROUND_TIMEOUT_MS)
            .firstOrNull { it.lyric is Lyrics.WordByWord }
            ?.lyric as? Lyrics.WordByWord

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        val path = track.streamables.firstOrNull()?.id
        if (path != null && path.startsWith("/")) {
            try {
                val tag = AudioFileIO.read(File(path)).tag
                val text = tag?.getFirst(FieldKey.LYRICS)?.ifBlank { tag.getFirst("USLT") }
                if (!text.isNullOrBlank()) {
                    val parsed = parseSimpleLrc(text) ?: Lyrics.Simple(text)
                    return PagedData.Single { listOf(Lyrics("local_${track.id}", track.title, "Metadata Locali", parsed)) }.toFeed()
                }
            } catch (e: Exception) {}
        }

        val candidates = fetchAllLyricCandidates(track, LINE_SYNC_TIMEOUT_MS)
        if (candidates.isEmpty()) return PagedData.empty<Lyrics>().toFeed()

        return PagedData.Single {
            candidates.mapIndexed { index, c ->
                Lyrics(
                    id = "candidate_${index}_${track.id}",
                    title = track.title,
                    subtitle = "${c.provider} (${c.syncType})",
                    lyrics = c.lyric
                )
            }
        }.toFeed()
    }

    override suspend fun searchLyrics(query: String): Feed<Lyrics> {
        val q = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://lrclib.net/api/search?q=$q"
        return try {
            val resp = callWithRetry(Request.Builder().url(searchUrl).build()) ?: return PagedData.empty<Lyrics>().toFeed()
            val results = resp.body?.string()?.toData<JsonArray>()?.getOrNull() ?: return PagedData.empty<Lyrics>().toFeed()
            PagedData.Single {
                results.mapNotNull { item ->
                    val obj = item.safeObj() ?: return@mapNotNull null
                    val rawLyrics = obj["syncedLyrics"]?.asString ?: obj["plainLyrics"]?.asString ?: return@mapNotNull null
                    Lyrics(
                        id = obj["id"]?.asString ?: return@mapNotNull null,
                        title = obj["trackName"]?.asString ?: "Unknown",
                        subtitle = obj["artistName"]?.asString ?: "Unknown",
                        lyrics = parseEnhancedLrc(rawLyrics) ?: parseSimpleLrc(rawLyrics) ?: Lyrics.Simple(rawLyrics)
                    )
                }
            }.toFeed()
        } catch (e: Exception) { PagedData.empty<Lyrics>().toFeed() }
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics = lyrics
    override suspend fun getSettingItems(): List<Setting> = emptyList()
    override fun setSettings(settings: Settings) {}
}