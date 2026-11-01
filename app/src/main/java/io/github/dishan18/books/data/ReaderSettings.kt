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
    DARK(background = Color(0xFF121212), content = Color(0xFFE2E2E2), chrome = Color(0xFF1E1E1E)),
}

data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    /** EPUB text size multiplier, 1.0 is the publisher's default. */
    val fontScale: Double = 1.0,
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
        ),
    )
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    fun setTheme(theme: ReaderTheme) {
        prefs.edit { putString("theme", theme.name) }
        _settings.value = _settings.value.copy(theme = theme)
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
