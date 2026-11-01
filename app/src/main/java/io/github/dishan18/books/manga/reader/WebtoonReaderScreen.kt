package io.github.dishan18.books.manga.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

private val ChromeColor = Color(0xF2151515)
private val ChromeContent = Color(0xFFEDEDED)

@Composable
fun WebtoonReaderScreen(viewModel: WebtoonViewModel, onBack: () -> Unit, onChromeChange: (Boolean) -> Unit) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val series by viewModel.series.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()
    val pending by viewModel.pendingScroll.collectAsStateWithLifecycle()
    val fatalError by viewModel.fatalError.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var chromeVisible by rememberSaveable { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(chromeVisible) { onChromeChange(chromeVisible) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        LaunchedEffect(width, height) { viewModel.onViewport(width, height) }

        if (fatalError != null) {
            ReaderMessage(fatalError!!, actionLabel = "Back", onAction = onBack)
            return@BoxWithConstraints
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = ChromeContent) }
        }

        // Restore the saved position, re-applying it once the target page has its real height.
        LaunchedEffect(pending, items) {
            val target = pending ?: return@LaunchedEffect
            val (index, offset, exact) = viewModel.resolve(target, items) ?: return@LaunchedEffect
            listState.scrollToItem(index, offset)
            if (exact) viewModel.consumePendingScroll()
        }
        // The reader's own scrolling wins over a restore that's still waiting for its page.
        LaunchedEffect(listState) {
            listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) viewModel.cancelPendingScroll() }
        }
        LaunchedEffect(listState) {
            snapshotFlow {
                Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0)
            }.collect { (first, offset, last) -> viewModel.onScroll(first, offset, last) }
        }
        LaunchedEffect(Unit) {
            viewModel.scrollEvents.collect { distance ->
                viewModel.cancelPendingScroll()
                listState.animateScrollBy(distance / scale, spring(stiffness = Spring.StiffnessMediumLow))
            }
        }

        val onTap by rememberUpdatedState { y: Float ->
            when {
                chromeVisible -> chromeVisible = false
                y < height * 0.3f -> viewModel.scrollBy(-0.75f)
                y > height * 0.7f -> viewModel.scrollBy(0.75f)
                else -> chromeVisible = true
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // Pinch to zoom (1x–3x) and pan sideways while zoomed; vertical scrolling stays with the list.
                .pointerInput(width) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2) {
                                val zoom = event.calculateZoom()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val newScale = (scale * zoom).coerceIn(1f, 3f)
                                if (centroid != Offset.Unspecified) {
                                    val focus = centroid.x - width / 2f
                                    offsetX = clampPan(offsetX * (newScale / scale) + focus * (1 - newScale / scale) + event.calculatePan().x, newScale, width)
                                }
                                scale = newScale
                                event.changes.forEach { it.consume() }
                            } else if (fingers == 1 && scale > 1f) {
                                val dx = event.changes.first().positionChange().x
                                offsetX = clampPan(offsetX + dx, scale, width)
                            }
                        } while (event.changes.any { it.pressed })
                        if (scale < 1.05f) {
                            scale = 1f
                            offsetX = 0f
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        if ((up.position - down.position).getDistance() < viewConfiguration.touchSlop) onTap(up.position.y)
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                },
        ) {
            items(items, key = { it.key }, contentType = { it::class }) { item ->
                when (item) {
                    is ReaderItem.Header -> ChapterHeader(item)
                    is ReaderItem.Placeholder -> PagePlaceholder(item, onRetry = { viewModel.retry(item.page) })
                    is ReaderItem.PageStrip -> PageStripImage(item, viewModel.decoder)
                    is ReaderItem.End -> ChapterEnd(
                        state = item.state,
                        onRetry = viewModel::retryNextChapter,
                        onBack = onBack,
                    )
                }
            }
        }

        ReaderControls(
            visible = chromeVisible,
            title = series?.title.orEmpty(),
            position = position,
            onBack = onBack,
            onSeek = { viewModel.jumpToPage(it) },
            onPrevious = {
                scope.launch { viewModel.previousChapter()?.let(viewModel::openChapter) }
            },
            onNext = {
                scope.launch { viewModel.nextChapter()?.let(viewModel::openChapter) }
            },
        )
    }
}

private fun clampPan(value: Float, scale: Float, width: Int): Float {
    val max = (scale - 1f) * width / 2f
    return value.coerceIn(-max, max)
}

@Composable
private fun ChapterHeader(item: ReaderItem.Header) {
    Text(
        item.chapter.name,
        color = Color(0xFF9A9A9A),
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp),
    )
}

@Composable
private fun PagePlaceholder(item: ReaderItem.Placeholder, onRetry: () -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxWidth().height(with(density) { item.height.toDp() }),
        contentAlignment = Alignment.Center,
    ) {
        when (val status = item.page.status) {
            is PageStatus.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Page ${item.page.index + 1} didn't load", color = ChromeContent, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(status.message, color = Color(0xFF9A9A9A), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onRetry) { Text("Retry", color = ChromeContent) }
            }
            is PageStatus.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (status.progress in 0f..1f) {
                    CircularProgressIndicator(progress = { status.progress }, color = ChromeContent, trackColor = Color(0x33FFFFFF))
                } else {
                    CircularProgressIndicator(color = ChromeContent)
                }
                Spacer(Modifier.height(10.dp))
                Text("${item.page.index + 1}", color = Color(0xFF7A7A7A), style = MaterialTheme.typography.labelMedium)
            }
            else -> Text("${item.page.index + 1}", color = Color(0xFF5A5A5A), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PageStripImage(item: ReaderItem.PageStrip, decoder: StripDecoder) {
    val density = LocalDensity.current
    val key = "${item.file.path}#${item.strip.top}-${item.strip.bottom}@${item.sampleSize}"
    val bitmap by produceState(decoder.cached(key), key) {
        if (value == null) value = decoder.decode(key, item.file, item.imageWidth, item.strip, item.sampleSize)
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Box(Modifier.fillMaxWidth().height(with(density) { item.strip.displayHeight.toDp() })) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = if (item.stripIndex == 0) "Page ${item.page.index + 1}" else null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ChapterEnd(state: EndState, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            EndState.Loading, is EndState.NextReady -> {
                CircularProgressIndicator(color = ChromeContent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    if (state is EndState.NextReady) "Up next: ${state.next.name}" else "Loading next chapter",
                    color = Color(0xFF9A9A9A),
                    textAlign = TextAlign.Center,
                )
            }
            EndState.NoMore -> {
                Text("You're all caught up", color = ChromeContent, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text("Back to series") }
            }
            is EndState.Failed -> {
                Text("Couldn't load the next chapter", color = ChromeContent, style = MaterialTheme.typography.titleSmall)
                Text(state.message, color = Color(0xFF9A9A9A), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onRetry) { Text("Retry", color = ChromeContent) }
            }
        }
    }
}

@Composable
private fun ReaderMessage(message: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = ChromeContent, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun ReaderControls(
    visible: Boolean,
    title: String,
    position: ReaderPosition?,
    onBack: () -> Unit,
    onSeek: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.fillMaxWidth().background(ChromeColor).pointerInput(Unit) { detectTapGestures { } }
                    .statusBarsPadding().height(60.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = ChromeContent)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, color = ChromeContent, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    position?.let {
                        Text(it.chapter.name, color = Color(0xFFA0A0A0), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = visible && position != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val current = position ?: return@AnimatedVisibility
            var dragging by remember(current.chapter.id) { mutableStateOf<Float?>(null) }
            val shown = dragging?.roundToInt() ?: current.page
            Column(
                Modifier.fillMaxWidth().background(ChromeColor).pointerInput(Unit) { detectTapGestures { } }
                    .navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious) {
                        Icon(Icons.Outlined.SkipPrevious, contentDescription = "Previous chapter", tint = ChromeContent)
                    }
                    if (current.pageCount > 1) {
                        Slider(
                            value = dragging ?: current.page.toFloat(),
                            onValueChange = {
                                if (it.roundToInt() != shown) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                dragging = it
                            },
                            onValueChangeFinished = {
                                dragging?.let { onSeek(it.roundToInt()) }
                                dragging = null
                            },
                            valueRange = 0f..max(1, current.pageCount - 1).toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = ChromeContent,
                                activeTrackColor = ChromeContent.copy(alpha = 0.8f),
                                inactiveTrackColor = ChromeContent.copy(alpha = 0.2f),
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    IconButton(onClick = onNext) {
                        Icon(Icons.Outlined.SkipNext, contentDescription = "Next chapter", tint = ChromeContent)
                    }
                }
                Text(
                    "Page ${shown + 1} of ${current.pageCount}",
                    color = Color(0xFFB0B0B0),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.width(1.dp))
            }
        }
    }
}
