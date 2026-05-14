package dev.matteomac81888.echo.ui.media.more

import android.graphics.drawable.Animatable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import dev.matteomac81888.echo.R
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.matteomac81888.echo.databinding.ItemMoreHeaderBinding
import dev.matteomac81888.echo.playback.PlayerState
import dev.matteomac81888.echo.playback.PlayerState.Current.Companion.isPlaying
import dev.matteomac81888.echo.ui.common.GridAdapter
import dev.matteomac81888.echo.ui.feed.viewholders.MediaViewHolder.Companion.applyCover
import dev.matteomac81888.echo.ui.media.MediaHeaderAdapter.Companion.typeInt
import dev.matteomac81888.echo.utils.ui.scrolling.ScrollAnimViewHolder

class MoreHeaderAdapter(
    private val onCloseClicked: () -> Unit,
    private val onItemClicked: () -> Unit
) : RecyclerView.Adapter<MoreHeaderAdapter.ViewHolder>(), GridAdapter {
    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) = count
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val holder = ViewHolder(parent)
        holder.binding.run {
            coverContainer.cover.clipToOutline = true
            coverContainer.root.setOnClickListener { onItemClicked() }
            closeButton.setOnClickListener { onCloseClicked() }
        }
        return holder
    }

    var item: EchoMediaItem? = null
        set(value) {
            field = value
            notifyItemChanged(0)
        }

    private var viewHolder: ViewHolder? = null
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = with(holder.binding) {
        viewHolder = holder
        val item = item
        holder.bind(item)
        holder.onCurrentChanged(item, current)
    }

    class ViewHolder(
        parent: ViewGroup,
        val binding: ItemMoreHeaderBinding = ItemMoreHeaderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : ScrollAnimViewHolder(binding.root) {

        fun bind(item: EchoMediaItem?) = with(binding) {
            if (item == null) return@with
            title.text = item.title
            type.text = when (item) {
                is Artist -> ""
                is EchoMediaItem.Lists -> root.context.getString(item.typeInt)
                is Track -> root.context.getString(R.string.track)
            }
            coverContainer.run { applyCover(item, cover, listBg1, listBg2, icon) }
        }

        fun onCurrentChanged(item: EchoMediaItem?, current: PlayerState.Current?) {
            binding.coverContainer.isPlaying.run {
                val isPlaying = current.isPlaying(item?.id)
                isVisible = isPlaying
                (icon as Animatable).start()
            }
        }
    }

    var current: PlayerState.Current? = null
    fun onCurrentChanged(current: PlayerState.Current?) {
        this.current = current
        viewHolder?.onCurrentChanged(item, current)
    }
}