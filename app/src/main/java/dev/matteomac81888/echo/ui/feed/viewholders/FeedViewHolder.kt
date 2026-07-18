package dev.matteomac81888.echo.ui.feed.viewholders

import android.view.View
import dev.matteomac81888.echo.playback.PlayerState
import dev.matteomac81888.echo.ui.feed.FeedType
import dev.matteomac81888.echo.utils.ui.scrolling.ScrollAnimViewHolder

sealed class FeedViewHolder<T : FeedType>(view: View) : ScrollAnimViewHolder(view) {
    abstract fun bind(feed: T)
    open fun canBeSwiped(): Boolean = false
    open fun onSwipe(): T? = null
    open fun onCurrentChanged(current: PlayerState.Current?) {}
}