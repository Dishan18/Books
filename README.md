# Books

A calm, fast reader for Android. **Books** holds your PDFs and EPUBs and finds free public-domain books online, **Manga** reads manga and webtoons from official sources, your own server or your own files, and **Progress** keeps your daily reading streak and your Year in Books. No ads, no accounts, no tracking.

Built with Kotlin and Jetpack Compose. EPUB rendering by the [Readium Kotlin toolkit](https://github.com/readium/kotlin-toolkit).

**Docs:** [User guide](docs/guide.md) · [Architecture](docs/hld.md) · [Benchmarks](docs/benchmark.md) · [Contributing](CONTRIBUTING.md)

## Books tab

- **Library**: a grid of covers, newest-read first, with a "Continue reading" card. Covers are taken from the EPUB or from page 1 of the PDF; books without one get a generated cover.
- **Import**: tap **Add books** to pick several PDFs and EPUBs, or use *Open with → Books* / *Share → Books* from Files, Drive or Gmail. Files are copied into the app, so deleting the original doesn't remove the book.
- **Excel and PowerPoint**: open `.xlsx` and `.pptx` files from *Open with → Books*. Spreadsheets get a zoomable sheet view and never count as reading; slides read like PDF pages and count toward your streak (not Year in Books).
- **PDF reader**: swipe or tap the left/right edges to turn pages. Pinch to zoom; pages are re-rendered sharper as you zoom in.
- **EPUB reader**: reflowable text with adjustable size, a table of contents, and the same tap and swipe navigation.
- **Light, Sepia and Dark** reading themes, plus **Midnight**, **Pink**, **Forest** and **True black** unlocked by reading. In dark themes PDF pages are recoloured to light text on the theme's background.
- **Page turning** side to side or up and down (EPUBs scroll continuously when set to up and down).
- **Bookmarks** and a page slider in both readers. Each book reopens where you left off.
- Long-press a book to remove it.

Tap the middle of a page to show or hide the controls.

### Discover free books

Tap the globe in the Library's top bar (or **Find free books online** in an empty library).

- **Project Gutenberg**: 75,000+ public-domain books through its official OPDS catalog. Browse popular and newest titles or search, read the summary and subjects, then download an EPUB with images or the much smaller text-only edition.
- **Your own OPDS catalogs**: a Calibre, Kavita or Komga server at home, or a library you have an account with. Folders, search (OpenSearch) and an optional password, stored encrypted and sent only to that catalog's address.
- Downloads run in the background and land in your library with their cover, author and subjects, ready to read offline. A book is never added twice.
- Only free downloads are offered: buy, borrow, subscription and sample links are left out, and a file has to really be an EPUB or PDF to be added. Standard Ebooks' catalog is only open to its paying patrons, so it isn't built in; patrons can add it as their own catalog.

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

- No ads, no analytics, no accounts. The app talks only to the sources and catalogs you use.
- Sources use documented APIs or servers you control. There's no scraping, no paywall or login bypassing, and no anti-bot workarounds. Third-party code ("extensions") is never downloaded or run.
- MangaDex's API guidelines are followed: requests are rate-limited, scanlation groups are credited on every chapter, and images fetched from the MangaDex@Home volunteer network are reported back to MangaDex's health endpoint (image URL, size, timing and cache status only, never anything about you).

### Battery and memory

- The reader keeps the screen on only while you're using it, and lets it sleep after 10 idle minutes.
- With Battery Saver on, fewer pages are loaded ahead.
- Background work goes through WorkManager with network constraints, so there are no wake locks or polling. Automatic library checks also wait until the battery isn't low.
- Library covers are decoded at the size they're shown, in a cache bounded by memory. Webtoon pages wider than the screen are scaled to the screen's width once, and their GPU upload starts off the main thread. Image caches are trimmed when the app goes to the background.
- The widget and the midnight rollover use no timers or wake-ups: an inexact alarm redraws the widget the next time the screen is on after midnight.

## Progress tab: streaks and rewards

Reading 5 minutes a day builds a streak, Duolingo-style, but without the guilt.

- **Daily goal**: 1 to 60 minutes (default 5). Books and manga both count. Only real reading counts: time stops after 2 minutes without touching the page.
- **Daily streak** 🔥 for every day you hit your goal, with the last seven days shown up to today. The day rolls over at midnight even with the app open. Every 7 days earns a **streak freeze** 🧊 (hold up to 2) that automatically covers a missed day. **Vacation mode** pauses your streak.
- **25 badges**: finishing books (1, 5, 10, 25, 50), streaks (3 to 365 days), a perfect week, a 500-page doorstopper, 10 manga chapters in a day, a completed series, night owl, early bird, weekend warrior, and a few secret ones.
- **XP and levels** from minutes read, chapters, finished books, goals and badges. Levels unlock cosmetic rewards, and an unlock is yours for good: confetti when you finish a book (level 2), the **Midnight** (3), **Pink** (4), **Forest** (5) and **True black** (8) reading themes, the **Paper** (6), **Blossom** (7), **Midnight** (10) and **Gold** (12) app icons, and a golden streak flame (12).
- A **celebration card** when you finish a book, and small toasts for goals, badges and level-ups while you read.
- **Stats**: an 18-week reading heatmap, total time, books this year, chapters read and your best streak. Pages turned are counted too (from v3 on).
- **App icon**: pick an unlocked icon at the bottom of the Progress tab. Some launchers remove the old home-screen icon when it changes; add Books again from the app drawer if so.
- An optional **daily reminder** at a time you pick, sent only if you haven't read yet that day.
- One switch turns streaks, badges and reminders off, leaving plain stats. Everything stays on your phone: no accounts, no leaderboards.

### Year in Books

A recap of your year, one page at a time, opened from the Progress tab: reading time, books finished (with their covers), pages turned, manga chapters, your favourites, the genres you read most, your longest streak, month by month, and what time of day you read. The last page is a card you can share as an image.

Everything comes from what the app recorded, nothing is estimated. If Books started keeping track partway through the year, the recap says from when. Genres come from your EPUBs' own subjects, Project Gutenberg's categories and manga genres, weighted by reading time.

## Home-screen widget

Long-press your home screen, tap **Widgets** and add **Books → Reading**.

- **Small**: your streak and how much of today's goal is left.
- **Medium and large**: the book or series you're reading, with its cover and progress, and your streak. Large also shows today's goal.
- Tap the widget to go straight back to your page; tap the streak to open the Progress tab. It redraws when you finish reading and just after midnight, never on a timer.

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
git tag v3.0.0
git push origin v3.0.0
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
├── HomeActivity.kt / AppRoot.kt   Books | Manga | Progress tabs, "Open with" / "Share", widget taps
├── BooksApp.kt              App-wide singletons and image loader
├── data/                    Room database (+ migrations), book import, reader settings, Readium setup
├── library/                 Books library screen and the Books tab's navigation
├── discover/                Online book catalogs: OPDS parser, Project Gutenberg, your catalogs, downloads
├── reader/                  PDF reader (Compose + PdfRenderer), EPUB reader (Readium), shared chrome
├── manga/
│   ├── data/                Manga entities, DAOs, repository
│   ├── source/              Source interfaces, MangaDex, Komga, local files, registry, networking
│   ├── download/            Download queue, worker, library updates, notifications
│   ├── reader/              Webtoon reader: page loading, strip planning, region decoding
│   └── ui/                  Library, history, browse, catalog, search, series, sources screens
├── rewards/                 Reading time, streaks, badges, levels, app icons, reminders, Progress tab
├── year/                    Year in Books: the recap engine and its screens
├── widget/                  The "Reading" home-screen widget
└── ui/                      Theme, covers, shared components
```

The architecture is described in [docs/hld.md](docs/hld.md), and the original Manga tab plan in [docs/manga-v2-plan.md](docs/manga-v2-plan.md).

## Tests

```bash
./gradlew testDebugUnitTest              # sources and book catalogs (against a mock server), OPDS parsing, Year in Books, rewards rules
./gradlew connectedDebugAndroidTest      # database migrations, DAOs, downloads, rewards, adding downloaded books (needs a device/emulator)
```

## Contributing

Contributions are welcome: bug reports, fixes, new sources and catalogs (documented APIs only), docs, testing and translations. Start with [CONTRIBUTING.md](CONTRIBUTING.md); everyone who helps is listed in [CONTRIBUTORS.md](CONTRIBUTORS.md). Please follow the [Code of Conduct](CODE_OF_CONDUCT.md), and report security problems privately as described in [SECURITY.md](SECURITY.md).

## License

Books is released under the [MIT License](LICENSE). The Readium toolkit it uses is under the BSD 3-Clause license.
