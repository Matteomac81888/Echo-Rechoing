package dev.matteomac81888.echo.ui.playlist.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.matteomac81888.echo.di.App
import dev.matteomac81888.echo.extensions.ExtensionLoader
import dev.matteomac81888.echo.extensions.ExtensionUtils.getIf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class CreatePlaylistViewModel(
    val app: App,
    val extensionLoader: ExtensionLoader,
) : ViewModel() {
    val createPlaylistStateFlow =
        MutableStateFlow<CreateState>(CreateState.CreatePlaylist)
    fun createPlaylist(title: String, desc: String?) {
        val extension = extensionLoader.current.value ?: return
        createPlaylistStateFlow.value = CreateState.Creating
        viewModelScope.launch(Dispatchers.IO) {
            val playlist = extension.getIf<PlaylistEditClient, Playlist>(app.throwFlow) {
                createPlaylist(title, desc)
            }
            createPlaylistStateFlow.value = CreateState.PlaylistCreated(extension.id, playlist)
        }
    }
}