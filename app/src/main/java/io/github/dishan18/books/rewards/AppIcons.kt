package io.github.dishan18.books.rewards

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.dishan18.books.rewards.engine.AppIcon

/**
 * Switches the launcher icon by enabling one `<activity-alias>` and disabling the others. The app
 * keeps running; the launcher picks the change up within a few seconds.
 */
object AppIcons {
    private const val NAMESPACE = "io.github.dishan18.books"

    private fun component(context: Context, icon: AppIcon) = ComponentName(context.packageName, NAMESPACE + icon.alias)

    fun current(context: Context): AppIcon {
        val pm = context.packageManager
        return AppIcon.entries.firstOrNull {
            it != AppIcon.CLASSIC && pm.getComponentEnabledSetting(component(context, it)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: AppIcon.CLASSIC
    }

    fun set(context: Context, icon: AppIcon) {
        if (icon == current(context)) return
        val pm = context.packageManager
        // Turn the new entry on first, so there is never a moment without a launcher icon.
        pm.setComponentEnabledSetting(component(context, icon), stateFor(icon, on = true), PackageManager.DONT_KILL_APP)
        AppIcon.entries.filter { it != icon }.forEach {
            pm.setComponentEnabledSetting(component(context, it), stateFor(it, on = false), PackageManager.DONT_KILL_APP)
        }
    }

    // The classic entry is enabled in the manifest and the others disabled, so "default" means
    // classic-on / others-off; only departures from that are stored.
    private fun stateFor(icon: AppIcon, on: Boolean) = when {
        icon == AppIcon.CLASSIC && on -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        icon == AppIcon.CLASSIC -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        on -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    }
}
