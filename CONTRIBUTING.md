# Contributing to Books

Thanks for wanting to help. Books is a calm, private reader for books, manga and webtoons: no ads, no accounts, no tracking. Every contribution should keep it that way.

New here? Read the [guide](docs/guide.md) to see what the app does and the [high-level design](docs/hld.md) to see how it's built. Good first issues are labelled [`good first issue`](https://github.com/Dishan18/Books/labels/good%20first%20issue).

## Ways to help

- **Report a bug** or **suggest a feature** with the [issue templates](https://github.com/Dishan18/Books/issues/new/choose).
- **Fix a bug or build a feature.** For anything bigger than a small fix, open an issue first so we can agree on the approach before you spend time on it.
- **Add a source or catalog.** See [Adding a source or catalog](#adding-a-source-or-catalog). It must use a documented API or a server the user controls.
- **Improve the docs**: the README, the [guide](docs/guide.md) or the [design notes](docs/hld.md).
- **Test on your phone** and report what feels slow, glitchy or confusing, with your device and Android version.
- **Translate.** Most text is still in code; moving it into `strings.xml` so it can be translated is a welcome first project.

## Ground rules

These aren't negotiable. A pull request that breaks one won't be merged, however good it is otherwise.

1. **Privacy.** No ads, analytics, crash reporters, telemetry, third-party SDKs that phone home, or mandatory accounts. Network requests go only to the sources and catalogs the user chooses.
2. **Fair use.** Only documented APIs, public catalogs or servers the user controls. No scraping, no bypassing paywalls, logins, rate limits or anti-bot measures, and no downloading or running third-party "extension" code. Respect each source's rate limits and guidelines.
3. **Keep user data safe.** Database changes are **additive migrations** (new tables, or new columns with defaults), each with a test in `MigrationTest` against the exported schema. Never drop, rename or rewrite user data, and never use destructive fallback migrations.
4. **Protect credentials.** Secrets go in `CredentialStore` (Android Keystore), never in the database, logs or plain preferences, and are sent only to their own host.
5. **Keep the feel.** The UI is minimal and quiet by design. Match the existing look (spacing, rounded cards, springs, subtle haptics). No em dashes in text the user sees.
6. **No unnecessary dependencies.** Prefer the platform and the libraries already in use. If you need a new one, explain why in the PR.

## Development setup

You need:

- **JDK 17 or newer** (CI uses 21)
- **Android SDK** with platform 37: Android Studio installs it, or use the command-line tools
- An emulator or phone with **Android 10 (API 29)** or newer

```bash
git clone https://github.com/Dishan18/Books.git
cd Books
./gradlew assembleDebug          # build
./gradlew installDebug           # install on a connected device or emulator
```

Debug builds are signed with your local debug key, so uninstall a release build from the device before installing one. Release signing needs the project key, which only the maintainer has; you never need it to contribute.

## Before you open a pull request

```bash
./gradlew testDebugUnitTest            # unit tests: sources, catalogs, parsers, rewards rules, Year in Books
./gradlew lintDebug                    # must report no errors
./gradlew connectedDebugAndroidTest    # instrumented tests: migrations, DAOs, downloads, rewards (needs a device)
```

CI runs the unit tests, lint and a release build on every pull request.

Also, please:

- **Try your change in the app**, on a real phone if you can, and in light and dark mode. Include screenshots or a short video for anything visible.
- **Add or update tests** for logic you change: sources and parsers get unit tests against `MockWebServer`; database changes get a migration test.
- **Keep changes focused.** One feature or fix per pull request is much easier to review.
- **Update the docs** (README, `docs/guide.md`) when behaviour changes.
- **Check performance** if you touch readers, images or background work. Before/after numbers from `dumpsys meminfo` or `dumpsys gfxinfo` (see [benchmark.md](docs/benchmark.md)) are very welcome.

## Code style

- Kotlin with the official code style (4-space indents, trailing commas), Jetpack Compose for UI, coroutines and `Flow` for async work.
- **Match the code around you**: naming, comment density and structure. Comments explain *why*, not *what*.
- Keep work off the main thread. Long-running or background work goes through WorkManager with constraints, never a wake lock or a polling loop.
- New screens follow the existing pattern: a ViewModel exposing `StateFlow`, a composable that collects it with `collectAsStateWithLifecycle`, and navigation through type-safe `@Serializable` routes.
- User-facing text is short, friendly and plain, with no em dashes. Errors say what happened and what to do next.

## Adding a source or catalog

- **Manga:** implement `MangaSource` (`manga/source/api/SourceApi.kt`), add a `SourceType`, and build it in `SourceRegistry`. Look at `KomgaSource` for an authenticated server and `MangaDexSource` for a rate-limited public API.
- **Books:** implement `BookCatalog` (`discover/catalog/BookCatalog.kt`), or reuse `OpdsCatalog` if the service speaks OPDS. Add a `BookSourceType` and build it in `BookSources`.
- Get an HTTP client from `MangaNetwork.clientFor(...)` with a `RateLimitInterceptor` matching the service's published limits, and `HostAuthInterceptor` for any sign-in.
- Map failures to `SourceError` so the UI can explain them.
- Add unit tests against `MockWebServer` with trimmed real responses, as in `MangaDexSourceTest` or `BookCatalogsTest`.
- In the pull request, link the service's API documentation or terms showing that this use is allowed.

## Commits and pull requests

- Write commit messages in the imperative, describing the change: "Add OPDS search for Kavita", not "fixed stuff".
- Open the pull request against `main` and fill in the template.
- Be patient and kind in review. This is a hobby project maintained in spare time.

## Releases

The maintainer releases by pushing a `vX.Y.Z` tag; GitHub Actions builds the signed APK and publishes it. Contributors don't need to touch versions.

## Recognition

Everyone who contributes is listed in [CONTRIBUTORS.md](CONTRIBUTORS.md). Add yourself in your first pull request, or the maintainer will.

## Code of conduct

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). To report a security problem, please follow [SECURITY.md](SECURITY.md) instead of opening a public issue.

## License

By contributing, you agree that your contributions are released under the project's [MIT License](LICENSE).
