package dev.matteomac81888.echo.di

import dev.matteomac81888.echo.download.DownloadWorker
import dev.matteomac81888.echo.download.Downloader
import dev.matteomac81888.echo.download.db.DownloadDatabase
import dev.matteomac81888.echo.extensions.ExtensionLoader
import dev.matteomac81888.echo.playback.PlayerService
import dev.matteomac81888.echo.playback.PlayerState
import dev.matteomac81888.echo.ui.common.SnackBarHandler
import dev.matteomac81888.echo.ui.common.UiViewModel
import dev.matteomac81888.echo.ui.download.DownloadViewModel
import dev.matteomac81888.echo.ui.extensions.ExtensionInfoViewModel
import dev.matteomac81888.echo.ui.extensions.ExtensionsViewModel
import dev.matteomac81888.echo.ui.extensions.add.AddViewModel
import dev.matteomac81888.echo.ui.extensions.login.LoginUserListViewModel
import dev.matteomac81888.echo.ui.extensions.login.LoginViewModel
import dev.matteomac81888.echo.ui.feed.FeedViewModel
import dev.matteomac81888.echo.ui.main.search.SearchViewModel
import dev.matteomac81888.echo.ui.media.MediaViewModel
import dev.matteomac81888.echo.ui.player.PlayerViewModel
import dev.matteomac81888.echo.ui.player.more.info.TrackInfoViewModel
import dev.matteomac81888.echo.ui.player.more.lyrics.LyricsViewModel
import dev.matteomac81888.echo.ui.playlist.create.CreatePlaylistViewModel
import dev.matteomac81888.echo.ui.playlist.delete.DeletePlaylistViewModel
import dev.matteomac81888.echo.ui.playlist.edit.EditPlaylistViewModel
import dev.matteomac81888.echo.ui.playlist.save.SaveToPlaylistViewModel
import dev.matteomac81888.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import org.koin.android.ext.koin.androidApplication
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

object DI {

    private val baseModule = module {
        single { androidApplication().getSettings() }
        singleOf(::App)
    }

    private val extensionModule = module {
        includes(baseModule)
        singleOf(::ExtensionLoader)
    }

    private val downloadModule = module {
        includes(extensionModule)
        singleOf(DownloadDatabase::create)
        singleOf(::Downloader)
        workerOf(::DownloadWorker)
    }

    private val playerModule = module {
        includes(extensionModule)
        singleOf(PlayerService::getCache)
        single { PlayerState() }
    }

    private val uiModules = module {
        singleOf(::SnackBarHandler)
        viewModelOf(::UiViewModel)

        viewModelOf(::PlayerViewModel)
        viewModelOf(::LyricsViewModel)
        viewModelOf(::TrackInfoViewModel)

        viewModelOf(::ExtensionsViewModel)
        viewModelOf(::ExtensionInfoViewModel)
        viewModelOf(::LoginUserListViewModel)
        viewModelOf(::AddViewModel)
        viewModelOf(::LoginViewModel)

        viewModelOf(::FeedViewModel)
        viewModelOf(::SearchViewModel)
        viewModelOf(::MediaViewModel)

        viewModelOf(::CreatePlaylistViewModel)
        viewModelOf(::DeletePlaylistViewModel)

        // SaveToPlaylistViewModel riceve parametri runtime (extensionId, item, preloadedTracks)
        // tramite parametersOf nell'UI. Non si può usare viewModelOf perché List<Track> è
        // soggetto a type erasure e Koin non riesce a risolverlo tramite reflection.
        // Si usa viewModel { params -> } con accesso posizionale (params[n]) per evitare
        // il problema della type erasure di List<T>.
        viewModel { params ->
            SaveToPlaylistViewModel(
                extensionId = params[0],
                item = params[1],
                preloadedTracks = params[2],
                app = get(),
                extensionLoader = get()
            )
        }

        viewModelOf(::EditPlaylistViewModel)

        viewModelOf(::DownloadViewModel)
    }

    val appModule = module {
        includes(baseModule)
        includes(extensionModule)
        includes(playerModule)
        includes(downloadModule)
        includes(uiModules)
    }
}