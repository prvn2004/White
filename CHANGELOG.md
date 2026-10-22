# Changelog

All notable changes to White are documented in this file.
This project adheres to [Semantic Versioning](https://semver.org/).

## [1.4.1] - 2026-10-23

### Added
- **Enhanced Liquid Glass Styling**: Refracted chromatic gradient overlays, crisp specular outlines, and soft light dispersion across glass cards, dialogs, and isolated controls.
- **Substantial 3D Book Thickness**: Added dynamic cylindrical spine gradients and doubled 3D book spine thickness (up to 18dp) scaled according to book size for physical depth on shelves.

## [1.4.0] - 2026-10-23

### Added
- **Apple-style Reflecting Glass UI Theme**: Implemented translucent glass aesthetics across the entire application with specular gradients, frosted surfaces, and refined refractive borders.
- **Glass Bookshelf**: Floating frosted glass shelves with specular top reflection lines, gradient refractive depth, and ambient shadows.
- **Substantial Book Width**: Doubled book cover width and enhanced 3D book spine depth with layered translucent glass reflections.
- **Circular Glass Back Button**: Separated reader back navigation into an isolated circular glass button on the top-left.
- **Glass Action Card**: Highlights and text size controls unified in a translucent glass pill on the top-right.
- **Glass Dialogs & Panels**: All popups, highlight editing dialogs, text size controls, and the highlight side drawer now feature frosted glass styling.

### Removed
- Removed the side scrollbar per user request, restoring a completely clean and distraction-free reading canvas.

## [1.3.1] - 2026-10-22

### Changed
- Reverted wood background and black theme per user request; the app retains its clean, pure white reading and library aesthetic.
- Refined fast page scroller to span full screen height with dynamic bell curve animation on active position and drag.
- Corrected text offset calculation during highlight replacement so color switches and additions apply instantly and cleanly without drift.

## [1.3.0] - 2026-10-22

### Added
- **Highlight Interaction & Management**: Tapping any highlighted text directly opens a dialog allowing immediate color change or deletion via a trash bin action.
- **Immediate In-Page Highlighting**: Marking a selection now updates highlights live in the page DOM without requiring closing and reopening the book.
- **5-Row Wooden Bookshelf with 3D Book Styling**: The library now features a warm wooden background, exactly 5 rows of shelves, clean cover-only book presentations without title text clutter, and dynamic 3D spine and page edge depth calculated from book volume.
- **Right-Edge Fast Page Scroller**: Added an intuitive dashed vertical fast-scroller on the right side of the reader that displays current page progress and allows dragging to jump instantly to any page.
- **Jump to Page Dialog**: Contents menu now includes a "Jump to page…" action to leap directly to any arbitrary page number.
- **Black Theme**: Full true-black reading theme option for comfortable night-time reading while keeping the warm wooden bookshelf at home.
- **Smart Reading Time Tracking**: Idle detection pauses reading session accumulation if inactive for over 5 minutes.

## [1.2.0] - 2026-10-22

### Fixed
- **Highlight in selection menu**: Added a custom HighlightWebView that injects a dedicated "Highlight" action into Android's native text-selection action mode alongside Copy and Share.
- **Runtime script injection**: Injected the reader bridge script at runtime via evaluateJavascript instead of inline HTML script tags, preventing CSP blocking and fixing page counting, progress tracking, dynamic font size adjustments, and contents jumping.
- **Accurate pagination**: Page calculation now considers scrollable span relative to viewport height across all document sizes instead of reporting p. 1 / 1.
- **Position preservation**: The reader script anchors and restores scroll position and percentage across book closes, app reopens, and cold launches.
- **Table of contents jump**: Chapter jump looks up both ID and chapter index selectors, jumping directly to the requested chapter heading.
- **Font size scaling**: Dynamically adjusts document font size via CSS variables while maintaining viewport anchor position.
- **Continuous bookshelf layout**: Empty bookshelf and partial rows now show full wooden shelf lines across the entire view, maintaining the physical bookshelf aesthetic regardless of book count.

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

