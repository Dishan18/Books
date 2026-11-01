package io.github.dishan18.books.year

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.manga.ui.common.MangaCover
import io.github.dishan18.books.ui.BookCover
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as DateTextStyle
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class Story(val colors: List<Color>) {
    INTRO(listOf(Color(0xFF1B1033), Color(0xFF5B2A86))),
    TIME(listOf(Color(0xFF0F2A4A), Color(0xFF1F7A8C))),
    BOOKS(listOf(Color(0xFF3B1D14), Color(0xFFC2552B))),
    PAGES(listOf(Color(0xFF12263A), Color(0xFF2E6E58))),
    MANGA(listOf(Color(0xFF2A0E2E), Color(0xFFB03A6F))),
    FAVOURITES(listOf(Color(0xFF1F1B2E), Color(0xFF6C4AB6))),
    GENRES(listOf(Color(0xFF10302B), Color(0xFF2F8F6B))),
    STREAK(listOf(Color(0xFF3A1500), Color(0xFFE8681A))),
    MONTHS(listOf(Color(0xFF101820), Color(0xFF3A5A80))),
    TIME_OF_DAY(listOf(Color(0xFF0B1026), Color(0xFF343A87))),
    SUMMARY(listOf(Color(0xFF140D22), Color(0xFF2B1B3D))),
}

private fun storiesFor(recap: YearRecap): List<Story> = Story.entries.filter { story ->
    when (story) {
        Story.INTRO, Story.SUMMARY -> true
        Story.TIME -> recap.totalSeconds >= 60
        Story.BOOKS -> recap.booksFinished.isNotEmpty()
        Story.PAGES -> recap.pagesTurned > 0
        Story.MANGA -> recap.chaptersRead > 0
        Story.FAVOURITES -> recap.topBook != null || recap.topSeries != null
        Story.GENRES -> recap.topGenres.isNotEmpty()
        Story.STREAK -> recap.longestStreak > 0
        Story.MONTHS -> recap.bestMonth != null
        Story.TIME_OF_DAY -> recap.timeOfDay != null
    }
}

private val White70 = Color.White.copy(alpha = 0.72f)
private val Big = TextStyle(fontSize = 76.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp, lineHeight = 80.sp, color = Color.White)
private val Lead = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp, color = Color.White)
private val Body = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, color = White70)

@Composable
fun YearInBooksScreen(viewModel: YearInBooksViewModel, onClose: () -> Unit, onShare: (Bitmap, Int) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recap = state.recap
    Box(Modifier.fillMaxSize().background(Story.INTRO.colors.first())) {
        when {
            recap == null -> CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            recap.isEmpty -> EmptyYear(state, onClose, viewModel::select)
            // A new year starts the story over from its first page.
            else -> key(recap.year) { Stories(state, recap, onClose, onShare, viewModel::select) }
        }
    }
}

@Composable
private fun Stories(
    state: YearInBooksViewModel.State,
    recap: YearRecap,
    onClose: () -> Unit,
    onShare: (Bitmap, Int) -> Unit,
    onSelectYear: (Int) -> Unit,
) {
    val stories = remember(recap) { storiesFor(recap) }
    val pager = rememberPagerState { stories.size }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.drop(1).collect { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                // Tap the left third to go back, anywhere else to go on, like stories.
                .pointerInput(stories.size) {
                    detectTapGestures { offset ->
                        val target = if (offset.x < size.width * 0.3f) pager.currentPage - 1 else pager.currentPage + 1
                        if (target in stories.indices) scope.launch { pager.animateScrollToPage(target) }
                    }
                },
        ) { index ->
            val story = stories[index]
            val active = pager.settledPage == index
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(story.colors))) {
                when (story) {
                    Story.INTRO -> IntroPage(state, recap, active, onSelectYear)
                    Story.TIME -> TimePage(recap, active)
                    Story.BOOKS -> BooksPage(recap, state.books, active)
                    Story.PAGES -> PagesPage(recap, active)
                    Story.MANGA -> MangaPage(recap, state, active)
                    Story.FAVOURITES -> FavouritesPage(recap, state, active)
                    Story.GENRES -> GenresPage(recap, active)
                    Story.STREAK -> StreakPage(recap, active)
                    Story.MONTHS -> MonthsPage(recap, active)
                    Story.TIME_OF_DAY -> TimeOfDayPage(recap, active)
                    Story.SUMMARY -> SummaryPage(recap, active, onShare = { bitmap ->
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onShare(bitmap, recap.year)
                    }, onDone = onClose)
                }
            }
        }

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                stories.indices.forEach { i ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(if (i <= pager.currentPage) Color.White else Color.White.copy(alpha = 0.28f)),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Books", color = White70, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 12.dp).weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White) }
            }
        }
    }
}

/** Fades and lifts [content] in when its page comes to rest. */
@Composable
private fun Reveal(active: Boolean, delay: Int = 0, content: @Composable () -> Unit) {
    val progress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = if (active) 650 else 150, delayMillis = if (active) delay else 0, easing = FastOutSlowInEasing),
        label = "reveal",
    )
    Box(
        Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 32.dp.toPx()
        },
    ) { content() }
}

/** A number that counts up from zero each time its page comes to rest. */
@Composable
private fun CountUp(target: Long, active: Boolean, style: TextStyle = Big, format: (Long) -> String = ::formatNumber) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(active, target) {
        if (active) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(1_100, delayMillis = 150, easing = FastOutSlowInEasing))
        }
    }
    Text(format((target * progress.value).roundToLong()), style = style, maxLines = 1)
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 32.dp, vertical = 72.dp),
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun IntroPage(state: YearInBooksViewModel.State, recap: YearRecap, active: Boolean, onSelectYear: (Int) -> Unit) {
    PageColumn {
        Reveal(active) { Text("Your", style = Lead) }
        Reveal(active, 120) { Text("${recap.year}", style = Big.copy(fontSize = 104.sp, lineHeight = 108.sp)) }
        Reveal(active, 240) { Text("in Books", style = Lead.copy(fontSize = 40.sp, lineHeight = 46.sp)) }
        Spacer(Modifier.height(20.dp))
        Reveal(active, 420) {
            Text(
                recap.countingSince?.let { "Counting from ${shortDate(it)}, when Books started keeping track." }
                    ?: "A look back at everything you read this year.",
                style = Body,
            )
        }
        if (state.years.size > 1) {
            Spacer(Modifier.height(28.dp))
            YearChips(state.years, state.year, onSelectYear)
        }
        Spacer(Modifier.height(48.dp))
        Reveal(active, 700) { Text("Tap to begin", style = Body.copy(fontSize = 14.sp)) }
    }
}

@Composable
private fun YearChips(years: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        years.forEach { year ->
            val on = year == selected
            Text(
                "$year",
                color = if (on) Color.Black else Color.White,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (on) Color.White else Color.White.copy(alpha = 0.14f))
                    .clickable(enabled = !on) { onSelect(year) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TimePage(recap: YearRecap, active: Boolean) {
    PageColumn {
        Reveal(active) { Text("You spent", style = Lead) }
        CountUp(recap.totalSeconds, active, format = ::formatDuration)
        Reveal(active, 200) { Text("reading this year", style = Lead) }
        Spacer(Modifier.height(24.dp))
        Reveal(active, 500) {
            val perDay = if (recap.daysRead > 0) recap.totalSeconds / recap.daysRead / 60 else 0
            Text(
                "Across ${plural(recap.daysRead, "day")}, about ${plural(perDay.toInt(), "minute")} on each day you read.",
                style = Body,
            )
        }
    }
}

@Composable
private fun BooksPage(recap: YearRecap, books: Map<String, Book>, active: Boolean) {
    val finished = recap.booksFinished
    PageColumn {
        Reveal(active) { Text("You finished", style = Lead) }
        CountUp(finished.size.toLong(), active)
        Reveal(active, 200) { Text(if (finished.size == 1) "book" else "books", style = Lead) }
        val covers = finished.mapNotNull { books[it.itemId] }.takeLast(5)
        if (covers.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Reveal(active, 450) {
                Row(horizontalArrangement = Arrangement.spacedBy((-14).dp)) {
                    covers.forEachIndexed { i, book ->
                        BookCover(book, Modifier.width(76.dp).aspectRatio(2f / 3f).graphicsLayer { rotationZ = (i - covers.size / 2) * 4f }, elevation = 10f)
                    }
                }
            }
        }
        finished.filter { it.pages > 0 }.maxByOrNull { it.pages }?.takeIf { finished.size > 1 }?.let { longest ->
            Spacer(Modifier.height(24.dp))
            Reveal(active, 650) { Text("The longest was ${longest.title}, at ${formatNumber(longest.pages.toLong())} pages.", style = Body) }
        }
    }
}

@Composable
private fun PagesPage(recap: YearRecap, active: Boolean) {
    PageColumn {
        Reveal(active) { Text("You turned", style = Lead) }
        CountUp(recap.pagesTurned.toLong(), active)
        Reveal(active, 200) { Text("pages", style = Lead) }
        recap.pagesSince?.let {
            Spacer(Modifier.height(24.dp))
            Reveal(active, 500) { Text("Counted since ${shortDate(it)}, when Books started counting pages.", style = Body) }
        }
    }
}

@Composable
private fun MangaPage(recap: YearRecap, state: YearInBooksViewModel.State, active: Boolean) {
    PageColumn {
        Reveal(active) { Text("You read", style = Lead) }
        CountUp(recap.chaptersRead.toLong(), active)
        Reveal(active, 200) { Text(if (recap.chaptersRead == 1) "manga chapter" else "manga chapters", style = Lead) }
        if (recap.seriesCompleted > 0) {
            Spacer(Modifier.height(16.dp))
            Reveal(active, 400) { Text("and read every chapter of ${plural(recap.seriesCompleted, "finished series", "finished series")}.", style = Body) }
        }
        state.topSeries?.let { series ->
            Spacer(Modifier.height(28.dp))
            Reveal(active, 600) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MangaCover(series.coverUrl, series.sourceId, series.title, Modifier.width(64.dp).aspectRatio(2f / 3f), elevation = 8f)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Most read", style = Body.copy(fontSize = 14.sp))
                        Text(series.title, style = Lead.copy(fontSize = 20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun FavouritesPage(recap: YearRecap, state: YearInBooksViewModel.State, active: Boolean) {
    PageColumn {
        Reveal(active) { Text("Your favourites", style = Lead.copy(fontSize = 34.sp, lineHeight = 40.sp)) }
        Spacer(Modifier.height(8.dp))
        Reveal(active, 100) { Text("By time spent reading.", style = Body) }
        Spacer(Modifier.height(32.dp))
        recap.topBook?.let { item ->
            Reveal(active, 300) {
                FavouriteRow("Book", item.title, item.seconds) {
                    val book = state.books[item.itemId]
                    if (book != null) BookCover(book, Modifier.fillMaxSize(), elevation = 8f) else CoverBlank()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        recap.topSeries?.let { item ->
            Reveal(active, 500) {
                FavouriteRow("Manga", item.title, item.seconds) {
                    val series = state.topSeries
                    if (series != null) MangaCover(series.coverUrl, series.sourceId, series.title, Modifier.fillMaxSize(), elevation = 8f) else CoverBlank()
                }
            }
        }
    }
}

@Composable
private fun CoverBlank() {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.15f)))
}

@Composable
private fun FavouriteRow(label: String, title: String, seconds: Long, cover: @Composable BoxScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(68.dp).aspectRatio(2f / 3f), content = cover)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(label.uppercase(), style = Body.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
            Text(title, style = Lead.copy(fontSize = 21.sp, lineHeight = 26.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(formatDuration(seconds), style = Body.copy(fontSize = 15.sp))
        }
    }
}

@Composable
private fun GenresPage(recap: YearRecap, active: Boolean) {
    val top = recap.topGenres.firstOrNull()?.second?.coerceAtLeast(1) ?: 1
    PageColumn {
        Reveal(active) { Text("What you read most", style = Lead.copy(fontSize = 34.sp, lineHeight = 40.sp)) }
        Spacer(Modifier.height(32.dp))
        recap.topGenres.forEachIndexed { i, (genre, seconds) ->
            Reveal(active, 200 + i * 180) {
                Column(Modifier.padding(bottom = 22.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${i + 1}", style = Lead.copy(fontSize = 28.sp, fontWeight = FontWeight.Black), modifier = Modifier.width(36.dp))
                        Text(genre, style = Lead.copy(fontSize = 22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.padding(start = 36.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
                        Box(Modifier.fillMaxWidth(seconds.toFloat() / top).fillMaxHeight().clip(CircleShape).background(Color.White))
                    }
                }
            }
        }
        Reveal(active, 800) { Text("From the subjects and genres of what you read, weighted by time.", style = Body.copy(fontSize = 14.sp)) }
    }
}

@Composable
private fun StreakPage(recap: YearRecap, active: Boolean) {
    PageColumn {
        Reveal(active) { Text("🔥", fontSize = 56.sp) }
        Spacer(Modifier.height(8.dp))
        Reveal(active, 100) { Text("Your longest streak", style = Lead) }
        CountUp(recap.longestStreak.toLong(), active)
        Reveal(active, 200) { Text(if (recap.longestStreak == 1) "day" else "days in a row", style = Lead) }
        Spacer(Modifier.height(24.dp))
        Reveal(active, 500) { Text("You reached your daily goal on ${plural(recap.goalDays, "day")} this year.", style = Body) }
    }
}

@Composable
private fun MonthsPage(recap: YearRecap, active: Boolean) {
    val best = recap.bestMonth ?: return
    val max = recap.monthMinutes.max().coerceAtLeast(1)
    val grow by animateFloatAsState(if (active) 1f else 0f, tween(900, delayMillis = 250, easing = FastOutSlowInEasing), label = "bars")
    val locale = LocalConfiguration.current.locales[0]
    PageColumn {
        Reveal(active) { Text("Month by month", style = Lead.copy(fontSize = 34.sp, lineHeight = 40.sp)) }
        Spacer(Modifier.height(36.dp))
        Row(Modifier.fillMaxWidth().height(200.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            recap.monthMinutes.forEachIndexed { i, minutes ->
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight((minutes.toFloat() / max * grow).coerceAtLeast(0.02f) * 0.88f)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (i == best) Color(0xFFFFB45C) else Color.White.copy(alpha = 0.55f)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(Month.of(i + 1).getDisplayName(DateTextStyle.NARROW, locale), style = Body.copy(fontSize = 12.sp))
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Reveal(active, 600) {
            val name = Month.of(best + 1).getDisplayName(DateTextStyle.FULL, locale)
            Text("$name was your biggest month, with ${formatDuration(recap.monthMinutes[best] * 60L)} of reading.", style = Body)
        }
        recap.busiestDay?.let { (date, seconds) ->
            Spacer(Modifier.height(12.dp))
            Reveal(active, 800) { Text("Your biggest day was ${shortDate(date)}: ${formatDuration(seconds)}.", style = Body) }
        }
    }
}

@Composable
private fun TimeOfDayPage(recap: YearRecap, active: Boolean) {
    val time = recap.timeOfDay ?: return
    val emoji = when (time) {
        TimeOfDay.MORNING -> "🌅"
        TimeOfDay.AFTERNOON -> "☀️"
        TimeOfDay.EVENING -> "🌆"
        TimeOfDay.NIGHT -> "🦉"
    }
    PageColumn {
        Reveal(active) { Text(emoji, fontSize = 64.sp) }
        Spacer(Modifier.height(12.dp))
        Reveal(active, 150) { Text("You're", style = Lead) }
        Reveal(active, 250) { Text(time.title, style = Big.copy(fontSize = 48.sp, lineHeight = 54.sp)) }
        Spacer(Modifier.height(24.dp))
        Reveal(active, 550) { Text("${(recap.timeOfDayShare * 100).roundToInt()}% of your reading happened from ${time.hours}.", style = Body) }
    }
}

@Composable
private fun SummaryPage(recap: YearRecap, active: Boolean, onShare: (Bitmap) -> Unit, onDone: () -> Unit) {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Reveal(active) {
            Box(
                Modifier
                    .widthIn(max = 340.dp)
                    .drawWithContent {
                        // Recorded so the same pixels can be saved as an image to share.
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) { SummaryCard(recap) }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { scope.launch { onShare(layer.toImageBitmap().asAndroidBitmap()) } },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) {
                Icon(Icons.Outlined.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share")
            }
            TextButton(onClick = onDone) { Text("Done", color = Color.White) }
        }
    }
}

@Composable
private fun SummaryCard(recap: YearRecap) {
    val stats = buildList {
        if (recap.totalSeconds >= 60) add("Reading time" to formatDuration(recap.totalSeconds))
        if (recap.booksFinished.isNotEmpty()) add("Books finished" to formatNumber(recap.booksFinished.size.toLong()))
        if (recap.pagesTurned > 0) add("Pages turned" to formatNumber(recap.pagesTurned.toLong()))
        if (recap.chaptersRead > 0) add("Manga chapters" to formatNumber(recap.chaptersRead.toLong()))
        if (recap.longestStreak > 0) add("Longest streak" to plural(recap.longestStreak, "day"))
        if (recap.daysRead > 0) add("Days read" to formatNumber(recap.daysRead.toLong()))
    }.take(6)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B1B4D), Color(0xFFB4462A), Color(0xFFE8901E))))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(28.dp))
            .padding(24.dp),
    ) {
        Text("${recap.year}", style = Big.copy(fontSize = 56.sp, lineHeight = 58.sp))
        Text("in Books", style = Lead.copy(fontSize = 22.sp))
        Spacer(Modifier.height(22.dp))
        stats.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(value, style = Lead.copy(fontSize = 24.sp, fontWeight = FontWeight.Black), maxLines = 1)
                        Text(label, style = Body.copy(fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f)))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        val favourite = recap.topBook ?: recap.topSeries
        if (favourite != null || recap.topGenres.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.25f)))
            Spacer(Modifier.height(14.dp))
        }
        favourite?.let {
            Text("Favourite", style = Body.copy(fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f)))
            Text(it.title, style = Lead.copy(fontSize = 18.sp, lineHeight = 23.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
        }
        recap.topGenres.firstOrNull()?.let { (genre, _) ->
            Text("Top genre", style = Body.copy(fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f)))
            Text(genre, style = Lead.copy(fontSize = 18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text("📖 Books", style = Body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
    }
}

@Composable
private fun EmptyYear(state: YearInBooksViewModel.State, onClose: () -> Unit, onSelectYear: (Int) -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(Story.INTRO.colors))) {
        PageColumn {
            Text("${state.year}", style = Big.copy(fontSize = 88.sp))
            Spacer(Modifier.height(12.dp))
            Text("Nothing to recap yet", style = Lead)
            Spacer(Modifier.height(12.dp))
            Text("Read a little and your year in books will build up here, from what you actually read.", style = Body)
            if (state.years.size > 1) {
                Spacer(Modifier.height(28.dp))
                YearChips(state.years, state.year, onSelectYear)
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().offset(x = (-4).dp, y = 8.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White)
        }
    }
}

private fun formatNumber(value: Long): String = NumberFormat.getIntegerInstance().format(value)

private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    return when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} m"
    }
}

private fun plural(count: Int, one: String, many: String = one + "s") = "${formatNumber(count.toLong())} ${if (count == 1) one else many}"

private fun shortDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
