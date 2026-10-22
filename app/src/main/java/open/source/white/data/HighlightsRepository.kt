package open.source.white.data

import android.content.Context
import open.source.white.common.AppConstants
import open.source.white.model.Highlight
import org.json.JSONArray
import java.io.File
import java.util.UUID

class HighlightsRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)

    fun getHighlights(bookId: String): List<Highlight> {
        val file = fileFor(bookId)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            val result = mutableListOf<Highlight>()
            for (index in 0 until array.length()) {
                try {
                    result.add(Highlight.fromJsonObject(bookId, array.getJSONObject(index)))
                } catch (error: Exception) {
                    // Skip a malformed entry but keep the rest.
                }
            }
            result.sortedBy { it.startOffset }
        } catch (error: Exception) {
            emptyList()
        }
    }

    fun addHighlight(
        bookId: String,
        chapterIndex: Int,
        start: Int,
        end: Int,
        text: String,
        color: Int
    ): Highlight {
        val highlight = Highlight(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            chapterIndex = chapterIndex,
            startOffset = minOf(start, end),
            endOffset = maxOf(start, end),
            text = text,
            color = color
        )
        val existing = getHighlights(bookId)
        save(bookId, existing + highlight)
        return highlight
    }

    fun deleteHighlight(bookId: String, highlightId: String) {
        save(bookId, getHighlights(bookId).filterNot { it.id == highlightId })
    }

    private fun save(bookId: String, highlights: List<Highlight>) {
        try {
            val array = JSONArray()
            highlights.sortedBy { it.startOffset }.forEach { array.put(it.toJsonObject()) }
            val file = fileFor(bookId)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(array.toString(2), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } catch (error: Exception) {
            // Persisting highlights is best effort; keep the session usable.
        }
    }

    private fun fileFor(bookId: String): File {
        val booksDir = File(appContext.filesDir, AppConstants.BOOKS_DIR)
        return File(File(booksDir, bookId), AppConstants.HIGHLIGHTS_FILE)
    }

    fun getLastHighlightColor(): Int {
        return prefs.getInt(AppConstants.KEY_HIGHLIGHT_COLOR, AppConstants.HIGHLIGHT_YELLOW)
    }

    fun setLastHighlightColor(color: Int) {
        prefs.edit().putInt(AppConstants.KEY_HIGHLIGHT_COLOR, color).apply()
    }

    companion object {
        @Volatile
        private var instance: HighlightsRepository? = null

        fun getInstance(context: Context): HighlightsRepository {
            return instance ?: synchronized(this) {
                instance ?: HighlightsRepository(context).also { instance = it }
            }
        }
    }
}

