package dev.matteomac81888.echo.ui.playlist.edit

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import dev.matteomac81888.echo.R
import dev.brahmkshatriya.echo.common.models.Track
import dev.matteomac81888.echo.databinding.ItemPlaylistTrackBinding
import dev.matteomac81888.echo.ui.player.more.upnext.QueueAdapter.Companion.bind
import dev.matteomac81888.echo.utils.ui.UiUtils.dpToPx
import dev.matteomac81888.echo.utils.ui.scrolling.ScrollAnimListAdapter
import dev.matteomac81888.echo.utils.ui.scrolling.ScrollAnimViewHolder

class PlaylistTrackAdapter(
    private val listener: Listener,
) : ScrollAnimListAdapter<Track, PlaylistTrackAdapter.ViewHolder>(DiffCallback) {

    private var selectedIds: Set<String> = emptySet()
    private var isSelectionMode: Boolean = false

    fun submitListWithSelection(list: List<Track>, selected: Set<String>, selectionMode: Boolean) {
        this.selectedIds = selected
        this.isSelectionMode = selectionMode
        submitList(list)
        notifyDataSetChanged()
    }

    object DiffCallback : DiffUtil.ItemCallback<Track>() {
        override fun areItemsTheSame(oldItem: Track, newItem: Track) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Track, newItem: Track) = oldItem == newItem
    }

    interface Listener {
        fun onTrackClicked(viewHolder: ViewHolder)
        fun onTrackClosedClicked(viewHolder: ViewHolder)
        fun onTrackRootClicked(viewHolder: ViewHolder) {}
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(parent, listener)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val track = getItem(position)
        holder.bind(track)
    }

    inner class ViewHolder(
        parent: ViewGroup,
        listener: Listener,
        val binding: ItemPlaylistTrackBinding = ItemPlaylistTrackBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : ScrollAnimViewHolder(binding.root) {
        var track: Track? = null

        init {
            binding.playlistItemClose.setOnClickListener {
                listener.onTrackClosedClicked(this)
            }
            binding.playlistItemDrag.setOnTouchListener { v, event ->
                if (!isSelectionMode && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    v.performClick()
                    listener.onTrackClicked(this)
                }
                true
            }
            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    listener.onTrackRootClicked(this)
                }
            }
            val color = MaterialColors.getColor(binding.root, R.attr.echoBackground)
            binding.root.backgroundTintList = ColorStateList.valueOf(color)
            binding.playlistItemNowPlaying.isVisible = false
            binding.playlistItem.updatePaddingRelative(start = 24.dpToPx(binding.root.context))
        }

        fun bind(track: Track) {
            this.track = track
            binding.bind(track)

            binding.playlistItemDrag.isVisible = !isSelectionMode
            binding.playlistItemClose.isVisible = !isSelectionMode

            val isSelected = selectedIds.contains(track.id)

            val color = if (isSelected) {
                MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceVariant)
            } else {
                MaterialColors.getColor(binding.root, R.attr.echoBackground)
            }
            binding.root.backgroundTintList = ColorStateList.valueOf(color)
        }
    }

    companion object {
        fun getTouchHelperAndListener(
            viewModel: EditPlaylistViewModel
        ): Pair<Listener, ItemTouchHelper> {
            val callback = object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
            ) {
                override fun getMovementFlags(
                    recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
                ): Int {
                    if (viewHolder !is ViewHolder) return 0
                    if (viewModel.selectionMode.value) return 0
                    return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START)
                }
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    if (viewHolder !is ViewHolder) return false
                    if (target !is ViewHolder) return false
                    if (viewModel.selectionMode.value) return false

                    val fromPos = viewHolder.bindingAdapterPosition
                    val toPos = target.bindingAdapterPosition
                    viewModel.edit(EditPlaylistViewModel.Action.Move(fromPos, toPos))
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val pos = viewHolder.bindingAdapterPosition
                    viewModel.edit(EditPlaylistViewModel.Action.Remove(listOf(pos)))
                }
            }
            val itemTouchHelper = ItemTouchHelper(callback)

            val listener = object : Listener {
                override fun onTrackClicked(viewHolder: ViewHolder) {
                    itemTouchHelper.startDrag(viewHolder)
                }

                override fun onTrackClosedClicked(viewHolder: ViewHolder) {
                    viewModel.edit(EditPlaylistViewModel.Action.Remove(listOf(viewHolder.bindingAdapterPosition)))
                }

                override fun onTrackRootClicked(viewHolder: ViewHolder) {
                    val track = viewHolder.track ?: return
                    if (viewModel.selectionMode.value) {
                        viewModel.toggleSelection(track.id)
                    }
                }
            }
            return listener to itemTouchHelper
        }
    }
}