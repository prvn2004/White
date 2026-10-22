# White

A calm, offline reader for Android. White page, blue accents, and a reading surface that gets out of the way.

## Features

- **Bookshelf**: 3-column shelf with cover art, generated covers, progress badges, and reading-time totals. Long-press to remove a book.
- **Formats**: EPUB, PDF, Markdown, HTML, XML/FB2, and plain text.
- **Pure-page reader**: one continuous, infinitely scrolling white page with no visible chrome. Tap once to reveal a slim toolbar; it fades on its own.
- **Highlights**: select text, pick a color, and highlight. Every highlight appears in the side panel; tap one to jump to it.
- **Display**: adjustable text size (14–30 px), remembered across books.
- **Reading stats bar**: a slim bottom bar shows time spent, current page / total pages, and progress percent.
  - Tap the time to see total and per-day reading logs.
  - Tap the page count for the table of contents.
  - Tap the percentage to open the highlights panel.
- **Left-edge brightness**: slide vertically along the left edge to adjust screen brightness; the level is remembered.
- **Auto-resume**: reopening the app returns to the last book at the saved position.
- **About and stats**: an overflow menu on the shelf with credits and library-wide reading time.

The app has no accounts and no trackers. The INTERNET permission is used only so WebView hands local asset requests to the app interceptor; every request is served from local storage, and the page CSP blocks all other origins.

## Download & Install

Signed packages are published by GitHub Actions on every push to main and on version tags:

- **Releases page**: download white-v1.1.0.apk (install on Android 7.0+) or the .aab bundle.
- **Actions tab**: every release run publishes white-apk and white-aab artifacts.

## Offline Setup & Build

Requirements: JDK 21 and Android SDK Platform 36.

1. Create local.properties in the project root:
   sdk.dir=/absolute/path/to/Android/Sdk
2. Build a debug APK:
   ./gradlew assembleDebug
3. Build signed release outputs (requires the four SIGNING_* environment variables):
   ./gradlew assembleRelease bundleRelease

Outputs live under app/build/outputs/apk and app/build/outputs/bundle.

After one online build, offline builds work with:

    ./gradlew assembleDebug --offline

## Project Layout

    app/src/main/java/open/source/white/
      MainActivity.kt                         Bookshelf, import, About, library stats
      common/AppConstants.kt                  Storage, bridge, format, and gesture constants
      data/BookRepository.kt                  Atomic library persistence and reading time
      data/HighlightsRepository.kt            Per-book highlight storage
      model/Book.kt                           Book metadata, progress, reading history
      model/Highlight.kt                      Highlight anchor, color, and text
      parser/EpubParser.kt                    EPUB/PDF/Markdown/HTML/XML/text import, sanitizer, reader builder
      ui/bookshelf/                           Adapter, cover loader, shelf decoration
      ui/reader/ReaderActivity.kt             Reader, stats bar, highlights, fonts, contents
      ui/reader/LeftEdgeBrightnessController.kt

## Tests

Reader and parser changes are verified on an emulator against a Calibre EPUB, a PDF, Markdown, and plain text fixtures, including import, rendering, scrolling, highlighting, jumping, font changes, and resume. CI runs lint and builds signed APK/AAB artifacts on every release.

Built by Praveen Kumar together with Codex (TARS).

