package open.source.white

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private lateinit var repository: BookRepository
    private lateinit var adapter: BookshelfAdapter
    private lateinit var bookshelf: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var importOverlay: View
    private lateinit var libraryCount: TextView
    private lateinit var addButton: FloatingActionButton

    private val pickBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importBook(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        configureSystemBars()
        bindViews()
        setupBookshelf()

        addButton.setOnClickListener {
            pickBook.launch(arrayOf(AppConstants.MIME_ANY))
        }

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
        bookshelf.addItemDecoration(ShelfDecoration(this))
        bookshelf.itemAnimator = null
        rebuildAdapterForScreenWidth()
    }

    private fun rebuildAdapterForScreenWidth() {
        val columnWidth = (resources.displayMetrics.widthPixels / 3 - dp(22)).coerceAtLeast(120)
        val coverHeight = (columnWidth * 1.5f).toInt()
        adapter = BookshelfAdapter(
            coverWidthPx = columnWidth,
            coverHeightPx = coverHeight,
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
        if (repository.getBook(lastBookId) != null) {
            openReader(lastBookId)
        } else {
            repository.setLastOpenedBookId(null)
        }
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
            bookshelf.visibility = View.INVISIBLE
            libraryCount.text = ""
        } else {
            emptyState.visibility = View.GONE
            bookshelf.visibility = View.VISIBLE
            libraryCount.text = resources.getQuantityString(
                R.plurals.book_count,
                books.size,
                books.size
            )
        }
    }

    private fun importBook(uri: Uri) {
        importOverlay.visibility = View.VISIBLE
        addButton.isEnabled = false

        lifecycleScope.launch {
            val savedBook: Book? = withContext(Dispatchers.IO + NonCancellable) {
                try {
                    val parsed = EpubParser.parseBookFromUri(applicationContext, uri)
                    if (repository.saveBook(parsed)) {
                        parsed
                    } else {
                        File(parsed.unpackedDirPath).parentFile?.deleteRecursively()
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

    private fun confirmRemove(book: Book) {
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setMessage(R.string.remove_book_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ ->
                if (repository.deleteBook(book.id)) {
                    refresh()
                } else {
                    Toast.makeText(this, R.string.remove_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun openReader(bookId: String) {
        val intent = Intent(this, ReaderActivity::class.java)
        intent.putExtra(AppConstants.EXTRA_BOOK_ID, bookId)
        startActivity(intent)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}

