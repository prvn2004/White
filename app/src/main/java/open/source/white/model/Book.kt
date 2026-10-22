package open.source.white.model

import open.source.white.common.AppConstants
import org.json.JSONArray
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
    val format: String = AppConstants.FORMAT_EPUB,
    val totalChapters: Int = 0,
    val chapterTitles: List<String> = emptyList(),
    val totalReadingMillis: Long = 0L,
    val dailyReadingMillis: Map<String, Long> = emptyMap(),
    val wordCount: Int = 0,
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
            put(AppConstants.FIELD_FORMAT, format)
            put(AppConstants.FIELD_TOTAL_CHAPTERS, totalChapters)
            val titles = JSONArray()
            chapterTitles.forEach { titles.put(it) }
            put(AppConstants.FIELD_CHAPTER_TITLES, titles)
            put(AppConstants.FIELD_TOTAL_READING_MS, totalReadingMillis)
            val reading = JSONObject()
            dailyReadingMillis.forEach { (day, millis) -> reading.put(day, millis) }
            put(AppConstants.FIELD_DAILY_READING_MS, reading)
            put(AppConstants.FIELD_WORD_COUNT, wordCount)
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
            val titles = mutableListOf<String>()
            val titlesArray = json.optJSONArray(AppConstants.FIELD_CHAPTER_TITLES)
            if (titlesArray != null) {
                for (index in 0 until titlesArray.length()) {
                    titles.add(titlesArray.optString(index, ""))
                }
            }
            val reading = mutableMapOf<String, Long>()
            val readingObject = json.optJSONObject(AppConstants.FIELD_DAILY_READING_MS)
            if (readingObject != null) {
                val keys = readingObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    reading[key] = readingObject.optLong(key, 0L)
                }
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
                format = json.optString(AppConstants.FIELD_FORMAT, AppConstants.FORMAT_EPUB),
                totalChapters = json.optInt(AppConstants.FIELD_TOTAL_CHAPTERS, 0),
                chapterTitles = titles,
                totalReadingMillis = json.optLong(AppConstants.FIELD_TOTAL_READING_MS, 0L),
                dailyReadingMillis = reading,
                wordCount = json.optInt(AppConstants.FIELD_WORD_COUNT, 0),
                addedTimestamp = json.optLong(AppConstants.FIELD_ADDED_AT, System.currentTimeMillis()),
                lastReadTimestamp = json.optLong(AppConstants.FIELD_LAST_READ_AT, System.currentTimeMillis())
            )
        }
    }
}

