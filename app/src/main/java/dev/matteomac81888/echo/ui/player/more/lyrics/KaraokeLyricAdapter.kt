package dev.matteomac81888.echo.ui.player.more.lyrics

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.matteomac81888.echo.databinding.ItemLyricKaraokeBinding
import dev.matteomac81888.echo.ui.common.UiViewModel
import dev.matteomac81888.echo.ui.player.PlayerColors.Companion.defaultPlayerColors
import kotlin.math.abs
import kotlin.math.roundToInt

class KaraokeLyricAdapter(
    private val uiViewModel: UiViewModel,
    private val onLineSelected: (Lyrics.Item) -> Unit,
) : ListAdapter<List<Lyrics.Item>, KaraokeLyricAdapter.ViewHolder>(DiffCallback) {

    private var recyclerView: RecyclerView? = null

    var mediaController: MediaController? = null
    private val VISUAL_OFFSET_MS = 0L
    private val LERP_FACTOR = 0.15f
    private val smoothedProgress = HashMap<Int, Float>()

    object DiffCallback : DiffUtil.ItemCallback<List<Lyrics.Item>>() {
        override fun areItemsTheSame(o: List<Lyrics.Item>, n: List<Lyrics.Item>) =
            o.firstOrNull()?.startTime == n.firstOrNull()?.startTime
        override fun areContentsTheSame(o: List<Lyrics.Item>, n: List<Lyrics.Item>) = o == n
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val rv = recyclerView
            if (rv != null) {
                val syncTime = (mediaController?.currentPosition ?: 0L) + VISUAL_OFFSET_MS
                for (i in 0 until rv.childCount) {
                    val vh = rv.getChildViewHolder(rv.getChildAt(i) ?: continue) as? ViewHolder ?: continue
                    val pos = vh.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) applyLineState(vh, pos, syncTime)
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    inner class ViewHolder(val binding: ItemLyricKaraokeBinding) :
        RecyclerView.ViewHolder(binding.root) {
        var boundLineKey: Long = Long.MIN_VALUE

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    getItem(pos)?.firstOrNull()?.let { onLineSelected(it) }
                }
            }
        }
    }

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        recyclerView = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemLyricKaraokeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = getItem(position) ?: return
        val lineKey = line.firstOrNull()?.startTime ?: Long.MIN_VALUE

        if (holder.boundLineKey != lineKey) {
            holder.boundLineKey = lineKey
            buildWordViews(holder, line)
            val base = position * 1000
            repeat(line.size + 1) { smoothedProgress.remove(base + it) }
        }

        val syncTime = (mediaController?.currentPosition ?: 0L) + VISUAL_OFFSET_MS
        applyLineState(holder, position, syncTime)
    }

    private fun buildWordViews(holder: ViewHolder, line: List<Lyrics.Item>) {
        val container = holder.binding.wordsContainer
        val ctx = holder.itemView.context

        container.removeAllViews()

        val isBlank = line.isEmpty() || (line.size == 1 && line[0].text.isBlank())
        if (isBlank) {
            val tv = makeWordView(ctx, "♪")
            tv.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) getItem(pos)?.firstOrNull()?.let { onLineSelected(it) }
            }
            container.addView(tv)
            return
        }

        line.forEach { word ->
            val text = word.text.trim()
            if (text.isNotEmpty()) {
                val tv = makeWordView(ctx, text)
                tv.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onLineSelected(word)
                }
                container.addView(tv)
            }
        }
    }

    private fun makeWordView(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(6.dpToPx(ctx), 0, 6.dpToPx(ctx), 0)
    }

    private fun applyLineState(holder: ViewHolder, position: Int, syncTime: Long) {
        val colors    = uiViewModel.playerColors.value ?: holder.itemView.context.defaultPlayerColors()
        val fullColor = colors.onBackground or -0x1000000
        val dimColor  = ColorUtils.setAlphaComponent(fullColor, 76)

        val wordsViews = holder.binding.wordsContainer.children.toList()
        val lineData   = getItem(position) ?: return
        val isBlank    = lineData.isEmpty() || (lineData.size == 1 && lineData[0].text.isBlank())

        if (isBlank) {
            val tv = wordsViews.firstOrNull() as? TextView ?: return
            val start = lineData.firstOrNull()?.startTime ?: 0L
            tv.paint.shader = null
            if (syncTime >= start) {
                tv.setTextColor(fullColor); lerpScale(tv, 1.05f)
            } else {
                tv.setTextColor(dimColor); lerpScale(tv, 1f)
            }
            tv.invalidate()
            return
        }

        val base = position * 1000

        wordsViews.forEachIndexed { i, view ->
            val tv = view as? TextView ?: return@forEachIndexed
            if (i >= lineData.size) return@forEachIndexed

            val w        = lineData[i]
            val duration = (w.endTime - w.startTime).coerceAtLeast(1L)
            val rawProgress = ((syncTime - w.startTime).toFloat() / duration).coerceIn(0f, 1f)

            val key     = base + i
            val prev    = smoothedProgress[key] ?: rawProgress
            val delta   = rawProgress - prev
            val smoothed = if (abs(delta) > 0.5f) rawProgress else prev + delta * LERP_FACTOR
            smoothedProgress[key] = smoothed

            tv.paint.shader = null

            when {
                smoothed >= 1f -> { tv.setTextColor(fullColor); lerpScale(tv, 1f) }
                smoothed <= 0f -> { tv.setTextColor(dimColor);  lerpScale(tv, 1f) }
                else -> {
                    tv.setTextColor(Color.WHITE)
                    lerpScale(tv, 1.05f)

                    val blurFactor = (duration.toFloat() / 800f).coerceIn(0f, 1f)
                    val blur  = 0.08f + blurFactor * 0.12f
                    val stop1 = (smoothed - blur).coerceIn(0f, 1f)
                    val stop2 = (smoothed + blur).coerceIn(0f, 1f).coerceAtLeast(stop1 + 0.001f)

                    tv.paint.shader = LinearGradient(
                        0f, 0f, tv.width.toFloat(), 0f,
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

    fun updateTime(playerTime: Long) = Unit
    fun setPlaying(playing: Boolean) = Unit

    fun updateColors() {
        val rv = recyclerView ?: return
        val syncTime = (mediaController?.currentPosition ?: 0L) + VISUAL_OFFSET_MS
        for (i in 0 until rv.childCount) {
            val vh = rv.getChildViewHolder(rv.getChildAt(i) ?: continue) as? ViewHolder ?: continue
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) applyLineState(vh, pos, syncTime)
        }
    }

    private fun Int.dpToPx(ctx: Context) = (this * ctx.resources.displayMetrics.density).roundToInt()
}