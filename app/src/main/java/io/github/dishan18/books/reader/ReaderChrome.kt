package io.github.dishan18.books.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dishan18.books.data.Bookmark
import io.github.dishan18.books.data.PageDirection
import io.github.dishan18.books.data.ReaderTheme
import kotlin.math.roundToInt

private val chromeEnter = spring<androidx.compose.ui.unit.IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * The bars that slide in over the page when you tap the middle of the screen. Draws nothing and
 * takes no touches while hidden, so it can sit on top of any reader.
 */
@Composable
fun ReaderChrome(
    visible: Boolean,
    theme: ReaderTheme,
    title: String,
    progressLabel: String,
    pageCount: Int,
    currentPage: Int,
    previewLabel: (Int) -> String,
    onSeek: (Int) -> Unit,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onBack: () -> Unit,
    onShowBookmarks: () -> Unit,
    onShowSettings: () -> Unit,
    onShowContents: (() -> Unit)? = null,
) {
    CompositionLocalProvider(LocalContentColor provides theme.content) {
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(chromeEnter) { -it } + fadeIn(),
                exit = slideOutVertically(chromeEnter) { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopBar(theme, title, bookmarked, onToggleBookmark, onBack, onShowBookmarks, onShowSettings, onShowContents)
            }
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(chromeEnter) { it } + fadeIn(),
                exit = slideOutVertically(chromeEnter) { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                BottomBar(theme, progressLabel, pageCount, currentPage, previewLabel, onSeek)
            }
        }
    }
}

/** Stops taps on a bar's empty space from falling through to the page underneath. */
private fun Modifier.swallowTaps() = pointerInput(Unit) { detectTapGestures { } }

@Composable
private fun TopBar(
    theme: ReaderTheme,
    title: String,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onBack: () -> Unit,
    onShowBookmarks: () -> Unit,
    onShowSettings: () -> Unit,
    onShowContents: (() -> Unit)?,
) {
    val haptic = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().swallowTaps().background(theme.chrome)) {
        Row(
            Modifier
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to library")
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            if (onShowContents != null) {
                IconButton(onClick = onShowContents) {
                    Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "Contents")
                }
            }
            IconButton(onClick = onShowBookmarks) {
                Icon(Icons.Outlined.Bookmarks, contentDescription = "Bookmarks")
            }
            IconButton(onClick = onShowSettings) {
                Text("Aa", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
            IconButton(onClick = {
                haptic.performHapticFeedback(if (bookmarked) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
                onToggleBookmark()
            }) {
                Icon(
                    if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = if (bookmarked) "Remove bookmark" else "Add bookmark",
                    tint = if (bookmarked) Color(0xFFE5483B) else LocalContentColor.current,
                )
            }
        }
        HorizontalDivider(color = theme.content.copy(alpha = 0.08f))
    }
}

@Composable
private fun BottomBar(
    theme: ReaderTheme,
    progressLabel: String,
    pageCount: Int,
    currentPage: Int,
    previewLabel: (Int) -> String,
    onSeek: (Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shownPage = dragging?.roundToInt() ?: currentPage

    Column(Modifier.fillMaxWidth().swallowTaps().background(theme.chrome)) {
        HorizontalDivider(color = theme.content.copy(alpha = 0.08f))
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (pageCount > 1) {
                Slider(
                    value = dragging ?: currentPage.toFloat(),
                    onValueChange = {
                        if (it.roundToInt() != shownPage) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        dragging = it
                    },
                    onValueChangeFinished = {
                        dragging?.let { onSeek(it.roundToInt()) }
                        dragging = null
                    },
                    valueRange = 0f..(pageCount - 1).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = theme.content,
                        activeTrackColor = theme.content.copy(alpha = 0.75f),
                        inactiveTrackColor = theme.content.copy(alpha = 0.15f),
                    ),
                )
            }
            Text(
                if (dragging != null) previewLabel(shownPage) else progressLabel,
                style = MaterialTheme.typography.labelMedium,
                color = theme.content.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReaderSheet(theme: ReaderTheme, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = theme.chrome,
        contentColor = theme.content,
    ) {
        CompositionLocalProvider(LocalContentColor provides theme.content) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) { content() }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
    )
}

/** Theme picker, plus text size for EPUBs (PDF pages have a fixed layout). */
@Composable
fun ReaderSettingsSheet(
    theme: ReaderTheme,
    onThemeChange: (ReaderTheme) -> Unit,
    pageDirection: PageDirection,
    onPageDirectionChange: (PageDirection) -> Unit,
    onDismiss: () -> Unit,
    fontScale: Double? = null,
    onFontScaleChange: ((Double) -> Unit)? = null,
) {
    ReaderSheet(theme, onDismiss) {
        SheetTitle("Themes & Settings")
        if (fontScale != null && onFontScaleChange != null) {
            Row(
                Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(theme.content.copy(alpha = 0.06f))
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.weight(1f).fillMaxSize().clickable { onFontScaleChange(fontScale - 0.1) },
                    contentAlignment = Alignment.Center,
                ) { Text("A", fontSize = 15.sp, fontFamily = FontFamily.Serif) }
                Text(
                    "${(fontScale * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.width(64.dp),
                    textAlign = TextAlign.Center,
                )
                Box(
                    Modifier.weight(1f).fillMaxSize().clickable { onFontScaleChange(fontScale + 0.1) },
                    contentAlignment = Alignment.Center,
                ) { Text("A", fontSize = 24.sp, fontFamily = FontFamily.Serif) }
            }
            Spacer(Modifier.height(20.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ReaderTheme.entries.forEach { option ->
                val ring by animateDpAsState(if (option == theme) 3.dp else 1.dp, label = "ring")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(option.background)
                            .border(
                                ring,
                                if (option == theme) Color(0xFF0A84FF) else theme.content.copy(alpha = 0.2f),
                                CircleShape,
                            )
                            .clickable { onThemeChange(option) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Aa", color = option.content, fontFamily = FontFamily.Serif, fontSize = 20.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        option.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Page turning",
            style = MaterialTheme.typography.labelLarge,
            color = theme.content.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(theme.content.copy(alpha = 0.06f))
                .padding(4.dp),
        ) {
            PageDirection.entries.forEach { option ->
                val selected = option == pageDirection
                Row(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) theme.background else Color.Transparent)
                        .clickable { onPageDirectionChange(option) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (option == PageDirection.HORIZONTAL) Icons.Outlined.SwapHoriz else Icons.Outlined.SwapVert,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (option == PageDirection.HORIZONTAL) "Side to side" else "Up and down",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
fun BookmarksSheet(
    theme: ReaderTheme,
    bookmarks: List<Bookmark>,
    onSelect: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    ReaderSheet(theme, onDismiss) {
        SheetTitle("Bookmarks")
        if (bookmarks.isEmpty()) {
            Text(
                "Tap the bookmark at the top of a page to save your spot.",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.content.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
        LazyColumn {
            items(bookmarks, key = { it.id }) { bookmark ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(bookmark) }
                        .padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Bookmark, contentDescription = null, tint = Color(0xFFE5483B))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bookmark.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${(bookmark.progress * 100).roundToInt()}% through",
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.content.copy(alpha = 0.6f),
                        )
                    }
                    IconButton(onClick = { onDelete(bookmark) }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Delete bookmark", tint = theme.content.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

data class ContentsEntry(val title: String, val depth: Int)

@Composable
fun ContentsSheet(
    theme: ReaderTheme,
    entries: List<ContentsEntry>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ReaderSheet(theme, onDismiss) {
        SheetTitle("Contents")
        LazyColumn {
            itemsIndexed(entries) { index, entry ->
                Text(
                    entry.title,
                    style = if (entry.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    color = if (entry.depth == 0) theme.content else theme.content.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .padding(start = 24.dp + 16.dp * entry.depth, end = 24.dp, top = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}
