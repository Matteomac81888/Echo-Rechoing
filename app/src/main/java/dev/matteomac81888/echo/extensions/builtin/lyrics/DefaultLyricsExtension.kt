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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.net.URLEncoder
import java.util.logging.Level
import java.util.logging.Logger

class DefaultLyricsExtension : LyricsSearchClient {

    init {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    companion object {
        val metadata = Metadata(
            className = "DefaultLyricsExtension",
            path = "",
            importType = ImportType.BuiltIn,
            type = ExtensionType.LYRICS,
            id = "echo-default-lyrics",
            name = "Testi (Auto)",
            description = "Ricerca base da Metadata locali e LRCLIB. La sincronizzazione Word-by-Word è gestita nativamente dall'app.",
            version = "v${BuildConfig.VERSION_CODE}",
            author = "Echo",
            icon = R.drawable.ic_queue_music.toResourceImageHolder(),
            isEnabled = true
        )
    }

    private val lrclibClient = OkHttpClient()

    private fun extractLyricsFromMetadata(track: Track): Lyrics? {
        val path = track.streamables.firstOrNull()?.id ?: return null
        val file = File(path)
        if (!file.exists() || !path.startsWith("/")) return null
        return try {
            val tag = AudioFileIO.read(file).tag ?: return null
            val text = tag.getFirst(FieldKey.LYRICS).ifBlank {
                tag.getFirst("USLT").ifBlank { tag.getFirst("UNSYNCEDLYRICS") }
            }
            if (text.isNullOrBlank()) return null
            Lyrics(
                id = "local_${track.id}",
                title = track.title,
                subtitle = "Metadata locali",
                lyrics = parseLrc(text) ?: Lyrics.Simple(text)
            )
        } catch (e: Exception) { null }
    }

    private suspend fun fetchLrclib(track: Track): Lyrics? {
        val cleanTitle = clean(track.title)
        val cleanArtist = track.artists.firstOrNull()?.name?.let { clean(it) } ?: ""
        val sb = StringBuilder("https://lrclib.net/api/get?")
            .append("artist_name=${URLEncoder.encode(cleanArtist, "UTF-8")}")
            .append("&track_name=${URLEncoder.encode(cleanTitle, "UTF-8")}")
        track.album?.title?.let { sb.append("&album_name=${URLEncoder.encode(clean(it), "UTF-8")}") }
        track.duration?.let { sb.append("&duration=${it / 1000}") }

        return try {
            val resp = lrclibClient.newCall(Request.Builder().url(sb.toString()).build()).await()
            if (!resp.isSuccessful) return null
            val res = resp.body?.string()?.toData<LrclibResponse>()?.getOrNull() ?: return null
            Lyrics(
                id = res.id.toString(),
                title = res.trackName ?: track.title,
                subtitle = "LRCLIB",
                lyrics = parseLrc(res.syncedLyrics) ?: res.plainLyrics?.let { Lyrics.Simple(it) }
            )
        } catch (e: Exception) { null }
    }

    private suspend fun searchLrclib(query: String): List<LrclibResponse> {
        val url = "https://lrclib.net/api/search?q=${URLEncoder.encode(query, "UTF-8")}"
        return try {
            val resp = lrclibClient.newCall(Request.Builder().url(url).build()).await()
            resp.body?.string()?.toData<List<LrclibResponse>>()?.getOrNull() ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun parseLrc(lrc: String?): Lyrics.Timed? {
        if (lrc.isNullOrBlank() || !lrc.contains("[")) return null
        // FIXED REGEX: Rimosso il pattern errato [:.:] e sostituito con [:.]
        val regex = Regex("""\[(\d{1,3}):(\d{2})[:.](\d{2,3})\](.*)""")
        val lines = lrc.split("\n")
        val items = mutableListOf<Lyrics.Item>()
        for (i in lines.indices) {
            val m = regex.find(lines[i]) ?: continue
            val (min, sec, ms, text) = m.destructured
            val mul = if (ms.length == 2) 10 else 1
            val startMs = min.toLong() * 60000 + sec.toLong() * 1000 + ms.toLong() * mul
            var endMs = startMs + 4000
            for (j in i + 1 until lines.size) {
                val nm = regex.find(lines[j]) ?: continue
                val (nm2, ns, nms, _) = nm.destructured
                val nMul = if (nms.length == 2) 10 else 1
                endMs = nm2.toLong() * 60000 + ns.toLong() * 1000 + nms.toLong() * nMul
                break
            }
            items.add(Lyrics.Item(text.trim(), startMs, endMs))
        }
        return if (items.isEmpty()) null else Lyrics.Timed(items)
    }

    private fun clean(s: String) = s
        .replace(Regex("(?i)\\(.*remaster.*\\)"), "")
        .replace(Regex("(?i)\\[.*official.*\\]"), "")
        .replace(Regex("(?i)\\(.*video.*\\)"), "")
        .replace(Regex("(?i)\\(.*lyrics.*\\)"), "")
        .trim()

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        extractLyricsFromMetadata(track)?.let {
            return PagedData.Single { listOf(it) }.toFeed()
        }
        fetchLrclib(track)?.let { return PagedData.Single { listOf(it) }.toFeed() }
        val q = "${track.artists.firstOrNull()?.name?.let { clean(it) } ?: ""} ${clean(track.title)}"
        return searchLyrics(q)
    }

    override suspend fun searchLyrics(query: String): Feed<Lyrics> {
        val results = searchLrclib(query)
        if (results.isEmpty()) return PagedData.empty<Lyrics>().toFeed()
        return PagedData.Single {
            results.map { res ->
                Lyrics(
                    id = res.id.toString(),
                    title = res.trackName ?: "Unknown",
                    subtitle = res.artistName ?: "Unknown",
                    lyrics = parseLrc(res.syncedLyrics) ?: res.plainLyrics?.let { Lyrics.Simple(it) }
                )
            }
        }.toFeed()
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics = lyrics
    override suspend fun getSettingItems(): List<Setting> = emptyList()
    override fun setSettings(settings: Settings) {}

    @Serializable
    data class LrclibResponse(
        val id: Long,
        val trackName: String? = null,
        val artistName: String? = null,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null
    )
}
