package open.source.white.ui.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class PageScrollerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onPageSelected: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var currentPage = 1
    private var totalPages = 1
    private var isDragging = false

    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#806B7280")
    }
    private val activeDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
    }
    private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E61E293B")
        style = Paint.Style.FILL
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val bubbleRect = RectF()

    fun update(page: Int, pages: Int) {
        currentPage = page.coerceAtLeast(1)
        totalPages = pages.coerceAtLeast(1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height <= 0 || width <= 0) return

        val topY = 24f * density
        val bottomY = height - 24f * density
        val availableH = (bottomY - topY).coerceAtLeast(10f)

        val numDashes = 18
        val dashW = 14f * density
        val dashH = 3.5f * density
        val dashRadius = 1.8f * density
        val dashRight = width.toFloat() - 4f * density
        val dashLeft = dashRight - dashW

        val activeIndex = if (totalPages <= 1) 0 else {
            ((currentPage - 1).toFloat() / (totalPages - 1) * (numDashes - 1)).roundToInt().coerceIn(0, numDashes - 1)
        }

        val step = availableH / (numDashes - 1)
        val rect = RectF()
        for (i in 0 until numDashes) {
            val centerY = topY + i * step
            rect.set(dashLeft, centerY - dashH / 2, dashRight, centerY + dashH / 2)
            val paint = if (i == activeIndex) activeDashPaint else dashPaint
            canvas.drawRoundRect(rect, dashRadius, dashRadius, paint)
        }

        if (isDragging) {
            val activeY = topY + activeIndex * step
            val text = "$currentPage / $totalPages"
            val textW = bubbleTextPaint.measureText(text)
            val bubbleW = textW + 18f * density
            val bubbleH = 28f * density
            val bubbleRight = dashLeft - 8f * density
            val bubbleLeft = bubbleRight - bubbleW
            val bTop = (activeY - bubbleH / 2).coerceIn(8f * density, height - bubbleH - 8f * density)
            val bBottom = bTop + bubbleH
            bubbleRect.set(bubbleLeft, bTop, bubbleRight, bBottom)
            canvas.drawRoundRect(bubbleRect, 8f * density, 8f * density, bubbleBgPaint)

            val fontMetrics = bubbleTextPaint.fontMetrics
            val baseline = bTop + (bubbleH - (fontMetrics.descent + fontMetrics.ascent)) / 2f
            canvas.drawText(text, bubbleRect.centerX(), baseline, bubbleTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                handleTouchY(event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    handleTouchY(event.y)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    handleTouchY(event.y)
                    invalidate()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTouchY(touchY: Float) {
        val topY = 24f * density
        val bottomY = height - 24f * density
        val availableH = (bottomY - topY).coerceAtLeast(10f)
        val clampedY = touchY.coerceIn(topY, bottomY)
        val ratio = (clampedY - topY) / availableH
        val selected = 1 + (ratio * (totalPages - 1)).roundToInt()
        val page = selected.coerceIn(1, totalPages.coerceAtLeast(1))
        if (page != currentPage) {
            currentPage = page
            onPageSelected?.invoke(page)
        }
    }
}
