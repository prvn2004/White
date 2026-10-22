package open.source.white.ui.reader

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.Keep
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import open.source.white.R
import open.source.white.common.AppConstants
import open.source.white.data.BookRepository
import open.source.white.data.HighlightsRepository
import open.source.white.model.Book
import open.source.white.model.Highlight
import open.source.white.parser.EpubParser
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class ReaderActivity : AppCompatActivity() {

    private lateinit var repository: BookRepository
    private lateinit var highlightsRepository: HighlightsRepository
    private lateinit var book: Book
    private lateinit var root: FrameLayout
    private lateinit var webView: HighlightWebView
    private lateinit var brightnessController: LeftEdgeBrightnessController

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastScroll = 0f
    @Volatile private var lastProgress = 0
    @Volatile private var page = 1
    @Volatile private var totalPages = 1
    private var isFinishingWithFlush = false
    private var fontSize = AppConstants.DEFAULT_FONT_SIZE
    private var sessionStart = 0L

    private lateinit var statsTime: TextView
    private lateinit var statsPage: TextView
    private lateinit var statsProgress: TextView
    private lateinit var topControls: FrameLayout
    private lateinit var backButton: TextView
    private lateinit var rightActionCard: LinearLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var panelItems: LinearLayout
    private lateinit var scrim: View
    private var panelOpen = false
    private var pendingSelection: IntArray? = null
    private var pendingSelectionText: String? = null
    private var selectedColor = AppConstants.HIGHLIGHT_YELLOW
    private var gestureDetector: GestureDetector? = null
    private var swatchViews: List<View> = emptyList()
    private var lastUserScrollOrInteraction = System.currentTimeMillis()

    private val persistRunnable = Runnable { flushProgress(false) }
    private val tickRunnable = object : Runnable {
        override fun run() {
            checkReadingIdle()
            updateStatsBar()
            mainHandler.postDelayed(this, 15000L)
        }
    }
    private val hideChromeRunnable = Runnable { hideChrome() }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        repository = BookRepository.getInstance(this)
        highlightsRepository = HighlightsRepository.getInstance(this)
        fontSize = repository.getFontSize()
        selectedColor = highlightsRepository.getLastHighlightColor()

        val bookId = intent.getStringExtra(AppConstants.EXTRA_BOOK_ID)
        val loaded = if (bookId == null) null else repository.getBook(bookId)
        if (loaded == null) {
            repository.setLastOpenedBookId(null)
            finish()
            return
        }
        book = loaded
        lastScroll = book.scrollPosition
        lastProgress = book.progressPercent

        setContentView(R.layout.activity_reader)
        root = findViewById(R.id.readerRoot)
        webView = findViewById(R.id.readerWebView)
        webView.onHighlightRequested = {
            mainHandler.post { showHighlightColorDialog() }
        }

        brightnessController = LeftEdgeBrightnessController(
            activity = this,
            initialBrightness = repository.getReaderBrightness(),
            onBrightnessPersist = { value -> repository.setReaderBrightness(value) }
        )

        buildOverlay()
        configureWebView()
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                toggleChrome()
                return false
            }
        })

        repository.setLastOpenedBookId(book.id)
        loadBookIntoWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (panelOpen) closePanel() else exitToBookshelf()
            }
        })
    }

    private fun loadBookIntoWebView() {
        lifecycleScope.launch {
            val pageHtml: String? = withContext(Dispatchers.IO) {
                val readyFile = EpubParser.ensureReaderHtmlReady(book)
                if (readyFile == null || !readyFile.exists() || readyFile.length() == 0L) {
                    null
                } else {
                    refreshChapterTitles(readyFile)
                    try { readyFile.readText(Charsets.UTF_8) } catch (error: Exception) { null }
                }
            }
            if (pageHtml.isNullOrBlank()) {
                repository.setLastOpenedBookId(null)
                finish()
                return@launch
            }
            webView.loadDataWithBaseURL(
                AppConstants.VIRTUAL_ASSET_ORIGIN,
                pageHtml,
                AppConstants.MIME_HTML,
                "UTF-8",
                null
            )
        }
    }

    private fun refreshChapterTitles(readerFile: File) {
        try {
            val html = readerFile.readText(Charsets.UTF_8)
            val titles = Regex("data-title='([^']*)'")
                .findAll(html)
                .map { it.groupValues[1] }
                .toList()
            if (titles.size <= 1) return
            val looksGeneric = book.chapterTitles.size != titles.size ||
                book.chapterTitles.distinct().size <= 1
            if (!looksGeneric) return
            val updated = book.copy(chapterTitles = titles, totalChapters = titles.size)
            if (repository.saveBook(updated)) book = updated
        } catch (error: Exception) {
            // Titles are cosmetic; ignore failures.
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun buildOverlay() {
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val blue = ContextCompat.getColor(this, R.color.blue_primary)

        val overlayContainer = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
        }

        // Separate circular back button with Apple-style glass effect
        backButton = TextView(this).apply {
            text = "‹"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(blue)
            background = ContextCompat.getDrawable(this@ReaderActivity, R.drawable.bg_glass_circle)
            elevation = dp(6).toFloat()
            setOnClickListener { exitToBookshelf() }
        }
        val backParams = FrameLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.START or Gravity.TOP
            setMargins(dp(16), dp(16), 0, 0)
        }
        overlayContainer.addView(backButton, backParams)

        // Top right glass card housing Highlights and Aa font buttons
        rightActionCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@ReaderActivity, R.drawable.bg_glass_card)
            elevation = dp(6).toFloat()
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val highlightsButton = TextView(this).apply {
            text = "Highlights"
            textSize = 13f
            setTextColor(blue)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(6), dp(10), dp(6))
            setOnClickListener { openPanel() }
        }
        val divider = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#40B0CDED"))
            }
        }
        val fontButton = TextView(this).apply {
            text = "Aa"
            textSize = 16f
            setTextColor(blue)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(6), dp(6))
            setOnClickListener { showFontDialog() }
        }
        rightActionCard.addView(highlightsButton)
        rightActionCard.addView(divider, LinearLayout.LayoutParams(dp(1), dp(18)))
        rightActionCard.addView(fontButton)

        val cardParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(44)
        ).apply {
            gravity = Gravity.END or Gravity.TOP
            setMargins(0, dp(16), dp(16), 0)
        }
        overlayContainer.addView(rightActionCard, cardParams)

        root.addView(overlayContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        topControls = overlayContainer

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CCFFFFFF")) // Glass bottom bar
                setStroke(dp(1), Color.parseColor("#40FFFFFF"))
            }
            elevation = dp(4).toFloat()
        }
        statsTime = statCell("0m", textSecondary).also { it.setOnClickListener { showReadingTimeDialog() } }
        statsPage = statCell("p. 1 / 1", textSecondary).also { it.setOnClickListener { showContentsDialog() } }
        statsProgress = statCell("0%", blue).also { it.setOnClickListener { openPanel() } }
        stats.addView(statsTime, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        stats.addView(statsPage, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        stats.addView(statsProgress, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(stats, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(26)
        ).apply { gravity = Gravity.BOTTOM })

        buildSelectionBar()
        buildPanel()

        scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#44000000")) // Softer frosted glass scrim
            visibility = View.GONE
            alpha = 0f
            setOnClickListener { closePanel() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(panel, FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.82f).toInt(),
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.START
            setMargins(dp(12), dp(12), 0, dp(12))
        })
        panel.translationX = -resources.displayMetrics.widthPixels.toFloat()
        scrim.bringToFront()
        panel.bringToFront()
        topControls.bringToFront()
        selectionBar.bringToFront()
        stats.bringToFront()

        updateStatsBar()
    }

    private fun statCell(initial: String, color: Int): TextView {
        return TextView(this).apply {
            text = initial
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(color)
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), dp(1))
        }
    }


    private fun buildSelectionBar() {
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val blue = ContextCompat.getColor(this, R.color.blue_primary)
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
            elevation = dp(8).toFloat()
            setPadding(dp(12), dp(6), dp(8), dp(6))
            visibility = View.GONE
        }
        val swatchHolder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val colors = listOf(
            AppConstants.HIGHLIGHT_YELLOW,
            AppConstants.HIGHLIGHT_GREEN,
            AppConstants.HIGHLIGHT_BLUE,
            AppConstants.HIGHLIGHT_PINK
        )
        val swatches = mutableListOf<View>()
        colors.forEach { color ->
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(color)
                    shape = GradientDrawable.OVAL
                    setStroke(dp(2), if (color == selectedColor) blue else Color.TRANSPARENT)
                }
            }
            val params = LinearLayout.LayoutParams(dp(22), dp(22))
            params.marginEnd = dp(8)
            swatchHolder.addView(swatch, params)
            swatch.setOnClickListener {
                selectedColor = color
                highlightsRepository.setLastHighlightColor(color)
                refreshSwatchStrokes(colors, swatches, blue)
            }
            swatches.add(swatch)
        }
        swatchViews = swatches
        val copyButton = TextView(this).apply {
            text = "Copy"
            setTextColor(textPrimary)
            textSize = 12f
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setOnClickListener {
                val text = pendingSelectionText
                if (!text.isNullOrBlank()) {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
                }
                hideSelectionBar()
            }
        }
        val applyButton = TextView(this).apply {
            text = "Highlight"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(blue)
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { applySelectionHighlight() }
        }
        holder.addView(swatchHolder)
        holder.addView(copyButton)
        holder.addView(applyButton)
        root.addView(holder, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(132)
        })
        selectionBar = holder
    }

    private fun refreshSwatchStrokes(colors: List<Int>, swatches: List<View>, blue: Int) {
        swatches.forEachIndexed { index, view ->
            (view.background as? GradientDrawable)?.setStroke(
                dp(2), if (colors[index] == selectedColor) blue else Color.TRANSPARENT
            )
        }
    }

    private fun buildPanel() {
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@ReaderActivity, R.drawable.bg_glass_dialog)
            elevation = dp(20).toFloat()
            setPadding(dp(18), dp(18), dp(18), dp(14))
            visibility = View.GONE
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerTitle = TextView(this).apply {
            text = "Highlights"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary)
        }
        val close = TextView(this).apply {
            text = "×"
            textSize = 22f
            setTextColor(textSecondary)
            setPadding(dp(10), 0, dp(4), dp(4))
            setOnClickListener { closePanel() }
        }
        header.addView(headerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close)
        val subtitle = TextView(this).apply {
            text = "Select text in the book, then tap Highlight."
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, dp(4), 0, dp(10))
        }
        val scroll = ScrollView(this)
        panelItems = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(panelItems, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        side.addView(header)
        side.addView(subtitle)
        side.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        panel = side
    }

    private fun toggleChrome() {
        if (topControls.visibility == View.VISIBLE) hideChrome() else showChrome()
    }

    private fun showChrome() {
        topControls.visibility = View.VISIBLE
        topControls.animate().alpha(1f).setDuration(160).start()
        mainHandler.removeCallbacks(hideChromeRunnable)
        mainHandler.postDelayed(hideChromeRunnable, 4500L)
    }

    private fun hideChrome() {
        mainHandler.removeCallbacks(hideChromeRunnable)
        if (topControls.visibility != View.VISIBLE) return
        topControls.animate().alpha(0f).setDuration(180).withEndAction {
            topControls.visibility = View.GONE
        }.start()
    }

    private fun openPanel() {
        hideChrome()
        hideSelectionBar()
        selectedColor = highlightsRepository.getLastHighlightColor()
        refreshPanelItems()
        panelOpen = true
        scrim.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(200).start()
        panel.animate().translationX(0f).setDuration(220).start()
    }

    private fun closePanel() {
        panelOpen = false
        scrim.animate().alpha(0f).setDuration(180).withEndAction { scrim.visibility = View.GONE }.start()
        panel.animate().translationX(-panel.width.toFloat().coerceAtLeast(1f)).setDuration(200)
            .withEndAction { panel.visibility = View.GONE }.start()
    }

    private fun refreshPanelItems() {
        panelItems.removeAllViews()
        val items = highlightsRepository.getHighlights(book.id)
        if (items.isEmpty()) {
            panelItems.addView(TextView(this).apply {
                text = "No highlights yet."
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@ReaderActivity, R.color.text_secondary))
                setPadding(0, dp(18), 0, dp(18))
            })
            return
        }
        items.forEach { panelItems.addView(buildHighlightRow(it)) }
    }

    private fun buildHighlightRow(highlight: Highlight): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#44F1F5F9")) // Glass card row
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setOnClickListener {
                closePanel()
                val script = "window.White && window.White.jumpToHighlight && window.White.jumpToHighlight('" +
                    highlight.id + "');"
                webView.postDelayed({
                    webView.evaluateJavascript(script) { result ->
                    }
                }, 260L)
            }
        }
        val rule = View(this).apply {
            background = GradientDrawable().apply {
                setColor(highlight.color)
                cornerRadius = dp(3).toFloat()
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        content.addView(TextView(this).apply {
            text = highlight.text.replace(Regex("\\s+"), " ").trim()
            textSize = 12.5f
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(this@ReaderActivity, R.color.text_primary))
        })
        content.addView(TextView(this).apply {
            text = book.chapterTitles.getOrNull(highlight.chapterIndex)
                ?: ("Chapter " + (highlight.chapterIndex + 1))
            textSize = 10.5f
            setTextColor(ContextCompat.getColor(this@ReaderActivity, R.color.text_secondary))
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(rule, LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = dp(8)
        row.layoutParams = params
        return row
    }


    private fun hideSelectionBar() {
        selectionBar.visibility = View.GONE
        pendingSelection = null
        pendingSelectionText = null
        webView.evaluateJavascript(
            "window.White && window.White.clearSelection && window.White.clearSelection();", null
        )
    }

    private fun applySelectionHighlight() {
        val selection = pendingSelection ?: return
        val text = pendingSelectionText ?: return
        if (text.isBlank()) return
        getExactHighlights(selection).forEach { duplicate ->
            if (duplicate.color == selectedColor) {
                hideSelectionBar()
                applyHighlightsToPage()
                return
            }
            highlightsRepository.deleteHighlight(book.id, duplicate.id)
        }
        highlightsRepository.addHighlight(
            bookId = book.id,
            chapterIndex = selection[0],
            start = selection[1],
            end = selection[2],
            text = text,
            color = selectedColor
        )
        hideSelectionBar()
        applyHighlightsToPage()
        refreshPanelItems()
        android.widget.Toast.makeText(this, "Highlight saved", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun getExactHighlights(selection: IntArray): List<Highlight> {
        return highlightsRepository.getHighlights(book.id).filter {
            it.chapterIndex == selection[0] &&
                it.startOffset == selection[1] &&
                it.endOffset == selection[2]
        }
    }

    private fun buildHighlightsJson(): String {
        val array = JSONArray()
        highlightsRepository.getHighlights(book.id).forEach { highlight ->
            val obj = JSONObject()
            obj.put("id", highlight.id)
            obj.put("c", highlight.chapterIndex)
            obj.put("s", highlight.startOffset)
            obj.put("e", highlight.endOffset)
            obj.put("color", String.format(Locale.US, "#%06X", 0xFFFFFF and highlight.color))
            array.put(obj)
        }
        return array.toString()
    }

    private fun applyHighlightsToPage() {
        val argument = JSONObject.quote(buildHighlightsJson())
        webView.evaluateJavascript(
            "window.White && window.White.applyHighlights && window.White.applyHighlights(" +
                argument + ");", null
        )
    }

    private fun applyFontSize() {
        webView.evaluateJavascript(
            "window.White && window.White.fontSize && window.White.fontSize(" +
                fontSize.toString() + ", true);", null
        )
    }

    private fun showHighlightColorDialog() {
        if (pendingSelection == null || pendingSelectionText.isNullOrBlank()) {
            webView.evaluateJavascript(
                "(function(){var s=window.White&&window.White.getSelection?" +
                    "window.White.getSelection():null;return s?JSON.stringify(s):'';})()"
            ) { result ->
                val captured = parseSelection(result)
                if (captured == null) {
                    android.widget.Toast.makeText(
                        this,
                        "Select some text first",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@evaluateJavascript
                }
                pendingSelection = intArrayOf(captured.chapter, captured.start, captured.end)
                pendingSelectionText = captured.text
                showHighlightColorDialog()
            }
            return
        }
        selectionBar.visibility = View.GONE
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        val colors = listOf(
            AppConstants.HIGHLIGHT_YELLOW,
            AppConstants.HIGHLIGHT_GREEN,
            AppConstants.HIGHLIGHT_BLUE,
            AppConstants.HIGHLIGHT_PINK
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle("Highlight color")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .create()
        container.background = ContextCompat.getDrawable(this, R.drawable.bg_glass_dialog)
        colors.forEach { color ->
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(color)
                    shape = GradientDrawable.OVAL
                    setStroke(dp(2), Color.parseColor("#E2E8F0"))
                }
            }
            val params = LinearLayout.LayoutParams(dp(46), dp(46))
            params.marginEnd = dp(14)
            swatch.setOnClickListener {
                selectedColor = color
                highlightsRepository.setLastHighlightColor(color)
                applySelectionHighlight()
                dialog.dismiss()
            }
            container.addView(swatch, params)
        }
        dialog.show()
    }

    private fun showHighlightEditDialog(highlightId: String) {
        val highlight = highlightsRepository.getHighlights(book.id).find { it.id == highlightId } ?: return
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            background = ContextCompat.getDrawable(this@ReaderActivity, R.drawable.bg_glass_dialog)
        }
        val preview = TextView(this).apply {
            text = highlight.text.replace(Regex("\\s+"), " ").trim()
            textSize = 13f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.parseColor("#18202C"))
            setPadding(0, 0, 0, dp(14))
        }
        rootLayout.addView(preview)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val colors = listOf(
            AppConstants.HIGHLIGHT_YELLOW,
            AppConstants.HIGHLIGHT_GREEN,
            AppConstants.HIGHLIGHT_BLUE,
            AppConstants.HIGHLIGHT_PINK
        )
        var dialogRef: AlertDialog? = null
        colors.forEach { color ->
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(color)
                    shape = GradientDrawable.OVAL
                    setStroke(dp(2), if (color == highlight.color) ContextCompat.getColor(this@ReaderActivity, R.color.blue_primary) else Color.TRANSPARENT)
                }
                setOnClickListener {
                    highlightsRepository.updateHighlightColor(book.id, highlightId, color)
                    applyHighlightsToPage()
                    refreshPanelItems()
                    dialogRef?.dismiss()
                }
            }
            val p = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
            actionRow.addView(swatch, p)
        }

        // Spacer
        actionRow.addView(View(this), LinearLayout.LayoutParams(0, dp(36), 1f))

        // Delete / Bin button
        val deleteBtn = TextView(this).apply {
            text = "🗑 Delete"
            textSize = 13f
            setTextColor(Color.parseColor("#EF4444"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                highlightsRepository.deleteHighlight(book.id, highlightId)
                applyHighlightsToPage()
                refreshPanelItems()
                dialogRef?.dismiss()
                android.widget.Toast.makeText(this@ReaderActivity, "Highlight deleted", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        actionRow.addView(deleteBtn)
        rootLayout.addView(actionRow)

        dialogRef = AlertDialog.Builder(this)
            .setTitle("Highlight")
            .setView(rootLayout)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun parseSelection(result: String?): CapturedSelection? {
        if (result.isNullOrBlank() || result == "null") return null
        return try {
            val value = JSONTokener(result).nextValue()
            val json = value as? String ?: return null
            if (json.isBlank()) return null
            val obj = JSONObject(json)
            val chapter = obj.optInt("c", -1)
            val start = obj.optInt("s", -1)
            val end = obj.optInt("e", -1)
            val text = obj.optString("text", "")
            if (chapter < 0 || start < 0 || end <= start || text.isBlank()) {
                null
            } else {
                CapturedSelection(chapter, start, end, text)
            }
        } catch (error: Exception) {
            null
        }
    }

    private fun showFontDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
            background = ContextCompat.getDrawable(this@ReaderActivity, R.drawable.bg_glass_dialog)
        }
        val label = TextView(this).apply {
            text = "Text size " + fontSize + "px"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@ReaderActivity, R.color.text_primary))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val smaller = fontControl("A−") {
            fontSize = (fontSize - 1).coerceIn(AppConstants.MIN_FONT_SIZE, AppConstants.MAX_FONT_SIZE)
            repository.setFontSize(fontSize)
            label.text = "Text size " + fontSize + "px"
            applyFontSize()
        }
        val bigger = fontControl("A+") {
            fontSize = (fontSize + 1).coerceIn(AppConstants.MIN_FONT_SIZE, AppConstants.MAX_FONT_SIZE)
            repository.setFontSize(fontSize)
            label.text = "Text size " + fontSize + "px"
            applyFontSize()
        }
        controls.addView(smaller, LinearLayout.LayoutParams(dp(64), dp(44)))
        controls.addView(View(this), LinearLayout.LayoutParams(0, dp(44), 1f))
        controls.addView(bigger, LinearLayout.LayoutParams(dp(64), dp(44)))
        container.addView(label)
        container.addView(controls)

        AlertDialog.Builder(this)
            .setTitle("Display")
            .setView(container)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun fontControl(textValue: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@ReaderActivity, R.color.blue_primary))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { action() }
        }
    }

    private fun showContentsDialog() {
        val titles = book.chapterTitles.ifEmpty {
            (0 until book.totalChapters.coerceAtLeast(1)).map { "Chapter " + (it + 1) }
        }
        val items = arrayOf("Jump to page…") + titles.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Contents")
            .setItems(items) { _, which ->
                if (which == 0) {
                    promptJumpToPage()
                } else {
                    val chapterIdx = which - 1
                    webView.evaluateJavascript(
                        "(function(){var e=document.getElementById('chapter-" + chapterIdx + "');" +
                            "if(!e){var a=document.querySelectorAll('.epub-chapter');" +
                            "for(var i=0;i<a.length;i++){if(a[i].getAttribute('data-chapter')=='" +
                            chapterIdx + "'){e=a[i];break;}}}" +
                            "if(e){e.scrollIntoView({block:'start'});return true;}return false;})()",
                        null
                    )
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun promptJumpToPage() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "1 - $totalPages"
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle("Jump to page")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val num = input.text.toString().trim().toIntOrNull()
                if (num != null && num in 1..totalPages) {
                    webView.evaluateJavascript("window.White && window.White.scrollToPage && window.White.scrollToPage($num, $totalPages);", null)
                } else {
                    android.widget.Toast.makeText(this, "Invalid page number", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showReadingTimeDialog() {
        val lines = mutableListOf<String>()
        lines.add("Total: " + formatDuration(book.totalReadingMillis))
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        lines.add("Today: " + formatDuration(book.dailyReadingMillis[today] ?: 0L))
        lines.add("")
        lines.add("Recent days")
        val formatter = SimpleDateFormat("EEE, d MMM", Locale.US)
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        book.dailyReadingMillis.entries
            .sortedByDescending { it.key }
            .take(14)
            .forEach { entry ->
                val label = try {
                    formatter.format(parser.parse(entry.key) ?: Date())
                } catch (error: Exception) {
                    entry.key
                }
                lines.add(label + "  •  " + formatDuration(entry.value))
            }
        if (book.dailyReadingMillis.isEmpty()) lines.add("No reading sessions recorded yet.")
        AlertDialog.Builder(this)
            .setTitle("Reading time")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("Close", null)
            .show()
    }

    private fun formatDuration(millis: Long): String {
        if (millis <= 0L) return "0m"
        val minutes = (millis / 60000L).toInt()
        val hours = minutes / 60
        val rest = minutes % 60
        return if (hours > 0) hours.toString() + "h " + String.format(Locale.US, "%02d", rest) + "m"
        else rest.toString() + "m"
    }

    private fun updateStatsBar() {
        if (!::book.isInitialized || !::statsTime.isInitialized) return
        val current = repository.getBook(book.id) ?: book
        statsTime.text = formatDuration(current.totalReadingMillis + liveSessionMillis())
        statsPage.text = "p. " + page + " / " + totalPages.coerceAtLeast(1)
        statsProgress.text = lastProgress.toString() + "%"
    }

    private fun liveSessionMillis(): Long {
        return if (sessionStart > 0L) System.currentTimeMillis() - sessionStart else 0L
    }

    private fun trackSessionStart() {
        if (!::book.isInitialized) return
        if (sessionStart == 0L) sessionStart = System.currentTimeMillis()
    }

    private fun trackSessionEnd() {
        if (!::book.isInitialized) return
        if (sessionStart == 0L) return
        val elapsed = System.currentTimeMillis() - sessionStart
        sessionStart = 0L
        if (elapsed > 500L) {
            repository.addReadingTime(book.id, elapsed)
            book = repository.getBook(book.id) ?: book
            updateStatsBar()
        }
    }

    private fun exitToBookshelf() {
        if (isFinishingWithFlush) return
        isFinishingWithFlush = true
        repository.setLastOpenedBookId(null)
        trackSessionEnd()
        if (!::webView.isInitialized) {
            flushProgress(true)
            finish()
            return
        }
        val fallback = Runnable {
            flushProgress(true)
            if (!isFinishing) finish()
        }
        mainHandler.postDelayed(fallback, 160L)
        val flushFn = AppConstants.JS_FUNC_FLUSH
        webView.evaluateJavascript("if (window." + flushFn + ") { window." + flushFn + "(); }") {
            mainHandler.removeCallbacks(fallback)
            flushProgress(true)
            if (!isFinishing) finish()
        }
    }


    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView.setBackgroundColor(Color.WHITE)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_ALWAYS
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            loadsImagesAutomatically = true
            blockNetworkImage = false
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
        }
        webView.addJavascriptInterface(Bridge(), AppConstants.JS_BRIDGE_NAME)
        webView.webViewClient = ReaderClient(
            root = File(book.unpackedDirPath),
            readerHtmlFile = File(book.readerHtmlPath)
        )
    }

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val params = window.attributes
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = params
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersiveMode()
        if (::webView.isInitialized) {
            val fn = AppConstants.JS_FUNC_RESTORE
            val pct = lastProgress
            webView.postDelayed({
                if (::webView.isInitialized && !isDestroyed) {
                    webView.evaluateJavascript(
                        "if (window." + fn + ") { window." + fn + "(0, " + pct + "); }", null
                    )
                }
            }, 80L)
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        trackSessionStart()
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.postDelayed(tickRunnable, 15000L)
        updateStatsBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun dispatchToSuper(event: MotionEvent): Boolean = super.dispatchTouchEvent(event)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastUserScrollOrInteraction = System.currentTimeMillis()
        if (sessionStart == 0L) trackSessionStart()
        gestureDetector?.onTouchEvent(ev)
        if (::brightnessController.isInitialized) {
            return brightnessController.onDispatchTouchEvent(ev, ::dispatchToSuper)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun flushProgress(synchronous: Boolean) {
        mainHandler.removeCallbacks(persistRunnable)
        if (::book.isInitialized) {
            repository.updateProgress(book.id, lastScroll, lastProgress, synchronous = synchronous)
        }
    }

    @Keep
    private inner class Bridge {
        @Keep
        @JavascriptInterface
        fun onScrollProgress(top: Double, progress: Int, currentPage: Int, pages: Int) {
            if (!top.isFinite()) return
            lastUserScrollOrInteraction = System.currentTimeMillis()
            lastScroll = top.toFloat().coerceAtLeast(0f)
            lastProgress = progress.coerceIn(AppConstants.MIN_PROGRESS, AppConstants.MAX_PROGRESS)
            page = currentPage.coerceAtLeast(1)
            totalPages = pages.coerceAtLeast(1)
            mainHandler.post { updateStatsBar() }
            mainHandler.removeCallbacks(persistRunnable)
            mainHandler.postDelayed(persistRunnable, AppConstants.PERSIST_DEBOUNCE_MS)
        }

        @Keep
        @JavascriptInterface
        fun onTextSelected(chapter: Int, start: Int, end: Int, text: String) {
            if (text.isBlank() || end <= start) return
            pendingSelection = intArrayOf(chapter, start, end)
            pendingSelectionText = text
            mainHandler.post {
                selectedColor = highlightsRepository.getLastHighlightColor()
                refreshSwatchStrokes(
                    listOf(
                        AppConstants.HIGHLIGHT_YELLOW,
                        AppConstants.HIGHLIGHT_GREEN,
                        AppConstants.HIGHLIGHT_BLUE,
                        AppConstants.HIGHLIGHT_PINK
                    ),
                    swatchViews,
                    ContextCompat.getColor(this@ReaderActivity, R.color.blue_primary)
                )
                selectionBar.visibility = View.VISIBLE
                hideChrome()
            }
        }

        @Keep
        @JavascriptInterface
        fun onSelectionCleared() {
            mainHandler.post {
                if (::selectionBar.isInitialized) selectionBar.visibility = View.GONE
            }
        }

        @Keep
        @JavascriptInterface
        fun onHighlightClicked(id: String) {
            mainHandler.post {
                lastUserScrollOrInteraction = System.currentTimeMillis()
                showHighlightEditDialog(id)
            }
        }
    }

    private data class CapturedSelection(
        val chapter: Int,
        val start: Int,
        val end: Int,
        val text: String
    )

    private inner class ReaderClient(
        private val root: File,
        private val readerHtmlFile: File
    ) : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? = serve(request.url)

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url
            val sameDocument = url.scheme == AppConstants.VIRTUAL_ASSET_SCHEME &&
                url.host == AppConstants.VIRTUAL_ASSET_HOST
            return !sameDocument
        }

        override fun onPageFinished(view: WebView, url: String) {
            val script = ReaderScript.build(
                scroll = lastScroll,
                progress = lastProgress,
                fontSize = fontSize,
                highlightsJson = buildHighlightsJson()
            )
            view.evaluateJavascript(script, null)
        }

        private fun serve(uri: Uri): WebResourceResponse? {
            if (uri.scheme != AppConstants.VIRTUAL_ASSET_SCHEME) return null
            if (uri.host != AppConstants.VIRTUAL_ASSET_HOST) return null
            val rawPath = (uri.path ?: return null).trimStart('/')
            if (rawPath.isEmpty()) return null
            if (rawPath == AppConstants.VIRTUAL_READER_PATH) {
                return try {
                    WebResourceResponse(AppConstants.MIME_HTML, "UTF-8", FileInputStream(readerHtmlFile))
                } catch (error: Exception) {
                    null
                }
            }
            return try {
                val canonicalRoot = root.canonicalFile
                val rootPath = canonicalRoot.canonicalPath
                val decodedPath = try { Uri.decode(rawPath) } catch (error: Exception) { rawPath }
                val file = File(canonicalRoot, decodedPath).canonicalFile
                val isInsideRoot = file.canonicalPath == rootPath ||
                    file.canonicalPath.startsWith(rootPath + File.separator)
                if (!isInsideRoot || !file.exists() || !file.isFile) return null
                val mime = allowedAssetMime(file.name) ?: return null
                WebResourceResponse(mime, null, FileInputStream(file))
            } catch (error: Exception) {
                null
            }
        }

        private fun allowedAssetMime(name: String): String? {
            return when (name.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> AppConstants.MIME_JPEG
                "png" -> AppConstants.MIME_PNG
                "gif" -> AppConstants.MIME_GIF
                "webp" -> AppConstants.MIME_WEBP
                "svg" -> AppConstants.MIME_SVG
                "bmp" -> AppConstants.MIME_BMP
                "css" -> AppConstants.MIME_CSS
                "ttf" -> AppConstants.MIME_TTF
                "otf" -> AppConstants.MIME_OTF
                "woff" -> AppConstants.MIME_WOFF
                "woff2" -> AppConstants.MIME_WOFF2
                else -> null
            }
        }
    }

    override fun onPause() {
        trackSessionEnd()
        if (::webView.isInitialized && !isFinishingWithFlush) {
            val flushFn = AppConstants.JS_FUNC_FLUSH
            webView.evaluateJavascript(
                "if (window." + flushFn + ") { window." + flushFn + "(); }"
            ) { flushProgress(true) }
        }
        flushProgress(true)
        super.onPause()
    }

    override fun onStop() {
        flushProgress(true)
        super.onStop()
    }

    override fun onDestroy() {
        trackSessionEnd()
        flushProgress(true)
        mainHandler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl(AppConstants.BLANK_URL)
            webView.removeJavascriptInterface(AppConstants.JS_BRIDGE_NAME)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun checkReadingIdle() {
        val now = System.currentTimeMillis()
        // If user hasn't scrolled or touched in 5 minutes, pause accumulating reading time
        if (sessionStart > 0L && (now - lastUserScrollOrInteraction) > 300000L) {
            trackSessionEnd()
        }
    }
}
