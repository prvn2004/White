package open.source.white.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import open.source.white.common.AppConstants
import open.source.white.model.Book
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

object EpubParser {

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String
    )

    private data class BookMetadata(
        val title: String,
        val author: String,
        val coverId: String?
    )

    private val DROPPED_SUBTREE_TAGS = setOf(
        "script", "style", "iframe", "object", "embed", "applet", "form", "head", "title", "foreignobject"
    )

    private val ALLOWED_TAGS = setOf(
        "p", "div", "span", "section", "article", "aside", "header", "footer", "main",
        "blockquote", "pre", "code", "h1", "h2", "h3", "h4", "h5", "h6",
        "ul", "ol", "li", "dl", "dt", "dd",
        "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption",
        "br", "hr", "em", "strong", "i", "b", "u", "s", "sub", "sup", "small",
        "mark", "q", "cite", "abbr", "figure", "figcaption", "img", "a",
        "text", "image",
        "math", "mrow", "mi", "mn", "mo", "msup", "msub", "msubsup", "mfrac", "msqrt", "mroot", "mtext"
    )

    private val VOID_TAGS = setOf("br", "hr", "img", "image")

    private val ALLOWED_ATTRS = setOf(
        "id", "class", "src", "href", "alt", "title", "colspan", "rowspan", "dir", "lang",
        "viewbox", "width", "height", "d", "fill", "stroke", "stroke-width", "x", "y", "cx", "cy", "r", "rx", "ry", "points"
    )

    private val secureRandom = SecureRandom()

    fun parseBookFromUri(context: Context, uri: Uri, displayName: String?): Book {
        val bookId = UUID.randomUUID().toString()
        val booksRoot = File(context.filesDir, AppConstants.BOOKS_DIR)
        val bookDir = File(booksRoot, bookId)
        bookDir.mkdirs()
        val format = detectFormat(context, uri, displayName)
        val sourceName = AppConstants.SOURCE_EPUB_FILE.replace("epub", extensionFor(format))
        val sourceFile = File(bookDir, sourceName)
        return try {
            copyUriToFile(context, uri, sourceFile)
            when (format) {
                AppConstants.FORMAT_PDF -> parsePdfBook(bookId, bookDir, sourceFile, displayName)
                AppConstants.FORMAT_MARKDOWN -> parseTextBook(
                    bookId, bookDir, sourceFile, displayName, AppConstants.FORMAT_MARKDOWN
                )
                AppConstants.FORMAT_HTML -> parseTextBook(
                    bookId, bookDir, sourceFile, displayName, AppConstants.FORMAT_HTML
                )
                AppConstants.FORMAT_XML -> parseTextBook(
                    bookId, bookDir, sourceFile, displayName, AppConstants.FORMAT_XML
                )
                AppConstants.FORMAT_TEXT -> parseTextBook(
                    bookId, bookDir, sourceFile, displayName, AppConstants.FORMAT_TEXT
                )
                else -> parseEpubBook(bookId, bookDir, sourceFile)
            }
        } catch (error: Exception) {
            bookDir.deleteRecursively()
            throw error
        }
    }

    private fun extensionFor(format: String): String = when (format) {
        AppConstants.FORMAT_PDF -> "pdf"
        AppConstants.FORMAT_MARKDOWN -> "md"
        AppConstants.FORMAT_HTML -> "html"
        AppConstants.FORMAT_XML -> "xml"
        AppConstants.FORMAT_TEXT -> "txt"
        else -> "epub"
    }

    private fun detectFormat(context: Context, uri: Uri, displayName: String?): String {
        val name = (displayName ?: uri.lastPathSegment ?: "").lowercase()
        return when {
            name.endsWith(".pdf") -> AppConstants.FORMAT_PDF
            name.endsWith(".md") || name.endsWith(".markdown") -> AppConstants.FORMAT_MARKDOWN
            name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xhtml") ->
                AppConstants.FORMAT_HTML
            name.endsWith(".xml") || name.endsWith(".fb2") -> AppConstants.FORMAT_XML
            name.endsWith(".txt") -> AppConstants.FORMAT_TEXT
            name.endsWith(".epub") -> AppConstants.FORMAT_EPUB
            else -> sniffFormat(context, uri)
        }
    }

    private fun sniffFormat(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(8)
                val read = input.read(header)
                if (read >= 4 &&
                    header[0].toInt() == 0x25 &&
                    header[1].toInt() == 0x50 &&
                    header[2].toInt() == 0x44 &&
                    header[3].toInt() == 0x46
                ) {
                    AppConstants.FORMAT_PDF
                } else {
                    AppConstants.FORMAT_EPUB
                }
            } ?: AppConstants.FORMAT_EPUB
        } catch (error: Exception) {
            AppConstants.FORMAT_EPUB
        }
    }

    fun ensureReaderHtmlReady(book: Book): File? {
        val readerFile = File(book.readerHtmlPath)
        if (readerFile.exists() && readerFile.length() > 0L) {
            val existing = try {
                readerFile.readText(Charsets.UTF_8)
            } catch (error: Exception) {
                ""
            }
            if (existing.contains("data-white-schema='7'") && existing.length > 1200) {
                return readerFile
            }
        }
        if (book.format != AppConstants.FORMAT_EPUB) {
            return rebuildSimpleBook(book, readerFile)
        }
        val unpackedDir = File(book.unpackedDirPath)
        if (!unpackedDir.exists() || !unpackedDir.isDirectory) {
            return if (readerFile.exists()) readerFile else null
        }
        return try {
            val root = unpackedDir.canonicalFile
            val opfFile = findOpfFile(root)
            val chapterPaths = if (opfFile != null) {
                val opfRelativePath = opfFile.relativeTo(root).invariantSeparatorsPath
                val opfParentDir = parentDirOf(opfRelativePath)
                val document = parseXmlDocument(opfFile)
                val manifest = extractManifest(document)
                extractChapterPaths(document, manifest, opfParentDir, root)
            } else {
                discoverHtmlFilesFallback(root)
            }
            if (chapterPaths.isEmpty()) return if (readerFile.exists()) readerFile else null
            val titles = chapterPaths.mapIndexed { index, path ->
                chapterTitleFor(File(root, path), path, book.title, index)
            }
            buildReaderHtml(root, chapterPaths, titles, readerFile, book.title)
            readerFile
        } catch (error: Exception) {
            if (readerFile.exists()) readerFile else null
        }
    }

    private fun rebuildSimpleBook(book: Book, readerFile: File): File? {
        return try {
            val source = findSourceFile(File(book.unpackedDirPath))
            if (source == null) return if (readerFile.exists()) readerFile else null
            val text = source.readText(Charsets.UTF_8)
            val title = book.title
            val body = when (book.format) {
                AppConstants.FORMAT_MARKDOWN -> markdownToHtml(text)
                AppConstants.FORMAT_HTML -> extractBodyContent(text)
                AppConstants.FORMAT_XML -> xmlToHtml(text)
                else -> textToHtml(text)
            }
            writeSimpleReader(readerFile, title, listOf("Content"), listOf(body))
            readerFile
        } catch (error: Exception) {
            if (readerFile.exists()) readerFile else null
        }
    }

    private fun findSourceFile(dir: File): File? {
        if (!dir.exists()) return null
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("source") }
    }

    private fun copyUriToFile(context: Context, uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException(AppConstants.ERR_OPEN_STREAM)
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                var read = source.read(buffer)
                while (read > 0) {
                    total += read
                    if (total > AppConstants.MAX_UNCOMPRESSED_BYTES) {
                        throw SecurityException(AppConstants.ERR_ZIP_LIMIT_EXCEEDED)
                    }
                    output.write(buffer, 0, read)
                    read = source.read(buffer)
                }
            }
        }
    }

    private fun parseEpubBook(bookId: String, bookDir: File, sourceFile: File): Book {
        val unpackedDir = File(bookDir, AppConstants.UNPACKED_DIR)
        unpackedDir.mkdirs()
        unzip(sourceFile, unpackedDir)
        val root = unpackedDir.canonicalFile

        val opfFile = findOpfFile(root)
            ?: throw IllegalStateException(AppConstants.ERR_MISSING_OPF)
        val opfRelativePath = opfFile.relativeTo(root).invariantSeparatorsPath
        val opfParentDir = parentDirOf(opfRelativePath)

        val document = parseXmlDocument(opfFile)
        val metadata = extractMetadata(document)
        val manifest = extractManifest(document)
        val coverItem = selectCoverItem(manifest, metadata.coverId)
        val coverPath = copyCoverImage(coverItem, opfParentDir, root, bookDir)
        val chapterPaths = extractChapterPaths(document, manifest, opfParentDir, root)
        val titles = chapterPaths.mapIndexed { index, path ->
            chapterTitleFor(File(root, path), path, metadata.title, index)
        }

        val readerHtml = File(bookDir, AppConstants.READER_HTML_FILE)
        buildReaderHtml(root, chapterPaths, titles, readerHtml, metadata.title)
        sourceFile.delete()

        return Book(
            id = bookId,
            title = metadata.title,
            author = metadata.author,
            unpackedDirPath = root.absolutePath,
            readerHtmlPath = readerHtml.absolutePath,
            coverPath = coverPath,
            format = AppConstants.FORMAT_EPUB,
            totalChapters = chapterPaths.size,
            chapterTitles = titles
        )
    }

    private fun parseTextBook(
        bookId: String,
        bookDir: File,
        sourceFile: File,
        displayName: String?,
        format: String
    ): Book {
        val raw = sourceFile.readText(Charsets.UTF_8)
        val title = displayName?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: AppConstants.DEFAULT_TITLE
        val rawBody = when (format) {
            AppConstants.FORMAT_MARKDOWN -> markdownToHtml(raw)
            AppConstants.FORMAT_HTML -> extractBodyContent(raw)
            AppConstants.FORMAT_XML -> xmlToHtml(raw)
            else -> textToHtml(raw)
        }
        val body = sanitizeAndRewriteHtml(rawBody, "", emptyMap(), 0)
        val readerHtml = File(bookDir, AppConstants.READER_HTML_FILE)
        writeSimpleReader(readerHtml, title, listOf("Content"), listOf(body))

        return Book(
            id = bookId,
            title = title,
            author = AppConstants.DEFAULT_AUTHOR,
            unpackedDirPath = bookDir.absolutePath,
            readerHtmlPath = readerHtml.absolutePath,
            coverPath = null,
            format = format,
            totalChapters = 1,
            chapterTitles = listOf("Content")
        )
    }

    private fun parsePdfBook(
        bookId: String,
        bookDir: File,
        sourceFile: File,
        displayName: String?
    ): Book {
        val pagesDir = File(bookDir, AppConstants.PDF_PAGES_DIR)
        pagesDir.mkdirs()
        val pageNames = mutableListOf<String>()
        ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val count = renderer.pageCount.coerceAtLeast(1).coerceAtMost(400)
                for (index in 0 until count) {
                    renderer.openPage(index).use { page ->
                        val width = 1240
                        val height = (width * page.height / page.width.toFloat()).toInt()
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val name = String.format(Locale.US, "page-%04d.png", index + 1)
                        File(pagesDir, name).outputStream().use { output ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 90, output)
                        }
                        if (index == 0) {
                            val coverDir = File(bookDir, AppConstants.COVER_DIR)
                            coverDir.mkdirs()
                            File(coverDir, "cover.png").outputStream().use { output ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 90, output)
                            }
                        }
                        bitmap.recycle()
                        pageNames.add(name)
                    }
                }
            }
        }

        val title = displayName?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: AppConstants.DEFAULT_TITLE
        val bodies = pageNames.map { name ->
            "<div class='pdf-page'><img src=\"" + AppConstants.VIRTUAL_ASSET_ORIGIN +
                AppConstants.PDF_PAGES_DIR + "/" + name + "\"/></div>"
        }
        val titles = pageNames.indices.map { "Page " + (it + 1) }
        val readerHtml = File(bookDir, AppConstants.READER_HTML_FILE)
        writeSimpleReader(readerHtml, title, titles, bodies)
        val cover = File(File(bookDir, AppConstants.COVER_DIR), "cover.png")

        return Book(
            id = bookId,
            title = title,
            author = AppConstants.DEFAULT_AUTHOR,
            unpackedDirPath = bookDir.absolutePath,
            readerHtmlPath = readerHtml.absolutePath,
            coverPath = if (cover.exists()) cover.absolutePath else null,
            format = AppConstants.FORMAT_PDF,
            totalChapters = pageNames.size,
            chapterTitles = titles
        )
    }

    private fun writeSimpleReader(
        target: File,
        title: String,
        titles: List<String>,
        bodies: List<String>
    ) {
        val sb = StringBuilder(64 * 1024)
        val nonce = generateScriptNonce()
        appendDocumentHead(sb, title, nonce)
        bodies.forEachIndexed { index, body ->
            sb.append("<section class='epub-chapter' id='chapter-")
                .append(index)
                .append("' data-chapter='")
                .append(index)
                .append("' data-title='")
                .append(escapeHtml(titles.getOrElse(index) { "Chapter " + (index + 1) }))
                .append("'>")
            sb.append(body)
            sb.append("</section>")
            if (index < bodies.size - 1) {
                sb.append("<div class='chapter-divider'></div>")
            }
        }
        appendDocumentTail(sb, nonce)
        target.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun textToHtml(text: String): String {
        val out = StringBuilder(text.length + 64)
        text.split(Regex("""\n\s*\n""")).forEach { paragraph ->
            val clean = paragraph.trim()
            if (clean.isNotEmpty()) {
                out.append("<p>").append(escapeHtml(clean).replace("\n", "<br/>")).append("</p>")
            }
        }
        return out.toString()
    }

    private fun xmlToHtml(text: String): String {
        val withoutDeclaration = text.replace(Regex("""(?is)<\?xml[^>]*\?>"""), "")
        val plain = withoutDeclaration
            .replace(Regex("""(?is)<!DOCTYPE[^>]*>"""), "")
            .replace(Regex("""(?is)<(script|style)[^>]*>.*?</\1>"""), "")
            .replace(Regex("""(?s)<[^>]+>"""), "\n")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return textToHtml(plain)
    }

    private fun markdownToHtml(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").split("\n")
        val out = StringBuilder(markdown.length + 128)
        var inCode = false
        var inList = false
        var paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                out.append("<p>").append(inlineMarkdown(paragraph.toString().trim())).append("</p>")
                paragraph = StringBuilder()
            }
        }

        fun closeList() {
            if (inList) {
                out.append("</ul>")
                inList = false
            }
        }

        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            when {
                line.startsWith("```") -> {
                    flushParagraph()
                    closeList()
                    if (inCode) {
                        out.append("</code></pre>")
                    } else {
                        out.append("<pre><code>")
                    }
                    inCode = !inCode
                }
                inCode -> out.append(escapeHtml(line)).append("\n")
                line.isBlank() -> {
                    flushParagraph()
                    closeList()
                }
                line.startsWith("#") -> {
                    flushParagraph()
                    closeList()
                    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    val content = line.drop(level).trim()
                    out.append("<h").append(level).append(">")
                        .append(inlineMarkdown(content))
                        .append("</h").append(level).append(">")
                }
                line.startsWith(">") -> {
                    flushParagraph()
                    closeList()
                    out.append("<blockquote>")
                        .append(inlineMarkdown(line.drop(1).trim()))
                        .append("</blockquote>")
                }
                line.matches(Regex("""^[-*+]\s+.*""")) || line.matches(Regex("""^\d+\.\s+.*""")) -> {
                    flushParagraph()
                    if (!inList) {
                        out.append("<ul>")
                        inList = true
                    }
                    val content = line.replaceFirst(Regex("""^([-*+]|\d+\.)\s+"""), "")
                    out.append("<li>").append(inlineMarkdown(content)).append("</li>")
                }
                line.startsWith("---") || line.startsWith("***") -> {
                    flushParagraph()
                    closeList()
                    out.append("<hr/>")
                }
                else -> {
                    closeList()
                    if (paragraph.isNotEmpty()) paragraph.append(" ")
                    paragraph.append(line)
                }
            }
        }
        flushParagraph()
        closeList()
        if (inCode) out.append("</code></pre>")
        return out.toString()
    }

    private fun inlineMarkdown(text: String): String {
        var result = escapeHtml(text)
        result = Regex("""\*\*(.+?)\*\*""").replace(result, "<strong>$1</strong>")
        result = Regex("""__(.+?)__""").replace(result, "<strong>$1</strong>")
        result = Regex("""(?<!\*)\*([^*]+)\*""").replace(result, "<em>$1</em>")
        result = Regex("""`([^`]+)`""").replace(result, "<code>$1</code>")
        result = Regex("""\[([^\]]+)\]\(([^)]+)\)""").replace(result, "<a href=\"$2\">$1</a>")
        return result
    }

    private fun chapterTitleFor(
        file: File,
        fallbackPath: String,
        bookTitle: String,
        index: Int
    ): String {
        return try {
            if (!file.exists()) return "Section " + (index + 1)
            val raw = file.readText(Charsets.UTF_8)
            val headingMatch = Regex("""(?is)<h[1-3][^>]*>(.*?)</h[1-3]>""").find(raw)
            val heading = headingMatch?.groupValues?.getOrNull(1)
                ?.replace(Regex("""(?s)<[^>]+>"""), "")
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()
            if (!heading.isNullOrBlank()) return heading
            val titleMatch = Regex("""(?is)<title[^>]*>(.*?)</title>""").find(raw)
            val title = titleMatch?.groupValues?.getOrNull(1)
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()
            if (!title.isNullOrBlank() && !title.equals(bookTitle, ignoreCase = true)) return title
            "Section " + (index + 1)
        } catch (error: Exception) {
            "Section " + (index + 1)
        }
    }

    private fun parseXmlDocument(file: File): Document {
        if (file.length() > AppConstants.MAX_XML_BYTES) {
            throw SecurityException(AppConstants.ERR_XML_TOO_LARGE)
        }
        val bytes = file.readBytes()
        val decodedXml = decodeXmlForSafetyCheck(bytes)
        if (decodedXml.indexOf(AppConstants.XML_ENTITY_MARKER, ignoreCase = true) >= 0) {
            throw SecurityException(AppConstants.ERR_UNSAFE_XML)
        }
        val cleanedXml = stripXmlDoctype(decodedXml)
        val factory = newSecureFactory()
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(ByteArrayInputStream(cleanedXml.toByteArray(Charsets.UTF_8)))
        document.documentElement.normalize()
        return document
    }

    private fun stripXmlDoctype(xml: String): String {
        var result = xml
        if (result.startsWith("\uFEFF")) result = result.substring(1)
        result = Regex("""(?is)<\?xml[^>]*\?>""").replaceFirst(result, "")
        result = Regex("""(?is)<!DOCTYPE[^\[>]*(\[[^\]]*\])?\s*>""").replace(result) { match ->
            if (match.value.contains(AppConstants.XML_ENTITY_MARKER, ignoreCase = true)) {
                throw SecurityException(AppConstants.ERR_UNSAFE_XML)
            }
            ""
        }
        return result.trimStart()
    }

    private fun decodeXmlForSafetyCheck(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, Charsets.UTF_16BE)
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, Charsets.UTF_16LE)
            if (b0 == 0x00 && b1 != 0x00) return String(bytes, Charsets.UTF_16BE)
            if (b0 != 0x00 && b1 == 0x00) return String(bytes, Charsets.UTF_16LE)
        }
        val charset = detectXmlCharset(bytes) ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    private fun elementsByLocalName(parent: Element, localName: String): List<Element> {
        val result = ArrayList<Element>()
        val all: NodeList = parent.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val node = all.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val elem = node as Element
                val name = (elem.localName ?: elem.tagName).substringAfterLast(':')
                if (name.equals(localName, ignoreCase = true)) result.add(elem)
            }
        }
        return result
    }

    private fun extractMetadata(document: Document): BookMetadata {
        var title = AppConstants.DEFAULT_TITLE
        var author = AppConstants.DEFAULT_AUTHOR
        var coverId: String? = null
        val root = document.documentElement ?: return BookMetadata(title, author, coverId)
        val metadataElement = elementsByLocalName(root, "metadata").firstOrNull() ?: root
        val children = metadataElement.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            val name = (element.localName ?: element.tagName).substringAfterLast(':')
            when {
                name.equals("title", true) -> {
                    val text = element.textContent.trim()
                    if (text.isNotEmpty()) title = text
                }
                name.equals("creator", true) -> {
                    val text = element.textContent.trim()
                    if (text.isNotEmpty()) author = text
                }
                name.equals("meta", true) -> {
                    val resolvedCover = extractCoverMetaId(element)
                    if (resolvedCover != null) coverId = resolvedCover
                }
            }
        }
        return BookMetadata(title, author, coverId)
    }

    private fun extractCoverMetaId(element: Element): String? {
        val metaName = element.getAttribute("name")
        val property = element.getAttribute("property")
        val content = element.getAttribute("content")
        if (metaName.equals("cover", true) && content.isNotEmpty()) return content
        if (property.equals("cover-image", true)) {
            val text = element.textContent.trim()
            return if (text.isNotEmpty()) text else content.ifEmpty { null }
        }
        return null
    }

    private fun extractManifest(document: Document): LinkedHashMap<String, ManifestItem> {
        val manifest = LinkedHashMap<String, ManifestItem>()
        val root = document.documentElement ?: return manifest
        val manifestElem = elementsByLocalName(root, "manifest").firstOrNull() ?: root
        for (item in elementsByLocalName(manifestElem, "item")) {
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            val mediaType = item.getAttribute("media-type")
            val properties = item.getAttribute("properties")
            if (id.isNotEmpty() && href.isNotEmpty()) {
                manifest[id] = ManifestItem(id, href, mediaType, properties)
            }
        }
        return manifest
    }

    private fun selectCoverItem(
        manifest: Map<String, ManifestItem>,
        coverId: String?
    ): ManifestItem? {
        val explicit = manifest.values.firstOrNull { item ->
            item.properties.contains("cover-image", true) ||
                (coverId != null && item.id.equals(coverId, true))
        }
        if (explicit != null) return explicit
        return manifest.values.firstOrNull { item ->
            item.mediaType.startsWith("image/") &&
                (item.id.contains("cover", true) || item.href.contains("cover", true))
        }
    }

    private fun copyCoverImage(
        coverItem: ManifestItem?,
        opfParentDir: String,
        unpackedDir: File,
        bookDir: File
    ): String? {
        if (coverItem == null) return null
        return try {
            val decoded = decodePercentPath(coverItem.href)
            val coverRelative = resolveRelativePath(opfParentDir, decoded)
            val coverSource = File(unpackedDir, coverRelative).canonicalFile
            if (!isInsideDirectory(coverSource, unpackedDir.canonicalFile)) return null
            if (!coverSource.exists() || !coverSource.isFile) return null
            val coverDir = File(bookDir, AppConstants.COVER_DIR)
            coverDir.mkdirs()
            val extension = coverSource.extension.ifEmpty { AppConstants.DEFAULT_COVER_EXT }
            val destination = File(coverDir, "cover." + extension)
            coverSource.copyTo(destination, overwrite = true)
            destination.absolutePath
        } catch (error: Exception) {
            null
        }
    }

    private fun extractChapterPaths(
        document: Document,
        manifest: Map<String, ManifestItem>,
        opfParentDir: String,
        unpackedDir: File
    ): List<String> {
        val chapterPaths = mutableListOf<String>()
        val root = document.documentElement
        if (root != null) {
            val spineElem = elementsByLocalName(root, "spine").firstOrNull() ?: root
            for (itemRef in elementsByLocalName(spineElem, "itemref")) {
                val idRef = itemRef.getAttribute("idref")
                val manifestItem = manifest[idRef] ?: continue
                val decoded = decodePercentPath(manifestItem.href)
                val resolved = resolveRelativePath(opfParentDir, decoded)
                if (resolved.isNotEmpty() && File(unpackedDir, resolved).exists()) {
                    chapterPaths.add(resolved)
                }
            }
        }
        if (chapterPaths.isNotEmpty()) return chapterPaths
        for (item in manifest.values) {
            val ext = item.href.substringBefore('#').substringAfterLast('.', "").lowercase()
            if (item.mediaType.contains("html", true) || ext in setOf("xhtml", "html", "htm")) {
                val decoded = decodePercentPath(item.href)
                val resolved = resolveRelativePath(opfParentDir, decoded)
                if (resolved.isNotEmpty() && File(unpackedDir, resolved).exists()) {
                    chapterPaths.add(resolved)
                }
            }
        }
        if (chapterPaths.isNotEmpty()) return chapterPaths
        return discoverHtmlFilesFallback(unpackedDir)
    }

    private fun discoverHtmlFilesFallback(unpackedDir: File): List<String> {
        return unpackedDir.walkTopDown()
            .filter { file ->
                file.isFile && file.extension.lowercase() in setOf("xhtml", "html", "htm") &&
                    !file.name.equals("toc.ncx", ignoreCase = true)
            }
            .map { it.relativeTo(unpackedDir).invariantSeparatorsPath }
            .sorted()
            .toList()
    }

    private fun decodePercentPath(raw: String): String {
        return try {
            Uri.decode(raw)
        } catch (error: Exception) {
            raw
        }
    }

    private fun generateScriptNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun buildReaderHtml(
        unpackedDir: File,
        chapterPaths: List<String>,
        chapterTitles: List<String>,
        outputFile: File,
        bookTitle: String
    ) {
        val html = StringBuilder(96 * 1024)
        val nonce = generateScriptNonce()
        appendDocumentHead(html, bookTitle, nonce)
        val chapterIndexMap = HashMap<String, Int>(chapterPaths.size)
        for (idx in chapterPaths.indices) chapterIndexMap[chapterPaths[idx]] = idx
        val canonicalRoot = unpackedDir.canonicalFile
        for (index in chapterPaths.indices) {
            val chapterPath = chapterPaths[index]
            val chapterFile = File(canonicalRoot, chapterPath).canonicalFile
            if (!isInsideDirectory(chapterFile, canonicalRoot)) continue
            if (!chapterFile.exists() || !chapterFile.isFile) continue
            val raw = readChapterText(chapterFile)
            val body = extractBodyContent(raw)
            val sanitized = sanitizeAndRewriteHtml(
                body,
                parentDirOf(chapterPath),
                chapterIndexMap,
                index
            )
            if (sanitized.isBlank()) continue
            html.append("<section class='epub-chapter' id='chapter-")
                .append(index)
                .append("' data-chapter='")
                .append(index)
                .append("' data-title='")
                .append(escapeHtml(chapterTitles.getOrElse(index) { "Chapter " + (index + 1) }))
                .append("'>")
            html.append(sanitized)
            html.append("</section>")
            if (index < chapterPaths.size - 1) {
                html.append("<div class='chapter-divider'></div>")
            }
        }
        appendDocumentTail(html, nonce)
        outputFile.writeText(html.toString(), Charsets.UTF_8)
    }

    private fun appendDocumentHead(html: StringBuilder, bookTitle: String, nonce: String) {
        val origin = AppConstants.VIRTUAL_ASSET_ORIGIN.trimEnd('/')
        html.append("""<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8"/>
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src """).append(origin).append(""" data:; style-src 'unsafe-inline' """).append(origin).append("""; font-src """).append(origin).append(""" data:; script-src 'nonce-""").append(nonce).append("""'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none';"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes"/>
<title>""").append(escapeHtml(bookTitle)).append("""</title>
<style>
  html, body { background: #ffffff !important; }
  html { margin: 0; padding: 0; }
  body {
    margin: 0 auto;
    padding: 22px 22px 96px 22px;
    max-width: 720px;
    color: #18202c !important;
    font-family: "Georgia", "Noto Serif", "Times New Roman", serif;
    font-size: var(--wh-font-size, 19px);
    line-height: 1.78;
    letter-spacing: 0.01em;
    text-rendering: optimizeLegibility;
    -webkit-font-smoothing: antialiased;
    word-wrap: break-word;
    overflow-wrap: break-word;
  }
  .epub-chapter { background: #ffffff !important; }
  .chapter-divider { width: 44px; height: 3px; margin: 42px auto; border-radius: 3px; background: #e3ebf5; }
  p, div, span, li, td, th { color: #18202c !important; background-color: transparent !important; }
  p { margin: 0 0 1.1em 0; }
  h1, h2, h3, h4, h5, h6 { color: #10233f !important; line-height: 1.35; font-weight: 700; margin: 1.4em 0 0.7em 0; }
  h1 { font-size: 1.5em; } h2 { font-size: 1.32em; } h3 { font-size: 1.18em; }
  img, svg, image { max-width: 100% !important; height: auto !important; display: block; margin: 16px auto; background: transparent !important; }
  blockquote { margin: 1.3em 0; padding: 2px 0 2px 16px; border-left: 3px solid #93b4e6; color: #44536b !important; font-style: italic; }
  a { color: #2563eb !important; }
  hr { border: 0; height: 1px; background: #e6edf6; margin: 28px auto; width: 72%; }
  pre, code { font-family: "Roboto Mono", monospace; font-size: 0.88em; }
  pre { padding: 12px; overflow-x: auto; background: #f6f8fb !important; border-radius: 6px; }
  mark.wh-hl { border-radius: 2px; padding: 0; }
  .pdf-page img { margin: 0 auto 14px auto; }
  .wh-flash { animation: whflash 1.2s ease-out; }
  @keyframes whflash { 0% { background: #FFEB3B; } 100% { background: transparent; } }
  ::selection { background: #CDE3FF; }
</style>
</head>
<body data-white-schema='7'>
""")
    }

    private fun appendDocumentTail(html: StringBuilder, nonce: String) {
        val bridge = AppConstants.JS_BRIDGE_NAME
        val onScroll = AppConstants.JS_METHOD_ON_SCROLL
        val onSelection = AppConstants.JS_METHOD_ON_SELECTION
        val restoreFn = AppConstants.JS_FUNC_RESTORE
        val flushFn = AppConstants.JS_FUNC_FLUSH
        html.append("<script nonce=\"").append(nonce).append("\">\n")
        html.append("""(function () {
  var restoring = false;
  var pending = null;
  var targetTop = 0;
  var targetPercent = 0;
  var selectionTimer = null;

  function currentScroll() { return window.pageYOffset || document.documentElement.scrollTop || 0; }
  function maxScroll() { return Math.max(0, document.documentElement.scrollHeight - window.innerHeight); }
  function progress() { var m = maxScroll(); return m <= 0 ? 0 : Math.min(100, Math.max(0, Math.round((currentScroll() / m) * 100))); }
  function pageInfo() {
    var h = Math.max(1, window.innerHeight);
    var pages = Math.max(1, Math.ceil(document.documentElement.scrollHeight / h));
    var page = Math.min(pages, Math.floor(currentScroll() / h) + 1);
    return [page, pages];
  }

  function sendProgress() {
    var top = currentScroll();
    var pct = progress();
    targetTop = top; targetPercent = pct;
    var pi = pageInfo();
    var bridge = window.""").append(bridge).append(""";
    if (bridge && bridge.""").append(onScroll).append(""") {
      bridge.""").append(onScroll).append("""(top, pct, pi[0], pi[1]);
    }
  }

  window.addEventListener("scroll", function () {
    if (restoring) return;
    if (pending) clearTimeout(pending);
    pending = setTimeout(sendProgress, 120);
  }, { passive: true });

  function applyTargetScroll() {
    var max = maxScroll();
    var desired = targetTop;
    if (desired <= 0 && targetPercent > 0 && max > 0) desired = (targetPercent / 100.0) * max;
    else if (desired > max && targetPercent > 0 && max > 0) desired = (targetPercent / 100.0) * max;
    window.scrollTo(0, desired);
  }

  window.""").append(restoreFn).append(""" = function (top, pct) {
    restoring = true; targetTop = top || 0; targetPercent = pct || 0;
    applyTargetScroll();
    setTimeout(applyTargetScroll, 40);
    setTimeout(function () { applyTargetScroll(); restoring = false; sendProgress(); }, 180);
  };

  window.""").append(flushFn).append(""" = function () {
    if (pending) { clearTimeout(pending); pending = null; }
    sendProgress();
  };

  window.addEventListener("load", function () {
    if (targetTop > 0 || targetPercent > 0) {
      restoring = true; applyTargetScroll();
      setTimeout(function () { restoring = false; sendProgress(); }, 60);
    }
  });

  window.addEventListener("resize", function () {
    if (targetPercent > 0) {
      restoring = true; targetTop = 0; applyTargetScroll();
      setTimeout(function () { restoring = false; sendProgress(); }, 80);
    }
  });

  function chapterOf(node) {
    while (node && node !== document.body) {
      if (node.classList && node.classList.contains("epub-chapter")) return node;
      node = node.parentNode;
    }
    return null;
  }

  function offsetIn(root, node, offset) {
    try {
      var r = document.createRange();
      r.selectNodeContents(root);
      r.setEnd(node, offset);
      return r.toString().length;
    } catch (e) { return -1; }
  }

  function reportSelection() {
    var sel = window.getSelection();
    if (!sel || sel.isCollapsed || !sel.rangeCount) return;
    var range = sel.getRangeAt(0);
    var chapter = chapterOf(range.startContainer);
    if (!chapter) return;
    var index = parseInt(chapter.getAttribute("data-chapter") || "0", 10);
    var start = offsetIn(chapter, range.startContainer, range.startOffset);
    var end = offsetIn(chapter, range.endContainer, range.endOffset);
    if (start < 0 || end <= start) return;
    var text = sel.toString().trim();
    if (!text.length) return;
    var bridge = window.""").append(bridge).append(""";
    if (bridge && bridge.""").append(onSelection).append(""") {
      bridge.""").append(onSelection).append("""(index, start, end, text.substring(0, 2000));
    }
  }

  document.addEventListener("selectionchange", function () {
    if (selectionTimer) clearTimeout(selectionTimer);
    selectionTimer = setTimeout(reportSelection, 220);
  });

  function textNodesInRange(range) {
    var chapter = chapterOf(range.startContainer);
    if (!chapter) return [];
    var walker = document.createTreeWalker(chapter, NodeFilter.SHOW_TEXT, null);
    var nodes = [], n;
    while ((n = walker.nextNode())) {
      var r = document.createRange();
      r.selectNodeContents(n);
      if (r.compareBoundaryPoints(Range.END_TO_START, range) > 0 &&
          r.compareBoundaryPoints(Range.START_TO_END, range) < 0) nodes.push(n);
    }
    return nodes;
  }

  window.White = window.White || {};
  window.White.fontSize = function (px) {
    document.documentElement.style.setProperty("--wh-font-size", px + "px");
  };
  window.White.clearSelection = function () {
    var sel = window.getSelection();
    if (sel) sel.removeAllRanges();
  };
  window.White.jumpToChapter = function (index) {
    var el = document.getElementById("chapter-" + index);
    if (el) { el.scrollIntoView({ block: "start" }); }
  };
  window.White.jumpToHighlight = function (id) {
    var el = document.querySelector("mark[data-hl=\"" + id + "\"]");
    if (!el) return false;
    el.scrollIntoView({ block: "center" });
    el.classList.add("wh-flash");
    setTimeout(function () { el.classList.remove("wh-flash"); }, 1400);
    return true;
  };
  window.White.applyHighlights = function (json) {
    var marks = document.querySelectorAll("mark.wh-hl");
    for (var i = 0; i < marks.length; i++) {
      var m = marks[i];
      var t = document.createTextNode(m.textContent);
      if (m.parentNode) { m.parentNode.replaceChild(t, m); m.parentNode.normalize(); }
    }
    var list = [];
    try { list = JSON.parse(json || "[]"); } catch (e) { return; }
    var byChapter = {};
    list.forEach(function (h) { (byChapter[h.c] = byChapter[h.c] || []).push(h); });
    Object.keys(byChapter).forEach(function (key) {
      var chapter = document.getElementById("chapter-" + key);
      if (!chapter) return;
      var items = byChapter[key].slice().sort(function (a, b) { return b.s - a.s; });
      items.forEach(function (h) {
        try {
          var walker = document.createTreeWalker(chapter, NodeFilter.SHOW_TEXT, null);
          var nodesInfo = [], total = 0, n;
          while ((n = walker.nextNode())) {
            nodesInfo.push({ node: n, start: total, end: total + n.nodeValue.length });
            total += n.nodeValue.length;
          }
          for (var k = nodesInfo.length - 1; k >= 0; k--) {
            var info = nodesInfo[k];
            if (info.end <= h.s || info.start >= h.e) continue;
            var localStart = Math.max(0, h.s - info.start);
            var localEnd = Math.min(info.node.nodeValue.length, h.e - info.start);
            if (localEnd <= localStart) continue;
            var r = document.createRange();
            r.setStart(info.node, localStart);
            r.setEnd(info.node, localEnd);
            var mark = document.createElement("mark");
            mark.className = "wh-hl";
            mark.setAttribute("data-hl", h.id);
            mark.style.backgroundColor = h.color;
            mark.style.color = "inherit";
            r.surroundContents(mark);
          }
      });
    });
  };
})();
</script>
</body>
</html>
""")
    }

    private fun readChapterText(file: File): String {
        val bytes = try { file.readBytes() } catch (error: Exception) { return "" }
        if (bytes.isEmpty()) return ""
        return decodeXmlForSafetyCheck(bytes)
    }

    private fun detectXmlCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1)
        val marker = "encoding="
        val index = head.lowercase().indexOf(marker)
        if (index < 0) return null
        var start = index + marker.length
        if (start < head.length) {
            val quote = head[start]
            if (quote == '"' || quote == '\'') start += 1
        }
        var end = start
        while (end < head.length && isCharsetChar(head[end])) end += 1
        if (end <= start) return null
        return try { Charset.forName(head.substring(start, end)) } catch (error: Exception) { null }
    }

    private fun isCharsetChar(ch: Char): Boolean {
        return (ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') || ch == '-' || ch == '_'
    }

    private fun extractBodyContent(html: String): String {
        val bodyMatch = Regex("""(?is)<(?:\w+:)?body\b[^>]*>(.*?)</(?:\w+:)?body\s*>""").find(html)
        if (bodyMatch != null) return bodyMatch.groupValues[1]
        return html
    }

    private fun sanitizeAndRewriteHtml(
        html: String,
        baseDir: String,
        chapterIndexMap: Map<String, Int>,
        chapterIndex: Int
    ): String {
        val out = StringBuilder(html.length)
        var idx = 0
        while (idx < html.length) {
            val lt = html.indexOf('<', startIndex = idx)
            if (lt < 0) {
                appendEscapedText(out, html, idx, html.length)
                break
            }
            if (lt > idx) appendEscapedText(out, html, idx, lt)
            if (html.startsWith("<!--", startIndex = lt)) {
                val commentEnd = html.indexOf("-->", startIndex = lt + 4)
                idx = if (commentEnd < 0) html.length else commentEnd + 3
                continue
            }
            if (html.startsWith("<![CDATA[", startIndex = lt)) {
                val cdataEnd = html.indexOf("]]>", startIndex = lt + 9)
                if (cdataEnd < 0) {
                    appendEscapedText(out, html, lt + 9, html.length)
                    break
                }
                appendEscapedText(out, html, lt + 9, cdataEnd)
                idx = cdataEnd + 3
                continue
            }
            val gt = findTagEnd(html, lt + 1)
            if (gt < 0) {
                appendEscapedText(out, html, lt, html.length)
                break
            }
            val rawTag = html.substring(lt + 1, gt).trim()
            idx = handleParsedTag(
                html, rawTag, gt + 1, baseDir, chapterIndexMap, chapterIndex, out
            )
        }
        return out.toString()
    }

    private fun findTagEnd(html: String, start: Int): Int {
        var i = start
        var quote = '\u0000'
        while (i < html.length) {
            val c = html[i]
            if (quote != '\u0000') { if (c == quote) quote = '\u0000' }
            else if (c == '"' || c == '\'') quote = c
            else if (c == '>') return i
            i += 1
        }
        return -1
    }

    private fun handleParsedTag(
        html: String,
        rawTag: String,
        afterTagIdx: Int,
        baseDir: String,
        chapterIndexMap: Map<String, Int>,
        chapterIndex: Int,
        out: StringBuilder
    ): Int {
        if (rawTag.isEmpty() || rawTag.startsWith("!") || rawTag.startsWith("?")) return afterTagIdx
        val isClosing = rawTag.startsWith("/")
        val body = if (isClosing) rawTag.substring(1).trim() else rawTag
        val selfClosing = !isClosing && body.endsWith("/")
        val cleanBody = if (selfClosing) body.dropLast(1).trim() else body
        val nameEnd = cleanBody.indexOfFirst { it.isWhitespace() }
        val rawName = if (nameEnd < 0) cleanBody else cleanBody.substring(0, nameEnd)
        val tagName = rawName.substringAfterLast(':').lowercase()

        if (tagName in DROPPED_SUBTREE_TAGS) {
            if (isClosing || selfClosing) return afterTagIdx
            return skipDroppedSubtree(html, tagName, afterTagIdx)
        }
        if (tagName !in ALLOWED_TAGS) return afterTagIdx
        if (isClosing) {
            if (tagName !in VOID_TAGS) out.append("</").append(tagName).append('>')
            return afterTagIdx
        }
        val attrSegment = if (nameEnd < 0) "" else cleanBody.substring(nameEnd + 1)
        if (tagName == "image") {
            out.append("<img")
            appendSanitizedAttributes(attrSegment, baseDir, chapterIndexMap, chapterIndex, out)
            out.append("/>")
            return afterTagIdx
        }
        out.append('<').append(tagName)
        appendSanitizedAttributes(attrSegment, baseDir, chapterIndexMap, chapterIndex, out)
        if (tagName in VOID_TAGS) out.append("/>")
        else if (selfClosing) out.append("></").append(tagName).append('>')
        else out.append('>')
        return afterTagIdx
    }

    private fun skipDroppedSubtree(html: String, tagName: String, fromIndex: Int): Int {
        val closeRegex = Regex("""(?is)</(?:\w+:)?$tagName\s*>""")
        val match = closeRegex.find(html, startIndex = fromIndex)
        return match?.range?.last?.plus(1) ?: html.length
    }

    private fun appendSanitizedAttributes(
        attrSegment: String,
        baseDir: String,
        chapterIndexMap: Map<String, Int>,
        chapterIndex: Int,
        out: StringBuilder
    ) {
        var i = 0
        while (i < attrSegment.length) {
            while (i < attrSegment.length && attrSegment[i].isWhitespace()) i += 1
            if (i >= attrSegment.length) break
            val nameStart = i
            while (i < attrSegment.length && !attrSegment[i].isWhitespace() && attrSegment[i] != '=') i += 1
            val rawName = attrSegment.substring(nameStart, i).lowercase()
            val attrName = if (rawName == "xlink:href") "src" else rawName
            while (i < attrSegment.length && attrSegment[i].isWhitespace()) i += 1
            if (i >= attrSegment.length || attrSegment[i] != '=') continue
            i += 1
            while (i < attrSegment.length && attrSegment[i].isWhitespace()) i += 1
            if (i >= attrSegment.length) break
            val value: String
            val first = attrSegment[i]
            if (first == '"' || first == '\'') {
                val endQuote = attrSegment.indexOf(first, startIndex = i + 1)
                if (endQuote < 0) { value = attrSegment.substring(i + 1); i = attrSegment.length }
                else { value = attrSegment.substring(i + 1, endQuote); i = endQuote + 1 }
            } else {
                val valStart = i
                while (i < attrSegment.length && !attrSegment[i].isWhitespace()) i += 1
                value = attrSegment.substring(valStart, i)
            }
            emitAllowedAttribute(
                attrName, value.trim(), baseDir, chapterIndexMap, chapterIndex, out
            )
        }
    }

    private fun emitAllowedAttribute(
        attrName: String,
        rawValue: String,
        baseDir: String,
        chapterIndexMap: Map<String, Int>,
        chapterIndex: Int,
        out: StringBuilder
    ) {
        if (attrName !in ALLOWED_ATTRS) return
        val finalValue = when (attrName) {
            "src" -> rewriteResourceUrl(rawValue, baseDir)
            "href" -> rewriteHref(rawValue, baseDir, chapterIndexMap, chapterIndex)
            "id" -> if (rawValue.isBlank()) rawValue else "ch" + chapterIndex + "-" + rawValue
            else -> rawValue
        }
        out.append(' ').append(attrName).append("=\"").append(escapeHtml(finalValue)).append('"')
    }

    private fun rewriteResourceUrl(url: String, baseDir: String): String {
        if (url.isEmpty()) return url
        val lower = url.lowercase()
        if (lower.startsWith(AppConstants.SCHEME_JAVASCRIPT) ||
            lower.startsWith(AppConstants.SCHEME_FILE)
        ) return AppConstants.HASH_ANCHOR
        if (lower.startsWith(AppConstants.SCHEME_DATA)) {
            return if (lower.startsWith(AppConstants.SCHEME_DATA_IMAGE)) url else AppConstants.HASH_ANCHOR
        }
        if (lower.startsWith(AppConstants.SCHEME_HTTP) || lower.startsWith(AppConstants.SCHEME_HTTPS)) {
            return url
        }
        val resolved = resolveRelativePath(baseDir, decodePercentPath(url))
        if (resolved.isEmpty()) return url
        return AppConstants.VIRTUAL_ASSET_ORIGIN + resolved.trimStart('/')
    }

    private fun rewriteHref(
        url: String,
        baseDir: String,
        chapterIndexMap: Map<String, Int>,
        chapterIndex: Int
    ): String {
        if (url.isEmpty()) return url
        val lower = url.lowercase()
        if (lower.startsWith(AppConstants.SCHEME_JAVASCRIPT) ||
            lower.startsWith(AppConstants.SCHEME_FILE)
        ) return AppConstants.HASH_ANCHOR
        if (lower.startsWith(AppConstants.SCHEME_HTTP) ||
            lower.startsWith(AppConstants.SCHEME_HTTPS) ||
            lower.startsWith(AppConstants.SCHEME_MAILTO) ||
            lower.startsWith(AppConstants.SCHEME_TEL)
        ) return url
        if (url.startsWith(AppConstants.HASH_ANCHOR)) {
            val fragment = url.substring(1)
            return if (fragment.isBlank()) url else AppConstants.HASH_ANCHOR + "ch" + chapterIndex + "-" + fragment
        }
        val decoded = decodePercentPath(url)
        val hashIndex = decoded.indexOf('#')
        val pathPart = if (hashIndex >= 0) decoded.substring(0, hashIndex) else decoded
        val fragment = if (hashIndex >= 0) decoded.substring(hashIndex + 1) else ""
        val resolved = resolveRelativePath(baseDir, pathPart)
        val target = chapterIndexMap[resolved]
        return when {
            target != null && fragment.isNotBlank() -> AppConstants.HASH_ANCHOR + "ch" + target + "-" + fragment
            target != null -> AppConstants.HASH_ANCHOR + "chapter-" + target
            else -> AppConstants.HASH_ANCHOR + "chapter-" + chapterIndex
        }
    }

    private fun appendEscapedText(out: StringBuilder, text: String, start: Int, end: Int) {
        for (idx in start until end) {
            when (val ch = text[idx]) {
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                else -> out.append(ch)
            }
        }
    }

    private fun resolveRelativePath(baseDir: String, reference: String): String {
        var relative = reference
        val hash = relative.indexOf('#')
        if (hash >= 0) relative = relative.substring(0, hash)
        val question = relative.indexOf('?')
        if (question >= 0) relative = relative.substring(0, question)
        if (relative.isEmpty()) return ""
        val combined = if (baseDir.isEmpty()) relative else "$baseDir/$relative"
        val segments = combined.split('/')
        val stack = ArrayList<String>(segments.size)
        for (segment in segments) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack.add(segment)
            }
        }
        return stack.joinToString("/")
    }

    private fun parentDirOf(path: String): String {
        val slash = path.lastIndexOf('/')
        return if (slash <= 0) "" else path.substring(0, slash)
    }

    private fun findOpfFile(unpackedDir: File): File? {
        val canonicalRoot = unpackedDir.canonicalFile
        val container = File(unpackedDir, AppConstants.CONTAINER_XML_PATH).canonicalFile
        if (isInsideDirectory(container, canonicalRoot) && container.exists()) {
            try {
                val document = parseXmlDocument(container)
                val root = document.documentElement
                val rootFiles = if (root != null) elementsByLocalName(root, "rootfile") else emptyList()
                if (rootFiles.isNotEmpty()) {
                    val candidate = decodePercentPath(rootFiles[0].getAttribute("full-path"))
                    if (candidate.isNotEmpty()) {
                        val file = File(unpackedDir, candidate).canonicalFile
                        if (isInsideDirectory(file, canonicalRoot) && file.exists() && file.isFile) return file
                    }
                }
            } catch (error: Exception) {
                // Fall through to walk
            }
        }
        return unpackedDir.walkTopDown().firstOrNull {
            it.isFile && it.extension.equals(AppConstants.OPF_EXTENSION, true)
        }
    }

    private fun isInsideDirectory(file: File, root: File): Boolean {
        val filePath = file.canonicalPath
        val rootPath = root.canonicalPath
        return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
    }

    private fun newSecureFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        try { factory.isXIncludeAware = false } catch (error: Exception) { }
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (error: Exception) { }
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        } catch (error: Exception) { }
        return factory
    }

    private fun unzip(archive: File, targetDir: File) {
        val destination = targetDir.canonicalFile
        ZipInputStream(FileInputStream(archive)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            val buffer = ByteArray(16 * 1024)
            var entryCount = 0
            var totalBytes = 0L
            while (entry != null) {
                entryCount += 1
                if (entryCount > AppConstants.MAX_ZIP_ENTRIES) {
                    throw SecurityException(AppConstants.ERR_ZIP_LIMIT_EXCEEDED)
                }
                val output = File(targetDir, entry.name).canonicalFile
                if (!isInsideDirectory(output, destination)) {
                    throw SecurityException(AppConstants.ERR_UNSAFE_ZIP_ENTRY + entry.name)
                }
                if (entry.isDirectory) output.mkdirs()
                else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { fileOutput ->
                        var read = zip.read(buffer)
                        while (read > 0) {
                            totalBytes += read
                            if (totalBytes > AppConstants.MAX_UNCOMPRESSED_BYTES) {
                                throw SecurityException(AppConstants.ERR_ZIP_LIMIT_EXCEEDED)
                            }
                            fileOutput.write(buffer, 0, read)
                            read = zip.read(buffer)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun escapeHtml(text: String): String {
        val output = StringBuilder(text.length + 16)
        for (character in text) {
            when (character) {
                '&' -> output.append("&amp;")
                '<' -> output.append("&lt;")
                '>' -> output.append("&gt;")
                '"' -> output.append("&quot;")
                '\'' -> output.append("&#39;")
                else -> output.append(character)
            }
        }
        return output.toString()
    }
}
