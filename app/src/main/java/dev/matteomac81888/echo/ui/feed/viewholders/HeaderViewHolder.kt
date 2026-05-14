package dev.matteomac81888.echo.ui.feed.viewholders

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import dev.matteomac81888.echo.databinding.ItemShelfHeaderBinding
import dev.matteomac81888.echo.ui.feed.FeedClickListener
import dev.matteomac81888.echo.ui.feed.FeedType

class HeaderViewHolder(
    parent: ViewGroup,
    listener: FeedClickListener,
    private val binding: ItemShelfHeaderBinding = ItemShelfHeaderBinding.inflate(
        LayoutInflater.from(parent.context), parent, false
    )
) : FeedViewHolder<FeedType.Header>(binding.root) {

    private var feed: FeedType.Header? = null

    init {
        binding.more.setOnClickListener {
            listener.openFeed(
                it,
                feed?.extensionId,
                feed?.id,
                feed?.title,
                feed?.subtitle,
                feed?.more
            )
        }
        binding.shuffle.setOnClickListener {
            listener.onPlayClicked(it, feed?.extensionId, feed?.context, feed?.tracks, true)
        }
    }

    override fun bind(feed: FeedType.Header) {
        this.feed = feed
        binding.title.text = feed.title
        binding.title.isVisible = feed.title.isNotEmpty()
        binding.subtitle.text = feed.subtitle
        binding.subtitle.isVisible = !feed.subtitle.isNullOrEmpty()
        binding.shuffle.isVisible = feed.tracks != null
        binding.more.isVisible = feed.more != null
    }
}