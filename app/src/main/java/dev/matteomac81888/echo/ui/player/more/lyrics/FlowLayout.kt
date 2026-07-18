package dev.matteomac81888.echo.ui.player.more.lyrics

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * ViewGroup leggero che dispone le view figlie in orizzontale, andando a capo
 * su una nuova riga quando lo spazio disponibile finisce, mantenendo sempre
 * l'allineamento a sinistra. Usato dalla modalità Karaoke per mostrare le
 * strofe come blocchi di testo che si colorano sul posto, invece di una
 * singola riga che scorre orizzontalmente.
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** Spazio orizzontale tra una parola e la successiva sulla stessa riga. */
    var horizontalSpacing: Int = (4 * resources.displayMetrics.density).toInt()

    /** Spazio verticale tra una riga andata a capo e la successiva. */
    var verticalSpacing: Int = (4 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)

        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = paddingTop + paddingBottom
        var widestLine = 0
        var childrenOnLine = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            val wouldOverflow = widthMode != MeasureSpec.UNSPECIFIED &&
                    childrenOnLine > 0 && lineWidth + childWidth > availableWidth

            if (wouldOverflow) {
                totalHeight += lineHeight + verticalSpacing
                widestLine = max(widestLine, lineWidth - horizontalSpacing)
                lineWidth = 0
                lineHeight = 0
                childrenOnLine = 0
            }

            lineWidth += childWidth + horizontalSpacing
            lineHeight = max(lineHeight, childHeight)
            childrenOnLine++
        }

        totalHeight += lineHeight
        widestLine = max(widestLine, lineWidth - horizontalSpacing)

        val width = if (widthMode == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(widthMeasureSpec)
        } else {
            widestLine + paddingLeft + paddingRight
        }
        setMeasuredDimension(width, resolveSize(totalHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val availableWidth = (r - l) - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        var childrenOnLine = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (childrenOnLine > 0 && x + childWidth > availableWidth) {
                x = paddingLeft
                y += lineHeight + verticalSpacing
                lineHeight = 0
                childrenOnLine = 0
            }

            child.layout(x, y, x + childWidth, y + childHeight)
            x += childWidth + horizontalSpacing
            lineHeight = max(lineHeight, childHeight)
            childrenOnLine++
        }
    }
}