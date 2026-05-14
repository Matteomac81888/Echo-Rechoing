package dev.matteomac81888.echo.ui.playlist.edit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ConcatAdapter
import dev.matteomac81888.echo.R
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.matteomac81888.echo.databinding.FragmentPlaylistEditBinding
import dev.matteomac81888.echo.ui.common.FragmentUtils.openFragment
import dev.matteomac81888.echo.ui.common.UiViewModel.Companion.applyBackPressCallback
import dev.matteomac81888.echo.ui.common.UiViewModel.Companion.applyInsetsWithChild
import dev.matteomac81888.echo.ui.feed.TabsAdapter
import dev.matteomac81888.echo.ui.playlist.edit.EditPlaylistBottomSheet.Companion.toText
import dev.matteomac81888.echo.ui.playlist.edit.search.EditPlaylistSearchFragment
import dev.matteomac81888.echo.utils.ContextUtils.observe
import dev.matteomac81888.echo.utils.Serializer.getSerialized
import dev.matteomac81888.echo.utils.Serializer.putSerialized
import dev.matteomac81888.echo.utils.ui.AnimationUtils.setupTransition
import dev.matteomac81888.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import dev.matteomac81888.echo.utils.ui.FastScrollerHelper
import dev.matteomac81888.echo.utils.ui.UiUtils.configureAppBar
import kotlinx.coroutines.flow.combine
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

class EditPlaylistFragment : Fragment() {

    companion object {
        fun getBundle(extension: String, playlist: Playlist, loaded: Boolean) = Bundle().apply {
            putString("extensionId", extension)
            putSerialized("playlist", playlist)
            putBoolean("loaded", loaded)
        }
    }

    private val args by lazy { requireArguments() }
    private val extensionId by lazy { args.getString("extensionId")!! }
    private val playlist by lazy { args.getSerialized<Playlist>("playlist")!!.getOrThrow() }
    private val loaded by lazy { args.getBoolean("loaded", false) }
    private val selectedTab by lazy { args.getString("selectedTabId").orEmpty() }

    private var binding: FragmentPlaylistEditBinding by autoCleared()
    private val vm by viewModel<EditPlaylistViewModel> {
        parametersOf(extensionId, playlist, loaded, selectedTab, -1)
    }

    private val adapter by lazy {
        val (listener, itemCallback) = PlaylistTrackAdapter.getTouchHelperAndListener(vm)
        itemCallback.attachToRecyclerView(binding.recyclerView)
        PlaylistTrackAdapter(listener)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentPlaylistEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupTransition(view)
        applyInsetsWithChild(binding.appBarLayout, binding.recyclerView, 96) { insets ->
            val layoutParams = binding.fabContainer.layoutParams as ViewGroup.MarginLayoutParams
            layoutParams.bottomMargin = insets.bottom
            layoutParams.setMargins(insets.start, 0, insets.end, insets.bottom)
            binding.fabContainer.layoutParams = layoutParams
        }

        applyBackPressCallback()
        binding.appBarLayout.configureAppBar { offset ->
            binding.toolbarOutline.alpha = offset
            binding.toolbarIconContainer.alpha = 1 - offset
        }

        val selectionBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                vm.clearSelection()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBackCallback)

        observe(vm.selectionMode) { isSelectionMode ->
            selectionBackCallback.isEnabled = isSelectionMode
            binding.toolbar.menu.clear()

            if (isSelectionMode) {
                binding.toolbar.title = "Selection"
                binding.toolbar.setNavigationIcon(R.drawable.ic_close)
                binding.toolbar.setNavigationOnClickListener { vm.clearSelection() }

                val deleteItem = binding.toolbar.menu.add(0, 1001, 0, getString(R.string.remove))
                deleteItem.setIcon(R.drawable.ic_delete)
                deleteItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            } else {
                binding.toolbar.title = ""
                binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
                binding.toolbar.setNavigationOnClickListener {
                    parentFragmentManager.popBackStack()
                }

                val selectItem = binding.toolbar.menu.add(0, 1004, 0, "Select")
                selectItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)

                val deletePlaylistItem = binding.toolbar.menu.add(0, 1002, 1, getString(R.string.delete_playlist))
                deletePlaylistItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
            }
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when(item.itemId) {
                1004 -> { vm.selectionMode.value = true; true }
                1001 -> { vm.deleteSelected(); true }
                1002 -> {
                    parentFragmentManager.setFragmentResult("delete", Bundle().apply {
                        putSerialized("playlist", playlist)
                    })
                    parentFragmentManager.popBackStack()
                    true
                }
                else -> false
            }
        }

        binding.save.setOnClickListener { vm.save() }
        observe(vm.isSaveable) { binding.save.isEnabled = it }

        binding.add.setOnClickListener {
            openFragment<EditPlaylistSearchFragment>(
                it, EditPlaylistSearchFragment.getBundle(extensionId)
            )
        }
        parentFragmentManager.setFragmentResultListener("searchedTracks", this) { _, bundle ->
            val tracks = bundle.getSerialized<List<Track>>("tracks")!!.getOrNull().orEmpty().toMutableList()
            vm.edit(EditPlaylistViewModel.Action.Add(vm.currentTracks.value?.size ?: 0, tracks))
        }

        FastScrollerHelper.applyTo(binding.recyclerView)

        val headerAdapter = EditPlaylistHeaderAdapter(this, vm)
        val tabAdapter = TabsAdapter<Tab>({ title }) { _, _, tab ->
            vm.selectedTabFlow.value = tab
        }

        binding.recyclerView.adapter = ConcatAdapter(headerAdapter, tabAdapter, adapter)

        observe(vm.dataFlow) { headerAdapter.data = it }
        observe(vm.tabsFlow) { tabAdapter.data = it }
        observe(vm.selectedTabFlow) { tabAdapter.selected = vm.tabsFlow.value.indexOf(it) }

        observe(
            vm.currentTracks.combine(vm.selectedTracks) { tracks, selected ->
                Pair(tracks, selected)
            }.combine(vm.selectionMode) { (tracks, selected), mode ->
                Triple(tracks, selected, mode)
            }
        ) { (tracks, selected, mode) ->
            adapter.submitListWithSelection(tracks.orEmpty(), selected, mode)
        }

        val combined = vm.originalList.combine(vm.saveState) { a, b -> a to b }
        observe(combined) { (tracks, save) ->
            val trackLoading = tracks == null
            val saving = save != EditPlaylistViewModel.SaveState.Initial
            val loading = trackLoading || saving
            binding.recyclerView.isVisible = !loading
            binding.fabContainer.isVisible = !loading
            binding.loading.root.isVisible = loading
            binding.loading.textView.text = save.toText(playlist, requireContext())

            if (save is EditPlaylistViewModel.SaveState.Saved) {
                if (save.result.isSuccess) {
                    parentFragmentManager.setFragmentResult("reload", bundleOf("id" to playlist.id))
                }
                parentFragmentManager.popBackStack()
            }
        }
    }
}