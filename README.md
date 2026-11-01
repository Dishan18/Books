# Books

A calm, fast reader for Android with two tabs: **Books** for the PDF and EPUB files you already have, and **Manga** for manga and webtoons from official sources, your own server, or your own files. No ads, no accounts, no tracking.

Built with Kotlin and Jetpack Compose. EPUB rendering by the [Readium Kotlin toolkit](https://github.com/readium/kotlin-toolkit).

## Books tab

- **Library**: a grid of covers, newest-read first, with a "Continue reading" card. Covers are taken from the EPUB or from page 1 of the PDF; books without one get a generated cover.
- **Import**: tap **Add books** to pick several PDFs and EPUBs, or use *Open with → Books* / *Share → Books* from Files, Drive or Gmail. Files are copied into the app, so deleting the original doesn't remove the book.
- **PDF reader**: swipe or tap the left/right edges to turn pages. Pinch to zoom; pages are re-rendered sharper as you zoom in.
- **EPUB reader**: reflowable text with adjustable size, a table of contents, and the same tap and swipe navigation.
- **Light, Sepia, Pink and Dark** reading themes. In Dark mode PDF pages are inverted to white text on dark grey.
- **Page turning** side to side or up and down (EPUBs scroll continuously when set to up and down).
- **Bookmarks** and a page slider in both readers. Each book reopens where you left off.
- Long-press a book to remove it.

Tap the middle of a page to show or hide the controls.

## Manga tab

- **Browse** sources, or search all of them at once. Each source loads on its own, so one being slow or down never blocks the others.
- **Sources**
  - **MangaDex**, through its official public API. No account needed. Choose chapter languages, content ratings (18+ is off by default) and a data-saver mode.
  - **Komga**, a free server you run at home for your own collection. Optional password or API key, stored encrypted on the phone.
  - **On this device**: import CBZ/ZIP files (each file becomes a chapter) or a folder of images from the Library menu.
- **Library**: favorites with unread counts, a "Continue reading" card, pull to check for new chapters, and a reading **History**.
- **Downloads** run in the background and survive the app being closed: progress notification, pause/resume, automatic retries, 1–3 chapters at a time, optional Wi‑Fi only. Downloaded chapters read offline.
- **Webtoon reader**: continuous vertical scrolling. Extremely tall images are drawn a screen at a time, so they stay smooth and never blank out. Upcoming pages load ahead of time, your exact spot is saved, and reaching the end of a chapter flows straight into the next. Tap the top or bottom of the screen to scroll, the middle for controls; pinch to zoom; volume keys scroll.

### Privacy and fair use

- No ads, no analytics, no accounts. The app talks only to the sources you use.
- Sources use documented APIs or servers you control. There's no scraping, no paywall or login bypassing, and no anti-bot workarounds. Third-party code ("extensions") is never downloaded or run.
- MangaDex's API guidelines are followed: requests are rate-limited, scanlation groups are credited on every chapter, and images fetched from the MangaDex@Home volunteer network are reported back to MangaDex's health endpoint (image URL, size, timing and cache status only, never anything about you).

### Battery

- The reader keeps the screen on only while you're using it, and lets it sleep after 10 idle minutes.
- With Battery Saver on, fewer pages are loaded ahead.
- Background work goes through WorkManager with network constraints, so there are no wake locks or polling. Automatic library checks also wait until the battery isn't low.

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
git tag v2.0.0
git push origin v2.0.0
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
├── MainActivity.kt / AppRoot.kt   Books | Manga tabs, "Open with" / "Share"
├── BooksApp.kt              App-wide singletons and image loader
├── data/                    Room database (+ migrations), book import, reader settings, Readium setup
├── library/                 Books library screen
├── reader/                  PDF reader (Compose + PdfRenderer), EPUB reader (Readium), shared chrome
├── manga/
│   ├── data/                Manga entities, DAOs, repository
│   ├── source/              Source interfaces, MangaDex, Komga, local files, registry, networking
│   ├── download/            Download queue, worker, library updates, notifications
│   ├── reader/              Webtoon reader: page loading, strip planning, region decoding
│   └── ui/                  Library, history, browse, catalog, search, series, sources screens
└── ui/                      Theme, covers, shared components
```

The design and the reasoning behind it are in [docs/manga-v2-plan.md](docs/manga-v2-plan.md).

## Tests

```bash
./gradlew testDebugUnitTest              # sources (against a mock server), rate limiting, ordering, strip planning
./gradlew connectedDebugAndroidTest      # database migration, DAOs, download manager (needs a device/emulator)
```

## License

Books is released under the [MIT License](LICENSE). The Readium toolkit it uses is under the BSD 3-Clause license.
