package dev.matteomac81888.echo.ui.playlist.save

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.matteomac81888.echo.R
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditorListenerClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track
import dev.matteomac81888.echo.di.App
import dev.matteomac81888.echo.extensions.ExtensionLoader
import dev.matteomac81888.echo.extensions.ExtensionUtils.getAs
import dev.matteomac81888.echo.extensions.ExtensionUtils.getExtensionOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SaveToPlaylistViewModel(
    private val extensionId: String,
    private val item: EchoMediaItem?,
    private val preloadedTracks: List<Track>?,
    private val app: App,
    extensionLoader: ExtensionLoader,
) : ViewModel() {

    private val extensions = extensionLoader.music
    private val extensionFlow = MutableStateFlow<Extension<*>?>(null)

    sealed class SaveState {
        data object Initial : SaveState()
        data object LoadingTracks : SaveState()
        data class LoadingPlaylist(val playlist: Playlist) : SaveState()
        data class Saving(val playlist: Playlist, val tracks: List<Track>) : SaveState()
        data class Saved(val success: Boolean) : SaveState()
    }

    val saveFlow = MutableStateFlow<SaveState>(SaveState.Initial)

    fun saveTracks() = viewModelScope.launch(Dispatchers.IO) {
        saveFlow.value = SaveState.LoadingTracks

        val result = runCatching {
            val extension = extensionFlow.value!!
            val playlists = when (val state = playlistsFlow.value) {
                is PlaylistState.Loaded -> state.list!!.mapNotNull { if (it.second) it.first else null }
                else -> throw IllegalStateException("Playlists not loaded")
            }
            if (playlists.isEmpty()) return@runCatching false

            val tracksToSave = preloadedTracks ?: when (val currentItem = item) {
                is Album -> extension.getAs<AlbumClient, List<Track>> {
                    loadTracks(currentItem)?.loadAll().orEmpty()
                }.getOrThrow()
                is Playlist -> extension.getAs<PlaylistClient, List<Track>> {
                    loadTracks(currentItem).loadAll()
                }.getOrThrow()
                is Radio -> extension.getAs<RadioClient, List<Track>> {
                    loadTracks(currentItem).loadAll()
                }.getOrThrow()
                is Track -> listOf(currentItem)
                else -> emptyList()
            }.orEmpty()

            if (tracksToSave.isEmpty()) return@runCatching false

            playlists.forEach { playlist ->
                extension.getAs<PlaylistEditClient, Unit> {
                    saveFlow.value = SaveState.LoadingPlaylist(playlist)
                    val loaded = loadPlaylist(playlist)
                    check(loaded.isEditable)

                    val playlistTracks = loadTracks(loaded).loadAll()
                    saveFlow.value = SaveState.Saving(loaded, tracksToSave)

                    (this as? PlaylistEditorListenerClient)?.onEnterPlaylistEditor(loaded, playlistTracks)

                    addTracksToPlaylist(loaded, playlistTracks, playlistTracks.size, tracksToSave)

                    (this as? PlaylistEditorListenerClient)?.onExitPlaylistEditor(loaded, playlistTracks + tracksToSave)
                }.getOrThrow()
            }

            val message = if (playlists.size == 1)
                app.context.getString(R.string.saved_to_x, playlists.first().title)
            else app.context.getString(R.string.saved_to_playlists)

            app.messageFlow.emit(Message(message))
            true
        }.getOrElse {
            app.throwFlow.emit(it)
            false
        }

        saveFlow.value = SaveState.Saved(result)
    }

    sealed class PlaylistState {
        data object Initial : PlaylistState()
        data object Loading : PlaylistState()
        data class Loaded(val list: List<Pair<Playlist, Boolean>>?) : PlaylistState()
    }

    val playlistsFlow = MutableStateFlow<PlaylistState>(PlaylistState.Initial)

    private suspend fun loadPlaylists(): List<Pair<Playlist, Boolean>> {
        val extension = extensions.getExtensionOrThrow(extensionId)
        return extension.getAs<PlaylistEditClient, List<Pair<Playlist, Boolean>>> {
            listEditablePlaylists(item as? Track)
        }.getOrThrow()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            playlistsFlow.value = PlaylistState.Loading
            val result = runCatching { loadPlaylists() }.getOrElse {
                app.throwFlow.emit(it)
                null
            }
            playlistsFlow.value = PlaylistState.Loaded(result)
        }
    }

    fun togglePlaylist(playlist: Playlist) {
        val state = playlistsFlow.value
        if (state !is PlaylistState.Loaded) return
        val newList = state.list?.toMutableList() ?: return
        val index = newList.indexOfFirst { it.first.id == playlist.id }
        if (index == -1) return
        newList[index] = newList[index].copy(second = !newList[index].second)
        playlistsFlow.value = PlaylistState.Loaded(newList)
    }

    fun toggleAll(selected: Boolean) {
        val state = playlistsFlow.value
        if (state !is PlaylistState.Loaded) return
        val newList = state.list?.map { it.copy(second = selected) } ?: return
        playlistsFlow.value = PlaylistState.Loaded(newList)
    }

    init {
        viewModelScope.launch {
            extensions.collectLatest {
                extensionFlow.value = it.find { ext -> ext.id == extensionId }
            }
        }
        refresh()
    }
}