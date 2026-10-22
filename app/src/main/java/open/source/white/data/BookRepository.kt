package open.source.white.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import open.source.white.common.AppConstants
import open.source.white.model.Book
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class BookRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val booksFile = File(appContext.filesDir, AppConstants.BOOKS_JSON_FILE)
    private val tempFile = File(booksFile.path + AppConstants.BOOKS_TMP_SUFFIX)
    private val backupFile = File(booksFile.path + AppConstants.BOOKS_BACKUP_SUFFIX)
    private val booksRootDir = File(appContext.filesDir, AppConstants.BOOKS_DIR)
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val lock = Any()
    private val cachedBooks = mutableListOf<Book>()
    private val preservedRawRecords = mutableListOf<JSONObject>()
    private var loadSucceeded = true

    init {
        loadBooks()
    }

    private fun loadBooks() {
        synchronized(lock) {
            cachedBooks.clear()
            preservedRawRecords.clear()
            if (!booksFile.exists() && !backupFile.exists() && !tempFile.exists()) {
                loadSucceeded = true
                return
            }
            if (booksFile.exists() && tryParseLibraryFile(booksFile)) {
                loadSucceeded = true
                writeBackupCopy(booksFile)
                return
            }
            if (backupFile.exists() && tryParseLibraryFile(backupFile)) {
                loadSucceeded = true
                persistBooksLocked()
                return
            }
            if (tempFile.exists() && tryParseLibraryFile(tempFile)) {
                loadSucceeded = true
                persistBooksLocked()
                return
            }
            loadSucceeded = false
        }
    }

    private fun tryParseLibraryFile(source: File): Boolean {
        return try {
            val rawBytes = source.readBytes()
            val jsonString = String(rawBytes, Charsets.UTF_8)
            val jsonArray = JSONArray(jsonString)
            val parsedBooks = mutableListOf<Book>()
            val unparsedObjects = mutableListOf<JSONObject>()
            for (i in 0 until jsonArray.length()) {
                val bookObj = jsonArray.optJSONObject(i) ?: continue
                try {
                    parsedBooks.add(Book.fromJsonObject(bookObj))
                } catch (recordError: Exception) {
                    unparsedObjects.add(bookObj)
                }
            }
            cachedBooks.clear()
            cachedBooks.addAll(parsedBooks)
            preservedRawRecords.clear()
            preservedRawRecords.addAll(unparsedObjects)
            true
        } catch (error: Exception) {
            false
        }
    }

    private fun persistBooksLocked(): Boolean {
        if (!loadSucceeded) {
            return false
        }
        val payload = try {
            val jsonArray = JSONArray()
            for (book in cachedBooks) {
                jsonArray.put(book.toJsonObject())
            }
            for (raw in preservedRawRecords) {
                jsonArray.put(raw)
            }
            jsonArray.toString(2).toByteArray(Charsets.UTF_8)
        } catch (error: Exception) {
            return false
        }

        val parent = booksFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false
        }

        return try {
            FileOutputStream(tempFile).use { stream ->
                stream.write(payload)
                stream.flush()
                stream.fd.sync()
            }
            if (!tempFile.exists() || tempFile.length() != payload.size.toLong()) {
                tempFile.delete()
                return false
            }
            if (booksFile.exists()) {
                writeBackupCopy(booksFile)
            }
            replaceCanonicalFile(tempFile, booksFile)
        } catch (error: Exception) {
            tempFile.delete()
            false
        }
    }

    private fun writeBackupCopy(source: File) {
        try {
            if (source.exists() && source.length() > 0L) {
                source.copyTo(backupFile, overwrite = true)
            }
        } catch (error: Exception) {
            // Best-effort backup refresh
        }
    }

    private fun replaceCanonicalFile(source: File, target: File): Boolean {
        return try {
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
            target.exists() && target.length() > 0L
        } catch (error: Exception) {
            false
        }
    }

    fun getAllBooks(): List<Book> {
        synchronized(lock) {
            return cachedBooks.sortedByDescending { it.lastReadTimestamp }
        }
    }

    fun getBook(id: String): Book? {
        synchronized(lock) {
            return cachedBooks.find { it.id == id }
        }
    }

    fun saveBook(book: Book): Boolean {
        synchronized(lock) {
            if (!loadSucceeded) {
                return false
            }
            val index = cachedBooks.indexOfFirst { it.id == book.id }
            val previous = if (index >= 0) cachedBooks[index] else null
            if (index >= 0) {
                cachedBooks[index] = book
            } else {
                cachedBooks.add(0, book)
            }
            if (!persistBooksLocked()) {
                if (index >= 0 && previous != null) {
                    cachedBooks[index] = previous
                } else {
                    cachedBooks.removeAt(0)
                }
                return false
            }
            return true
        }
    }

    fun updateProgress(
        id: String,
        scrollPosition: Float,
        progressPercent: Int,
        synchronous: Boolean = false
    ) {
        val clampedScroll = scrollPosition.coerceAtLeast(0f)
        val clampedProgress = progressPercent.coerceIn(
            AppConstants.MIN_PROGRESS,
            AppConstants.MAX_PROGRESS
        )
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val index = cachedBooks.indexOfFirst { it.id == id }
            if (index < 0) return
            val current = cachedBooks[index]
            if (current.scrollPosition == clampedScroll &&
                current.progressPercent == clampedProgress
            ) {
                return
            }
            cachedBooks[index] = current.copy(
                scrollPosition = clampedScroll,
                progressPercent = clampedProgress,
                lastReadTimestamp = now
            )
            if (synchronous) {
                persistBooksLocked()
                return
            }
        }
        ioExecutor.execute {
            synchronized(lock) {
                persistBooksLocked()
            }
        }
    }

    fun addReadingTime(id: String, millis: Long) {
        if (millis <= 0L) return
        val safeMillis = millis.coerceAtMost(6L * 60L * 60L * 1000L)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        synchronized(lock) {
            val index = cachedBooks.indexOfFirst { it.id == id }
            if (index < 0) return
            val current = cachedBooks[index]
            val days = current.dailyReadingMillis.toMutableMap()
            days[day] = (days[day] ?: 0L) + safeMillis
            cachedBooks[index] = current.copy(
                totalReadingMillis = current.totalReadingMillis + safeMillis,
                dailyReadingMillis = days,
                lastReadTimestamp = System.currentTimeMillis()
            )
        }
        ioExecutor.execute {
            synchronized(lock) {
                persistBooksLocked()
            }
        }
    }

    fun getFontSize(): Int {
        return prefs.getInt(AppConstants.KEY_FONT_SIZE, AppConstants.DEFAULT_FONT_SIZE)
            .coerceIn(AppConstants.MIN_FONT_SIZE, AppConstants.MAX_FONT_SIZE)
    }

    fun setFontSize(size: Int) {
        prefs.edit()
            .putInt(
                AppConstants.KEY_FONT_SIZE,
                size.coerceIn(AppConstants.MIN_FONT_SIZE, AppConstants.MAX_FONT_SIZE)
            )
            .apply()
    }

    fun deleteBook(id: String): Boolean {
        val removedBook: Book?
        synchronized(lock) {
            if (!loadSucceeded) return false
            val index = cachedBooks.indexOfFirst { it.id == id }
            if (index < 0) return false
            val candidate = cachedBooks.removeAt(index)
            if (!persistBooksLocked()) {
                cachedBooks.add(index, candidate)
                return false
            }
            removedBook = candidate
            if (getLastOpenedBookId() == id) {
                setLastOpenedBookId(null)
            }
        }
        if (removedBook != null) {
            ioExecutor.execute {
                deleteBookFiles(removedBook)
            }
        }
        return true
    }

    private fun deleteBookFiles(book: Book) {
        try {
            val canonicalBooksRoot = booksRootDir.canonicalFile
            val bookDir = File(book.unpackedDirPath).parentFile?.canonicalFile ?: return
            val rootPath = canonicalBooksRoot.canonicalPath
            if (bookDir.canonicalPath.startsWith(rootPath + File.separator) && bookDir.exists()) {
                bookDir.deleteRecursively()
            }
        } catch (error: Exception) {
            // Best-effort cleanup of local book directory
        }
    }

    fun getLastOpenedBookId(): String? {
        return prefs.getString(AppConstants.KEY_LAST_OPENED_BOOK_ID, null)
    }

    fun setLastOpenedBookId(bookId: String?) {
        val editor = prefs.edit()
        if (bookId == null) {
            editor.remove(AppConstants.KEY_LAST_OPENED_BOOK_ID)
        } else {
            editor.putString(AppConstants.KEY_LAST_OPENED_BOOK_ID, bookId)
        }
        editor.apply()
    }

    fun getReaderBrightness(): Float {
        return prefs.getFloat(AppConstants.KEY_READER_BRIGHTNESS, AppConstants.UNSET_BRIGHTNESS)
    }

    fun setReaderBrightness(brightness: Float) {
        val clamped = brightness.coerceIn(AppConstants.MIN_BRIGHTNESS, AppConstants.MAX_BRIGHTNESS)
        prefs.edit().putFloat(AppConstants.KEY_READER_BRIGHTNESS, clamped).apply()
    }

    companion object {
        @Volatile
        private var instance: BookRepository? = null

        fun getInstance(context: Context): BookRepository {
            return instance ?: synchronized(this) {
                instance ?: BookRepository(context).also { instance = it }
            }
        }
    }
}

