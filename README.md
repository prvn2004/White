# White

A fast, minimal EPUB reader for Android with a clean white-and-blue theme.

## Features

- **Bookshelf**: 3-column shelf with extracted cover art, generated blue covers when a book has no cover image, and reading progress badges. Long-press any book to remove it.
- **One-tap import**: Tap the blue **+** button in the bottom-right corner to import any `.epub` file into local app storage.
- **Pure-page continuous reader**: Opens the entire book as a single infinitely scrolling white page with zero buttons, headers, or system status/navigation bars visible.
- **Left-edge brightness control**: Slide vertically along the left edge of the screen while reading to adjust screen brightness smoothly; your brightness level is remembered across sessions.
- **Auto-resume**: Remembers both the last open book and your exact scroll position so reopening the app takes you straight back to the page you were reading.

## Download & Install

Prebuilt, signed installable packages are built automatically by GitHub Actions on every push to `main` and on version tags:

- **Releases page**: Download `white-v1.0.0.apk` (direct install on any Android 7.0+ phone) or `white-v1.0.0.aab` (Android App Bundle).
- **Actions tab**: Every `main` and tag workflow run publishes `white-apk` and `white-aab` artifacts.

## Offline Setup & Build

### Prerequisites

- **JDK 21** (configured in `gradle/gradle-daemon-jvm.properties`)
- **Android SDK Platform 36** (minimum supported device is Android 7.0 / API 24)

### 1. Configure Android SDK path

Create `local.properties` in the project root (ignored by Git):

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

### 2. Build Debug APK (No Signing Setup Required)

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (installable directly via Android Studio or `adb install`).

### 3. Build Signed Release APK & AAB

Release builds use four environment variables for signing (`SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`):

```bash
export SIGNING_STORE_FILE=/path/to/release.p12
export SIGNING_STORE_PASSWORD=your_store_password
export SIGNING_KEY_ALIAS=your_key_alias
export SIGNING_KEY_PASSWORD=your_key_password
./gradlew assembleRelease bundleRelease
```

Outputs:
- Signed Release APK: `app/build/outputs/apk/release/app-release.apk`
- Signed Release AAB: `app/build/outputs/bundle/release/app-release.aab`

### 4. Building Completely Offline

After running one Gradle build to populate your local Gradle cache (`~/.gradle`), you can build at any time without an internet connection:

```bash
./gradlew assembleDebug --offline
```

## Project Structure

```text
app/src/main/java/open/source/white/
  MainActivity.kt                           Bookshelf screen, EPUB picker, auto-resume
  common/AppConstants.kt                    Centralized storage, bridge, and gesture constants
  data/BookRepository.kt                    Synced atomic JSON persistence and backup recovery
  model/Book.kt                             Immutable book metadata and reading progress model
  parser/EpubParser.kt                      Safe Zip extraction, OPF parser, allowlist HTML builder
  ui/bookshelf/BookshelfAdapter.kt          RecyclerView adapter for book covers and progress pills
  ui/bookshelf/CoverLoader.kt               Downsampled RGB_565 LruCache cover bitmap loader
  ui/bookshelf/ShelfDecoration.kt           Custom Canvas shelf board decoration
  ui/reader/LeftEdgeBrightnessController.kt Left-edge vertical drag screen brightness controller
  ui/reader/ReaderActivity.kt               Full-screen immersive WebView reader and asset server
```

