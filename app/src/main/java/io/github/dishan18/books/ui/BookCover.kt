package io.github.dishan18.books.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dishan18.books.app
import io.github.dishan18.books.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object CoverCache {
    val bitmaps = LruCache<String, ImageBitmap>(80)
}

val CoverShape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 8.dp, bottomEnd = 8.dp)

/** A book cover with a soft shadow; books without a cover get a generated one. */
@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier, elevation: Float = 6f) {
    // Keyed so a slot that switches books (like "Continue reading") never shows the old cover.
    key(book.id) { BookCoverContent(book, modifier, elevation) }
}

@Composable
private fun BookCoverContent(book: Book, modifier: Modifier, elevation: Float) {
    val context = LocalContext.current
    val cover by produceState(CoverCache.bitmaps.get(book.id), book.hasCover) {
        if (value != null || !book.hasCover) return@produceState
        value = withContext(Dispatchers.IO) {
            val file = context.app.repository.coverOf(book)
            BitmapFactory.decodeFile(file.path)?.asImageBitmap()
        }?.also { CoverCache.bitmaps.put(book.id, it) }
    }

    Box(
        modifier
            .shadow(elevation.dp, CoverShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CoverShape),
    ) {
        Crossfade(targetState = cover, animationSpec = tween(220), label = "cover") { bitmap ->
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                GeneratedCover(book)
            }
        }
        // A thin highlight down the spine, like light catching a real cover.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.18f),
                        0.035f to Color.White.copy(alpha = 0.12f),
                        0.07f to Color.Transparent,
                    ),
                ),
        )
    }
}

private val coverPalettes = listOf(
    Color(0xFF2E4057) to Color(0xFF4B6584),
    Color(0xFF6D2E46) to Color(0xFFA26769),
    Color(0xFF1B4332) to Color(0xFF40916C),
    Color(0xFF3D348B) to Color(0xFF7678ED),
    Color(0xFF7F4F24) to Color(0xFFB08968),
    Color(0xFF22223B) to Color(0xFF4A4E69),
)

@Composable
private fun GeneratedCover(book: Book) {
    val (top, bottom) = coverPalettes[Math.floorMod(book.title.hashCode(), coverPalettes.size)]
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(bottom, top)))
            .padding(horizontal = 10.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            book.title,
            color = Color.White,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
