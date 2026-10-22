package open.source.white.ui.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.exp
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
    private var animatedActiveRatio = 0f
    private var animator: ValueAnimator? = null

    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D374151")
    }
    private val activeDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
    }
    private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F21E293B")
        style = Paint.Style.FILL
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12.5f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val bubbleRect = RectF()

    fun update(page: Int, pages: Int) {
        currentPage = page.coerceAtLeast(1)
        totalPages = pages.coerceAtLeast(1)
        val targetRatio = if (totalPages <= 1) 0f else (currentPage - 1).toFloat() / (totalPages - 1)
        if (!isDragging) {
            animateToRatio(targetRatio)
        } else {
            animatedActiveRatio = targetRatio
            invalidate()
        }
    }

    private fun animateToRatio(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(animatedActiveRatio, target).apply {
            duration = 180L
            interpolator = OvershootInterpolator(0.6f)
            addUpdateListener {
                animatedActiveRatio = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height <= 0 || width <= 0) return

        val topY = 32f * density
        val bottomY = height.toFloat() - 32f * density
        val availableH = (bottomY - topY).coerceAtLeast(20f)

        val numDashes = 28
        val step = availableH / (numDashes - 1)

        val activeContinuousIndex = animatedActiveRatio * (numDashes - 1)
        val baseDashW = 9f * density
        val maxExtraW = 24f * density
        val baseDashH = 2.8f * density
        val maxDashH = 4.2f * density
        val dashRight = width.toFloat() - 6f * density

        val rect = RectF()
        for (i in 0 until numDashes) {
            val centerY = topY + i * step
            val dist = abs(i - activeContinuousIndex)
            // Bell curve Gaussian falloff: exp(-0.5 * (dist / sigma)^2)
            val sigma = 2.2f
            val bell = exp(-0.5 * (dist / sigma) * (dist / sigma)).toFloat()

            val dashW = baseDashW + bell * maxExtraW
            val dashH = baseDashH + bell * (maxDashH - baseDashH)
            val dashLeft = dashRight - dashW
            val r = dashH / 2f

            rect.set(dashLeft, centerY - dashH / 2, dashRight, centerY + dashH / 2)

            if (bell > 0.75f) {
                canvas.drawRoundRect(rect, r, r, activeDashPaint)
            } else {
                canvas.drawRoundRect(rect, r, r, dashPaint)
            }
        }

        if (isDragging) {
            val activeY = topY + animatedActiveRatio * availableH
            val text = "p. $currentPage / $totalPages"
            val textW = bubbleTextPaint.measureText(text)
            val bubbleW = textW + 20f * density
            val bubbleH = 30f * density
            val maxDashLeft = dashRight - (baseDashW + maxExtraW)
            val bubbleRight = maxDashLeft - 10f * density
            val bubbleLeft = bubbleRight - bubbleW
            val bTop = (activeY - bubbleH / 2).coerceIn(8f * density, height - bubbleH - 8f * density)
            val bBottom = bTop + bubbleH
            bubbleRect.set(bubbleLeft, bTop, bubbleRight, bBottom)
            canvas.drawRoundRect(bubbleRect, 10f * density, 10f * density, bubbleBgPaint)

            val fontMetrics = bubbleTextPaint.fontMetrics
            val baseline = bTop + (bubbleH - (fontMetrics.descent + fontMetrics.ascent)) / 2f
            canvas.drawText(text, bubbleRect.centerX(), baseline, bubbleTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                animator?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
                handleTouchY(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    handleTouchY(event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    handleTouchY(event.y)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTouchY(touchY: Float) {
        val topY = 32f * density
        val bottomY = height.toFloat() - 32f * density
        val availableH = (bottomY - topY).coerceAtLeast(20f)
        val clampedY = touchY.coerceIn(topY, bottomY)
        val ratio = (clampedY - topY) / availableH
        animatedActiveRatio = ratio
        val page = (1 + ratio * (totalPages - 1)).roundToInt().coerceIn(1, totalPages.coerceAtLeast(1))
        if (page != currentPage) {
            currentPage = page
            onPageSelected?.invoke(page)
        }
        invalidate()
    }
}
