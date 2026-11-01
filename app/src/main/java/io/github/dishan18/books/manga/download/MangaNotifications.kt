package io.github.dishan18.books.manga.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.dishan18.books.MainActivity
import io.github.dishan18.books.R

object MangaNotifications {
    const val DOWNLOADS_CHANNEL = "manga_downloads"
    const val UPDATES_CHANNEL = "manga_updates"
    const val DOWNLOAD_ID = 1001
    const val UPDATE_ID = 1002
    const val UPDATE_RESULT_ID = 1003

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(DOWNLOADS_CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of chapter downloads"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(UPDATES_CHANNEL, "Library updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New chapters in your manga library"
            },
        )
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(context: Context, channel: String, title: String, text: String?, done: Int, total: Int) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .setProgress(total.coerceAtLeast(0), done.coerceIn(0, total.coerceAtLeast(0)), total <= 0)
            .build()

    /** Posts a one-off notification if the user allowed notifications. */
    fun notify(context: Context, id: Int, channel: String, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
