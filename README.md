# Books

A calm, fast e-book reader for Android, for PDF and EPUB files you already have. No accounts, no store, no sync: your books live on your phone.

Built with Kotlin and Jetpack Compose. EPUB rendering by the [Readium Kotlin toolkit](https://github.com/readium/kotlin-toolkit).

## Features

- **Library**: a grid of covers, newest-read first, with a "Continue reading" card. Covers are taken from the EPUB or from page 1 of the PDF; books without one get a generated cover.
- **Import**: tap **Add books** to pick several PDFs and EPUBs, or use *Open with → Books* / *Share → Books* from Files, Drive or Gmail. Files are copied into the app, so deleting the original doesn't remove the book.
- **PDF reader**: swipe or tap the left/right edges to turn pages. Pinch to zoom; pages are re-rendered sharper as you zoom in.
- **EPUB reader**: reflowable text with adjustable size, a table of contents, and the same tap and swipe navigation.
- **Light, Sepia and Dark** reading themes. In Dark mode PDF pages are inverted to white text on dark grey.
- **Bookmarks** and a page slider in both readers. Each book reopens where you left off.
- Long-press a book to remove it.

Tap the middle of a page to show or hide the controls.

## Install on your phone

**From GitHub Actions:** every push to `main` builds an APK. Open the latest run under *Actions → Build APK*, download the `Books-apk` artifact, unzip it and open the `.apk` on your phone (allow installs from your browser or Files app when asked).

**From your computer:** turn on *Developer options → USB debugging* on the phone, plug it in, then run:

```bash
./gradlew installRelease
```

The release build is signed with the local debug key so it installs without extra setup. APKs built on different machines (your PC vs. GitHub Actions) have different keys, so Android will ask you to uninstall one before installing the other. Uninstalling also deletes your imported books.

## Build requirements

- JDK 17 or newer
- Android SDK with platform 37 (Android Studio installs this automatically)

```bash
./gradlew assembleDebug
```

## Project layout

```
app/src/main/java/io/github/dishan18/books/
├── MainActivity.kt          Library host, handles "Open with" / "Share"
├── BooksApp.kt              App-wide singletons
├── data/                    Room database, import pipeline, reader settings, Readium setup
├── library/                 Library screen and its ViewModel
├── reader/                  PDF reader (Compose + PdfRenderer), EPUB reader (Readium), shared chrome
└── ui/                      Theme and cover rendering
```

## License

Books is released under the [MIT License](LICENSE). The Readium toolkit it uses is under the BSD 3-Clause license.
