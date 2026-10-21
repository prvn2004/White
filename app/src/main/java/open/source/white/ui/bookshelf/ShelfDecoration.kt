package open.source.white.ui.bookshelf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import open.source.white.R

class ShelfDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density
    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.shelf_board)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.shelf_edge)
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (parent.childCount == 0) return
        val layoutManager = parent.layoutManager as? GridLayoutManager ?: return
        val spanCount = layoutManager.spanCount.coerceAtLeast(1)
        val left = parent.paddingLeft.toFloat() + 8f * density
        val right = (parent.width - parent.paddingRight).toFloat() - 8f * density
        if (right <= left) return

        var index = 0
        while (index < parent.childCount) {
            val first = parent.getChildAt(index) ?: break
            val lastIndex = minOf(index + spanCount - 1, parent.childCount - 1)
            val last = parent.getChildAt(lastIndex) ?: first
            var rowBottom = first.bottom
            for (position in index..lastIndex) {
                val child = parent.getChildAt(position) ?: continue
                rowBottom = maxOf(rowBottom, child.bottom)
            }
            val boardTop = rowBottom.toFloat() + 5f * density
            val boardHeight = 9f * density
            val radius = 4f * density

            rect.set(left, boardTop, right, boardTop + boardHeight)
            canvas.drawRoundRect(rect, radius, radius, edgePaint)

            rect.set(left, boardTop + 2.2f * density, right, boardTop + boardHeight)
            canvas.drawRoundRect(rect, radius, radius, boardPaint)

            index += spanCount
        }
    }
}

