# Books: the guide

Everything Books can do, and how. For the architecture see [hld.md](hld.md); for performance numbers see [benchmark.md](benchmark.md).

- [Install and update](#install-and-update)
- [Books tab](#books-tab)
- [Reading PDFs and EPUBs](#reading-pdfs-and-epubs)
- [Discover free books](#discover-free-books)
- [Manga tab](#manga-tab)
- [The webtoon reader](#the-webtoon-reader)
- [Progress: streaks and rewards](#progress-streaks-and-rewards)
- [Year in Books](#year-in-books)
- [Home-screen widget](#home-screen-widget)
- [App icons](#app-icons)
- [Privacy](#privacy)
- [Battery and storage](#battery-and-storage)
- [Troubleshooting](#troubleshooting)

## Install and update

1. On your phone, open the [latest release](https://github.com/Dishan18/Books/releases/latest) and tap `Books-vX.Y.Z.apk`.
2. Open the download and tap **Install** (allow installs from your browser if asked).

To update, install the newer APK the same way; it replaces the old version and keeps everything. Every release is signed with the same key, so updates always install over the previous version. Books needs Android 10 or newer.

## Books tab

### Your library

- Books appear as a grid of covers, most recently read first. A **Continue reading** card at the top takes you back to the book you're in the middle of.
- Each tile shows its progress (`New`, `42%` or `Finished`) and format.
- Covers come from the EPUB or from page 1 of the PDF. A book without one gets a generated cover with its title.
- **Long-press** a book to remove it. This deletes the copy inside the app, your place in it and its bookmarks.

### Adding books

- Tap **Add books** and pick one or more PDF or EPUB files.
- Or, from Files, Drive or Gmail, use **Open with → Books** or **Share → Books**.
- Files are copied into the app, so deleting or moving the original doesn't affect your library.
- Tap the **globe** in the top bar to find free books online (see [Discover](#discover-free-books)).

## Reading PDFs and EPUBs

| To | Do |
|---|---|
| Turn the page | Swipe, or tap the left or right edge (top or bottom edge in up-and-down mode) |
| Show or hide the controls | Tap the middle of the page |
| Jump anywhere | Drag the slider at the bottom; it shows the page (and chapter, for EPUBs) as you drag |
| Bookmark a page | Tap the bookmark at top right; tap again to remove it |
| See bookmarks | Tap the bookmarks icon, then a bookmark to jump to it |
| Table of contents (EPUB) | Tap the list icon |
| Themes, text size, page turning | Tap **Aa** |
| Zoom (PDF) | Pinch. When you stop, the visible part is re-rendered sharp at that zoom |

**Themes**: Light, Sepia and Dark are always available. Midnight, Pink, Forest and True black are earned by reading (see [rewards](#progress-streaks-and-rewards)). In every theme PDF pages are recoloured to match: paper becomes the theme's background and ink its text colour.

**Page turning**: *Side to side* turns pages horizontally. *Up and down* turns PDF pages vertically and makes EPUBs scroll continuously.

Every book reopens exactly where you left off.

## Discover free books

Open the Library, then tap the **globe**.

### Project Gutenberg

More than 75,000 books whose US copyright has expired, from Project Gutenberg's official catalog.

- **Popular on Project Gutenberg** shows the most downloaded books; **See all** opens the full catalog with **Popular** and **Newest** lists.
- Search by title or author with **Search free books** (this searches every catalog you have).
- Open a book to see its summary, language, rights and subjects. Download either **with images** (larger) or **text only** (often under 1 MB).
- If you're outside the USA, check your country's copyright rules before downloading.

### Your own catalogs (OPDS)

OPDS is the standard catalog format used by Calibre (content server or Calibre-Web), Kavita, Komga and many libraries.

1. In Discover, tap **Add an OPDS catalog**.
2. Enter the address, for example `http://192.168.1.20:8083/opds` for Calibre-Web at home.
3. If the catalog needs a sign-in, enter your username and password. The password is encrypted on your phone and only ever sent to that catalog's address.
4. Tap **Test & save**. Books checks that the address really is a catalog before saving it.

Browse folders, search (if the catalog supports it) and download. To change or remove a catalog, tap the pencil next to it; books you downloaded from it stay in your library.

### Downloads

- Downloads continue in the background, with a progress notification, even if you leave the app. They wait for a connection if you're offline.
- A finished download appears in your library with its cover, author and subjects, ready to read offline. The book's page then shows **Read**.
- A book is never added twice: download it again and you're taken to the copy you already have.
- Only free downloads are offered: links to buy, borrow, subscribe or preview are skipped, and a file must really be an EPUB or PDF to be added.

## Manga tab

The Manga tab has four sections: **Library**, **History**, **Browse** and **Downloads**.

### Sources

| Source | What it is | Notes |
|---|---|---|
| **MangaDex** | MangaDex's official public API | No account needed. In *Browse → Manage sources*, choose chapter languages, content ratings (18+ is off by default) and **Data saver** (smaller images) |
| **Komga** | A free server you run at home for your own comics | *Browse → Manage sources → Add Komga server*. No sign-in, a password, or an API key, stored encrypted |
| **On this device** | Your own files | *Library → ⋮ → Import CBZ / ZIP files* (each file becomes a chapter) or *Import image folder* |

### Finding and following series

- **Browse** lists your sources. Open one to see Popular and Latest, or search all sources at once from the top. A slow or broken source never blocks the others.
- Open a series and tap **Add to library** to follow it. **Start reading** / **Continue reading** opens the right chapter.
- **Library** shows the series you follow, with unread counts and a Continue reading card. Tap refresh (or pull down in a series) to check for new chapters; you'll get a notification when there are some.
- **History** shows the last chapter you read in each series.
- Long-press a chapter to mark it read or unread, mark every chapter before it read, or download or delete it.

### Downloads

- Download a chapter with its download button, or *⋮ → Download unread* for a whole series.
- In **Downloads**: pause or resume everything, choose 1 to 3 chapters at a time, and limit downloads to Wi-Fi.
- Downloads survive the app being closed, retry automatically, and read fully offline.

## The webtoon reader

- Scroll continuously. When a chapter ends, the next one follows straight on.
- Tap the top or bottom of the screen to scroll most of a page; the volume keys do the same.
- Tap the middle to show the controls: chapter title, a page slider, and previous/next chapter buttons.
- Pinch to zoom (up to 3×) and drag sideways while zoomed.
- Your exact spot is saved, down to the position within a tall page.
- Very tall images (20,000 px and more) are drawn a screen at a time, so they never blank out or stutter.
- The screen stays on while you read, then sleeps after 10 idle minutes.

## Progress: streaks and rewards

### Daily goal and streak

- Set a **daily goal** of 1 to 60 minutes (5 by default). Books and manga both count.
- Only real reading counts: the timer pauses after 2 minutes without touching the page.
- Each day you reach the goal adds a day to your 🔥 **streak**. The row of circles shows the last seven days, ending today.
- Every 7 days of streak earns a 🧊 **streak freeze** (you can hold 2), which automatically covers a day you miss.
- **Vacation mode** pauses your streak while you're away.
- An optional **daily reminder** arrives at the time you choose, only if you haven't read that day.

### Levels and rewards

You earn XP for minutes read, chapters, finished books and series, goal days and badges. Rewards stay unlocked once earned, even if you turn rewards off and on again.

| Level | Reward |
|---|---|
| 2 | Confetti when you finish a book |
| 3 | Midnight reading theme |
| 4 | Pink reading theme |
| 5 | Forest reading theme |
| 6 | Paper app icon |
| 7 | Blossom app icon |
| 8 | True black (AMOLED) theme |
| 10 | Midnight app icon |
| 12 | Golden streak flame and Gold app icon |

### Badges and stats

- 25 badges: finishing books, streaks, a perfect week, long books, binge-reading manga, completing a series, reading late at night or early in the morning, weekends, and a few secret ones.
- Stats: an 18-week reading heatmap, total reading time, books this year, chapters read and your best streak.
- Finishing a book shows a celebration card while you read; goals, badges and new levels show small notices.
- **Streaks & rewards** at the bottom turns streaks, badges, reminders and celebrations off, leaving plain stats.

## Year in Books

Open it from the **2026 in Books** card (with the current year) on the Progress tab. Tap the right side (or swipe) for the next page, and the left side to go back.

The pages show, when there's something to show:

- total reading time and days read
- books finished, with their covers, and the longest one
- pages turned
- manga chapters read and series completed
- your favourite book and series, by time spent
- the genres you read most
- your longest streak and how many days you reached your goal
- month by month, with your biggest month and day
- what time of day you read most

The last page is a summary card: tap **Share** to send it as an image. If you've read in more than one year, choose the year on the first page.

Everything comes from what Books recorded on your phone; nothing is estimated. Reading time has been recorded since v2.1 and pages turned since v3, and the recap says so when it covers only part of the year. Genres come from your EPUBs' own subjects, Project Gutenberg's categories and manga genres.

## Home-screen widget

1. Long-press your home screen and tap **Widgets**.
2. Find **Books**, then **Reading**, and add it. Resize it to switch layouts.

| Size | Shows |
|---|---|
| Small (2×2) | Your streak and how much of today's goal is left |
| Medium (4×2) | What you're reading (cover, title, progress) and your streak |
| Large (4×3 and up) | The same with a bigger cover, plus today's goal |

Tap the widget to go straight back to your page in the book or chapter; tap the streak to open Progress. The widget updates when you stop reading and just after midnight. It never wakes your phone on a timer.

## App icons

At the bottom of the Progress tab, pick any icon you've unlocked: **Classic**, **Paper**, **Blossom**, **Midnight** or **Gold**. Some launchers remove the old home-screen icon when the app's icon changes; if Books disappears from your home screen, add it again from the app drawer.

## Privacy

- No ads, no analytics, no accounts and no third-party tracking.
- Your library, progress, streaks and Year in Books stay on your phone.
- Books talks only to the sources and catalogs you use: MangaDex, Project Gutenberg, and any Komga or OPDS servers you add. Passwords and API keys are encrypted with the Android Keystore and sent only to their own server.
- MangaDex images from its volunteer network are reported back to MangaDex's health endpoint (address, size, timing and cache status only), as its guidelines ask.
- Sharing a Year in Books card saves the image in the app's cache and opens Android's share sheet; nothing is uploaded by Books.

## Battery and storage

- Readers keep the screen on only while you use them.
- With Battery Saver on, the webtoon reader loads fewer pages ahead.
- Downloads and library checks use Android's WorkManager, which waits for a network and doesn't keep the phone awake.
- Image caches are trimmed when Books goes into the background. The webtoon page cache is limited to 400 MB, the cover image cache to 150 MB, and the HTTP cache to 32 MB.
- Downloaded books, chapters and imported files live in the app's private storage and are removed with the app. Keep the originals if you want copies outside the app.

## Troubleshooting

| Problem | Try |
|---|---|
| "This address isn't an OPDS catalog" | Use the catalog's OPDS address, not its website (often ends in `/opds`). Check the username and password |
| A Project Gutenberg cover is missing | Some books have no cover; a generated one is shown instead |
| A MangaDex series has no chapters | They may only be in other languages (*Browse → Manage sources*) or available only from the official publisher |
| A download is stuck on "Waiting for a connection" | It starts when you're online (and on Wi-Fi, if you chose Wi-Fi only) |
| The app icon vanished from the home screen after changing it | Add Books again from the app drawer; your data isn't affected |
| A theme or icon shows a lock | Read to reach the level shown; rewards never lock features, only cosmetics |
| The widget looks out of date | Open and close any book, or wait until after midnight; resize it to change layout |
