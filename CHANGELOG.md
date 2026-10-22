# Changelog

All notable changes to White are documented in this file.
This project adheres to [Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-10-22

### Added
- Text highlights in four colors, with a per-book side panel. Tapping a highlight jumps to and flashes the marked passage.
- Adjustable reading text size, stored across sessions.
- Slim reading stats bar: time spent, page / total pages, and progress percent, each tappable. Time opens per-day reading logs, page opens the table of contents, and progress opens highlights.
- Reading-time tracking per book and per day, with library-wide totals.
- PDF, Markdown, HTML, XML/FB2, and plain-text import alongside EPUB.
- About and Reading stats dialogs in the shelf overflow menu, with credits.
- New adaptive launcher icon: a white page on blue.

### Fixed
- CSP nonce mismatch between the document head and inline script, which disabled progress reporting, font sizing, and highlights.
- Cross-chapter and same-page links now resolve to prefixed anchors and navigate inside the continuous document.
- Chapter titles prefer real headings instead of repeating the book title.
- Highlight anchoring uses direct text offsets, so marks apply reliably.

## [1.0.1] - 2026-10-22

### Fixed
- Books imported from Calibre and other OPF variants opened as a blank white page. The OPF directory was resolved from a canonical path while the unpacked root was lexical (data/user/0 vs data/data), so every relative chapter and cover path pointed at a nonexistent ../../../data/... location.
- Reader pages now load through loadDataWithBaseURL on the local asset origin, so the page renders even when the WebView cannot navigate the virtual https origin directly.
- SVG image elements are converted to plain img elements, and the sanitizer keeps safe inline SVG and MathML content.
- Library JSON is written atomically via temp-file sync plus rename, with a real .bak backup and per-record fault tolerance.
- Added the INTERNET permission so WebView hands local asset requests to the app interceptor; all such requests are served from local storage and CSP blocks everything else.

### Changed
- Existing books are re-parsed automatically on open when their cached reader page was produced by an older parser.

## [1.0.0] - 2026-10-22

### Added
- White-and-blue 3-column bookshelf with extracted cover artwork, fallback blue gradient covers, and reading progress badges.
- Floating **+** button to import EPUB archives into private app storage with Zip-Slip and XXE protection.
- Continuous, infinitely scrolling pure-white EPUB reader with local asset interception (`https://epub.local/`).
- Full-screen immersive mode while reading with system status bar, navigation bar, and on-screen buttons hidden.
- Left-edge vertical swipe gesture while reading to smoothly adjust and persist screen brightness.
- Automatic session restoration that reopens the last active book at the exact pixel scroll offset on app launch.
- GitHub Actions CI/CD workflow building and publishing downloadable `.apk` and `.aab` artifacts.

