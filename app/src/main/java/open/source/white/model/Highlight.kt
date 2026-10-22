package open.source.white.model

import open.source.white.common.AppConstants
import org.json.JSONObject

data class Highlight(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val color: Int,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(AppConstants.FIELD_HIGHLIGHT_ID, id)
            put(AppConstants.FIELD_HIGHLIGHT_CHAPTER, chapterIndex)
            put(AppConstants.FIELD_HIGHLIGHT_START, startOffset)
            put(AppConstants.FIELD_HIGHLIGHT_END, endOffset)
            put(AppConstants.FIELD_HIGHLIGHT_TEXT, text)
            put(AppConstants.FIELD_HIGHLIGHT_COLOR, color)
            put(AppConstants.FIELD_HIGHLIGHT_CREATED, createdAt)
        }
    }

    companion object {
        fun fromJsonObject(bookId: String, json: JSONObject): Highlight {
            return Highlight(
                id = json.getString(AppConstants.FIELD_HIGHLIGHT_ID),
                bookId = bookId,
                chapterIndex = json.optInt(AppConstants.FIELD_HIGHLIGHT_CHAPTER, 0),
                startOffset = json.optInt(AppConstants.FIELD_HIGHLIGHT_START, 0),
                endOffset = json.optInt(AppConstants.FIELD_HIGHLIGHT_END, 0),
                text = json.optString(AppConstants.FIELD_HIGHLIGHT_TEXT, ""),
                color = json.optInt(AppConstants.FIELD_HIGHLIGHT_COLOR, AppConstants.HIGHLIGHT_YELLOW),
                createdAt = json.optLong(AppConstants.FIELD_HIGHLIGHT_CREATED, System.currentTimeMillis())
            )
        }
    }
}

