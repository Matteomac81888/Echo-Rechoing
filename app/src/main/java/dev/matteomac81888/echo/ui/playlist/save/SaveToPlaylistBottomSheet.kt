package dev.matteomac81888.echo.ui.playlist.save

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.matteomac81888.echo.R
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.matteomac81888.echo.databinding.DialogPlaylistSaveBinding
import dev.matteomac81888.echo.ui.common.GridAdapter
import dev.matteomac81888.echo.ui.common.GridAdapter.Companion.configureGridLayout
import dev.matteomac81888.echo.ui.playlist.SelectableMediaAdapter
import dev.matteomac81888.echo.ui.playlist.create.CreatePlaylistBottomSheet
import dev.matteomac81888.echo.utils.ContextUtils.observe
import dev.matteomac81888.echo.utils.Serializer.getSerialized
import dev.matteomac81888.echo.utils.Serializer.putSerialized
import dev.matteomac81888.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import dev.matteomac81888.echo.utils.ui.UiUtils.configureBottomBar
import kotlinx.coroutines.flow.combine
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

class SaveToPlaylistBottomSheet : BottomSheetDialogFragment() {

    companion object {
        fun newInstance(extensionId: String, item: EchoMediaItem? = null, tracks: List<Track>? = null) =
            SaveToPlaylistBottomSheet().apply {
                arguments = Bundle().apply {
                    putString("extensionId", extensionId)
                    if (item != null) putSerialized("item", item)
                    if (tracks != null) putSerialized("tracks", tracks)
                }
            }
    }

    private val args by lazy { requireArguments() }
    private val extensionId by lazy { args.getString("extensionId")!! }
    private val item by lazy { args.getSerialized<EchoMediaItem>("item")?.getOrNull() }
    private val tracks by lazy { args.getSerialized<List<Track>>("tracks")?.getOrNull() }

    private val itemAdapter by lazy {
        MediaItemAdapter { _, _ -> }
    }

    private val adapter by lazy {
        SelectableMediaAdapter { _, item ->
            viewModel.togglePlaylist(item as Playlist)
        }
    }

    private val topBarAdapter by lazy {
        TopAppBarAdapter(
            { dismiss() },
            { CreatePlaylistBottomSheet().show(parentFragmentManager, null) }
        )
    }

    private var binding by autoCleared<DialogPlaylistSaveBinding>()
    private val viewModel by viewModel<SaveToPlaylistViewModel> {
        parametersOf(extensionId, item, tracks)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        binding = DialogPlaylistSaveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        configureBottomBar(binding.saveCont)
        binding.save.setOnClickListener {
            viewModel.saveTracks()
        }

        if (item != null) {
            itemAdapter.submitList(listOf(MediaItemAdapter.Item(extensionId, item!!)))
        }

        val combined = viewModel.playlistsFlow.combine(viewModel.saveFlow) { playlists, save ->
            playlists to save
        }
        configureGridLayout(
            binding.recyclerView,
            GridAdapter.Concat(
                topBarAdapter,
                itemAdapter,
                adapter.withHeader { viewModel.toggleAll(it) },
            ),
            false
        )
        // ... (parte iniziale invariata)

        observe(combined) { (state, save) ->
            val playlistLoading = state !is SaveToPlaylistViewModel.PlaylistState.Loaded
            val saving = save != SaveToPlaylistViewModel.SaveState.Initial
            val loading = playlistLoading || saving

            binding.recyclerView.isVisible = !saving
            binding.loading.root.isVisible = loading

            binding.loading.textView.text = when (save) {
                SaveToPlaylistViewModel.SaveState.Initial -> getString(R.string.loading)
                SaveToPlaylistViewModel.SaveState.LoadingTracks ->
                    getString(R.string.loading_x, item?.title ?: getString(R.string.tracks))
                is SaveToPlaylistViewModel.SaveState.LoadingPlaylist ->
                    getString(R.string.loading_x, save.playlist.title)
                is SaveToPlaylistViewModel.SaveState.Saving ->
                    getString(R.string.saving_x, save.playlist.title)
                is SaveToPlaylistViewModel.SaveState.Saved -> {
                    dismiss()
                    getString(R.string.not_loading)
                }
            }

            if (state is SaveToPlaylistViewModel.PlaylistState.Loaded) {
                if (state.list == null) {
                    dismiss()
                    return@observe
                }
                adapter.submitList(state.list)
                binding.save.isEnabled = state.list.any { it.second }
            }
        }

        parentFragmentManager.setFragmentResultListener(
            "createPlaylist", this
        ) { _, _ -> viewModel.refresh() }
    }
}