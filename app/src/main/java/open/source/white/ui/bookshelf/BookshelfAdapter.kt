package open.source.white.ui.bookshelf

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import open.source.white.R
import open.source.white.model.Book
import java.io.File

class BookshelfAdapter(
    private val coverWidthPx: Int,
    private val coverHeightPx: Int,
    private val onBookClick: (Book) -> Unit,
    private val onBookLongClick: (Book) -> Unit
) : RecyclerView.Adapter<BookshelfAdapter.BookHolder>() {

    private val books = ArrayList<Book>()

    fun submit(items: List<Book>) {
        books.clear()
        books.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_book, parent, false)
        val holder = BookHolder(view)
        val containerParams = holder.coverContainer.layoutParams
        containerParams.height = coverHeightPx
        holder.coverContainer.layoutParams = containerParams
        return holder
    }

    override fun getItemCount(): Int = books.size

    override fun onBindViewHolder(holder: BookHolder, position: Int) {
        val book = books[position]

        holder.title.text = book.title
        val authorText = if (book.author.isBlank()) {
            holder.itemView.context.getString(R.string.unknown_author)
        } else {
            book.author
        }
        holder.author.text = authorText
        holder.fallbackTitle.text = book.title
        holder.fallbackAuthor.text = authorText
        holder.fallback.background = gradientFor(book.title)

        holder.cover.visibility = View.GONE
        holder.cover.setImageDrawable(null)
        holder.fallback.visibility = View.VISIBLE
        holder.cover.tag = book.id

        if (book.progressPercent > 0) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = holder.itemView.context.getString(
                R.string.progress_percent,
                book.progressPercent
            )
        } else {
            holder.badge.visibility = View.GONE
        }

        val coverPath = book.coverPath
        if (coverPath != null && File(coverPath).exists()) {
            CoverLoader.load(coverPath, coverWidthPx, coverHeightPx) { bitmap ->
                if (bitmap != null && holder.cover.tag == book.id) {
                    holder.cover.setImageBitmap(bitmap)
                    holder.cover.visibility = View.VISIBLE
                    holder.fallback.visibility = View.GONE
                }
            }
        }

        holder.itemView.setOnClickListener { onBookClick(book) }
        holder.itemView.setOnLongClickListener {
            onBookLongClick(book)
            true
        }
    }

    private fun gradientFor(title: String): GradientDrawable {
        val index = Math.floorMod(title.hashCode(), PALETTE.size)
        val start = PALETTE[index]
        val end = Color.rgb(12, 32, 61)
        return GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            intArrayOf(start, end)
        )
    }

    class BookHolder(view: View) : RecyclerView.ViewHolder(view) {
        val coverContainer: FrameLayout = view.findViewById(R.id.coverContainer)
        val cover: ImageView = view.findViewById(R.id.coverImage)
        val fallback: LinearLayout = view.findViewById(R.id.coverFallback)
        val fallbackTitle: TextView = view.findViewById(R.id.fallbackTitle)
        val fallbackAuthor: TextView = view.findViewById(R.id.fallbackAuthor)
        val badge: TextView = view.findViewById(R.id.progressBadge)
        val title: TextView = view.findViewById(R.id.bookTitle)
        val author: TextView = view.findViewById(R.id.bookAuthor)
    }

    companion object {
        private val PALETTE = intArrayOf(
            Color.rgb(37, 99, 235),
            Color.rgb(29, 78, 216),
            Color.rgb(30, 64, 175),
            Color.rgb(59, 130, 246),
            Color.rgb(23, 92, 168),
            Color.rgb(42, 90, 160)
        )
    }
}

