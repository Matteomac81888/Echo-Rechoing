package dev.matteomac81888.echo.ui.player.more.lyrics

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.matteomac81888.echo.databinding.ItemLoadingBinding
import dev.matteomac81888.echo.databinding.ItemLyricBinding
import dev.matteomac81888.echo.ui.common.UiViewModel
import dev.matteomac81888.echo.ui.feed.FeedLoadingAdapter
import dev.matteomac81888.echo.ui.player.PlayerColors.Companion.defaultPlayerColors
import dev.matteomac81888.echo.utils.ui.AnimationUtils.applyTranslationYAnimation
import dev.matteomac81888.echo.utils.ui.scrolling.ScrollAnimListAdapter
import dev.matteomac81888.echo.utils.ui.scrolling.ScrollAnimViewHolder
import kotlin.math.abs

class LyricAdapter(
    val uiViewModel: UiViewModel, val listener: Listener,
) : ScrollAnimListAdapter<Lyrics.Item, LyricAdapter.ViewHolder>(DiffCallback) {
    fun interface Listener {
        fun onLyricSelected(adapter: LyricAdapter, lyric: Lyrics.Item)
    }

    object DiffCallback : DiffUtil.ItemCallback<Lyrics.Item>() {
        override fun areItemsTheSame(oldItem: Lyrics.Item, newItem: Lyrics.Item) =
            oldItem.startTime == newItem.startTime
        override fun areContentsTheSame(oldItem: Lyrics.Item, newItem: Lyrics.Item) =
            oldItem == newItem
    }

    var mode: LyricsMode = LyricsMode.SYNCED
        set(value) {
            field = value
            onEachViewHolder { updateCurrentState() }
        }

    var mediaController: MediaController? = null
    private val VISUAL_OFFSET_MS = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val rv = recyclerView
            if (rv != null && mode == LyricsMode.SYNCED) {
                val syncTime = (mediaController?.currentPosition ?: 0L) + VISUAL_OFFSET_MS
                for (i in 0 until rv.childCount) {
                    val vh = rv.getChildViewHolder(rv.getChildAt(i) ?: continue) as? ViewHolder ?: continue
                    if (vh.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                        vh.updateCurrentState(syncTime)
                    }
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    inner class ViewHolder(val binding: ItemLyricBinding) : ScrollAnimViewHolder(binding.root) {
        init {
            val tv = binding.root as TextView
            tv.textSize = 28f // Font più grande e "cicciotto"
            tv.typeface = Typeface.DEFAULT_BOLD // Font moderno bold

            // Distanzia leggermente i testi dai bordi laterali (padding 24dp)
            val px = 24.dpToPx(tv.context)
            tv.setPadding(px, 16, px, 16)

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val lyric = getItem(pos) ?: return@setOnClickListener
                    listener.onLyricSelected(this@LyricAdapter, lyric)
                }
            }
        }

        fun updateCurrentState(syncTime: Long = (mediaController?.currentPosition ?: 0L)) {
            val tv = binding.root as TextView
            val colors = uiViewModel.playerColors.value ?: tv.context.defaultPlayerColors()
            val fullColor = colors.onBackground or -0x1000000
            val dimColor = ColorUtils.setAlphaComponent(fullColor, 76)

            if (mode == LyricsMode.UNSYNCED) {
                tv.paint.shader = null
                tv.setTextColor(fullColor)
                tv.alpha = 1f
                lerpScale(tv, 1f)
                return
            }

            val pos = bindingAdapterPosition
            val item = runCatching { getItem(pos) }.getOrNull() ?: return

            val duration = (item.endTime - item.startTime).coerceAtLeast(1L)
            val rawProgress = ((syncTime - item.startTime).toFloat() / duration).coerceIn(0f, 1f)

            tv.paint.shader = null
            tv.alpha = 1f

            when {
                rawProgress >= 1f || syncTime > item.endTime -> {
                    tv.setTextColor(fullColor)
                    lerpScale(tv, 1.0f)
                }
                rawProgress <= 0f || syncTime < item.startTime -> {
                    tv.setTextColor(dimColor)
                    lerpScale(tv, 1.0f)
                }
                else -> {
                    tv.setTextColor(Color.WHITE)
                    lerpScale(tv, 1.05f)

                    val blur = 0.12f
                    val stop1 = (rawProgress - blur).coerceIn(0f, 1f)
                    val stop2 = (rawProgress + blur).coerceIn(0f, 1f).coerceAtLeast(stop1 + 0.001f)

                    // Cambiamento fluido top-to-bottom (Y-axis gradient) invece che statico di linea
                    tv.paint.shader = LinearGradient(
                        0f, 0f, 0f, tv.height.toFloat(),
                        intArrayOf(fullColor, fullColor, dimColor, dimColor),
                        floatArrayOf(0f, stop1, stop2, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
            }
            tv.invalidate()
        }
    }

    private fun lerpScale(tv: TextView, target: Float) {
        val cur = tv.scaleX
        if (abs(cur - target) < 0.001f) return
        val next = cur + (target - cur) * 0.18f
        tv.scaleX = next; tv.scaleY = next
    }

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemLyricBinding.inflate(inflater, parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val lyric = getItem(position) ?: return
        holder.binding.root.text = lyric.text.trim().trim('\n').ifEmpty { "♪" }
        holder.updateCurrentState()
        holder.itemView.applyTranslationYAnimation(scrollY)
    }

    fun updateColors() {
        onEachViewHolder { updateCurrentState() }
    }

    fun updateCurrent(currentPos: Int) {
        // Mantenuto solo per compatibilità
    }

    class Loading(
        parent: ViewGroup,
        val binding: ItemLoadingBinding = ItemLoadingBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : FeedLoadingAdapter.ViewHolder(binding.root)

    private fun Int.dpToPx(ctx: android.content.Context) = (this * ctx.resources.displayMetrics.density).toInt()
}