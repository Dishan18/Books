# Benchmarks: v2.1.0 → v3.0.0

Numbers measured while building v3, so performance changes are backed by data rather than claims. Every figure below comes from a run described here; nothing is extrapolated.

## Setup

| | |
|---|---|
| Device | Android Emulator, AVD "BooksTest", Android 15 (API 35), x86_64, 1080 × 2400, `dalvik.vm.heapgrowthlimit` 192 MB |
| Builds | Release builds (R8 minified, signed), `v2.1.0` and `v3.0.0`, installed fresh or upgraded in place as noted |
| Library data | 40 generated EPUBs, each with a 600 × 900 JPEG cover |
| Webtoon data | One local series ("On this device"): 3 CBZ chapters × 6 pages, pages 1600 px wide and alternately 16,000 and 9,000 px tall |
| Driver | `adb` scripts: identical swipes, sleeps and GC for both versions |

### What each number is

- **Memory**: `dumpsys meminfo io.github.dishan18.books`. *Native Heap* is where bitmaps live; *TOTAL PSS* is the app's real share of RAM.
- **Jank**: `dumpsys gfxinfo` after a reset, so it covers just the scroll. *Janky frames* uses the modern (deadline-based) count; percentiles are frame times.
- **CPU**: utime + stime from `/proc/<pid>/stat`, in clock ticks, before and after the scroll.
- **Cold start**: `am start -W` *TotalTime* after `force-stop`.

### Scenarios

- **Library**: cold start, 12 swipes to the bottom of the 40-book grid and 12 back up, then a forced GC.
- **Webtoon**: open chapter 1 of the tall series and make 70 swipes (≈ 60 s), scrolling through all three chapters to "You're all caught up".
- **Leaks**: open 6 EPUBs (turn a page in each) and 1 webtoon chapter, closing each, then two forced GCs.

## Results

### A/B run (same session, same data; v2.1 installed fresh, then upgraded in place to v3)

| Scenario | Metric | v2.1.0 | v3.0.0 | Change |
|---|---|---|---|---|
| Library (40 books) | Native heap | 98.6 MB | 39.0 MB | **−60%** |
| | Total PSS | 125.8 MB | 66.5 MB | **−47%** |
| | Janky frames | 5.89% | 3.67% | better |
| | 95th percentile frame | 36 ms | 31 ms | better |
| Webtoon (3 runs each) | Total PSS | 160.8 / 201.1 / 180.9 MB | 107.1 / 125.2 / 116.4 MB | **−35 to −40%** |
| | Janky frames | 4.37 / 3.51 / 3.28% | 2.71 / 4.93 / 2.36% | within noise |
| | 95th percentile frame | 32 / 31 / 31 ms | 26 / 32 / 24 ms | slightly better |
| | CPU ticks during scroll | 1686 / 1508 / 1449 | 1847 / 1802 / 1829 | **+12% (trade-off, below)** |

### Earlier single runs (first session)

| Scenario | Metric | v2.1.0 | v3.0.0 |
|---|---|---|---|
| Library | Native heap / PSS | 98.8 / 125.3 MB | 39.1 / 66.9 MB |
| | Janky frames, p95 | 3.23%, 26 ms | 2.81%, 30 ms |
| Webtoon | Native heap at end of scroll | 133.7 MB | 71.3 MB |
| | Total PSS at end of scroll | 160.2 MB | 101.3 MB |
| | After forced GC: native / PSS | 93.6 / 125.5 MB | 62.1 / 97.6 MB |
| | CPU ticks | 1563 | 1869, 1489, 1556 (three runs) |
| | Janky frames, p95 | 2.58%, 31 ms | 2.13%, 26 ms |
| Leaks | Activities alive after closing 7 readers | 1 | 1 |

No activity leak in either version: only the home screen remained after readers were closed.

### Cold start (interleaved, fresh installs, empty library)

The emulator's speed drifts over time, so startup was measured A, B, A, B, back to back. Each row is 12 launches, sorted.

| Run | Launch times (ms) | Median |
|---|---|---|
| v2.1.0, round 1 | 801 873 926 951 966 984 1013 1022 1080 1091 1129 1338 | 998 |
| v3.0.0, round 1 | 847 865 882 903 912 913 932 949 966 968 976 1010 | 922 |
| v2.1.0, round 2 | 823 834 840 879 885 905 914 929 933 952 989 1101 | 910 |
| v3.0.0, round 2 | 789 797 828 829 841 866 907 931 956 969 973 1008 | 887 |

Result: **no startup regression.** An earlier v3 build measured about 50 ms slower (median 540 ms against 489 ms) because the Books tab built its navigation host at launch. v3.0.0 builds it only when Discover is first opened, and that removed the gap.

### APK size

| v2.1.0 | v3.0.0 |
|---|---|
| 7,388,957 bytes | 7,634,085 bytes (+245 KB for Discover, Year in Books, the widget and the app icons) |

## What changed, and why the numbers moved

| Change | Effect |
|---|---|
| Library covers decoded at 400 px wide instead of their stored 600 px, scaled during decode | 2.16 MB → 0.96 MB per cover |
| Cover cache bounded by bytes (⅛ of the heap limit, max 32 MB) instead of 80 entries | Was able to hold ≈ 170 MB of covers; now bounded |
| Neutral placeholder while a real cover loads (not a generated cover first) | Removes a flicker when scrolling |
| Webtoon strips wider than the screen scaled to screen width once, after decoding | A 1600 px strip drops from 22.7 MB to 10.4 MB |
| `Bitmap.prepareToDraw()` on decoded strips | GPU upload starts off the main thread instead of on first draw |
| `onTrimMemory`: caches cut to ¼ when the UI is hidden, emptied in the background | Less memory held while you're in another app |
| `AsyncImage` instead of `SubcomposeAsyncImage` for manga covers | No subcomposition per grid tile |
| Reading-time pings throttled to one queued update per 2 s (was one per touch event) | Same time recorded (tested: a 90 s scroll of 9,000 touch events records exactly 90 s) with far fewer coroutine launches |
| Online catalogs and the Books-tab navigation host created lazily | No startup cost for people who never open Discover |

### The webtoon CPU trade-off, measured

To find where the extra CPU comes from, one build differed from v3.0.0 only in skipping the strip downscale:

| Build | Total PSS | CPU ticks | Janky frames |
|---|---|---|---|
| v3.0.0 without downscaling | 183.2 / 201.3 / 201.3 MB | 1607 / 1626 / 1585 | 3.35 / 3.05 / 3.08% |
| v3.0.0 | 107.1 / 125.2 / 116.4 MB | 1847 / 1802 / 1829 | 2.71 / 4.93 / 2.36% |

So scaling wide pages costs about 12% more CPU while you scroll continuously, and saves about 40% of memory. It applies **only to pages wider than the screen**: most webtoon pages (typically 690 to 1000 px wide) are never scaled, and their cost is unchanged. Image loaders such as Glide make the same choice by default (decode to the size shown). It's kept because the reader's memory use is what decides whether Android keeps the app alive in the background.

## Not covered

- **Physical devices.** All runs were on an x86_64 emulator. Absolute numbers on a Pixel will differ; the ratios are what to compare.
- **Battery** wasn't measured directly (an emulator has no meaningful battery). CPU time stands in for it, together with design choices that avoid wake-ups: no periodic widget updates, a non-waking midnight alarm, and WorkManager with constraints for all background work.
- **Network performance** of online sources depends on their servers. For reference, Project Gutenberg's OPDS catalog answered in 1.2 to 1.8 s per request during development; the third-party Gutendex API took 15 s or timed out, which is why it isn't used.

## Reproducing

1. Build both versions with `./gradlew assembleRelease -PversionName=<version>`.
2. Generate the dataset: 40 EPUBs with 600 × 900 cover images, and CBZ chapters of 1600 px wide JPEG pages 9,000 to 16,000 px tall (any image tool will do).
3. Install v2.1.0, import the EPUBs (*Open with → Books*), and copy the CBZ series folder (with a `series.json`) into `files/manga-local/<key>/`.
4. For each scenario: `am force-stop`, start, `dumpsys gfxinfo <pkg> reset`, run the swipes, then read `dumpsys meminfo`, `dumpsys gfxinfo` and `/proc/<pid>/stat`.
5. Upgrade in place to v3.0.0 (`adb install -r`) and repeat.
