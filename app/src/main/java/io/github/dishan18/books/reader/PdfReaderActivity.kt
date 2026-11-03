package io.github.dishan18.books.reader

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dishan18.books.app
import io.github.dishan18.books.widget.ReadingWidget
import io.github.dishan18.books.rewards.data.ReadingKind
import io.github.dishan18.books.rewards.ui.RewardOverlay
import io.github.dishan18.books.data.PageDirection
import io.github.dishan18.books.data.ReaderSettingsStore
import io.github.dishan18.books.data.ReaderTheme
import io.github.dishan18.books.ui.theme.BooksTheme
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.util.lerp as lerpFloat

class PdfReaderActivity : ComponentActivity() {

    private val viewModel: PdfReaderViewModel by viewModels {
        viewModelFactory {
            initializer { PdfReaderViewModel(intent.getStringExtra(EXTRA_BOOK_ID).orEmpty(), app.repository, app.rewards) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = app.readerSettings
        applyReaderWindow(settings.settings.value.theme, chromeVisible = false)
        setContent {
            BooksTheme {
                PdfReaderScreen(
                    viewModel = viewModel,
                    settingsStore = settings,
                    onClose = ::finish,
                    onWindowChange = { theme, chromeVisible -> applyReaderWindow(theme, chromeVisible) },
                )
            }
        }
    }

    // Reading time for streaks: counts while the reader is in front and being touched.
    override fun onResume() {
        super.onResume()
        app.rewards.resume(ReadingKind.BOOK) {
            (viewModel.state.value as? PdfReaderViewModel.State.Ready)?.book?.let { it.id to it.title }
        }
    }

    override fun onPause() {
        super.onPause()
        app.rewards.pause()
        ReadingWidget.refresh(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            (viewModel.state.value as? PdfReaderViewModel.State.Ready)?.document?.trim(background = level >= TRIM_MEMORY_BACKGROUND)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        app.rewards.ping()
        return super.dispatchTouchEvent(event)
    }
}

private enum class PdfSheet { Settings, Bookmarks }

/** The PDF reader; with [slides] it shows a presentation's slides in their own colours. */
@Composable
internal fun PdfReaderScreen(
    viewModel: PdfReaderViewModel,
    settingsStore: ReaderSettingsStore,
    onClose: () -> Unit,
    onWindowChange: (ReaderTheme, Boolean) -> Unit,
    slides: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by settingsStore.settings.collectAsStateWithLifecycle()
    val theme = settings.theme

    Box(Modifier.fillMaxSize().background(theme.background)) {
        when (val s = state) {
            PdfReaderViewModel.State.Loading -> Unit
            is PdfReaderViewModel.State.Failed -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(s.message, color = theme.content, textAlign = TextAlign.Center)
                TextButton(onClick = onClose) { Text("Back to library") }
            }
            is PdfReaderViewModel.State.Ready ->
                PdfReader(s, viewModel, settingsStore, theme, settings.pageDirection, onClose, onWindowChange, slides)
        }
        RewardOverlay()
    }
}

@Composable
private fun PdfReader(
    ready: PdfReaderViewModel.State.Ready,
    viewModel: PdfReaderViewModel,
    settingsStore: ReaderSettingsStore,
    theme: ReaderTheme,
    pageDirection: PageDirection,
    onClose: () -> Unit,
    onWindowChange: (ReaderTheme, Boolean) -> Unit,
    slides: Boolean,
) {
    val document = ready.document
    val unit = if (slides) "Slide" else "Page"
    val pageCount = document.pageCount
    val pagerState = rememberPagerState(initialPage = ready.initialPage) { pageCount }
    val scope = rememberCoroutineScope()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    var chromeVisible by rememberSaveable { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<PdfSheet?>(null) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect {
            zoomed = false
            viewModel.onPageSettled(it, pageCount)
        }
    }
    LaunchedEffect(theme, chromeVisible) { onWindowChange(theme, chromeVisible) }

    fun turn(delta: Int) {
        val target = (pagerState.currentPage + delta).coerceIn(0, pageCount - 1)
        if (target == pagerState.currentPage) return
        scope.launch {
            pagerState.animateScrollToPage(
                target,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            )
        }
    }

    val vertical = pageDirection == PageDirection.VERTICAL

    // Edge taps turn pages: left/right edges side to side, top/bottom edges up and down.
    val onTap by rememberUpdatedState { position: Offset, size: IntSize ->
        val along = if (vertical) position.y / size.height else position.x / size.width
        when {
            chromeVisible -> chromeVisible = false
            zoomed -> chromeVisible = true
            along < 0.25f -> turn(-1)
            along > 0.75f -> turn(+1)
            else -> chromeVisible = true
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // A tap only if the finger barely moved, so a drag across the pager's direction
                // (which nothing consumes) doesn't toggle the controls.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    if ((up.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                        onTap(up.position, size)
                    }
                }
            },
    ) {
        val flingBehavior = PagerDefaults.flingBehavior(
            state = pagerState,
            snapAnimationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        )
        val pageContent: @Composable PagerScope.(Int) -> Unit = { page ->
            PdfPage(
                document = document,
                index = page,
                theme = theme,
                isCurrent = page == pagerState.currentPage,
                onZoomChange = { if (page == pagerState.currentPage) zoomed = it },
                unit = unit,
                // Slides keep their own colours; the theme only colours the space around them.
                recolor = !slides,
            )
        }
        if (vertical) {
            VerticalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                pageSpacing = 12.dp,
                userScrollEnabled = !zoomed,
                key = { it },
                flingBehavior = flingBehavior,
                modifier = Modifier.fillMaxSize(),
                pageContent = pageContent,
            )
        } else {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                pageSpacing = 12.dp,
                userScrollEnabled = !zoomed,
                key = { it },
                flingBehavior = flingBehavior,
                modifier = Modifier.fillMaxSize(),
                pageContent = pageContent,
            )
        }

        val current = pagerState.currentPage
        ReaderChrome(
            visible = chromeVisible,
            theme = theme,
            title = ready.book.title,
            progressLabel = "$unit ${current + 1} of $pageCount",
            pageCount = pageCount,
            currentPage = current,
            previewLabel = { "$unit ${it + 1} of $pageCount" },
            onSeek = { scope.launch { pagerState.scrollToPage(it) } },
            bookmarked = bookmarks.any { it.location == current.toString() },
            onToggleBookmark = { viewModel.toggleBookmark(current, pageCount) },
            onBack = onClose,
            onShowBookmarks = { sheet = PdfSheet.Bookmarks },
            onShowSettings = { sheet = PdfSheet.Settings },
        )
    }

    when (sheet) {
        PdfSheet.Settings -> ReaderSettingsSheet(
            theme = theme,
            onThemeChange = settingsStore::setTheme,
            pageDirection = pageDirection,
            onPageDirectionChange = settingsStore::setPageDirection,
            onDismiss = { sheet = null },
            lockedThemeLevel = LocalContext.current.app.rewards::lockedLevel,
        )
        PdfSheet.Bookmarks -> BookmarksSheet(
            theme = theme,
            bookmarks = bookmarks,
            onSelect = { bookmark ->
                sheet = null
                chromeVisible = false
                bookmark.location.toIntOrNull()?.let { scope.launch { pagerState.scrollToPage(it) } }
            },
            onDelete = viewModel::deleteBookmark,
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

/** Night mode maps white paper to the theme's dark grey and black ink to soft white. */
private val darkPageFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.816f, 0f, 0f, 0f, 226f,
            0f, -0.816f, 0f, 0f, 226f,
            0f, 0f, -0.816f, 0f, 226f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** Sepia multiplies the page by the paper colour, so white turns cream and ink stays dark. */
private val sepiaPageFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.965f, 0f, 0f, 0f, 0f,
            0f, 0.933f, 0f, 0f, 0f,
            0f, 0f, 0.863f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** Same idea as sepia, with a soft blush paper colour. */
private val pinkPageFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.988f, 0f, 0f, 0f, 0f,
            0f, 0.933f, 0f, 0f, 0f,
            0f, 0f, 0.949f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

private fun pageFilter(theme: ReaderTheme): ColorFilter? = when (theme) {
    ReaderTheme.LIGHT -> null
    ReaderTheme.SEPIA -> sepiaPageFilter
    ReaderTheme.PINK -> pinkPageFilter
    ReaderTheme.DARK -> darkPageFilter
    ReaderTheme.MIDNIGHT, ReaderTheme.FOREST, ReaderTheme.AMOLED -> themeFilters.getOrPut(theme) { paperAndInkFilter(theme) }
}

private val themeFilters = mutableMapOf<ReaderTheme, ColorFilter>()

/**
 * Maps white paper to the theme's background and black ink to its text colour, channel by channel,
 * so any theme (light or dark) can recolour a PDF page.
 */
private fun paperAndInkFilter(theme: ReaderTheme): ColorFilter {
    fun row(paper: Float, ink: Float, channel: Int) = FloatArray(5).also {
        it[channel] = paper - ink
        it[4] = ink * 255f
    }
    val bg = theme.background
    val fg = theme.content
    return ColorFilter.colorMatrix(
        ColorMatrix(
            row(bg.red, fg.red, 0) + row(bg.green, fg.green, 1) + row(bg.blue, fg.blue, 2) + floatArrayOf(0f, 0f, 0f, 1f, 0f),
        ),
    )
}

private const val MAX_ZOOM = 5f

@Composable
private fun PdfPage(
    document: PageDocument,
    index: Int,
    theme: ReaderTheme,
    isCurrent: Boolean,
    onZoomChange: (Boolean) -> Unit,
    unit: String,
    recolor: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        val aspect by produceState(document.cachedAspectRatio(index), index) {
            if (value == null) value = document.aspectRatio(index)
        }
        val ratio = aspect ?: return@BoxWithConstraints

        // Fit the whole page on screen.
        val fitWidth = if (ratio > boxWidth / boxHeight) boxWidth else boxHeight * ratio
        val fitHeight = fitWidth / ratio
        val renderWidth = fitWidth.roundToInt().coerceAtLeast(1)

        val bitmap by produceState(document.cached(index, renderWidth), index, renderWidth) {
            // Keep showing the previous bitmap (e.g. before a rotation) until the new size is ready.
            document.render(index, renderWidth)?.let { value = it }
        }

        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        // Bumped whenever the page comes to rest at a new zoom or position.
        var settledGeneration by remember { mutableIntStateOf(0) }
        var transforming by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        fun clamp(value: Offset, atScale: Float): Offset {
            val maxX = max(0f, (fitWidth * atScale - boxWidth) / 2f)
            val maxY = max(0f, (fitHeight * atScale - boxHeight) / 2f)
            return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
        }

        fun resetZoom(animated: Boolean) {
            if (!animated) {
                scale = 1f
                offset = Offset.Zero
                settledGeneration++
                return
            }
            val startScale = scale
            val startOffset = offset
            scope.launch {
                animate(0f, 1f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { t, _ ->
                    scale = lerpFloat(startScale, 1f, t)
                    offset = lerp(startOffset, Offset.Zero, t)
                }
                settledGeneration++
            }
        }

        LaunchedEffect(isCurrent) {
            if (!isCurrent && scale != 1f) resetZoom(animated = false)
        }

        // When zoomed in and still, render just the visible part of the page at screen resolution
        // and lay it over the scaled-up page, so text stays crisp at any zoom without ever
        // allocating a bitmap bigger than the screen (very large bitmaps silently fail to draw).
        val sharp by produceState<SharpRegion?>(null, settledGeneration, renderWidth) {
            val zoom = scale
            if (zoom < 1.05f) {
                value = null
                return@produceState
            }
            val pageLeft = boxWidth / 2f + offset.x - zoom * fitWidth / 2f
            val pageTop = boxHeight / 2f + offset.y - zoom * fitHeight / 2f
            document.renderRegion(
                index = index,
                width = boxWidth.roundToInt().coerceAtLeast(1),
                height = boxHeight.roundToInt().coerceAtLeast(1),
                left = pageLeft,
                top = pageTop,
                pageWidth = fitWidth * zoom,
            )?.let { value = SharpRegion(it.asImageBitmap(), settledGeneration) }
        }

        val image = remember(bitmap) { bitmap?.asImageBitmap() }
        val density = LocalDensity.current

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(index, fitWidth, fitHeight) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var transformed = false
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            // One finger at 1x belongs to the pager; pinches and zoomed pans are ours.
                            // The lift event has no pressed fingers, so no centroid: skip it, or the
                            // offset becomes NaN and the page vanishes.
                            if (fingers >= 1 && (fingers >= 2 || scale > 1.01f)) {
                                transformed = true
                                transforming = true
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val newScale = (scale * zoom).coerceIn(0.85f, MAX_ZOOM)
                                val k = newScale / scale
                                val focus = centroid - Offset(boxWidth / 2f, boxHeight / 2f)
                                offset = clamp(offset * k + focus * (1f - k) + pan, newScale)
                                scale = newScale
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })

                        if (transformed) {
                            transforming = false
                            if (scale < 1.05f) {
                                resetZoom(animated = true)
                                onZoomChange(false)
                            } else {
                                settledGeneration++
                                onZoomChange(true)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(with(density) { fitWidth.toDp() }, with(density) { fitHeight.toDp() })
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .background(theme.background),
            ) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = "$unit ${index + 1}",
                        contentScale = ContentScale.FillBounds,
                        colorFilter = if (recolor) pageFilter(theme) else null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Only while at rest and matching the current zoom; during a gesture the scaled page shows.
            val region = sharp
            if (region != null && region.generation == settledGeneration && !transforming && scale > 1.01f) {
                Image(
                    bitmap = region.bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    colorFilter = if (recolor) pageFilter(theme) else null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private class SharpRegion(val bitmap: ImageBitmap, val generation: Int)
