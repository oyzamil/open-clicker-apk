package com.oyzamil.openclicker

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Android switches an accessibility service off after a force-stop, some updates or battery
 * cleaners, and normally only the user can switch it back on. If the one-time ADB permission
 * WRITE_SECURE_SETTINGS has been granted, the app can switch its own service back on.
 */
object AccessibilityEnabler {

    fun canWrite(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Opens this service's own page in Accessibility settings (falls back to the general screen). */
    fun detailsIntent(c: Context): android.content.Intent =
        android.content.Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(
                android.content.Intent.EXTRA_COMPONENT_NAME,
                ComponentName(c, ClickerService::class.java).flattenToString()
            )

    fun adbCommand(c: Context) =
        "adb shell pm grant ${c.packageName} android.permission.WRITE_SECURE_SETTINGS"

    private fun forms(c: Context): Set<String> {
        val cn = ComponentName(c, ClickerService::class.java)
        return setOf(cn.flattenToString(), cn.flattenToShortString())
    }

    private fun current(c: Context): MutableList<String> =
        (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotEmpty() }.toMutableList()

    fun isEnabledInSettings(c: Context): Boolean =
        runCatching { current(c).any { it in forms(c) } }.getOrDefault(false)

    /**
     * Turns the service on. [repair] also handles "enabled but not connected" ("Not working")
     * by switching it off and on again. Returns true if something was written.
     */
    fun ensureEnabled(c: Context, repair: Boolean, force: Boolean = false): Boolean {
        if (!canWrite(c)) return false
        if (!force && !Prefs.autoEnable) return false
        return runCatching {
            val cr = c.contentResolver
            val f = forms(c)
            val list = current(c)
            val enabled = list.any { it in f }
            val full = ComponentName(c, ClickerService::class.java).flattenToString()
            if (enabled && ClickerService.instance != null) return@runCatching false
            if (enabled && !repair) return@runCatching false
            if (enabled) {
                // enabled in Settings but not connected: toggle it so Android rebinds the service
                list.removeAll { it in f }
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
                val appCtx = c.applicationContext
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { write(appCtx, full) }
                }, 700)
            } else {
                write(c, full)
            }
            true
        }.getOrDefault(false)
    }

    private fun write(c: Context, full: String) {
        val cr = c.contentResolver
        val list = current(c)
        CrashGuard.note("auto re-enable: writing accessibility setting")
        if (list.none { it == full }) {
            list.add(full)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
        }
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }
}
