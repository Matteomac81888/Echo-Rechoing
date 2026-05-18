package dev.matteomac81888.echo.ui.player.more.lyrics

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
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

    // Fonte di verità per il tempo: viene settato da LyricsFragment non appena
    // il browser (MediaController) è disponibile tramite playerVM.browser.
    var mediaController: MediaController? = null

    // Offset visivo: piccolo cushion per codec ad alta latenza (Bluetooth).
    // Spicy-lyrics legge currentPosition puro; noi aggiungiamo 80ms di margine.
    private val VISUAL_OFFSET_MS = 80L

    // Pre-roll: la prima parola di ogni riga si "accende" questo tempo prima del suo startTime.
    private val PRE_ROLL_MS = 150L

    // Lerp factor per smussare il progress frame per frame (0.15 = rapido, senza jitter)
    private val LERP_FACTOR = 0.15f

    // Progress smoothato per ogni parola (key = position * 1000 + wordIndex)
    private val smoothedProgress = HashMap<Int, Float>()

    object DiffCallback : DiffUtil.ItemCallback<List<Lyrics.Item>>() {
        override fun areItemsTheSame(o: List<Lyrics.Item>, n: List<Lyrics.Item>) =
            o.firstOrNull()?.startTime == n.firstOrNull()?.startTime
        override fun areContentsTheSame(o: List<Lyrics.Item>, n: List<Lyrics.Item>) = o == n
    }

    // ─── Choreographer Frame Loop ─────────────────────────────────────────────
    // Leggiamo currentPosition direttamente dal MediaController ogni frame (60fps).
    // Questo elimina il lag da polling a 500ms e funziona identicamente per tutte
    // le estensioni, perché il tempo viene sempre dal player nativo Media3.
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

    // ─── ViewHolder ───────────────────────────────────────────────────────────
    inner class ViewHolder(val binding: ItemLyricKaraokeBinding) :
        RecyclerView.ViewHolder(binding.root) {
        var boundLineKey: Long = Long.MIN_VALUE
        var scrollAnimator: ValueAnimator? = null
        var lastActiveWordIndex: Int = -1

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                onLineSelected(getItem(pos)?.firstOrNull() ?: return@setOnClickListener)
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────
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
            holder.lastActiveWordIndex = -1
            buildWordViews(holder, line)
            val base = position * 1000
            repeat(line.size + 1) { smoothedProgress.remove(base + it) }
        }

        val syncTime = (mediaController?.currentPosition ?: 0L) + VISUAL_OFFSET_MS
        applyLineState(holder, position, syncTime)
    }

    // ─── View Building ────────────────────────────────────────────────────────
    private fun buildWordViews(holder: ViewHolder, line: List<Lyrics.Item>) {
        val container = holder.binding.wordsContainer
        container.removeAllViews()
        val ctx = holder.itemView.context
        val isBlank = line.isEmpty() || (line.size == 1 && line[0].text.isBlank())
        if (isBlank) { container.addView(makeWordView(ctx, "♪")); return }
        line.forEach { word ->
            val text = word.text.trim().ifEmpty { return@forEach }
            container.addView(makeWordView(ctx, text))
        }
    }

    private fun makeWordView(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(6.dpToPx(ctx), 0, 6.dpToPx(ctx), 0)
    }

    // ─── Rendering ────────────────────────────────────────────────────────────
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
            if (syncTime >= start - PRE_ROLL_MS) {
                tv.setTextColor(fullColor); lerpScale(tv, 1.05f)
            } else {
                tv.setTextColor(dimColor); lerpScale(tv, 1f)
            }
            tv.invalidate()
            return
        }

        var activeWordIndex = -1
        val base = position * 1000

        wordsViews.forEachIndexed { i, view ->
            val tv = view as? TextView ?: return@forEachIndexed
            if (i >= lineData.size) return@forEachIndexed

            val w        = lineData[i]
            val duration = (w.endTime - w.startTime).coerceAtLeast(1L)

            // Pre-roll solo sulla prima parola della riga
            val effectiveStart = if (i == 0) w.startTime - PRE_ROLL_MS else w.startTime
            val rawProgress = ((syncTime - effectiveStart).toFloat() / duration).coerceIn(0f, 1f)

            // Lerp: se il salto è > 50% (seek) bypassa per reattività immediata
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
                    activeWordIndex = i
                    tv.setTextColor(Color.WHITE)
                    lerpScale(tv, 1.05f)

                    // Blur proporzionale alla durata (breve = netto, lunga = morbido)
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

        // Scroll orizzontale sulla parola attiva
        if (activeWordIndex != -1 && holder.lastActiveWordIndex != activeWordIndex) {
            holder.lastActiveWordIndex = activeWordIndex
            wordsViews.getOrNull(activeWordIndex)?.let { target ->
                val sv = holder.binding.wordsScrollView
                if (sv.width > 0) {
                    val center = target.left + target.width / 2
                    holder.smoothScrollTo((center - sv.width / 2).coerceAtLeast(0))
                }
            }
        } else if (activeWordIndex == -1 &&
            syncTime < ((lineData.firstOrNull()?.startTime ?: 0L) - PRE_ROLL_MS)) {
            if (holder.lastActiveWordIndex != -1) {
                holder.lastActiveWordIndex = -1
                holder.smoothScrollTo(0)
            }
        }
    }

    private fun lerpScale(tv: TextView, target: Float) {
        val cur = tv.scaleX
        if (abs(cur - target) < 0.001f) return
        val next = cur + (target - cur) * 0.18f
        tv.scaleX = next; tv.scaleY = next
    }

    private fun ViewHolder.smoothScrollTo(targetX: Int) {
        val sv = binding.wordsScrollView
        scrollAnimator?.cancel()
        if (sv.scrollX == targetX) return
        scrollAnimator = ValueAnimator.ofInt(sv.scrollX, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { sv.scrollTo(it.animatedValue as Int, 0) }
            start()
        }
    }

    // ─── API pubblica ─────────────────────────────────────────────────────────
    // updateTime() e setPlaying() sono no-op: il tempo viene letto ogni frame
    // direttamente da mediaController, indipendente dallo stato di riproduzione.
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