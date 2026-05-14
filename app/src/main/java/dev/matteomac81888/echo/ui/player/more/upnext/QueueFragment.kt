package dev.matteomac81888.echo.ui.player.more.upnext

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialSharedAxis
import dev.matteomac81888.echo.R
import dev.matteomac81888.echo.databinding.FragmentPlayerQueueBinding
import dev.matteomac81888.echo.playback.MediaItemUtils.track
import dev.matteomac81888.echo.ui.player.PlayerViewModel
import dev.matteomac81888.echo.utils.ContextUtils.observe
import dev.matteomac81888.echo.utils.ui.AnimationUtils.setupTransition
import dev.matteomac81888.echo.utils.ui.AutoClearedValue.Companion.autoClearedNullable
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class QueueFragment : Fragment() {

    private var binding by autoClearedNullable<FragmentPlayerQueueBinding>()
    private val viewModel by activityViewModel<PlayerViewModel>()

    // --- Selection state ---
    private val selectedRealPositions = mutableSetOf<Int>()
    private var selectionMode = false

    // --- Search state ---
    private var searchQuery = ""

    // Full queue snapshot for filtering
    private var fullQueue: List<Pair<Boolean?, MediaItem>> = emptyList()

    private val queueAdapter by lazy {
        QueueAdapter(object : QueueAdapter.Listener() {
            override fun onDragHandleTouched(viewHolder: RecyclerView.ViewHolder) {
                if (selectionMode) return
                touchHelper.startDrag(viewHolder)
            }

            override fun onItemClicked(position: Int) {
                if (selectionMode) {
                    toggleSelection(position)
                } else {
                    val realPos = filteredToReal(position) ?: return
                    viewModel.play(realPos)
                }
            }

            override fun onItemClosedClicked(position: Int) {
                if (selectionMode) return
                val realPos = filteredToReal(position) ?: return
                viewModel.removeQueueItem(realPos)
            }

            override fun onItemLongClicked(position: Int) {
                if (!selectionMode) enterSelectionMode()
                toggleSelection(position)
            }
        })
    }

    private val touchHelper by lazy {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.START
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (selectionMode) return false
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                val realFrom = filteredToReal(fromPos) ?: return false
                val realTo = filteredToReal(toPos) ?: return false
                viewModel.moveQueueItems(realFrom, realTo)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                val realPos = filteredToReal(pos) ?: return
                viewModel.removeQueueItem(realPos)
            }

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                if (selectionMode) return 0
                return makeMovementFlags(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                    ItemTouchHelper.START
                )
            }
        })
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentPlayerQueueBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupTransition(view, false, axis = MaterialSharedAxis.Y)
        val b = binding!!

        b.queueRecyclerView.adapter = queueAdapter
        touchHelper.attachToRecyclerView(b.queueRecyclerView)
        val manager = b.queueRecyclerView.layoutManager as LinearLayoutManager
        val screenHeight = view.resources.displayMetrics.heightPixels / 3

        // Search
        b.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                submitFiltered()
            }
        })

        // Selection bar actions
        b.cancelSelection.setOnClickListener { exitSelectionMode() }

        b.deleteSelectedBtn.setOnClickListener {
            selectedRealPositions.sortedDescending().forEach { viewModel.removeQueueItem(it) }
            exitSelectionMode()
        }

        b.moveToNextBtn.setOnClickListener {
            val currentIndex = viewModel.playerState.current.value?.index ?: 0
            val sorted = selectedRealPositions.sorted()
            var insertAt = currentIndex + 1
            sorted.forEach { from ->
                viewModel.moveQueueItems(from, insertAt.coerceAtMost(viewModel.queue.size - 1))
                insertAt++
            }
            exitSelectionMode()
        }

        fun submit() {
            val current = viewModel.playerState.current.value
            val currentIndex = current?.index
            fullQueue = viewModel.queue.mapIndexed { index, mediaItem ->
                if (currentIndex == index) current.isPlaying to current.mediaItem
                else null to mediaItem
            }
            submitFiltered {
                currentIndex ?: return@submitFiltered
                if (searchQuery.isBlank())
                    b.queueRecyclerView.scrollToPosition(currentIndex)
            }
        }

        observe(viewModel.playerState.current) { submit() }
        observe(viewModel.queueFlow) { submit() }

        val index = viewModel.playerState.current.value?.index ?: return
        manager.scrollToPositionWithOffset(index + 1, screenHeight)
    }

    private fun filteredToReal(filteredPos: Int): Int? {
        if (searchQuery.isBlank()) return filteredPos
        val filtered = getFilteredQueue()
        val item = filtered.getOrNull(filteredPos) ?: return null
        return fullQueue.indexOf(item).takeIf { it >= 0 }
    }

    private fun getFilteredQueue(): List<Pair<Boolean?, MediaItem>> {
        if (searchQuery.isBlank()) return fullQueue
        val q = searchQuery.lowercase()
        return fullQueue.filter { (_, mediaItem) ->
            val track = mediaItem.track
            track.title.lowercase().contains(q) ||
                track.artists.any { it.name.lowercase().contains(q) } ||
                (track.subtitle?.lowercase()?.contains(q) == true)
        }
    }

    private fun submitFiltered(onCommit: (() -> Unit)? = null) {
        val filtered = getFilteredQueue()
        // Resolve selected real positions to mediaIds so the adapter can highlight them
        val selectedIds = selectedRealPositions
            .mapNotNull { fullQueue.getOrNull(it)?.second?.mediaId }
            .toSet()
        queueAdapter.submitListWithSelectedIds(filtered, selectedIds, onCommit)
        binding?.noQueueResults?.isVisible = filtered.isEmpty() && searchQuery.isNotBlank()
        binding?.queueRecyclerView?.isVisible = filtered.isNotEmpty() || searchQuery.isBlank()
    }

    private fun enterSelectionMode() {
        selectionMode = true
        updateSelectionBar()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedRealPositions.clear()
        updateSelectionBar()
        submitFiltered()
    }

    private fun toggleSelection(filteredPos: Int) {
        val realPos = filteredToReal(filteredPos) ?: return
        if (!selectedRealPositions.remove(realPos)) selectedRealPositions.add(realPos)
        if (selectedRealPositions.isEmpty()) exitSelectionMode()
        else {
            updateSelectionBar()
            submitFiltered()
        }
    }

    private fun updateSelectionBar() {
        val b = binding ?: return
        b.selectionBar.isVisible = selectionMode
        b.selectedCount.text = getString(R.string.selected_n, selectedRealPositions.size)
    }
}
