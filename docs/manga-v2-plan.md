# Books v2: Manga tab implementation plan

Books v1 (PDF/EPUB) stays as the **Books** tab, unchanged in behaviour. v2 adds a **Manga** tab: an online-first, ad-free manga and webtoon reader with offline downloads.

## What we reuse from v1

| v1 component | Reused for |
|---|---|
| `BooksApp` app-wide singletons | Adds manga singletons (database DAOs, source registry, downloader, image loader) |
| `BooksDatabase` (Room, v1) | Bumped to v2 with a hand-written, additive `MIGRATION_1_2` (new tables only; v1 tables untouched) |
| `ui/theme/BooksTheme`, cover styling, press-spring interaction | Manga library/browse grids look and feel like the Books library |
| `reader/ReaderCommon.applyReaderWindow` pattern | Immersive webtoon reader window |
| Release pipeline, signing, CI | Unchanged |

## Architecture

```
manga/
├── data/        Room entities + DAOs (series, chapters, sources, downloads, history), MangaRepository
├── source/
│   ├── api/     CatalogProvider, ChapterProvider, MangaSource, models, SourceError, SourceResult
│   ├── net/     Shared OkHttp, per-source clients, rate limiting, User-Agent, 429 handling
│   ├── mangadex/  Official MangaDex API v5 (documented, no account needed)
│   ├── komga/     User's own Komga server (optional Basic auth or API key)
│   ├── local/     Imported CBZ/ZIP archives and image folders
│   ├── SourceRegistry  builds sources from saved configs; isolates failures
│   └── CredentialStore Android Keystore AES-GCM for optional source credentials
├── download/    DownloadManager (queue in Room) + DownloadWorker (WorkManager, foreground)
├── reader/      PageLoader (download → local file → online cache), strip planner,
│                tiled region decoding, WebtoonReaderActivity + ViewModel
└── ui/          Manga home (Library / History / Browse / Downloads), catalog, global search,
                 series details, source management
```

Layer rules:
- **Catalog providers** (browse/search/details) and **chapter providers** (chapter lists, page lists, image fetch) are separate interfaces. A source implements both.
- The **downloader** depends only on `ChapterProvider` and the repository.
- The **reader** depends only on `PageLoader`, never on a concrete source.
- Every source call returns `SourceResult` (success or typed `SourceError`); one source failing never breaks another (supervisor scopes, per-source clients, per-source health state).

## Sources

Only documented APIs or content the user is authorised to access:
- **MangaDex** via its public API v5. Respects the documented rate limits (client-side token bucket below 5 req/s, at-home server endpoint limits), sends an identifying User-Agent, credits scanlation groups, filters external/unavailable chapters, and reports MangaDex@Home image fetches to MangaDex's CDN health endpoint as its API guidelines ask (no user data).
- **Komga**: a self-hosted server the user runs; optional Basic or API-key auth.
- **Local**: imported files.

No anti-bot bypass, no cookie scraping, no paywall circumvention. Dynamic loading of third-party code ("extensions") is deliberately not supported, for security; new source types are added in code and ship with app updates. "Add / enable / disable / update" applies to source *instances* (e.g. several Komga servers, MangaDex language settings).

## Database v2 (additive migration)

New tables: `manga_sources`, `manga_series`, `manga_chapters`, `manga_history`, `manga_downloads`. Room schema export is switched on (`app/schemas/`) so migrations are tested with `MigrationTestHelper`.

## Downloads

Queue rows live in Room (`QUEUED / DOWNLOADING / PAUSED / FAILED / COMPLETED`). One unique WorkManager job processes the queue with a configurable chapter concurrency and per-chapter page concurrency, retries pages with exponential backoff, resumes partially downloaded chapters (existing pages are kept), runs as a foreground `dataSync` worker with a progress notification, and can be restricted to Wi‑Fi. Pause cancels the job and leaves progress on disk; resume re-queues.

## Webtoon reader

- Continuous vertical `LazyColumn`; every page is split into **strips** of at most ~1 screen height that are decoded on demand with `BitmapRegionDecoder` at screen width (sub-sampled), so extremely tall images never allocate huge bitmaps or exceed GPU texture limits.
- Pages resolve to local files (downloaded → imported archive → online page cache) through `PageLoader`; the next pages are prefetched.
- Strip bitmaps live in a size-bounded LRU; region decoders are cached per file.
- Reading position (chapter, page, offset within page) is saved continuously and restored exactly.
- Reaching the end of a chapter appends the next one seamlessly; chapters are marked read as you pass them.
- Tap the middle for controls, top/bottom to scroll by a screen, pinch to zoom, volume keys to scroll.

## Increments

1. Navigation (Books / Manga tabs) and manga database + migration
2. Source interfaces, MangaDex/Komga, source management, catalog browse and global search
3. Series details and online chapter lists (favorites, read state, history)
4. Download manager
5. Webtoon reader
6. Local CBZ/ZIP/folder import and UI polish

Each increment ships with unit tests (parsers via MockWebServer, rate limiter, strip planner, natural sort, chapter merging) and instrumented tests (Room migration, DAOs).
