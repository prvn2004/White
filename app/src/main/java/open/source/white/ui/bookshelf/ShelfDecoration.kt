package open.source.white.ui.bookshelf

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.recyclerview.widget.RecyclerView

class ShelfDecoration(
    context: Context,
    private val rowHeightPx: Int
) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density

    // Glass shelf frosted surface
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Specular top reflection line
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD8FFFFFF.toInt() // crisp white glass reflection
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    // Frosted border outline
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66B0CDED.toInt() // subtle translucent refraction border
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    // Soft depth shadow below glass shelf
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x180E2440.toInt()
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (rowHeightPx <= 0 || parent.width <= 0) return
        val left = parent.paddingLeft.toFloat() + 8f * density
        val right = parent.width.toFloat() - parent.paddingRight.toFloat() - 8f * density
        if (right <= left) return

        val boardHeight = 12f * density
        val radius = 4f * density
        var rowBottom = parent.paddingTop.toFloat() + rowHeightPx

        while (rowBottom - boardHeight < parent.height + rowHeightPx) {
            val boardTop = rowBottom - boardHeight - 3f * density

            // 1. Soft ambient shadow under the floating glass plank
            rect.set(left + 2f * density, boardTop + 5f * density, right - 2f * density, boardTop + boardHeight + 6f * density)
            canvas.drawRoundRect(rect, radius + 2f * density, radius + 2f * density, shadowPaint)

            // 2. Translucent reflective glass body with gradient refraction
            rect.set(left, boardTop, right, boardTop + boardHeight)
            glassPaint.shader = LinearGradient(
                left, boardTop, left, boardTop + boardHeight,
                intArrayOf(0xB8FFFFFF.toInt(), 0x66E8F2FD.toInt(), 0x88D4E7FA.toInt()),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, glassPaint)

            // 3. Crisp glass bevel border
            canvas.drawRoundRect(rect, radius, radius, borderPaint)

            // 4. Sharp top specular highlight
            val hlY = boardTop + 1.2f * density
            canvas.drawLine(left + 4f * density, hlY, right - 4f * density, hlY, specularPaint)

            rowBottom += rowHeightPx
        }
    }
}

