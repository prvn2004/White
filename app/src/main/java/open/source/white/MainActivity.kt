package open.source.white

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import open.source.white.common.AppConstants
import open.source.white.data.BookRepository
import open.source.white.model.Book
import open.source.white.parser.EpubParser
import open.source.white.ui.bookshelf.BookshelfAdapter
import open.source.white.ui.bookshelf.ShelfDecoration
import open.source.white.ui.reader.ReaderActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var repository: BookRepository
    private lateinit var adapter: BookshelfAdapter
    private lateinit var bookshelf: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var importOverlay: View
    private lateinit var libraryCount: TextView
    private lateinit var addButton: FloatingActionButton

    private val pickBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBook(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        configureSystemBars()
        bindViews()
        setupBookshelf()

        addButton.setOnClickListener { pickBook.launch(arrayOf("*/*")) }
        findViewById<TextView>(R.id.menuButton).setOnClickListener { showMoreMenu() }

        refresh()
        autoOpenLastBookIfNeeded(savedInstanceState)
    }

    private fun configureSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
        val root = findViewById<View>(R.id.rootView)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    private fun bindViews() {
        repository = BookRepository.getInstance(this)
        bookshelf = findViewById(R.id.bookshelf)
        emptyState = findViewById(R.id.emptyState)
        importOverlay = findViewById(R.id.importOverlay)
        libraryCount = findViewById(R.id.libraryCount)
        addButton = findViewById(R.id.addBookButton)
    }

    private fun setupBookshelf() {
        bookshelf.layoutManager = GridLayoutManager(this, 3)
        bookshelf.itemAnimator = null
        rebuildAdapterForScreenWidth()
    }

    private fun rebuildAdapterForScreenWidth() {
        val displayH = resources.displayMetrics.heightPixels
        val topHeaderApprox = dp(80)
        val availableH = (displayH - topHeaderApprox).coerceAtLeast(dp(400))
        val rowHeight = (availableH / 5).coerceAtLeast(dp(130))
        val coverHeight = (rowHeight - dp(28)).coerceAtLeast(dp(100))
        // Substantial cover proportion for 3-column shelf
        val columnWidth = ((resources.displayMetrics.widthPixels - dp(40)) / 3).coerceAtLeast(100)
        val itemHeight = coverHeight + dp(8)
        while (bookshelf.itemDecorationCount > 0) {
            bookshelf.removeItemDecorationAt(0)
        }
        bookshelf.addItemDecoration(ShelfDecoration(this, rowHeight))
        adapter = BookshelfAdapter(
            coverWidthPx = columnWidth,
            coverHeightPx = coverHeight,
            itemHeightPx = itemHeight,
            onBookClick = { book -> openReader(book.id) },
            onBookLongClick = { book -> confirmRemove(book) }
        )
        bookshelf.adapter = adapter
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::bookshelf.isInitialized) {
            rebuildAdapterForScreenWidth()
            refresh()
        }
    }

    private fun autoOpenLastBookIfNeeded(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        val lastBookId = repository.getLastOpenedBookId() ?: return
        if (repository.getBook(lastBookId) != null) openReader(lastBookId)
        else repository.setLastOpenedBookId(null)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val books = repository.getAllBooks()
        adapter.submit(books)
        if (books.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            bookshelf.visibility = View.VISIBLE
        } else {
            emptyState.visibility = View.GONE
            bookshelf.visibility = View.VISIBLE
        }
    }

    private fun importBook(uri: Uri) {
        importOverlay.visibility = View.VISIBLE
        addButton.isEnabled = false
        val displayName = resolveDisplayName(uri)
        lifecycleScope.launch {
            val savedBook: Book? = withContext(Dispatchers.IO + NonCancellable) {
                try {
                    val parsed = EpubParser.parseBookFromUri(applicationContext, uri, displayName)
                    if (repository.saveBook(parsed)) parsed
                    else {
                        File(parsed.unpackedDirPath).deleteRecursively()
                        null
                    }
                } catch (error: Exception) {
                    null
                }
            }
            importOverlay.visibility = View.GONE
            addButton.isEnabled = true
            if (savedBook == null) {
                Toast.makeText(this@MainActivity, R.string.import_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            refresh()
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment
        } catch (error: Exception) {
            uri.lastPathSegment
        }
    }

    private fun confirmRemove(book: Book) {
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setMessage(R.string.remove_book_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ ->
                if (repository.deleteBook(book.id)) refresh()
                else Toast.makeText(this, R.string.remove_failed, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showMoreMenu() {
        val options = arrayOf(
            getString(R.string.about),
            getString(R.string.reading_stats)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAbout()
                    1 -> showReadingStats()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (error: Exception) {
            "1.0.0"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.about)
            .setMessage(getString(R.string.about_body, version))
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showReadingStats() {
        val books = repository.getAllBooks()
        if (books.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.reading_stats)
                .setMessage("No reading sessions yet.")
                .setPositiveButton("Close", null)
                .show()
            return
        }
        val lines = mutableListOf<String>()
        val total = books.sumOf { it.totalReadingMillis }
        lines.add("Total across library: " + formatDuration(total))
        lines.add("")
        books.sortedByDescending { it.totalReadingMillis }.take(10).forEach { book ->
            lines.add(book.title)
            lines.add("   " + formatDuration(book.totalReadingMillis))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reading_stats)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("Close", null)
            .show()
    }

    private fun formatDuration(millis: Long): String {
        if (millis <= 0) return "0m"
        val minutes = (millis / 60000L).toInt()
        val hours = minutes / 60
        val rest = minutes % 60
        return if (hours > 0) hours.toString() + "h " + String.format(Locale.US, "%02d", rest) + "m"
        else rest.toString() + "m"
    }

    private fun openReader(bookId: String) {
        val intent = Intent(this, ReaderActivity::class.java)
        intent.putExtra(AppConstants.EXTRA_BOOK_ID, bookId)
        startActivity(intent)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

