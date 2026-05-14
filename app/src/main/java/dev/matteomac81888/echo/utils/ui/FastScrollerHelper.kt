package dev.matteomac81888.echo.utils.ui

import android.view.View
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import dev.matteomac81888.echo.utils.ContextUtils.getSettings
import dev.matteomac81888.echo.utils.ui.UiUtils.dpToPx
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder


object FastScrollerHelper {
    const val SCROLL_BAR = "scroll_bar"
    fun View.isScrollBarEnabled() = context.getSettings().getBoolean(SCROLL_BAR, false)

    fun applyTo(view: RecyclerView): FastScroller? {
        view.isVerticalScrollBarEnabled = false
        if (!view.isScrollBarEnabled()) return null
        return FastScrollerBuilder(view).apply {
            useMd2Style()
            val pad = 8.dpToPx(view.context)
            setPadding(pad, pad, pad, pad)
        }.build()
    }

    fun applyTo(view: NestedScrollView): FastScroller? {
        view.isVerticalScrollBarEnabled = false
        if (!view.isScrollBarEnabled()) return null
        return FastScrollerBuilder(view).apply {
            useMd2Style()
            val pad = 8.dpToPx(view.context)
            setPadding(pad, pad, pad, pad)
        }.build()
    }

}
