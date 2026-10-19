package com.oyzamil.openclicker

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * Why this exists: if the app process dies from ANY uncaught exception, Android marks the
 * accessibility service as "Not working" and the user has to switch it on again in Settings.
 * So (1) every error is written to errors.log and (2) the main thread is kept alive after
 * unexpected exceptions while the service is running.
 */
object CrashGuard {
    private var appCtx: Context? = null
    private var loggerInstalled = false
    private var loopGuardInstalled = false

    private fun file(): File? = appCtx?.let { File(it.filesDir, "errors.log") }

    private fun enabled() = runCatching { Prefs.crashGuard }.getOrDefault(true)

    fun log(where: String, t: Throwable) {
        runCatching {
            Log.e("OpenClicker", where, t)
            val f = file() ?: return
            if (f.exists() && f.length() > 60_000L) f.writeText("")
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            f.appendText("[${Date()}] $where\n${sw.toString().take(3000)}\n\n")
        }
    }

    /** Plain diagnostic line (service connected / stopped ...) so we can tell crashes from Android switching it off. */
    fun note(msg: String) {
        runCatching {
            val f = file() ?: return
            if (f.exists() && f.length() > 60_000L) f.writeText("")
            f.appendText("[${Date()}] NOTE $msg\n")
        }
    }

    class ExitInfo(val whenMs: Long, val reason: Int, val description: String)

    /** Why did the previous app process end? (Android 11+). Newest first. */
    fun recentExits(c: Context, n: Int): List<ExitInfo> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return runCatching {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(c.packageName, 0, n)
                .filter { it.processName == c.packageName }
                .map { ExitInfo(it.timestamp, it.reason, it.description ?: "") }
        }.getOrDefault(emptyList())
    }

    fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER (phone cleaner / OEM)"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (killed)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (recents / force stop)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "REASON_$r"
    }

    /** Records the battery state at every service start, so the log shows whether restrictions were on. */
    fun batteryNote(c: Context) {
        runCatching {
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val ignoring = pm.isIgnoringBatteryOptimizations(c.packageName)
            val restricted = if (Build.VERSION.SDK_INT >= 28) am.isBackgroundRestricted else false
            val bucket = if (Build.VERSION.SDK_INT >= 28) {
                (c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).appStandbyBucket
            } else {
                -1
            }
            note("battery: ignoringOptimizations=$ignoring backgroundRestricted=$restricted standbyBucket=$bucket")
        }
    }

    /** Writes the reasons of earlier process deaths to the log once, so we can see WHY the service stopped. */
    fun logExitReasons(c: Context) {
        runCatching {
            val last = Prefs.lastExitLogged
            val fresh = recentExits(c, 8).filter { it.whenMs > last }
            for (e in fresh.reversed()) {
                note("previous process ended: ${reasonName(e.reason)} ${e.description} at ${Date(e.whenMs)}")
            }
            fresh.maxOfOrNull { it.whenMs }?.let { Prefs.lastExitLogged = it }
        }
    }

    fun readLog(): String = runCatching {
        file()?.takeIf { it.exists() }?.readText() ?: ""
    }.getOrDefault("")

    fun clearLog() {
        runCatching { file()?.delete() }
    }

    /** Log every uncaught exception. Background-thread errors no longer kill the process. */
    fun installLogger(c: Context) {
        appCtx = c.applicationContext
        if (loggerInstalled) return
        loggerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            log("uncaught on ${t.name}", e)
            val isMain = t === Looper.getMainLooper().thread
            if (isMain || !enabled()) previous?.uncaughtException(t, e)
        }
    }

    /** Keep the main thread alive after an unexpected exception (service stays "working"). */
    fun installLoopGuard() {
        if (loopGuardInstalled) return
        loopGuardInstalled = true
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                    return@post
                } catch (t: Throwable) {
                    log("main loop", t)
                    if (!enabled()) throw t
                }
            }
        }
    }
}
