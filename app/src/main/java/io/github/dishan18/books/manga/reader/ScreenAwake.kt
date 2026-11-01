package io.github.dishan18.books.manga.reader

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * Keeps the screen on while the reader is being used, then lets the normal screen timeout apply
 * again after [idleMillis] without any touch or key, so a phone left open on a page doesn't burn
 * battery with the display lit.
 */
class ScreenAwake(private val activity: Activity, private val idleMillis: Long = 10 * 60_000L) {
    private val handler = Handler(Looper.getMainLooper())
    private val release = Runnable { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private var lastPoke = 0L

    /** Call on any interaction. Cheap enough for every touch event (re-arms at most once a second). */
    fun poke() {
        val now = System.currentTimeMillis()
        if (now - lastPoke < 1_000) return
        lastPoke = now
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(release)
        handler.postDelayed(release, idleMillis)
    }

    fun stop() {
        handler.removeCallbacks(release)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
