package open.source.white.ui.reader

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
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
import open.source.white.model.Book
import open.source.white.parser.EpubParser
import java.io.File
import java.io.FileInputStream

class ReaderActivity : AppCompatActivity() {

    private lateinit var repository: BookRepository
    private lateinit var book: Book
    private lateinit var webView: WebView
    private lateinit var brightnessController: LeftEdgeBrightnessController

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var lastScroll = 0f
    @Volatile
    private var lastProgress = 0
    private var isFinishingWithFlush = false

    private val persistRunnable = Runnable {
        flushProgress(synchronous = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()

        repository = BookRepository.getInstance(this)
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

        brightnessController = LeftEdgeBrightnessController(
            activity = this,
            initialBrightness = repository.getReaderBrightness(),
            onBrightnessPersist = { value -> repository.setReaderBrightness(value) }
        )

        webView = findViewById(R.id.readerWebView)
        configureWebView()

        repository.setLastOpenedBookId(book.id)
        loadBookIntoWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitToBookshelf()
            }
        })
    }

    private fun loadBookIntoWebView() {
        lifecycleScope.launch {
            val page: String? = withContext(Dispatchers.IO) {
                val readyFile = EpubParser.ensureReaderHtmlReady(book)
                if (readyFile == null || !readyFile.exists() || readyFile.length() == 0L) {
                    null
                } else {
                    try {
                        readyFile.readText(Charsets.UTF_8)
                    } catch (error: Exception) {
                        null
                    }
                }
            }
            if (page.isNullOrBlank()) {
                repository.setLastOpenedBookId(null)
                finish()
                return@launch
            }
            webView.loadDataWithBaseURL(
                AppConstants.VIRTUAL_ASSET_ORIGIN,
                page,
                AppConstants.MIME_HTML,
                "UTF-8",
                null
            )
        }
    }

    private fun exitToBookshelf() {
        if (isFinishingWithFlush) return
        isFinishingWithFlush = true
        repository.setLastOpenedBookId(null)
        if (!::webView.isInitialized) {
            flushProgress(synchronous = true)
            finish()
            return
        }
        val fallback = Runnable {
            flushProgress(synchronous = true)
            if (!isFinishing) {
                finish()
            }
        }
        mainHandler.postDelayed(fallback, 160L)
        val flushFn = AppConstants.JS_FUNC_FLUSH
        val script = "if (window." + flushFn + ") { window." + flushFn + "(); }"
        webView.evaluateJavascript(script) {
            mainHandler.removeCallbacks(fallback)
            flushProgress(synchronous = true)
            if (!isFinishing) {
                finish()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
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
                        "if (window." + fn + ") { window." + fn + "(0, " + pct + "); }",
                        null
                    )
                }
            }, 80L)
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    private fun dispatchToSuper(event: MotionEvent): Boolean {
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
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
        fun onScrollProgress(top: Double, progress: Int) {
            if (!top.isFinite()) return
            lastScroll = top.toFloat().coerceAtLeast(0f)
            lastProgress = progress.coerceIn(AppConstants.MIN_PROGRESS, AppConstants.MAX_PROGRESS)
            mainHandler.removeCallbacks(persistRunnable)
            mainHandler.postDelayed(persistRunnable, AppConstants.PERSIST_DEBOUNCE_MS)
        }
    }

    private inner class ReaderClient(
        private val root: File,
        private val readerHtmlFile: File
    ) : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            return serve(request.url)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url
            val isSameDocument = url.scheme == AppConstants.VIRTUAL_ASSET_SCHEME &&
                url.host == AppConstants.VIRTUAL_ASSET_HOST &&
                url.path == "/" + AppConstants.VIRTUAL_READER_PATH
            return !isSameDocument
        }

        override fun onPageFinished(view: WebView, url: String) {
            val fn = AppConstants.JS_FUNC_RESTORE
            val script = "if (window." + fn + ") { window." + fn + "(" +
                lastScroll.toString() + ", " + lastProgress.toString() + "); }"
            view.evaluateJavascript(script, null)
        }

        private fun serve(uri: Uri): WebResourceResponse? {
            if (uri.scheme != AppConstants.VIRTUAL_ASSET_SCHEME) return null
            if (uri.host != AppConstants.VIRTUAL_ASSET_HOST) return null
            val rawPath = (uri.path ?: return null).trimStart('/')
            if (rawPath.isEmpty()) return null
            if (rawPath == AppConstants.VIRTUAL_READER_PATH) {
                return try {
                    WebResourceResponse(
                        AppConstants.MIME_HTML,
                        "UTF-8",
                        FileInputStream(readerHtmlFile)
                    )
                } catch (error: Exception) {
                    null
                }
            }
            return try {
                val canonicalRoot = root.canonicalFile
                val rootPath = canonicalRoot.canonicalPath
                val decodedPath = try {
                    Uri.decode(rawPath)
                } catch (error: Exception) {
                    rawPath
                }
                val file = File(canonicalRoot, decodedPath).canonicalFile
                val isInsideRoot = file.canonicalPath == rootPath ||
                    file.canonicalPath.startsWith(rootPath + File.separator)
                if (!isInsideRoot || !file.exists() || !file.isFile) {
                    return null
                }
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
        if (::webView.isInitialized && !isFinishingWithFlush) {
            val flushFn = AppConstants.JS_FUNC_FLUSH
            val script = "if (window." + flushFn + ") { window." + flushFn + "(); }"
            webView.evaluateJavascript(script) {
                flushProgress(synchronous = true)
            }
        }
        flushProgress(synchronous = true)
        super.onPause()
    }

    override fun onStop() {
        flushProgress(synchronous = true)
        super.onStop()
    }

    override fun onDestroy() {
        flushProgress(synchronous = true)
        mainHandler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl(AppConstants.BLANK_URL)
            webView.removeJavascriptInterface(AppConstants.JS_BRIDGE_NAME)
            webView.destroy()
        }
        super.onDestroy()
    }
}

