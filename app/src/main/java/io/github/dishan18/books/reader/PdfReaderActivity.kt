package io.github.dishan18.books.reader

import android.graphics.Bitmap
import android.os.Bundle
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dishan18.books.app
import io.github.dishan18.books.data.ReaderSettingsStore
import io.github.dishan18.books.data.ReaderTheme
import io.github.dishan18.books.ui.theme.BooksTheme
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.util.lerp as lerpFloat

class PdfReaderActivity : ComponentActivity() {

    private val viewModel: PdfReaderViewModel by viewModels {
        viewModelFactory {
            initializer { PdfReaderViewModel(intent.getStringExtra(EXTRA_BOOK_ID).orEmpty(), app.repository) }
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
}

private enum class PdfSheet { Settings, Bookmarks }

@Composable
private fun PdfReaderScreen(
    viewModel: PdfReaderViewModel,
    settingsStore: ReaderSettingsStore,
    onClose: () -> Unit,
    onWindowChange: (ReaderTheme, Boolean) -> Unit,
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
            is PdfReaderViewModel.State.Ready -> PdfReader(s, viewModel, settingsStore, theme, onClose, onWindowChange)
        }
    }
}

@Composable
private fun PdfReader(
    ready: PdfReaderViewModel.State.Ready,
    viewModel: PdfReaderViewModel,
    settingsStore: ReaderSettingsStore,
    theme: ReaderTheme,
    onClose: () -> Unit,
    onWindowChange: (ReaderTheme, Boolean) -> Unit,
) {
    val document = ready.document
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

    val onTap by rememberUpdatedState { position: Offset, width: Int ->
        when {
            chromeVisible -> chromeVisible = false
            zoomed -> chromeVisible = true
            position.x < width * 0.25f -> turn(-1)
            position.x > width * 0.75f -> turn(+1)
            else -> chromeVisible = true
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onTap(it, size.width) } },
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            pageSpacing = 12.dp,
            userScrollEnabled = !zoomed,
            key = { it },
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                snapAnimationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            ),
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            PdfPage(
                document = document,
                index = page,
                theme = theme,
                isCurrent = page == pagerState.currentPage,
                onZoomChange = { if (page == pagerState.currentPage) zoomed = it },
            )
        }

        val current = pagerState.currentPage
        ReaderChrome(
            visible = chromeVisible,
            theme = theme,
            title = ready.book.title,
            progressLabel = "Page ${current + 1} of $pageCount",
            pageCount = pageCount,
            currentPage = current,
            previewLabel = { "Page ${it + 1} of $pageCount" },
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
            onDismiss = { sheet = null },
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

private fun pageFilter(theme: ReaderTheme): ColorFilter? = when (theme) {
    ReaderTheme.LIGHT -> null
    ReaderTheme.SEPIA -> sepiaPageFilter
    ReaderTheme.DARK -> darkPageFilter
}

private const val MAX_ZOOM = 5f
private const val SHARP_RENDER_LIMIT = 2.5f

@Composable
private fun PdfPage(
    document: PdfDocument,
    index: Int,
    theme: ReaderTheme,
    isCurrent: Boolean,
    onZoomChange: (Boolean) -> Unit,
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
        var settledScale by remember { mutableFloatStateOf(1f) }
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
                settledScale = 1f
                return
            }
            val startScale = scale
            val startOffset = offset
            scope.launch {
                animate(0f, 1f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { t, _ ->
                    scale = lerpFloat(startScale, 1f, t)
                    offset = lerp(startOffset, Offset.Zero, t)
                }
                settledScale = 1f
            }
        }

        LaunchedEffect(isCurrent) {
            if (!isCurrent && scale != 1f) resetZoom(animated = false)
        }

        // When zoomed in, re-render this page at a higher resolution so text stays crisp.
        val sharp by produceState<Bitmap?>(null, settledScale, renderWidth) {
            value = if (settledScale > 1.3f) {
                val width = (renderWidth * min(settledScale, SHARP_RENDER_LIMIT)).roundToInt()
                document.render(index, width, keep = false)
            } else {
                null
            }
        }

        val shown = sharp ?: bitmap
        val image = remember(shown) { shown?.asImageBitmap() }
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
                            if (fingers >= 2 || scale > 1.01f) {
                                transformed = true
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
                            if (scale < 1.05f) {
                                resetZoom(animated = true)
                                onZoomChange(false)
                            } else {
                                settledScale = scale
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
                        contentDescription = "Page ${index + 1}",
                        contentScale = ContentScale.FillBounds,
                        colorFilter = pageFilter(theme),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
