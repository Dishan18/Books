package io.github.dishan18.books.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReaderTheme(val background: Color, val content: Color, val chrome: Color) {
    LIGHT(background = Color(0xFFFFFFFF), content = Color(0xFF1C1C1E), chrome = Color(0xFFF7F7F7)),
    SEPIA(background = Color(0xFFF6EEDC), content = Color(0xFF5B4636), chrome = Color(0xFFEFE5CF)),
    PINK(background = Color(0xFFFCEEF2), content = Color(0xFF5A3443), chrome = Color(0xFFF6E1E8)),
    DARK(background = Color(0xFF121212), content = Color(0xFFE2E2E2), chrome = Color(0xFF1E1E1E)),
}

/** Which way pages move. Only one direction is active at a time. */
enum class PageDirection { HORIZONTAL, VERTICAL }

data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    /** EPUB text size multiplier, 1.0 is the publisher's default. */
    val fontScale: Double = 1.0,
    val pageDirection: PageDirection = PageDirection.HORIZONTAL,
)

/** Reader preferences shared by the PDF and EPUB readers, kept in SharedPreferences. */
class ReaderSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        ReaderSettings(
            theme = prefs.getString("theme", null)
                ?.let { name -> ReaderTheme.entries.firstOrNull { it.name == name } }
                ?: ReaderTheme.LIGHT,
            fontScale = prefs.getFloat("fontScale", 1f).toDouble(),
            pageDirection = prefs.getString("pageDirection", null)
                ?.let { name -> PageDirection.entries.firstOrNull { it.name == name } }
                ?: PageDirection.HORIZONTAL,
        ),
    )
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    fun setTheme(theme: ReaderTheme) {
        prefs.edit { putString("theme", theme.name) }
        _settings.value = _settings.value.copy(theme = theme)
    }

    fun setPageDirection(direction: PageDirection) {
        prefs.edit { putString("pageDirection", direction.name) }
        _settings.value = _settings.value.copy(pageDirection = direction)
    }

    fun setFontScale(scale: Double) {
        val clamped = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        prefs.edit { putFloat("fontScale", clamped.toFloat()) }
        _settings.value = _settings.value.copy(fontScale = clamped)
    }

    companion object {
        const val MIN_FONT_SCALE = 0.7
        const val MAX_FONT_SCALE = 2.5
    }
}
