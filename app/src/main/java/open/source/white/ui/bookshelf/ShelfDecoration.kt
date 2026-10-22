package open.source.white.ui.bookshelf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import open.source.white.R

class ShelfDecoration(
    context: Context,
    private val rowHeightPx: Int
) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density
    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.shelf_board)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.shelf_edge)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14000000
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (rowHeightPx <= 0 || parent.width <= 0) return
        val left = parent.paddingLeft.toFloat() + 8f * density
        val right = parent.width.toFloat() - parent.paddingRight.toFloat() - 8f * density
        if (right <= left) return

        val boardHeight = 10f * density
        val radius = 3f * density
        var rowBottom = parent.paddingTop.toFloat() + rowHeightPx

        while (rowBottom - boardHeight < parent.height + rowHeightPx) {
            val boardTop = rowBottom - boardHeight - 2f * density

            rect.set(left + 2f * density, boardTop + 4f * density, right + 2f * density, boardTop + boardHeight + 4f * density)
            canvas.drawRoundRect(rect, radius, radius, shadowPaint)

            rect.set(left, boardTop, right, boardTop + boardHeight)
            canvas.drawRoundRect(rect, radius, radius, edgePaint)

            rect.set(left, boardTop + 2f * density, right, boardTop + boardHeight - 1f * density)
            canvas.drawRoundRect(rect, radius, radius, boardPaint)

            rowBottom += rowHeightPx
        }
    }
}

