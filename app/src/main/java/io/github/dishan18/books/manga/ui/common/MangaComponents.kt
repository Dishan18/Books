package io.github.dishan18.books.manga.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import io.github.dishan18.books.app
import io.github.dishan18.books.manga.MangaModule
import io.github.dishan18.books.ui.CoverShape
import io.github.dishan18.books.ui.PressableBox

/** A ViewModel built from the app's [MangaModule]. */
@Composable
inline fun <reified VM : ViewModel> mangaViewModel(key: String? = null, crossinline create: (MangaModule) -> VM): VM {
    val module = LocalContext.current.app.manga
    return viewModel(key = key, factory = viewModelFactory { initializer { create(module) } })
}

@Composable
fun mangaModule(): MangaModule = LocalContext.current.app.manga

/** A cover image from any source, with that source's auth headers and a generated fallback. */
@Composable
fun MangaCover(
    url: String?,
    sourceId: String,
    title: String,
    modifier: Modifier = Modifier,
    elevation: Float = 5f,
) {
    val module = mangaModule()
    val headers = remember(sourceId) { module.sources.peek(sourceId)?.imageHeaders().orEmpty() }
    RemoteCover(url, headers, title, modifier, elevation)
}

/** A cover image from the web (with any [headers] its server needs) and a generated fallback. */
@Composable
fun RemoteCover(
    url: String?,
    headers: Map<String, String>,
    title: String,
    modifier: Modifier = Modifier,
    elevation: Float = 5f,
) {
    val context = LocalContext.current
    val request = remember(url, headers) {
        url?.let {
            ImageRequest.Builder(context)
                .data(it)
                .httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build())
                .build()
        }
    }
    Box(
        modifier
            .shadow(elevation.dp, CoverShape, clip = false)
            .clip(CoverShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // AsyncImage rather than SubcomposeAsyncImage: no subcomposition per tile, so grids scroll
        // lighter. The box's own background is the loading state.
        var failed by remember(request) { mutableStateOf(false) }
        if (request == null || failed) {
            CoverPlaceholder(title)
        } else {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true },
            )
        }
    }
}

private val placeholderPalettes = listOf(
    Color(0xFF2E4057) to Color(0xFF4B6584),
    Color(0xFF6D2E46) to Color(0xFFA26769),
    Color(0xFF1B4332) to Color(0xFF40916C),
    Color(0xFF3D348B) to Color(0xFF7678ED),
    Color(0xFF7F4F24) to Color(0xFFB08968),
)

@Composable
private fun CoverPlaceholder(title: String) {
    val (top, bottom) = placeholderPalettes[Math.floorMod(title.hashCode(), placeholderPalettes.size)]
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bottom, top))).padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            color = Color.White,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A grid tile: cover, optional badge in the corner, title and a subtitle line. */
@Composable
fun SeriesTile(
    title: String,
    coverUrl: String?,
    sourceId: String,
    subtitle: String?,
    onClick: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    badge: String? = null,
) {
    PressableBox(onClick = onClick, onLongClick = onLongClick, modifier = modifier) {
        Column {
            Box {
                MangaCover(coverUrl, sourceId, title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                if (badge != null) {
                    Text(
                        badge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun MessageState(
    icon: ImageVector,
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** "3 min ago", "Yesterday", "12 Mar" style timestamps. */
fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - epochMillis) / 60_000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        minutes < 48 * 60 -> "Yesterday"
        minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)} days ago"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(epochMillis))
    }
}
