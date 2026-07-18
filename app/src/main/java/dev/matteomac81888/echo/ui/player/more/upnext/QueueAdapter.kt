package dev.matteomac81888.echo.ui.player.more.upnext

import android.annotation.SuppressLint
import android.graphics.drawable.Animatable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.matteomac81888.echo.R
import dev.brahmkshatriya.echo.common.models.Track
import dev.matteomac81888.echo.databinding.ItemPlaylistTrackBinding
import dev.matteomac81888.echo.playback.MediaItemUtils.isLoaded
import dev.matteomac81888.echo.playback.MediaItemUtils.track
import dev.matteomac81888.echo.ui.feed.viewholders.MediaViewHolder.Companion.subtitle
import dev.matteomac81888.echo.utils.image.ImageUtils.loadInto
import dev.matteomac81888.echo.utils.ui.AnimationUtils.applyTranslationYAnimation
import dev.matteomac81888.echo.utils.ui.UiUtils.marquee
import dev.matteomac81888.echo.utils.ui.scrolling.ScrollAnimViewHolder

class QueueAdapter(
    private val listener: Listener,
    private val inactive: Boolean = false
) : ListAdapter<Pair<Boolean?, MediaItem>, QueueAdapter.ViewHolder>(DiffCallback) {

    /** Set of mediaIds that are currently selected. Updated by the fragment. */
    private var selectedMediaIds: Set<String> = emptySet()

    object DiffCallback : DiffUtil.ItemCallback<Pair<Boolean?, MediaItem>>() {
        override fun areItemsTheSame(
            oldItem: Pair<Boolean?, MediaItem>,
            newItem: Pair<Boolean?, MediaItem>
        ) = oldItem.second.mediaId == newItem.second.mediaId

        override fun areContentsTheSame(
            oldItem: Pair<Boolean?, MediaItem>,
            newItem: Pair<Boolean?, MediaItem>
        ) = oldItem == newItem
    }

    open class Listener {
        open fun onItemClicked(position: Int) {}
        open fun onItemClosedClicked(position: Int) {}
        open fun onDragHandleTouched(viewHolder: RecyclerView.ViewHolder) {}
        open fun onItemLongClicked(position: Int) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class ViewHolder(
        val binding: ItemPlaylistTrackBinding
    ) : ScrollAnimViewHolder(binding.root) {

        init {
            binding.playlistItemClose.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                listener.onItemClosedClicked(pos)
            }

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                listener.onItemClicked(pos)
            }

            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                listener.onItemLongClicked(pos)
                true
            }

            binding.playlistItemDrag.setOnTouchListener { _, event ->
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnTouchListener false
                if (event.actionMasked != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
                listener.onDragHandleTouched(this)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ViewHolder(ItemPlaylistTrackBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.onBind(position)
        holder.itemView.applyTranslationYAnimation(scrollAmount)
    }

    /**
     * Submit a filtered list along with the set of selected mediaIds.
     * The fragment resolves real-position selections to mediaIds before calling this.
     */
    fun submitListWithSelectedIds(
        items: List<Pair<Boolean?, MediaItem>>,
        selectedIds: Set<String>,
        commitCallback: (() -> Unit)? = null
    ) {
        this.selectedMediaIds = selectedIds
        submitList(items, commitCallback)
    }

    private fun ViewHolder.onBind(position: Int) {
        val (current, item) = getItem(position)
        val isCurrent = current != null
        val isPlaying = current == true
        val track = item.track
        val isSelected = selectedMediaIds.contains(item.mediaId)

        binding.bind(track)
        binding.isPlaying(isPlaying)
        binding.playlistItemClose.isVisible = !inactive && !isSelected
        binding.playlistItemDrag.isVisible = !inactive && !isSelected
        binding.playlistItem.alpha = if (inactive) 0.5f else 1f

        // Selection highlight: reuse playlistCurrentItem view with a distinct tint
        binding.playlistCurrentItem.isVisible = isCurrent || isSelected
        if (isSelected) {
            binding.playlistCurrentItem.alpha = 0.28f
            val colorSecondary = com.google.android.material.color.MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorSecondary
            )
            binding.playlistCurrentItem.backgroundTintList =
                android.content.res.ColorStateList.valueOf(colorSecondary)
        } else if (isCurrent) {
            binding.playlistCurrentItem.alpha = 0.15f
            binding.playlistCurrentItem.backgroundTintList = null
        }
        binding.playlistProgressBar.isVisible = isCurrent && !item.isLoaded
    }

    private var scrollAmount: Int = 0
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            scrollAmount = dy
        }
    }

    var recyclerView: RecyclerView? = null
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
        recyclerView.addOnScrollListener(scrollListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnScrollListener(scrollListener)
        this.recyclerView = null
    }

    companion object {
        fun ItemPlaylistTrackBinding.bind(track: Track) {
            playlistItemTitle.run {
                text = track.title
                marquee()
            }

            track.cover.loadInto(playlistItemImageView, R.drawable.art_music)
            val subtitle = track.subtitle(root.context)
            playlistItemAuthor.run {
                isVisible = !subtitle.isNullOrEmpty()
                text = subtitle
                marquee()
            }
        }

        fun ItemPlaylistTrackBinding.isPlaying(isPlaying: Boolean) {
            playlistItemNowPlaying.isVisible = isPlaying
            (playlistItemNowPlaying.drawable as Animatable).start()
        }
    }
}
