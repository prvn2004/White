package open.source.white.parser

import android.content.Context
import android.net.Uri
import android.util.Base64
import open.source.white.common.AppConstants
import open.source.white.model.Book
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLDecoder
import java.nio.charset.Charset
import java.security.SecureRandom
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
        "svg", "g", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "text", "image",
        "math", "mrow", "mi", "mn", "mo", "msup", "msub", "msubsup", "mfrac", "msqrt", "mroot", "mtext"
    )

    private val VOID_TAGS = setOf("br", "hr", "img", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "image")

    private val ALLOWED_ATTRS = setOf(
        "id", "class", "src", "href", "alt", "title", "colspan", "rowspan", "dir", "lang",
        "viewbox", "width", "height", "d", "fill", "stroke", "stroke-width", "x", "y", "cx", "cy", "r", "rx", "ry", "points"
    )

    private val secureRandom = SecureRandom()

    fun parseBookFromUri(context: Context, uri: Uri): Book {
        val bookId = UUID.randomUUID().toString()
        val booksRoot = File(context.filesDir, AppConstants.BOOKS_DIR)
        val bookDir = File(booksRoot, bookId)
        val unpackedDir = File(bookDir, AppConstants.UNPACKED_DIR)
        bookDir.mkdirs()
        unpackedDir.mkdirs()

        val sourceEpub = File(bookDir, AppConstants.SOURCE_EPUB_FILE)
        return try {
            copyUriToFile(context, uri, sourceEpub)
            unzip(sourceEpub, unpackedDir)
            val parsed = parseUnpackedEpub(bookId, bookDir, unpackedDir)
            sourceEpub.delete()
            parsed
        } catch (error: Exception) {
            bookDir.deleteRecursively()
            throw error
        }
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

    private fun parseUnpackedEpub(
        bookId: String,
        bookDir: File,
        unpackedDir: File
    ): Book {
        val opfFile = findOpfFile(unpackedDir)
            ?: throw IllegalStateException(AppConstants.ERR_MISSING_OPF)

        val opfRelativePath = unpackedDir.toURI().relativize(opfFile.toURI()).path
        val opfParentDir = parentDirOf(opfRelativePath)

        val document = parseXmlDocument(opfFile)
        val metadata = extractMetadata(document)
        val manifest = extractManifest(document)
        val coverItem = selectCoverItem(manifest, metadata.coverId)
        val coverPath = copyCoverImage(coverItem, opfParentDir, unpackedDir, bookDir)
        val chapterPaths = extractChapterPaths(document, manifest, opfParentDir)

        val readerHtml = File(bookDir, AppConstants.READER_HTML_FILE)
        buildReaderHtml(unpackedDir, chapterPaths, readerHtml, metadata.title)

        return Book(
            id = bookId,
            title = metadata.title,
            author = metadata.author,
            unpackedDirPath = unpackedDir.absolutePath,
            readerHtmlPath = readerHtml.absolutePath,
            coverPath = coverPath
        )
    }

    private fun parseXmlDocument(file: File): Document {
        if (file.length() > AppConstants.MAX_XML_BYTES) {
            throw SecurityException(AppConstants.ERR_XML_TOO_LARGE)
        }
        val bytes = file.readBytes()
        val decodedXml = decodeXmlForSafetyCheck(bytes)
        if (decodedXml.indexOf(AppConstants.XML_DOCTYPE_MARKER, ignoreCase = true) >= 0 ||
            decodedXml.indexOf(AppConstants.XML_ENTITY_MARKER, ignoreCase = true) >= 0
        ) {
            throw SecurityException(AppConstants.ERR_UNSAFE_XML)
        }
        val factory = newSecureFactory()
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(ByteArrayInputStream(bytes))
        document.documentElement.normalize()
        return document
    }

    private fun decodeXmlForSafetyCheck(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFE && b1 == 0xFF) {
                return String(bytes, Charsets.UTF_16BE)
            }
            if (b0 == 0xFF && b1 == 0xFE) {
                return String(bytes, Charsets.UTF_16LE)
            }
            if (b0 == 0x00 && b1 != 0x00) {
                return String(bytes, Charsets.UTF_16BE)
            }
            if (b0 != 0x00 && b1 == 0x00) {
                return String(bytes, Charsets.UTF_16LE)
            }
        }
        val charset = detectXmlCharset(bytes) ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    private fun extractMetadata(document: Document): BookMetadata {
        var title = AppConstants.DEFAULT_TITLE
        var author = AppConstants.DEFAULT_AUTHOR
        var coverId: String? = null

        val metadataNodes = document.getElementsByTagName("metadata")
        if (metadataNodes.length == 0) {
            return BookMetadata(title, author, coverId)
        }
        val children = metadataNodes.item(0).childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            val name = element.localName ?: element.tagName
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
        if (metaName.equals("cover", true) && content.isNotEmpty()) {
            return content
        }
        if (property.equals("cover-image", true)) {
            val text = element.textContent.trim()
            return if (text.isNotEmpty()) text else content.ifEmpty { null }
        }
        return null
    }

    private fun extractManifest(document: Document): LinkedHashMap<String, ManifestItem> {
        val manifest = LinkedHashMap<String, ManifestItem>()
        val manifestNodes = document.getElementsByTagName("manifest")
        if (manifestNodes.length == 0) return manifest

        val items = (manifestNodes.item(0) as Element).getElementsByTagName("item")
        for (index in 0 until items.length) {
            val item = items.item(index) as Element
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            val mediaType = item.getAttribute("media-type")
            val properties = item.getAttribute("properties")
            manifest[id] = ManifestItem(id, href, mediaType, properties)
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
            val decoded = URLDecoder.decode(coverItem.href, "UTF-8")
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
        opfParentDir: String
    ): List<String> {
        val chapterPaths = mutableListOf<String>()
        val spineNodes = document.getElementsByTagName("spine")
        if (spineNodes.length > 0) {
            val itemRefs = (spineNodes.item(0) as Element).getElementsByTagName("itemref")
            for (index in 0 until itemRefs.length) {
                val itemRef = itemRefs.item(index) as Element
                val idRef = itemRef.getAttribute("idref")
                val manifestItem = manifest[idRef] ?: continue
                val decoded = URLDecoder.decode(manifestItem.href, "UTF-8")
                chapterPaths.add(resolveRelativePath(opfParentDir, decoded))
            }
        }
        if (chapterPaths.isNotEmpty()) return chapterPaths

        for (item in manifest.values) {
            if (item.mediaType.contains("html", true)) {
                val decoded = URLDecoder.decode(item.href, "UTF-8")
                chapterPaths.add(resolveRelativePath(opfParentDir, decoded))
            }
        }
        return chapterPaths
    }

    private fun generateScriptNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun buildReaderHtml(
        unpackedDir: File,
        chapterPaths: List<String>,
        outputFile: File,
        bookTitle: String
    ) {
        val html = StringBuilder(64 * 1024)
        val safeTitle = escapeHtml(bookTitle)
        val nonce = generateScriptNonce()
        val origin = AppConstants.VIRTUAL_ASSET_ORIGIN.trimEnd('/')
        val bridge = AppConstants.JS_BRIDGE_NAME
        val onScroll = AppConstants.JS_METHOD_ON_SCROLL
        val restoreFn = AppConstants.JS_FUNC_RESTORE
        val flushFn = AppConstants.JS_FUNC_FLUSH

        html.append("""<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8"/>
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src """).append(origin).append(""" data:; style-src 'unsafe-inline' """).append(origin).append("""; font-src """).append(origin).append(""" data:; script-src 'nonce-""").append(nonce).append("""'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none';"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes"/>
<title>""").append(safeTitle).append("""</title>
<style>
  html, body {
    background-color: #ffffff !important;
    background: #ffffff !important;
  }
  html {
    margin: 0;
    padding: 0;
  }
  body {
    margin: 0 auto;
    padding: 26px 22px 120px 22px;
    max-width: 720px;
    color: #18202c !important;
    font-family: "Georgia", "Noto Serif", "Times New Roman", serif;
    font-size: 19px;
    line-height: 1.78;
    letter-spacing: 0.01em;
    text-rendering: optimizeLegibility;
    -webkit-font-smoothing: antialiased;
    word-wrap: break-word;
    overflow-wrap: break-word;
  }
  .epub-chapter {
    background: #ffffff !important;
    background-color: #ffffff !important;
  }
  .chapter-divider {
    width: 44px;
    height: 3px;
    margin: 46px auto;
    border-radius: 3px;
    background: #e3ebf5;
  }
  p, div, span, li, td, th {
    color: #18202c !important;
    background-color: transparent !important;
  }
  p {
    margin: 0 0 1.15em 0;
  }
  h1, h2, h3, h4, h5, h6 {
    color: #10233f !important;
    line-height: 1.35;
    font-weight: 700;
    margin: 1.5em 0 0.7em 0;
  }
  h1 { font-size: 1.5em; }
  h2 { font-size: 1.32em; }
  h3 { font-size: 1.18em; }
  img, svg, image {
    max-width: 100% !important;
    height: auto !important;
    display: block;
    margin: 18px auto;
    background: transparent !important;
  }
  blockquote {
    margin: 1.4em 0;
    padding: 2px 0 2px 16px;
    border-left: 3px solid #93b4e6;
    color: #44536b !important;
    font-style: italic;
  }
  a { color: #2563eb !important; }
  hr {
    border: 0;
    height: 1px;
    background: #e6edf6;
    margin: 30px auto;
    width: 72%;
  }
  pre, code {
    font-family: "Roboto Mono", monospace;
    font-size: 0.88em;
  }
  pre {
    padding: 12px;
    overflow-x: auto;
    background: #f6f8fb !important;
    border-radius: 6px;
  }
</style>
</head>
<body>
""")

        val chapterIndexMap = HashMap<String, Int>(chapterPaths.size)
        for (idx in chapterPaths.indices) {
            chapterIndexMap[chapterPaths[idx]] = idx
        }
        appendChapters(html, unpackedDir, chapterPaths, chapterIndexMap)

        html.append("<script nonce=\"").append(nonce).append("\">\n")
        html.append("""(function () {
  var restoring = false;
  var pending = null;
  var targetTop = 0;
  var targetPercent = 0;

  function currentScroll() {
    return window.pageYOffset || document.documentElement.scrollTop || 0;
  }

  function maxScroll() {
    return Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
  }

  function progress() {
    var max = maxScroll();
    if (max <= 0) return 0;
    var value = Math.round((currentScroll() / max) * 100);
    return Math.min(100, Math.max(0, value));
  }

  function sendProgress() {
    var top = currentScroll();
    var pct = progress();
    targetTop = top;
    targetPercent = pct;
    var bridge = window.""").append(bridge).append(""";
    if (bridge && bridge.""").append(onScroll).append(""") {
      bridge.""").append(onScroll).append("""(top, pct);
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
    if (desired <= 0 && targetPercent > 0 && max > 0) {
      desired = (targetPercent / 100.0) * max;
    } else if (desired > max && targetPercent > 0 && max > 0) {
      desired = (targetPercent / 100.0) * max;
    }
    window.scrollTo(0, desired);
  }

  window.""").append(restoreFn).append(""" = function (top, pct) {
    restoring = true;
    targetTop = top || 0;
    targetPercent = pct || 0;
    applyTargetScroll();
    setTimeout(applyTargetScroll, 40);
    setTimeout(function () {
      applyTargetScroll();
      restoring = false;
      sendProgress();
    }, 180);
  };

  window.""").append(flushFn).append(""" = function () {
    if (pending) {
      clearTimeout(pending);
      pending = null;
    }
    sendProgress();
  };

  window.addEventListener("load", function () {
    if (targetTop > 0 || targetPercent > 0) {
      restoring = true;
      applyTargetScroll();
      setTimeout(function () {
        restoring = false;
        sendProgress();
      }, 60);
    }
  });

  window.addEventListener("resize", function () {
    if (targetPercent > 0) {
      restoring = true;
      targetTop = 0;
      applyTargetScroll();
      setTimeout(function () {
        restoring = false;
        sendProgress();
      }, 80);
    }
  });
})();
</script>
</body>
</html>
""")

        outputFile.writeText(html.toString(), Charsets.UTF_8)
    }

    private fun appendChapters(
        html: StringBuilder,
        unpackedDir: File,
        chapterPaths: List<String>,
        chapterIndexMap: Map<String, Int>
    ) {
        val canonicalRoot = unpackedDir.canonicalFile
        for (index in chapterPaths.indices) {
            val chapterPath = chapterPaths[index]
            val chapterFile = File(unpackedDir, chapterPath).canonicalFile
            if (!isInsideDirectory(chapterFile, canonicalRoot)) continue
            if (!chapterFile.exists() || !chapterFile.isFile) continue
            val baseDir = parentDirOf(chapterPath)
            val raw = readChapterText(chapterFile)
            val body = extractBodyContent(raw)
            val sanitized = sanitizeAndRewriteHtml(body, baseDir, chapterIndexMap)

            html.append("<section class='epub-chapter' id='chapter-")
                .append(index)
                .append("' data-chapter='")
                .append(index)
                .append("'>")
            html.append(sanitized)
            html.append("</section>")
            if (index < chapterPaths.size - 1) {
                html.append("<div class='chapter-divider'></div>")
            }
        }
    }

    private fun readChapterText(file: File): String {
        val bytes = try {
            file.readBytes()
        } catch (error: Exception) {
            return ""
        }
        if (bytes.isEmpty()) return ""
        val charset = detectXmlCharset(bytes) ?: Charsets.UTF_8
        return String(bytes, charset)
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
        while (end < head.length && isCharsetChar(head[end])) {
            end += 1
        }
        if (end <= start) return null
        return try {
            Charset.forName(head.substring(start, end))
        } catch (error: Exception) {
            null
        }
    }

    private fun isCharsetChar(ch: Char): Boolean {
        return (ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') || ch == '-' || ch == '_'
    }

    private fun extractBodyContent(html: String): String {
        val bodyStart = html.indexOf("<body", ignoreCase = true)
        if (bodyStart < 0) return html
        val tagEnd = html.indexOf('>', startIndex = bodyStart)
        val bodyEnd = html.indexOf("</body", startIndex = bodyStart, ignoreCase = true)
        if (tagEnd < 0 || bodyEnd < 0 || bodyEnd <= tagEnd) return html
        return html.substring(tagEnd + 1, bodyEnd)
    }

    private fun sanitizeAndRewriteHtml(
        html: String,
        baseDir: String,
        chapterIndexMap: Map<String, Int>
    ): String {
        val out = StringBuilder(html.length)
        var idx = 0
        while (idx < html.length) {
            val lt = html.indexOf('<', startIndex = idx)
            if (lt < 0) {
                appendEscapedText(out, html, idx, html.length)
                break
            }
            if (lt > idx) {
                appendEscapedText(out, html, idx, lt)
            }
            if (html.startsWith("<!--", startIndex = lt)) {
                val commentEnd = html.indexOf("-->", startIndex = lt + 4)
                idx = if (commentEnd < 0) html.length else commentEnd + 3
                continue
            }
            val gt = findTagEnd(html, lt + 1)
            if (gt < 0) {
                appendEscapedText(out, html, lt, html.length)
                break
            }
            val rawTag = html.substring(lt + 1, gt).trim()
            idx = handleParsedTag(html, rawTag, gt + 1, baseDir, chapterIndexMap, out)
        }
        return out.toString()
    }

    private fun findTagEnd(html: String, start: Int): Int {
        var i = start
        var quote = '\u0000'
        while (i < html.length) {
            val c = html[i]
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return i
            }
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
        out: StringBuilder
    ): Int {
        if (rawTag.isEmpty() || rawTag.startsWith("!") || rawTag.startsWith("?")) {
            return afterTagIdx
        }
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
        if (tagName !in ALLOWED_TAGS) {
            return afterTagIdx
        }
        if (isClosing) {
            if (tagName !in VOID_TAGS) {
                out.append("</").append(tagName).append('>')
            }
            return afterTagIdx
        }
        val attrSegment = if (nameEnd < 0) "" else cleanBody.substring(nameEnd + 1)
        out.append('<').append(tagName)
        appendSanitizedAttributes(attrSegment, baseDir, chapterIndexMap, out)
        if (selfClosing || tagName in VOID_TAGS) {
            out.append("/>")
        } else {
            out.append('>')
        }
        return afterTagIdx
    }

    private fun skipDroppedSubtree(html: String, tagName: String, fromIndex: Int): Int {
        val closeToken = "</$tagName"
        val closeIdx = html.indexOf(closeToken, startIndex = fromIndex, ignoreCase = true)
        if (closeIdx < 0) return html.length
        val closeGt = html.indexOf('>', startIndex = closeIdx + closeToken.length)
        return if (closeGt < 0) html.length else closeGt + 1
    }

    private fun appendSanitizedAttributes(
        attrSegment: String,
        baseDir: String,
        chapterIndexMap: Map<String, Int>,
        out: StringBuilder
    ) {
        var i = 0
        while (i < attrSegment.length) {
            while (i < attrSegment.length && attrSegment[i].isWhitespace()) i += 1
            if (i >= attrSegment.length) break
            val nameStart = i
            while (i < attrSegment.length && !attrSegment[i].isWhitespace() && attrSegment[i] != '=') {
                i += 1
            }
            val rawName = attrSegment.substring(nameStart, i).lowercase()
            val attrName = if (rawName == "xlink:href") "href" else rawName
            while (i < attrSegment.length && attrSegment[i].isWhitespace()) i += 1
            if (i >= attrSegment.length || attrSegment[i] != '=') {
                continue
            }
            i += 1
            while (i < attrSegment.length && attrSegment[i].isWhitespace()) i += 1
            if (i >= attrSegment.length) break
            val value: String
            val first = attrSegment[i]
            if (first == '"' || first == '\'') {
                val endQuote = attrSegment.indexOf(first, startIndex = i + 1)
                if (endQuote < 0) {
                    value = attrSegment.substring(i + 1)
                    i = attrSegment.length
                } else {
                    value = attrSegment.substring(i + 1, endQuote)
                    i = endQuote + 1
                }
            } else {
                val valStart = i
                while (i < attrSegment.length && !attrSegment[i].isWhitespace()) i += 1
                value = attrSegment.substring(valStart, i)
            }
            emitAllowedAttribute(attrName, value.trim(), baseDir, chapterIndexMap, out)
        }
    }

    private fun emitAllowedAttribute(
        attrName: String,
        rawValue: String,
        baseDir: String,
        chapterIndexMap: Map<String, Int>,
        out: StringBuilder
    ) {
        if (attrName !in ALLOWED_ATTRS) return
        val finalValue = if (attrName == "src" || attrName == "href") {
            rewriteSingleUrl(rawValue, baseDir, attrName, chapterIndexMap)
        } else {
            rawValue
        }
        out.append(' ').append(attrName).append("=\"").append(escapeHtml(finalValue)).append('"')
    }

    private fun appendEscapedText(out: StringBuilder, text: String, start: Int, end: Int) {
        for (idx in start until end) {
            val ch = text[idx]
            when (ch) {
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                else -> out.append(ch)
            }
        }
    }

    private fun rewriteSingleUrl(
        url: String,
        baseDir: String,
        attrName: String,
        chapterIndexMap: Map<String, Int>
    ): String {
        if (url.isEmpty()) return url
        val schemeResult = classifySchemeUrl(url)
        if (schemeResult != null) return schemeResult

        val resolved = resolveRelativePath(baseDir, url)
        if (resolved.isEmpty()) return AppConstants.HASH_ANCHOR
        if (attrName == "href") {
            val lowerExt = resolved.substringAfterLast('.', "").lowercase()
            if (lowerExt in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")) {
                return AppConstants.VIRTUAL_ASSET_ORIGIN + resolved.trimStart('/')
            }
            return resolveInternalHref(url, resolved, chapterIndexMap)
        }
        return AppConstants.VIRTUAL_ASSET_ORIGIN + resolved.trimStart('/')
    }

    private fun classifySchemeUrl(url: String): String? {
        val lower = url.lowercase()
        if (lower.startsWith(AppConstants.SCHEME_JAVASCRIPT) ||
            lower.startsWith(AppConstants.SCHEME_FILE)
        ) {
            return AppConstants.HASH_ANCHOR
        }
        if (lower.startsWith(AppConstants.SCHEME_DATA)) {
            return if (lower.startsWith(AppConstants.SCHEME_DATA_IMAGE)) url else AppConstants.HASH_ANCHOR
        }
        if (lower.startsWith(AppConstants.SCHEME_HTTP) ||
            lower.startsWith(AppConstants.SCHEME_HTTPS) ||
            lower.startsWith(AppConstants.SCHEME_MAILTO) ||
            lower.startsWith(AppConstants.SCHEME_TEL) ||
            url.startsWith(AppConstants.HASH_ANCHOR)
        ) {
            return url
        }
        return null
    }

    private fun resolveInternalHref(
        originalUrl: String,
        resolvedPath: String,
        chapterIndexMap: Map<String, Int>
    ): String {
        val targetChapter = chapterIndexMap[resolvedPath] ?: return AppConstants.HASH_ANCHOR
        val fragmentIdx = originalUrl.indexOf('#')
        return if (fragmentIdx >= 0 && fragmentIdx + 1 < originalUrl.length) {
            originalUrl.substring(fragmentIdx)
        } else {
            "#chapter-$targetChapter"
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
                val rootFiles = document.getElementsByTagName("rootfile")
                if (rootFiles.length > 0) {
                    val candidate = (rootFiles.item(0) as Element).getAttribute("full-path")
                    if (candidate.isNotEmpty()) {
                        val file = File(unpackedDir, candidate).canonicalFile
                        if (isInsideDirectory(file, canonicalRoot) && file.exists() && file.isFile) {
                            return file
                        }
                    }
                }
            } catch (error: Exception) {
                // Fall back to directory walk below
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
        try {
            factory.isXIncludeAware = false
        } catch (error: Exception) {
            // Ignore unsupported parser toggle
        }
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (error: Exception) {
            // Ignore unsupported feature on Android XML parser
        }
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        } catch (error: Exception) {
            // Ignore unsupported feature on Android XML parser
        }
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
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
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
