package io.github.dishan18.books.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.dishan18.books.R
import io.github.dishan18.books.app
import io.github.dishan18.books.widget.ReadingWidget
import io.github.dishan18.books.rewards.data.ReadingKind
import io.github.dishan18.books.rewards.ui.RewardOverlay
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.data.Bookmark
import io.github.dishan18.books.data.PageDirection
import io.github.dishan18.books.data.ReaderSettings
import io.github.dishan18.books.data.ReaderTheme
import io.github.dishan18.books.ui.theme.BooksTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.input.TapEvent
import androidx.compose.ui.graphics.toArgb
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.AbsoluteUrl
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reflowable EPUB reader. Readium renders the book in a fragment; the Compose chrome floats on top.
 */
class EpubReaderActivity : AppCompatActivity() {

    private enum class Sheet { Settings, Bookmarks, Contents }

    private val bookId by lazy { intent.getStringExtra(EXTRA_BOOK_ID).orEmpty() }
    private val repository by lazy { app.repository }
    private val settingsStore by lazy { app.readerSettings }

    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null

    // UI state read by the Compose overlay.
    private var book by mutableStateOf<Book?>(null)
    private var error by mutableStateOf<String?>(null)
    private var chromeVisible by mutableStateOf(false)
    private var sheet by mutableStateOf<Sheet?>(null)
    private var locator by mutableStateOf<Locator?>(null)
    private var positions by mutableStateOf<List<Locator>>(emptyList())
    private var currentPosition by mutableIntStateOf(0)
    private var contents by mutableStateOf<List<Pair<ContentsEntry, Link>>>(emptyList())
    private val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // Readium's fragment can't be restored without its publication, so we always start fresh
        // and reopen the book at its saved location instead.
        super.onCreate(null)

        val root = FrameLayout(this)
        root.addView(FragmentContainerView(this).apply { id = R.id.epub_navigator_container }, MATCH_PARENT, MATCH_PARENT)
        root.addView(
            ComposeView(this).apply { setContent { BooksTheme { Overlay() } } },
            MATCH_PARENT,
            MATCH_PARENT,
        )
        setContentView(root)
        applyReaderWindow(settingsStore.settings.value.theme, chromeVisible = false)

        lifecycleScope.launch { open() }
    }

    override fun onDestroy() {
        super.onDestroy()
        publication?.close()
    }

    // Reading time for streaks: counts while the reader is in front and being touched.
    override fun onResume() {
        super.onResume()
        app.rewards.resume(ReadingKind.BOOK) { book?.let { it.id to it.title } }
    }

    override fun onPause() {
        super.onPause()
        app.rewards.pause()
        ReadingWidget.refresh(this)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        app.rewards.ping()
        return super.dispatchTouchEvent(event)
    }

    private suspend fun open() {
        val book = repository.get(bookId) ?: run {
            error = "This book is no longer in your library."
            return
        }
        this.book = book
        val publication = app.readium.open(repository.fileOf(book)).getOrElse {
            error = "Couldn't open this EPUB. ${it.message.orEmpty()}"
            return
        }
        this.publication = publication
        repository.markOpened(bookId)

        val initialLocator = book.location?.let {
            runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull()
        }
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(publication).createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = preferencesFor(settingsStore.settings.value),
            listener = object : EpubNavigatorFragment.Listener {
                override fun onExternalLinkActivated(url: AbsoluteUrl) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url.toString().toUri()))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(this@EpubReaderActivity, "No app can open this link", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
        supportFragmentManager.commitNow {
            add(R.id.epub_navigator_container, EpubNavigatorFragment::class.java, null, NAVIGATOR_TAG)
        }
        val navigator = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as EpubNavigatorFragment
        this.navigator = navigator
        navigator.addInputListener(TapHandler(DirectionalNavigationAdapter(navigator, animatedTransition = true)))

        contents = flatten(publication.tableOfContents, depth = 0)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    navigator.currentLocator.collect { onLocatorChanged(it) }
                }
                launch {
                    settingsStore.settings.collect {
                        navigator.submitPreferences(preferencesFor(it))
                        applyReaderWindow(it.theme, chromeVisible)
                    }
                }
                launch {
                    repository.bookmarks(bookId).collect { bookmarks.value = it }
                }
            }
        }
        positions = publication.positions()
        locator?.let { onLocatorChanged(it) }
    }

    private var lastCountedPosition: Int? = null

    private fun onLocatorChanged(newLocator: Locator) {
        locator = newLocator
        currentPosition = ((newLocator.locations.position ?: 1) - 1).coerceIn(0, (positions.size - 1).coerceAtLeast(0))
        // Readium positions are page-sized; moving forward counts as a page read (a jump counts once).
        newLocator.locations.position?.let { position ->
            val last = lastCountedPosition
            if (last != null && position > last) app.rewards.pageTurned()
            if (last == null || position != last) lastCountedPosition = position
        }
        checkFinished(newLocator)
        lifecycleScope.launch {
            repository.saveLocation(
                bookId,
                newLocator.toJSON().toString(),
                newLocator.locations.totalProgression?.toFloat() ?: 0f,
            )
        }
    }

    private var openedAt: Locator? = null

    /** Reaching the end counts as finishing the book; reopening a book already at its end doesn't. */
    private fun checkFinished(current: Locator) {
        val opened = openedAt ?: run {
            openedAt = current
            return
        }
        if (opened.locations == current.locations || positions.isEmpty()) return
        val atEnd = (current.locations.position ?: 0) >= positions.size || (current.locations.totalProgression ?: 0.0) >= 0.99
        val finished = book ?: return
        if (atEnd) app.rewards.onBookFinished(finished.id, finished.title, positions.size)
    }

    /** Edge taps turn pages; any other tap shows or hides the chrome. */
    private inner class TapHandler(private val directional: DirectionalNavigationAdapter) : InputListener {
        override fun onTap(event: TapEvent): Boolean {
            when {
                chromeVisible -> setChrome(false)
                directional.onTap(event) -> Unit
                else -> setChrome(true)
            }
            return true
        }

        override fun onDrag(event: DragEvent): Boolean = directional.onDrag(event)
        override fun onKey(event: KeyEvent): Boolean = directional.onKey(event)
    }

    private fun setChrome(visible: Boolean) {
        chromeVisible = visible
        applyReaderWindow(settingsStore.settings.value.theme, visible)
    }

    private fun go(target: Locator) {
        navigator?.go(target, animated = false)
        sheet = null
        setChrome(false)
    }

    private fun toggleBookmark() {
        val current = locator ?: return
        lifecycleScope.launch {
            val existing = bookmarkAt(current, bookmarks.value)
            if (existing != null) {
                repository.deleteBookmark(existing)
            } else {
                repository.addBookmark(
                    Bookmark(
                        bookId = bookId,
                        location = current.toJSON().toString(),
                        label = current.title ?: "Page ${currentPosition + 1}",
                        progress = current.locations.totalProgression?.toFloat() ?: 0f,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    @Composable
    private fun Overlay() {
        val settings by settingsStore.settings.collectAsStateWithLifecycle()
        val bookmarkList by bookmarks.collectAsStateWithLifecycle()
        val theme = settings.theme

        error?.let { message ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(message, color = theme.content, textAlign = TextAlign.Center)
                    TextButton(onClick = ::finish) { Text("Back to library") }
                }
            }
            return
        }

        val current = locator
        val total = positions.size
        val percent = ((current?.locations?.totalProgression ?: 0.0) * 100).roundToInt()
        ReaderChrome(
            visible = chromeVisible,
            theme = theme,
            title = book?.title.orEmpty(),
            progressLabel = listOfNotNull(
                current?.title,
                if (total > 0) "Page ${currentPosition + 1} of $total" else null,
                "$percent%",
            ).joinToString(" · "),
            pageCount = total,
            currentPage = currentPosition,
            previewLabel = { index ->
                listOfNotNull(positions.getOrNull(index)?.title, "Page ${index + 1} of $total").joinToString(" · ")
            },
            onSeek = { index -> positions.getOrNull(index)?.let { navigator?.go(it) } },
            bookmarked = current != null && bookmarkAt(current, bookmarkList) != null,
            onToggleBookmark = ::toggleBookmark,
            onBack = ::finish,
            onShowBookmarks = { sheet = Sheet.Bookmarks },
            onShowSettings = { sheet = Sheet.Settings },
            onShowContents = if (contents.isNotEmpty()) ({ sheet = Sheet.Contents }) else null,
        )

        when (sheet) {
            Sheet.Settings -> ReaderSettingsSheet(
                theme = theme,
                onThemeChange = settingsStore::setTheme,
                pageDirection = settings.pageDirection,
                onPageDirectionChange = settingsStore::setPageDirection,
                onDismiss = { sheet = null },
                fontScale = settings.fontScale,
                onFontScaleChange = settingsStore::setFontScale,
                lockedThemeLevel = app.rewards::lockedLevel,
            )
            Sheet.Bookmarks -> BookmarksSheet(
                theme = theme,
                bookmarks = bookmarkList,
                onSelect = { bookmark ->
                    runCatching { Locator.fromJSON(JSONObject(bookmark.location)) }.getOrNull()?.let(::go)
                },
                onDelete = { lifecycleScope.launch { repository.deleteBookmark(it) } },
                onDismiss = { sheet = null },
            )
            Sheet.Contents -> ContentsSheet(
                theme = theme,
                entries = contents.map { it.first },
                onSelect = { index ->
                    val link = contents[index].second
                    publication?.locatorFromLink(link)?.let(::go)
                },
                onDismiss = { sheet = null },
            )
            null -> Unit
        }
        RewardOverlay()
    }

    private companion object {
        const val NAVIGATOR_TAG = "epub-navigator"

        fun preferencesFor(settings: ReaderSettings) = EpubPreferences(
            theme = when (settings.theme) {
                // Readium has no pink theme; its light theme plus our colours below gives the same look.
                ReaderTheme.LIGHT, ReaderTheme.PINK -> Theme.LIGHT
                ReaderTheme.SEPIA -> Theme.SEPIA
                ReaderTheme.DARK, ReaderTheme.MIDNIGHT, ReaderTheme.AMOLED -> Theme.DARK
                ReaderTheme.FOREST -> Theme.LIGHT
            },
            // Match the PDF reader and the chrome rather than Readium's own palette.
            backgroundColor = Color(settings.theme.background.toArgb()),
            textColor = Color(settings.theme.content.toArgb()),
            fontSize = settings.fontScale,
            // Up and down scrolls continuously; side to side turns pages.
            scroll = settings.pageDirection == PageDirection.VERTICAL,
        )

        fun flatten(links: List<Link>, depth: Int): List<Pair<ContentsEntry, Link>> = links.flatMap { link ->
            listOf(ContentsEntry(link.title ?: "Untitled", depth) to link) + flatten(link.children, depth + 1)
        }

        /** A bookmark "covers" the current page if it points into the same spot of the same chapter. */
        fun bookmarkAt(current: Locator, bookmarks: List<Bookmark>): Bookmark? = bookmarks.firstOrNull { bookmark ->
            val saved = runCatching { Locator.fromJSON(JSONObject(bookmark.location)) }.getOrNull() ?: return@firstOrNull false
            val savedPosition = saved.locations.position
            val currentPosition = current.locations.position
            saved.href == current.href && if (savedPosition != null && currentPosition != null) {
                savedPosition == currentPosition
            } else {
                abs((saved.locations.progression ?: 0.0) - (current.locations.progression ?: 0.0)) < 0.01
            }
        }
    }
}
