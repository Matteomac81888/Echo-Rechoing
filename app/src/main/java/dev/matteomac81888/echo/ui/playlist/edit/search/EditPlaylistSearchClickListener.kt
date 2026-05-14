package dev.matteomac81888.echo.ui.playlist.edit.search

import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dev.matteomac81888.echo.R
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.matteomac81888.echo.ui.feed.FeedClickListener

class EditPlaylistSearchClickListener(fragment: Fragment) : FeedClickListener(
    fragment, fragment.parentFragmentManager, R.id.playlistSearchContainer
) {

    private val parentFragment: Fragment
        get() = fragmentManager.fragments.first().requireParentFragment()

    private val viewModel by lazy {
        parentFragment.viewModels<EditPlaylistSearchViewModel>().value
    }

    override fun onTracksClicked(
        view: View?, extensionId: String?, context: EchoMediaItem?, tracks: List<Track>?, pos: Int
    ): Boolean {
        val track = tracks?.getOrNull(pos) ?: return notFoundSnack(R.string.track)
        viewModel.addTrack(track)
        return true
    }

    override fun onMediaClicked(
        view: View?, extensionId: String?, item: EchoMediaItem?, context: EchoMediaItem?
    ) = when (item) {
        is Track -> {
            viewModel.addTrack(item)
            true
        }

        else -> super.onMediaClicked(view, extensionId, item, context)
    }
}