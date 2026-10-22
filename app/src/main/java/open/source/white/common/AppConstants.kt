package open.source.white.common

object AppConstants {
    // SharedPreferences & local storage
    const val PREFS_NAME = "white_reader_prefs"
    const val KEY_LAST_OPENED_BOOK_ID = "key_last_opened_book_id"
    const val KEY_READER_BRIGHTNESS = "key_reader_brightness"
    const val BOOKS_JSON_FILE = "books.json"
    const val BOOKS_TMP_SUFFIX = ".tmp"
    const val BOOKS_BACKUP_SUFFIX = ".bak"
    const val BOOKS_DIR = "books"
    const val UNPACKED_DIR = "unpacked"
    const val COVER_DIR = "cover"
    const val SOURCE_EPUB_FILE = "source.epub"
    const val READER_HTML_FILE = "reader.html"
    const val HIGHLIGHTS_FILE = "highlights.json"
    const val SOURCE_FILE = "source"
    const val PDF_PAGES_DIR = "pages"

    // Book JSON fields
    const val FIELD_ID = "id"
    const val FIELD_TITLE = "title"
    const val FIELD_AUTHOR = "author"
    const val FIELD_UNPACKED_PATH = "unpackedDirPath"
    const val FIELD_HTML_PATH = "readerHtmlPath"
    const val FIELD_COVER_PATH = "coverPath"
    const val FIELD_SCROLL_POS = "scrollPosition"
    const val FIELD_PROGRESS = "progressPercent"
    const val FIELD_FORMAT = "format"
    const val FIELD_TOTAL_CHAPTERS = "totalChapters"
    const val FIELD_CHAPTER_TITLES = "chapterTitles"
    const val FIELD_TOTAL_READING_MS = "totalReadingMillis"
    const val FIELD_DAILY_READING_MS = "dailyReadingMillis"
    const val FIELD_WORD_COUNT = "wordCount"
    const val FIELD_ADDED_AT = "addedTimestamp"
    const val FIELD_LAST_READ_AT = "lastReadTimestamp"

    // Highlight fields
    const val FIELD_HIGHLIGHT_ID = "id"
    const val FIELD_HIGHLIGHT_CHAPTER = "chapterIndex"
    const val FIELD_HIGHLIGHT_START = "startOffset"
    const val FIELD_HIGHLIGHT_END = "endOffset"
    const val FIELD_HIGHLIGHT_TEXT = "text"
    const val FIELD_HIGHLIGHT_COLOR = "color"
    const val FIELD_HIGHLIGHT_CREATED = "createdAt"

    val HIGHLIGHT_YELLOW = 0xFFFFE082.toInt()
    val HIGHLIGHT_GREEN = 0xFFB2DFDB.toInt()
    val HIGHLIGHT_BLUE = 0xFFBBDEFB.toInt()
    val HIGHLIGHT_PINK = 0xFFF8BBD0.toInt()

    // Supported formats
    const val FORMAT_EPUB = "EPUB"
    const val FORMAT_PDF = "PDF"
    const val FORMAT_MARKDOWN = "MARKDOWN"
    const val FORMAT_TEXT = "TEXT"
    const val FORMAT_HTML = "HTML"
    const val FORMAT_XML = "XML"

    // Reader preferences
    const val KEY_FONT_SIZE = "key_font_size"
    const val KEY_HIGHLIGHT_COLOR = "key_highlight_color"
    const val MIN_FONT_SIZE = 14
    const val MAX_FONT_SIZE = 30
    const val DEFAULT_FONT_SIZE = 19

    // Defaults & archive constants
    const val DEFAULT_TITLE = "Untitled Book"
    const val DEFAULT_AUTHOR = "Unknown Author"
    const val DEFAULT_COVER_EXT = "jpg"
    const val CONTAINER_XML_PATH = "META-INF/container.xml"
    const val OPF_EXTENSION = "opf"
    const val MAX_ZIP_ENTRIES = 4096
    const val MAX_UNCOMPRESSED_BYTES = 250L * 1024L * 1024L
    const val MAX_XML_BYTES = 10 * 1024 * 1024

    // XML safety markers
    const val XML_DOCTYPE_MARKER = "<!DOCTYPE"
    const val XML_ENTITY_MARKER = "<!ENTITY"

    // Error messages
    const val ERR_OPEN_STREAM = "Cannot open stream for this book"
    const val ERR_MISSING_OPF = "This EPUB has no package document"
    const val ERR_UNSAFE_XML = "External DTD or entity declarations are not permitted"
    const val ERR_XML_TOO_LARGE = "XML package document exceeds safe size limit"
    const val ERR_UNSAFE_ZIP_ENTRY = "Unsafe archive entry: "
    const val ERR_ZIP_LIMIT_EXCEEDED = "EPUB archive exceeds safe extraction limits"

    // Reader & WebView contract
    const val EXTRA_BOOK_ID = "extra_book_id"
    const val VIRTUAL_ASSET_SCHEME = "https"
    const val VIRTUAL_ASSET_HOST = "epub.local"
    const val VIRTUAL_ASSET_ORIGIN = "$VIRTUAL_ASSET_SCHEME://$VIRTUAL_ASSET_HOST/"
    const val VIRTUAL_READER_PATH = "__reader__.html"
    const val VIRTUAL_READER_URL = "$VIRTUAL_ASSET_ORIGIN$VIRTUAL_READER_PATH"
    const val JS_BRIDGE_NAME = "AndroidBridge"
    const val JS_METHOD_ON_SCROLL = "onScrollProgress"
    const val JS_METHOD_ON_SELECTION = "onTextSelected"
    const val JS_METHOD_ON_SELECTION_CLEARED = "onSelectionCleared"
    const val JS_METHOD_ON_HIGHLIGHT_CLICKED = "onHighlightClicked"
    const val JS_FUNC_RESTORE = "restorePosition"
    const val JS_FUNC_FLUSH = "flushReaderProgress"
    const val BLANK_URL = "about:blank"
    const val MIME_ANY = "*/*"

    // Allowed URL schemes in chapter markup
    const val SCHEME_HTTP = "http://"
    const val SCHEME_HTTPS = "https://"
    const val SCHEME_MAILTO = "mailto:"
    const val SCHEME_TEL = "tel:"
    const val SCHEME_DATA_IMAGE = "data:image/"
    const val SCHEME_JAVASCRIPT = "javascript:"
    const val SCHEME_FILE = "file:"
    const val SCHEME_DATA = "data:"
    const val HASH_ANCHOR = "#"

    // MIME types
    const val MIME_HTML = "text/html"
    const val MIME_CSS = "text/css"
    const val MIME_JPEG = "image/jpeg"
    const val MIME_PNG = "image/png"
    const val MIME_GIF = "image/gif"
    const val MIME_WEBP = "image/webp"
    const val MIME_SVG = "image/svg+xml"
    const val MIME_BMP = "image/bmp"
    const val MIME_TTF = "font/ttf"
    const val MIME_OTF = "font/otf"
    const val MIME_WOFF = "font/woff"
    const val MIME_WOFF2 = "font/woff2"

    // Left-edge brightness gesture
    const val LEFT_EDGE_GESTURE_DP = 28f
    const val BRIGHTNESS_SWIPE_HEIGHT_RATIO = 0.7f
    const val MIN_BRIGHTNESS = 0.02f
    const val MAX_BRIGHTNESS = 1.0f
    const val UNSET_BRIGHTNESS = -1f
    const val DEFAULT_FALLBACK_BRIGHTNESS = 0.5f

    // Progress & persistence
    const val MIN_PROGRESS = 0
    const val MAX_PROGRESS = 100
    const val PERSIST_DEBOUNCE_MS = 600L
}

