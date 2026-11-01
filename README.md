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

**From GitHub Releases (easiest):** on your phone, open the [latest release](https://github.com/Dishan18/Books/releases/latest) and tap `Books-vX.Y.Z.apk`. Open the download and tap **Install** (allow installs from your browser when asked). No GitHub account needed.

To update, install the newer APK the same way; it replaces the old version and keeps your books.

**From your computer:** turn on *Developer options → USB debugging* on the phone, plug it in, then run:

```bash
./gradlew installRelease
```

## Signing

Every build is signed with one key, so APKs from your PC and from GitHub install over each other. The key never goes into git:

- **Locally** it lives in `app/signing/` (`books.jks` plus `keystore.properties` with its password), which is git-ignored. Back this folder up somewhere safe: if you lose it, future builds can't update the installed app.
- **On GitHub** it comes from two repository secrets (*Settings → Secrets and variables → Actions*):
  - `BOOKS_KEYSTORE_BASE64`: the contents of `app/signing/books.jks.base64.txt`
  - `BOOKS_KEYSTORE_PASSWORD`: the `storePassword` value from `app/signing/keystore.properties`

Without a key (for example in a fork) builds fall back to the debug key.

## Publishing a release

Tag a commit and push the tag; GitHub Actions builds the APK and attaches it to a new release:

```bash
git tag v1.1.0
git push origin v1.1.0
```

The version number comes from the tag, so each new tag must be higher than the last.

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
