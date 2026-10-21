package open.source.white.model

import open.source.white.common.AppConstants
import org.json.JSONObject

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val unpackedDirPath: String,
    val readerHtmlPath: String,
    val coverPath: String? = null,
    val scrollPosition: Float = 0f,
    val progressPercent: Int = 0,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val lastReadTimestamp: Long = System.currentTimeMillis()
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(AppConstants.FIELD_ID, id)
            put(AppConstants.FIELD_TITLE, title)
            put(AppConstants.FIELD_AUTHOR, author)
            put(AppConstants.FIELD_UNPACKED_PATH, unpackedDirPath)
            put(AppConstants.FIELD_HTML_PATH, readerHtmlPath)
            put(AppConstants.FIELD_COVER_PATH, coverPath ?: JSONObject.NULL)
            put(AppConstants.FIELD_SCROLL_POS, scrollPosition.toDouble())
            put(AppConstants.FIELD_PROGRESS, progressPercent)
            put(AppConstants.FIELD_ADDED_AT, addedTimestamp)
            put(AppConstants.FIELD_LAST_READ_AT, lastReadTimestamp)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): Book {
            val rawCover = if (json.isNull(AppConstants.FIELD_COVER_PATH)) {
                null
            } else {
                json.optString(AppConstants.FIELD_COVER_PATH, "").ifEmpty { null }
            }
            return Book(
                id = json.getString(AppConstants.FIELD_ID),
                title = json.optString(AppConstants.FIELD_TITLE, AppConstants.DEFAULT_TITLE),
                author = json.optString(AppConstants.FIELD_AUTHOR, AppConstants.DEFAULT_AUTHOR),
                unpackedDirPath = json.getString(AppConstants.FIELD_UNPACKED_PATH),
                readerHtmlPath = json.getString(AppConstants.FIELD_HTML_PATH),
                coverPath = rawCover,
                scrollPosition = json.optDouble(AppConstants.FIELD_SCROLL_POS, 0.0).toFloat(),
                progressPercent = json.optInt(AppConstants.FIELD_PROGRESS, AppConstants.MIN_PROGRESS),
                addedTimestamp = json.optLong(AppConstants.FIELD_ADDED_AT, System.currentTimeMillis()),
                lastReadTimestamp = json.optLong(AppConstants.FIELD_LAST_READ_AT, System.currentTimeMillis())
            )
        }
    }
}

