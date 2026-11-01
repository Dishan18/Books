package io.github.dishan18.books.rewards.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dishan18.books.app
import io.github.dishan18.books.rewards.RewardEvent
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class Toast(val emoji: String, val title: String, val subtitle: String?)

/**
 * Celebrations shown on top of a reader: small toasts for goals, badges and level-ups, and a
 * full card (with confetti once unlocked) when a book is finished. Draws nothing otherwise and
 * never takes touches unless the finish card is showing.
 */
@Composable
fun RewardOverlay() {
    val rewards = LocalContext.current.app.rewards
    val haptic = LocalHapticFeedback.current
    val queue = remember { mutableStateListOf<Toast>() }
    var finished by remember { mutableStateOf<RewardEvent.BookFinished?>(null) }

    LaunchedEffect(Unit) {
        rewards.events.collect { event ->
            when (event) {
                is RewardEvent.GoalReached -> {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    queue += Toast(
                        "🔥",
                        "Daily goal reached",
                        if (event.streak > 1) "${event.streak}-day streak" else "Your streak starts today",
                    )
                }
                is RewardEvent.BadgeEarned -> queue += Toast(event.badge.emoji, "Badge earned: ${event.badge.title}", event.badge.description)
                is RewardEvent.LevelUp -> {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    queue += Toast("⭐", "Level ${event.level}", event.perks.joinToString { it.title }.ifBlank { null }?.let { "Unlocked: $it" })
                }
                is RewardEvent.BookFinished -> {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    finished = event
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        ToastHost(queue)
        finished?.let { event -> FinishedCard(event, onDismiss = { finished = null }) }
    }
}

@Composable
private fun ToastHost(queue: MutableList<Toast>) {
    val current = queue.firstOrNull()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(current) {
        if (current == null) return@LaunchedEffect
        visible = true
        delay(3_200)
        visible = false
        delay(350)
        queue.removeAt(0)
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = visible && current != null,
            enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { -it * 2 } + fadeIn(),
            exit = slideOutVertically { -it * 2 } + fadeOut(),
        ) {
            val toast = current ?: return@AnimatedVisibility
            Row(
                Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 420.dp)
                    .shadow(12.dp, RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF1F1F23))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(toast.emoji, fontSize = 26.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(toast.title, color = Color.White, style = MaterialTheme.typography.titleSmall)
                    toast.subtitle?.let { Text(it, color = Color(0xFFBDBDC2), style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                }
            }
        }
    }
}

@Composable
private fun FinishedCard(event: RewardEvent.BookFinished, onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // While the card is up, the page underneath shouldn't react to taps.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        if (event.confetti) Confetti()
        AnimatedVisibility(visible = shown, enter = scaleIn(spring(dampingRatio = 0.6f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
            Column(
                Modifier
                    .padding(32.dp)
                    .widthIn(max = 380.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("🎉", fontSize = 52.sp)
                Spacer(Modifier.height(8.dp))
                Text("You finished", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(event.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(
                    listOfNotNull(
                        formatDuration(event.seconds).takeIf { event.seconds >= 60 }?.let { "$it of reading" },
                        event.pages.takeIf { it > 0 }?.let { "$it pages" },
                    ).joinToString(" · ").ifBlank { "Another one for the shelf" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text("+50 XP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(20.dp))
                Button(onClick = onDismiss) { Text("Nice!") }
            }
        }
    }
}

private val confettiColors = listOf(
    Color(0xFFFF5A5F), Color(0xFFFFB400), Color(0xFF00A699), Color(0xFF7B61FF), Color(0xFF3DDC97), Color(0xFFFF7AB6),
)

private class Piece(val x: Float, val delay: Float, val speed: Float, val drift: Float, val spin: Float, val color: Color, val size: Float)

/** A short burst of falling paper. Pure Canvas, no extra libraries. */
@Composable
private fun Confetti() {
    val pieces = remember {
        List(90) {
            Piece(
                x = Random.nextFloat(),
                delay = Random.nextFloat() * 0.6f,
                speed = 0.35f + Random.nextFloat() * 0.45f,
                drift = Random.nextFloat() * 2f - 1f,
                spin = Random.nextFloat() * 720f - 360f,
                color = confettiColors.random(),
                size = 8f + Random.nextFloat() * 10f,
            )
        }
    }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withInfiniteAnimationFrameMillis { it }
        while (elapsed < 4_000) elapsed = withInfiniteAnimationFrameMillis { it } - start
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = elapsed / 1000f
        pieces.forEach { p ->
            val life = t - p.delay
            if (life < 0) return@forEach
            val y = -40f + life * p.speed * size.height
            if (y > size.height + 40f) return@forEach
            val x = p.x * size.width + sin(life * 3f + p.drift * 6f) * 40f * p.drift
            rotate(degrees = p.spin * life, pivot = Offset(x, y)) {
                drawRect(p.color, topLeft = Offset(x - p.size / 2, y - p.size / 4), size = Size(p.size, p.size * 0.5f + cos(life * 8f) * 2f))
            }
        }
    }
}

fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    return when {
        minutes < 1 -> "under a minute"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}
