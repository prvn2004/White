package open.source.white.ui.reader

import open.source.white.common.AppConstants
import org.json.JSONObject

object ReaderScript {

    fun build(
        scroll: Float,
        progress: Int,
        fontSize: Int,
        highlightsJson: String,
        isDark: Boolean = false
    ): String {
        return TEMPLATE
            .replace("__BRIDGE__", AppConstants.JS_BRIDGE_NAME)
            .replace("__ONSCROLL__", AppConstants.JS_METHOD_ON_SCROLL)
            .replace("__ONSELECT__", AppConstants.JS_METHOD_ON_SELECTION)
            .replace("__ONCLEAR__", AppConstants.JS_METHOD_ON_SELECTION_CLEARED)
            .replace("__ONHLCLICK__", AppConstants.JS_METHOD_ON_HIGHLIGHT_CLICKED)
            .replace("__SCROLL__", scroll.toString())
            .replace("__PROGRESS__", progress.toString())
            .replace("__FONT__", fontSize.toString())
            .replace("__HIGHLIGHTS__", JSONObject.quote(highlightsJson))
            .replace("__DARK__", isDark.toString())
    }

    private val TEMPLATE = """
(function () {
  if (window.__whiteReady) {
    window.White.theme(__DARK__);
    window.White.fontSize(__FONT__);
    window.White.applyHighlights(__HIGHLIGHTS__);
    window.restorePosition(__SCROLL__, __PROGRESS__);
    return;
  }
  window.__whiteReady = true;

  var restoring = false;
  var pending = null;
  var targetTop = __SCROLL__;
  var targetPercent = __PROGRESS__;
  var selectionTimer = null;

  function currentScroll() {
    return window.pageYOffset || document.documentElement.scrollTop || 0;
  }

  function maxScroll() {
    return Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
  }

  function progressPercent() {
    var max = maxScroll();
    if (max <= 0) return 0;
    return Math.min(100, Math.max(0, Math.round((currentScroll() / max) * 100)));
  }

  function pageInfo() {
    var height = Math.max(1, window.innerHeight);
    var max = maxScroll();
    var pages = Math.max(1, Math.ceil((max + height) / height));
    var page = max <= 0 ? 1 : Math.round((currentScroll() / max) * (pages - 1)) + 1;
    page = Math.min(pages, Math.max(1, page));
    return [page, pages];
  }

  function sendProgress() {
    var top = currentScroll();
    var pct = progressPercent();
    targetTop = top;
    targetPercent = pct;
    var info = pageInfo();
    var bridge = window.__BRIDGE__;
    if (bridge && bridge.__ONSCROLL__) {
      bridge.__ONSCROLL__(top, pct, info[0], info[1]);
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

  window.restorePosition = function (top, pct) {
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

  window.flushReaderProgress = function () {
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

  function chapterOf(node) {
    while (node && node !== document.body) {
      if (node.classList && node.classList.contains("epub-chapter")) return node;
      node = node.parentNode;
    }
    return null;
  }

  function offsetIn(root, node, offset) {
    try {
      var range = document.createRange();
      range.selectNodeContents(root);
      range.setEnd(node, offset);
      return range.toString().length;
    } catch (error) {
      return -1;
    }
  }

  function selectionInfo() {
    var selection = window.getSelection();
    if (!selection || selection.isCollapsed || !selection.rangeCount) return null;
    var range = selection.getRangeAt(0);
    var chapter = chapterOf(range.startContainer);
    if (!chapter) return null;
    var index = parseInt(chapter.getAttribute("data-chapter") || "0", 10);
    var start = offsetIn(chapter, range.startContainer, range.startOffset);
    var end = offsetIn(chapter, range.endContainer, range.endOffset);
    if (start < 0 || end <= start) return null;
    var text = selection.toString().trim();
    if (!text.length) return null;
    return {
      c: index,
      s: start,
      e: end,
      text: text.substring(0, 2000)
    };
  }

  function reportSelection() {
    var info = selectionInfo();
    if (!info) return;
    var bridge = window.__BRIDGE__;
    if (bridge && bridge.__ONSELECT__) {
      bridge.__ONSELECT__(info.c, info.s, info.e, info.text);
    }
  }

  document.addEventListener("selectionchange", function () {
    if (selectionTimer) clearTimeout(selectionTimer);
    selectionTimer = setTimeout(function () {
      var info = selectionInfo();
      if (info) {
        reportSelection();
      } else {
        var bridge = window.__BRIDGE__;
        if (bridge && bridge.__ONCLEAR__) {
          bridge.__ONCLEAR__();
        }
      }
    }, 220);
  });

  window.White = window.White || {};

  window.White.theme = function (dark) {
    var root = document.documentElement;
    if (dark) {
      root.style.setProperty("--wh-bg", "#000000");
      root.style.setProperty("--wh-text", "#e5e7eb");
      root.style.setProperty("--wh-heading", "#f3f4f6");
      root.style.setProperty("--wh-divider", "#27272a");
      root.style.setProperty("--wh-quote", "#9ca3af");
      root.style.setProperty("--wh-link", "#60a5fa");
      root.style.setProperty("--wh-pre-bg", "#18181b");
    } else {
      root.style.setProperty("--wh-bg", "#ffffff");
      root.style.setProperty("--wh-text", "#18202c");
      root.style.setProperty("--wh-heading", "#10233f");
      root.style.setProperty("--wh-divider", "#e3ebf5");
      root.style.setProperty("--wh-quote", "#44536b");
      root.style.setProperty("--wh-link", "#2563eb");
      root.style.setProperty("--wh-pre-bg", "#f6f8fb");
    }
  };

  window.White.getSelection = function () {
    return selectionInfo();
  };

  function anchorAtViewportTop() {
    if (!document.caretRangeFromPoint) return null;
    var range = document.caretRangeFromPoint(Math.floor(window.innerWidth / 2), 2);
    if (!range || !range.startContainer) return null;
    var chapter = chapterOf(range.startContainer);
    if (!chapter) return null;
    var offset = offsetIn(chapter, range.startContainer, range.startOffset);
    if (offset < 0) return null;
    var rect = range.getBoundingClientRect();
    if (!rect || (!rect.height && !rect.width)) return null;
    return {
      c: parseInt(chapter.getAttribute("data-chapter") || "0", 10),
      o: offset,
      y: rect.top
    };
  }

  function rectForOffset(chapter, offset) {
    var walker = document.createTreeWalker(chapter, NodeFilter.SHOW_TEXT, null);
    var total = 0;
    var node;
    while ((node = walker.nextNode())) {
      var length = node.nodeValue ? node.nodeValue.length : 0;
      if (length === 0) continue;
      if (offset <= total + length) {
        var local = Math.max(0, Math.min(length, offset - total));
        var range = document.createRange();
        range.setStart(node, local);
        range.setEnd(node, Math.min(length, local + 1));
        var rect = range.getBoundingClientRect();
        if (rect && (rect.height > 0 || rect.width > 0)) return rect;
        range.selectNodeContents(node);
        rect = range.getBoundingClientRect();
        if (rect) return rect;
      }
      total += length;
    }
    return null;
  }

  window.White.fontSize = function (px, keepPosition) {
    var anchor = keepPosition ? anchorAtViewportTop() : null;
    document.documentElement.style.setProperty("--wh-font-size", px + "px");
    void document.body.offsetHeight;
    if (anchor) {
      var chapter = document.getElementById("chapter-" + anchor.c) ||
        document.querySelector(".epub-chapter[data-chapter='" + anchor.c + "']");
      var rect = chapter ? rectForOffset(chapter, anchor.o) : null;
      if (rect) window.scrollBy(0, rect.top - anchor.y);
    }
    if (keepPosition) setTimeout(sendProgress, 60);
  };

  window.White.clearSelection = function () {
    var selection = window.getSelection();
    if (selection) selection.removeAllRanges();
  };

  window.White.jumpToChapter = function (index) {
    var element = document.getElementById("chapter-" + index) ||
      document.querySelector(".epub-chapter[data-chapter='" + index + "']");
    if (!element) return false;
    element.scrollIntoView({ block: "start" });
    return true;
  };

  window.White.scrollToPage = function (page, total) {
    var max = maxScroll();
    if (max <= 0 || total <= 1) return;
    var targetRatio = Math.max(0, Math.min(1, (page - 1) / (total - 1)));
    var desired = targetRatio * max;
    window.scrollTo(0, desired);
    sendProgress();
  };

  window.White.jumpToHighlight = function (id) {
    var element = document.querySelector("mark[data-hl=\"" + id + "\"]");
    if (!element) return false;
    element.scrollIntoView({ block: "center" });
    element.classList.add("wh-flash");
    setTimeout(function () { element.classList.remove("wh-flash"); }, 1400);
    return true;
  };

  window.White.applyHighlights = function (json) {
    var marks = document.querySelectorAll("mark.wh-hl");
    for (var i = 0; i < marks.length; i++) {
      var current = marks[i];
      var textNode = document.createTextNode(current.textContent);
      if (current.parentNode) {
        current.parentNode.replaceChild(textNode, current);
        current.parentNode.normalize();
      }
    }
    var list = [];
    try {
      list = JSON.parse(json || "[]");
    } catch (error) {
      return;
    }
    var byChapter = {};
    list.forEach(function (item) {
      (byChapter[item.c] = byChapter[item.c] || []).push(item);
    });
    Object.keys(byChapter).forEach(function (key) {
      var chapter = document.getElementById("chapter-" + key);
      if (!chapter) return;
      var items = byChapter[key].slice().sort(function (a, b) { return b.s - a.s; });
      items.forEach(function (item) {
        try {
          var walker = document.createTreeWalker(chapter, NodeFilter.SHOW_TEXT, null);
          var nodes = [];
          var total = 0;
          var node;
          while ((node = walker.nextNode())) {
            nodes.push({ node: node, start: total, end: total + node.nodeValue.length });
            total += node.nodeValue.length;
          }
          for (var k = nodes.length - 1; k >= 0; k--) {
            var info = nodes[k];
            if (info.end <= item.s || info.start >= item.e) continue;
            var localStart = Math.max(0, item.s - info.start);
            var localEnd = Math.min(info.node.nodeValue.length, item.e - info.start);
            if (localEnd <= localStart) continue;
            var range = document.createRange();
            range.setStart(info.node, localStart);
            range.setEnd(info.node, localEnd);
            var mark = document.createElement("mark");
            mark.className = "wh-hl";
            mark.setAttribute("data-hl", item.id);
            mark.style.backgroundColor = item.color;
            mark.style.color = "inherit";
            range.surroundContents(mark);
          }
        } catch (error) {
          // A malformed range should not block the remaining highlights.
        }
      });
    });
  };

  document.addEventListener("click", function (e) {
    var target = e.target;
    while (target && target !== document.body) {
      if (target.tagName && target.tagName.toLowerCase() === "mark" && target.classList.contains("wh-hl")) {
        var id = target.getAttribute("data-hl");
        if (id) {
          var bridge = window.__BRIDGE__;
          if (bridge && bridge.__ONHLCLICK__) {
            bridge.__ONHLCLICK__(id);
            e.preventDefault();
            e.stopPropagation();
            return;
          }
        }
      }
      target = target.parentNode;
    }
  }, true);

  window.White.theme(__DARK__);
  window.White.fontSize(__FONT__);
  window.White.applyHighlights(__HIGHLIGHTS__);
  window.restorePosition(__SCROLL__, __PROGRESS__);
})();
"""
}

