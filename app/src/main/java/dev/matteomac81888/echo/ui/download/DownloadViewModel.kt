package dev.matteomac81888.echo.ui.download

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.matteomac81888.echo.R
import dev.brahmkshatriya.echo.common.clients.DownloadClient
import dev.brahmkshatriya.echo.common.models.DownloadContext
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.Track
import dev.matteomac81888.echo.di.App
import dev.matteomac81888.echo.download.Downloader
import dev.matteomac81888.echo.extensions.ExtensionLoader
import dev.matteomac81888.echo.extensions.ExtensionUtils.getIf
import dev.matteomac81888.echo.extensions.ExtensionUtils.isClient
import dev.matteomac81888.echo.ui.common.FragmentUtils.openFragment
import dev.matteomac81888.echo.ui.extensions.add.ExtensionsAddBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted.Companion.Eagerly
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadViewModel(
    app: App,
    extensionLoader: ExtensionLoader,
    val downloader: Downloader,
) : ViewModel() {

    private val app = app.context
    private val messageFlow = app.messageFlow
    private val throwableFlow = app.throwFlow

    @OptIn(ExperimentalCoroutinesApi::class)
    val downloadExtensions = extensionLoader.misc.mapLatest { list ->
        list.filter { it.isEnabled && it.isClient<DownloadClient>() }
    }.stateIn(viewModelScope, Eagerly, null)

    val extensions = extensionLoader

    val flow = downloader.flow

    fun addToDownload(
        activity: FragmentActivity,
        extensionId: String,
        item: EchoMediaItem,
        context: EchoMediaItem?,
    ) = viewModelScope.launch(Dispatchers.IO) {
        with(activity) {
            messageFlow.emit(Message(getString(R.string.downloading_x, item.title)))
            val downloadExt = downloadExtensions.first { it != null }?.firstOrNull()
                ?: return@with messageFlow.emit(
                    Message(
                        app.getString(R.string.no_download_extension),
                        Message.Action(getString(R.string.add_extension)) {
                            ExtensionsAddBottomSheet().show(supportFragmentManager, null)
                        }
                    )
                )

            val downloads =
                downloadExt.getIf<DownloadClient, List<DownloadContext>>(throwableFlow) {
                    getDownloadTracks(extensionId, item, context)
                } ?: return@with

            if (downloads.isEmpty()) return@with messageFlow.emit(
                Message(app.getString(R.string.nothing_to_download_in_x, item.title))
            )

            downloader.add(downloads)
            messageFlow.emit(
                Message(
                    getString(R.string.download_started),
                    Message.Action(getString(R.string.view)) {
                        openFragment<DownloadFragment>()
                    }
                )
            )
        }
    }

    fun cancel(trackId: Long) {
        downloader.cancel(trackId)
    }

    fun restart(trackId: Long) {
        downloader.restart(trackId)
    }

    fun cancelAll() {
        downloader.cancelAll()
    }

    fun deleteDownload(item: EchoMediaItem) {
        when (item) {
            is Track -> downloader.deleteDownload(item.id)
            else -> downloader.deleteContext(item.id)
        }
        viewModelScope.launch {
            messageFlow.emit(
                Message(app.getString(R.string.removed_x_from_downloads, item.title))
            )
        }
    }
}