package com.oyzamil.openclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class ClickerService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClickerService? = null

        const val CHANNEL_ID = "run_state"
        private const val NOTIF_ID = 42

        private val BLUE = 0xFF1E88E5.toInt()
        private val AMBER = 0xFFFB8C00.toInt()
        private val PURPLE = 0xFF8E24AA.toInt()
        private val GREEN = 0xFF43A047.toInt()
        private val ORANGE = 0xFFFF5A1F.toInt()
        private val TEAL = 0xFF00897B.toInt()
        private val PINK = 0xFFD81B60.toInt()
    }

    private class MarkerRef(
        val view: View,
        val params: WindowManager.LayoutParams,
        var size: Int,
        val onMoved: (Float, Float) -> Unit
    )

    /** Description of one draggable marker. */
    private class Spec(
        val label: String,
        val tint: Int,
        val crosshair: Boolean,
        val x: Float,
        val y: Float,
        val onMoved: (Float, Float) -> Unit
    )

    private class Built(val gesture: GestureDescription, val durMs: Long)

    private lateinit var wm: WindowManager

    /** Run-loop handler only (cleared on stop). */
    private val runHandler = Handler(Looper.getMainLooper())

    /** UI housekeeping handler (watchdog, stats, menu). Never cleared by stopRun. */
    private val ui = Handler(Looper.getMainLooper())
    private val rnd = Random()

    // panel
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var startBtn: FrameLayout? = null
    private var startIcon: ImageView? = null
    private var startNum: TextView? = null
    private var statsView: TextView? = null
    private var addBtn: View? = null
    private var recBtn: ImageView? = null
    private var centerBtn: View? = null
    private var eyeBtn: ImageView? = null
    private var lockBtn: ImageView? = null
    private var closeBtn: View? = null
    private var settingsBtn: View? = null
    private var collapsed = false
    private var savedX = 0
    private var builtScale = 100
    private var menuView: View? = null

    // markers
    private val markers = mutableListOf<MarkerRef>()
    private var markersSig: String? = null
    private var markersHidden = false
    private var recordView: View? = null
    private var lastW = 0
    private var lastH = 0

    // run state
    private var running = false
    private var counting = false
    private var countRemaining = 0
    private var steps: List<ClickAction> = emptyList()
    private var idx = 0
    private var repIdx = 0
    private var burstIdx = 0
    private var loopCount = 0
    private var stepSeq = 0
    private var runCount = 0
    private var runStartAt = 0L
    private var failStreak = 0
    private var nextAt = 0L

    // misc
    private var screenReceiver: BroadcastReceiver? = null
    private val downKeys = HashSet<Int>()
    private var comboFired = false
    private var lastPkg: String? = null

    // ---------- lifecycle ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Nothing in here may throw: a crash while connecting leaves the service "malfunctioning".
        try {
            runCatching { Prefs.init(this) }
            runCatching { Session.init(this) }
            runCatching { CrashGuard.installLogger(this) }
            runCatching { CrashGuard.logExitReasons(this) }
            runCatching { if (Prefs.crashGuard) CrashGuard.installLoopGuard() }
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            instance = this
            CrashGuard.note("service connected")
            CrashGuard.batteryNote(this)
            registerScreenReceiver()
            updateKeyFilter()
            refreshTile()
        } catch (e: Throwable) {
            instance = this
            CrashGuard.log("onServiceConnected", e)
        }
    }

    /** Never let an exception escape a callback: a dead process = "Not working" accessibility service. */
    private inline fun safe(where: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            CrashGuard.log(where, e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        runCatching {
            val pkg = event.packageName?.toString() ?: return
            val cls = event.className?.toString() ?: return
            if (pkg == packageName || pkg == "com.android.systemui") return
            if (!cls.contains("Activity")) return
            if (pkg == lastPkg) return
            lastPkg = pkg
            onForegroundChanged(pkg)
        }
    }

    /** Per-app profile: auto-show the panel (and optionally load a script) for chosen apps. */
    private fun onForegroundChanged(pkg: String) {
        val map = Prefs.autoAppMap()
        val script = map[pkg] ?: return
        if (isBusy()) return
        if (script.isNotEmpty()) {
            ScriptStore.load(this, script)?.let {
                Session.script = it
                Session.persist()
                Prefs.multi = true
            }
        }
        if (panel == null) showPanel() else onSettingsChanged()
    }

    override fun onInterrupt() {
        CrashGuard.note("service interrupted")
        stopRun()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ui.postDelayed({ runCatching { housekeeping() } }, 300)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        CrashGuard.note("service stopped (unbind/destroy)")
        runCatching { hidePanel() }
        unregisterScreenReceiver()
        ui.removeCallbacksAndMessages(null)
        runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) }
        if (instance === this) instance = null
        refreshTile()
    }

    /** Kill switch: stop clicking when the screen turns off (if enabled in settings). */
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                safe("screen off") {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                        downKeys.clear()
                        comboFired = false
                        if (Prefs.stopOnScreenOff) {
                            stopRun()
                            stopRecord()
                        }
                    }
                }
            }
        }
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(r, f)
            screenReceiver = r
        }
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
    }

    // ---------- volume key start / stop ----------

    private fun updateKeyFilter() {
        runCatching {
            val info = serviceInfo ?: return
            val has = (info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
            if (Prefs.volumeKeyToggle && !has) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                serviceInfo = info
            } else if (!Prefs.volumeKeyToggle && has) {
                info.flags = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
                serviceInfo = info
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!Prefs.volumeKeyToggle) return false
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            downKeys.add(code)
            if (downKeys.size >= 2) {
                if (!comboFired) {
                    comboFired = true
                    ui.post { runCatching { toggleFromKey() } }
                }
                return true
            }
            return false
        }
        if (event.action == KeyEvent.ACTION_UP) {
            downKeys.remove(code)
            val consume = comboFired
            if (downKeys.isEmpty()) comboFired = false
            return consume
        }
        return false
    }

    private fun toggleFromKey() {
        if (panel == null && !isBusy()) {
            showPanel()
            toast(tr("Panel shown. Press Volume Up + Down again to start."))
            return
        }
        toggleRun()
    }

    // ---------- helpers ----------

    private fun tr(s: String) = I18n.tr(s)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun ps() = Prefs.panelScalePct.coerceIn(60, 160) / 100f

    private fun pdp(v: Int) = (v * resources.displayMetrics.density * ps()).toInt()

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun lp(w: Int, h: Int): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun toast(t: String) = runCatching { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }

    private fun iconBtn(res: Int, onClick: () -> Unit): ImageView {
        val v = ImageView(this)
        v.setImageResource(res)
        v.setColorFilter(Color.WHITE)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = pdp(8)
        v.setPadding(pad, pad, pad, pad)
        v.setOnClickListener { safe("panel button") { onClick() } }
        return v
    }

    private fun iconLp() = LinearLayout.LayoutParams(pdp(38), pdp(40))

    // ---------- panel ----------

    fun showPanel() {
        if (panel != null) return
        buildPanel(dp(8), dp(160))
    }

    private fun buildPanel(x: Int, y: Int) {
        val p = LinearLayout(this)
        p.orientation = LinearLayout.HORIZONTAL
        p.gravity = Gravity.CENTER_VERTICAL
        p.setPadding(pdp(6), pdp(4), pdp(6), pdp(4))
        p.background = GradientDrawable().apply {
            setColor(0xF2171A22.toInt())
            cornerRadius = pdp(26).toFloat()
            setStroke(dp(1), 0x33FFFFFF)
        }
        p.alpha = Prefs.panelOpacityPct.coerceIn(30, 100) / 100f
        val params = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        params.x = x
        params.y = y

        // drag handle: drag = move, tap = minimize / maximize
        val handle = iconBtn(R.drawable.ic_drag) {}
        var sx = 0f
        var sy = 0f
        var px = 0
        var py = 0
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; px = params.x; py = params.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - sx) > dp(6) || abs(e.rawY - sy) > dp(6)) moved = true
                    if (moved) {
                        dismissMenu()
                        val (w, h) = screenSize()
                        params.x = (px + (e.rawX - sx).toInt()).coerceIn(0, (w - p.width).coerceAtLeast(0))
                        params.y = (py + (e.rawY - sy).toInt()).coerceIn(0, (h - p.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(p, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        dismissMenu()
                        toggleCollapse()
                    }
                }
            }
            true
        }

        val sb = FrameLayout(this)
        val si = ImageView(this)
        si.setImageResource(R.drawable.ic_play)
        si.setColorFilter(Color.WHITE)
        si.setPadding(pdp(9), pdp(9), pdp(9), pdp(9))
        val sn = TextView(this)
        sn.setTextColor(Color.WHITE)
        sn.textSize = 17f * ps()
        sn.typeface = Typeface.DEFAULT_BOLD
        sn.gravity = Gravity.CENTER
        sn.visibility = View.GONE
        sb.addView(si, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        sb.addView(sn, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        sb.setOnClickListener { safe("start button") { toggleRun() } }
        startBtn = sb
        startIcon = si
        startNum = sn
        styleStartBtn()

        val st = TextView(this)
        st.setTextColor(Color.WHITE)
        st.textSize = 12f * ps()
        st.typeface = Typeface.DEFAULT_BOLD
        st.gravity = Gravity.CENTER
        st.minWidth = pdp(74)
        st.setPadding(pdp(4), 0, pdp(4), 0)
        st.visibility = View.GONE
        statsView = st

        addBtn = iconBtn(R.drawable.ic_add) { toggleMenu() }
        recBtn = iconBtn(R.drawable.ic_record) { if (recordView != null) stopRecord() else startRecord() }
        centerBtn = iconBtn(R.drawable.ic_center) { recenter() }
        eyeBtn = iconBtn(if (markersHidden) R.drawable.ic_eye_off else R.drawable.ic_eye) { toggleMarkersHidden() }
        lockBtn = iconBtn(if (Prefs.markersLocked) R.drawable.ic_lock else R.drawable.ic_lock_open) { toggleLock() }
        settingsBtn = iconBtn(R.drawable.ic_settings) { openApp() }
        closeBtn = iconBtn(R.drawable.ic_close) { hidePanel() }

        p.addView(handle, iconLp())
        p.addView(sb, LinearLayout.LayoutParams(pdp(40), pdp(40)).apply { setMargins(pdp(4), 0, pdp(4), 0) })
        p.addView(st)
        p.addView(addBtn, iconLp())
        p.addView(recBtn, iconLp())
        p.addView(centerBtn, iconLp())
        p.addView(eyeBtn, iconLp())
        p.addView(lockBtn, iconLp())
        p.addView(settingsBtn, iconLp())
        p.addView(closeBtn, iconLp())

        panel = p
        panelLp = params
        builtScale = Prefs.panelScalePct
        if (runCatching { wm.addView(p, params) }.isFailure) {
            panel = null
            panelLp = null
            toast(tr("Could not show panel. Re-enable the accessibility service."))
            return
        }
        val (w, h) = screenSize()
        lastW = w
        lastH = h
        updatePanelButtons()
        restoreStartState()
        refreshMarkers()
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 1000)
        if (running) startStatsTick()
    }

    private fun rebuildPanel() {
        val l = panelLp
        val x = l?.x ?: dp(8)
        val y = l?.y ?: dp(160)
        dismissMenu()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelLp = null
        buildPanel(x, y)
    }

    fun hidePanel() {
        ui.removeCallbacks(tick)
        ui.removeCallbacks(statsTick)
        dismissMenu()
        stopRun()
        stopRecord(refresh = false)
        clearMarkers()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelLp = null
    }

    fun isPanelVisible() = panel != null

    fun isBusy() = running || counting

    /** Called by the activity when mode / actions / preferences change. */
    fun onSettingsChanged() {
        updateKeyFilter()
        if (panel == null) return
        panel?.alpha = Prefs.panelOpacityPct.coerceIn(30, 100) / 100f
        updatePanelButtons()
        refreshMarkers()
    }

    /** Opacity applies live; a new scale rebuilds the panel (only while idle). */
    fun onPanelStyleChanged(rebuild: Boolean) {
        panel?.alpha = Prefs.panelOpacityPct.coerceIn(30, 100) / 100f
        if (rebuild && panel != null && Prefs.panelScalePct != builtScale && !isBusy()) rebuildPanel()
    }

    fun stopFromOutside() {
        stopRun()
    }

    private fun updatePanelButtons() {
        val multi = Prefs.multi
        val full = !collapsed
        val busy = isBusy()
        val vis = { b: Boolean -> if (b) View.VISIBLE else View.GONE }
        statsView?.visibility = vis(full && running)
        addBtn?.visibility = vis(full && multi && !busy)
        recBtn?.visibility = vis(full && multi && !busy)
        centerBtn?.visibility = vis(full && !multi && !busy)
        eyeBtn?.visibility = vis(full)
        lockBtn?.visibility = vis(full)
        lockBtn?.setImageResource(if (Prefs.markersLocked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        settingsBtn?.visibility = vis(full && !busy)
        closeBtn?.visibility = vis(full && !busy)
    }

    /** Red while started (tap = stop), normal orange while stopped. */
    private fun styleStartBtn() {
        val b = startBtn ?: return
        val colors = if (isBusy()) {
            intArrayOf(0xFFE53935.toInt(), 0xFFB71C1C.toInt())
        } else {
            intArrayOf(0xFFFF7A00.toInt(), 0xFFFF4D1A.toInt())
        }
        b.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
            .apply { shape = GradientDrawable.OVAL }
    }

    private fun showStartIcon(res: Int) {
        startIcon?.setImageResource(res)
        startIcon?.visibility = View.VISIBLE
        startNum?.visibility = View.GONE
    }

    private fun showStartNumber(n: Int) {
        startNum?.text = n.toString()
        startNum?.visibility = View.VISIBLE
        startIcon?.visibility = View.GONE
    }

    private fun restoreStartState() {
        when {
            counting -> showStartNumber(max(1, countRemaining))
            running -> showStartIcon(R.drawable.ic_stop)
            else -> showStartIcon(R.drawable.ic_play)
        }
        styleStartBtn()
    }

    private fun setRecording(on: Boolean) {
        recBtn?.setImageResource(if (on) R.drawable.ic_stop else R.drawable.ic_record)
        recBtn?.setColorFilter(if (on) 0xFFFF5252.toInt() else Color.WHITE)
    }

    /** Opens the app so the user can change settings; the floating panel stays on screen. */
    private fun openApp() {
        dismissMenu()
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (runCatching { startActivity(i) }.isFailure) {
            runCatching {
                PendingIntent.getActivity(
                    this, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                ).send()
            }
        }
    }

    private fun toggleCollapse() {
        collapsed = !collapsed
        val l0 = panelLp
        if (l0 != null) {
            if (collapsed) {
                savedX = l0.x
                l0.x = 0
            } else if (l0.x == 0) {
                l0.x = savedX
            }
        }
        updatePanelButtons()
        val p = panel
        val l = panelLp
        if (p != null && l != null) runCatching { wm.updateViewLayout(p, l) }
    }

    private fun bringPanelToFront() {
        ui.post {
            val p = panel
            val l = panelLp
            if (p != null && l != null && p.isAttachedToWindow) {
                runCatching { wm.removeView(p) }
                runCatching { wm.addView(p, l) }
            }
        }
    }

    // ---------- add-action menu ----------

    private val menuTimeout = Runnable { safe("menu timeout") { dismissMenu() } }

    private fun toggleMenu() {
        if (menuView != null) dismissMenu() else showMenu()
    }

    private fun dismissMenu() {
        ui.removeCallbacks(menuTimeout)
        menuView?.let { runCatching { wm.removeView(it) } }
        menuView = null
    }

    private fun showMenu() {
        val p = panel ?: return
        val l = panelLp ?: return
        dismissMenu()
        val items: List<Pair<String, () -> Unit>> = listOf(
            "◎ ${tr("Click")}" to { addAction(ActionType.TAP) },
            "◉ ${tr("Hold")}" to { addAction(ActionType.LONG_PRESS) },
            "⇄ ${tr("Slide")}" to { addAction(ActionType.SWIPE) },
            "⇅ ${tr("Scroll")}" to { addAction(ActionType.SCROLL) },
            "⤢ ${tr("Pinch")}" to { addAction(ActionType.PINCH) },
            "⏱ ${tr("Wait")}" to { addAction(ActionType.WAIT) },
            "◁ ${tr("Back")}" to { addAction(ActionType.GLOBAL, GlobalAct.BACK) },
            "○ ${tr("Home")}" to { addAction(ActionType.GLOBAL, GlobalAct.HOME) },
            "▭ ${tr("Recents")}" to { addAction(ActionType.GLOBAL, GlobalAct.RECENTS) },
            "▾ ${tr("Notifications")}" to { addAction(ActionType.GLOBAL, GlobalAct.NOTIFICATIONS) },
            "⚙ ${tr("Quick settings")}" to { addAction(ActionType.GLOBAL, GlobalAct.QUICK) },
            "↶ ${tr("Undo")}" to { doUndo() }
        )
        val menu = LinearLayout(this)
        menu.orientation = LinearLayout.VERTICAL
        menu.setPadding(dp(6), dp(6), dp(6), dp(6))
        menu.background = GradientDrawable().apply {
            setColor(0xF2171A22.toInt())
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), 0x33FFFFFF)
        }
        var i = 0
        while (i < items.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (j in i until min(i + 2, items.size)) {
                val (label, act) = items[j]
                val cell = TextView(this)
                cell.text = label
                cell.setTextColor(Color.WHITE)
                cell.textSize = 14f
                cell.setPadding(dp(14), dp(10), dp(14), dp(10))
                cell.minWidth = dp(130)
                cell.setOnClickListener {
                    dismissMenu()
                    runCatching { act() }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            menu.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            i += 2
        }
        menu.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) dismissMenu()
            false
        }
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val mw = menu.measuredWidth
        val mh = menu.measuredHeight
        val (w, h) = screenSize()
        val params = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        params.x = l.x.coerceIn(0, max(0, w - mw))
        val below = l.y + max(p.height, dp(40)) + dp(6)
        params.y = if (below + mh <= h) below else max(0, l.y - mh - dp(6))
        if (runCatching { wm.addView(menu, params) }.isSuccess) {
            menuView = menu
            ui.postDelayed(menuTimeout, 10_000)
        }
    }

    private fun doUndo() {
        if (isBusy()) return
        if (Session.undo()) {
            refreshMarkers()
            toast(tr("Undone"))
        } else {
            toast(tr("Nothing to undo"))
        }
    }

    // ---------- watchdog: keeps panel/markers attached and on-screen ----------

    private val tick = object : Runnable {
        override fun run() {
            if (panel == null) return
            runCatching { housekeeping() }
            ui.postDelayed(this, 1000)
        }
    }

    private fun housekeeping() {
        if (panel == null) return
        val (w, h) = screenSize()
        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            clampAll(w, h)
        }
        val p = panel
        val l = panelLp
        if (p != null && l != null && !p.isAttachedToWindow) runCatching { wm.addView(p, l) }
        for (m in markers) {
            if (!m.view.isAttachedToWindow) runCatching { wm.addView(m.view, m.params) }
        }
    }

    private fun clampAll(w: Int, h: Int) {
        for (m in markers) {
            val cx = (m.params.x + m.size / 2f).coerceIn(0f, w.toFloat())
            val cy = (m.params.y + m.size / 2f).coerceIn(0f, h.toFloat())
            m.params.x = (cx - m.size / 2f).toInt()
            m.params.y = (cy - m.size / 2f).toInt()
            runCatching { wm.updateViewLayout(m.view, m.params) }
            m.onMoved(cx, cy)
        }
        Session.persist()
        val p = panel
        val l = panelLp
        if (p != null && l != null) {
            l.x = l.x.coerceIn(0, (w - p.width).coerceAtLeast(0))
            l.y = l.y.coerceIn(0, (h - p.height).coerceAtLeast(0))
            runCatching { wm.updateViewLayout(p, l) }
        }
    }

    // ---------- markers (target points) ----------

    private fun clearMarkers() {
        markers.forEach { runCatching { wm.removeView(it.view) } }
        markers.clear()
        markersSig = null
    }

    private fun sanitizeSingle() {
        val (w, h) = screenSize()
        if (Prefs.singleX <= 0f || Prefs.singleX > w || Prefs.singleY <= 0f || Prefs.singleY > h) {
            Prefs.singleX = w / 2f
            Prefs.singleY = h * 0.6f
        }
        if (Prefs.singleX2 <= 0f || Prefs.singleX2 > w || Prefs.singleY2 <= 0f || Prefs.singleY2 > h) {
            Prefs.singleX2 = Prefs.singleX
            Prefs.singleY2 = (Prefs.singleY - h * 0.3f).coerceAtLeast(dp(40).toFloat())
        }
    }

    private fun recenter() {
        val (w, h) = screenSize()
        Prefs.singleX = w / 2f
        Prefs.singleY = h * 0.6f
        Prefs.singleX2 = w / 2f
        Prefs.singleY2 = h * 0.3f
        refreshMarkers()
    }

    private fun buildSpecs(): List<Spec> {
        val out = mutableListOf<Spec>()
        if (Prefs.multi) {
            Session.script.actions.forEachIndexed { i, a ->
                val n = i + 1
                val moveStart = { nx: Float, ny: Float ->
                    Session.script.actions.getOrNull(i)?.let { it.x = nx; it.y = ny }
                    Unit
                }
                when (a.type) {
                    ActionType.TAP -> out.add(Spec("$n", BLUE, false, a.x, a.y, moveStart))
                    ActionType.LONG_PRESS -> out.add(Spec("H$n", TEAL, false, a.x, a.y, moveStart))
                    ActionType.PINCH -> out.add(Spec("P$n", PINK, false, a.x, a.y, moveStart))
                    ActionType.SCROLL -> out.add(Spec("$n${ScrollDir.arrow(a.dir)}", GREEN, false, a.x, a.y, moveStart))
                    ActionType.SWIPE -> {
                        out.add(Spec("S$n", AMBER, false, a.x, a.y, moveStart))
                        out.add(Spec("E$n", PURPLE, false, a.x2, a.y2) { nx, ny ->
                            Session.script.actions.getOrNull(i)?.let { it.x2 = nx; it.y2 = ny }
                        })
                    }
                    ActionType.WAIT, ActionType.GLOBAL -> {}
                }
            }
        } else {
            val moveSingle = { nx: Float, ny: Float ->
                Prefs.singleX = nx
                Prefs.singleY = ny
            }
            when (Prefs.singleType) {
                0 -> out.add(Spec("", ORANGE, true, Prefs.singleX, Prefs.singleY, moveSingle))
                1 -> {
                    out.add(Spec("S", AMBER, false, Prefs.singleX, Prefs.singleY, moveSingle))
                    out.add(Spec("E", PURPLE, false, Prefs.singleX2, Prefs.singleY2) { nx, ny ->
                        Prefs.singleX2 = nx
                        Prefs.singleY2 = ny
                    })
                }
                2 -> out.add(Spec(ScrollDir.arrow(Prefs.scrollDir), GREEN, false, Prefs.singleX, Prefs.singleY, moveSingle))
                3 -> out.add(Spec("H", TEAL, false, Prefs.singleX, Prefs.singleY, moveSingle))
                else -> out.add(Spec("P", PINK, false, Prefs.singleX, Prefs.singleY, moveSingle))
            }
        }
        return out
    }

    fun refreshMarkers() {
        if (panel == null) return
        if (recordView != null) {
            clearMarkers()
            return
        }
        if (!Prefs.multi) sanitizeSingle()
        val specs = buildSpecs()
        val sig = specs.joinToString(";") { "${it.label}|${it.tint}|${it.crosshair}" }
        if (sig != markersSig || markers.size != specs.size) {
            clearMarkers()
            markersSig = sig
            specs.forEach { addMarker(it) }
            bringPanelToFront()
        } else {
            markers.forEachIndexed { i, m ->
                m.params.x = (specs[i].x - m.size / 2f).toInt()
                m.params.y = (specs[i].y - m.size / 2f).toInt()
                runCatching { wm.updateViewLayout(m.view, m.params) }
            }
        }
        applyMarkerState()
    }

    private fun markerPx() = dp(Prefs.markerSizeDp)

    /** Live resize from the settings slider; keeps each marker centered on its point. */
    fun onMarkerSizeChanged() {
        if (panel == null) return
        val ns = markerPx()
        for (m in markers) {
            val cx = m.params.x + m.size / 2f
            val cy = m.params.y + m.size / 2f
            m.size = ns
            m.params.width = ns
            m.params.height = ns
            m.params.x = (cx - ns / 2f).toInt()
            m.params.y = (cy - ns / 2f).toInt()
            runCatching { wm.updateViewLayout(m.view, m.params) }
        }
    }

    private fun addMarker(spec: Spec) {
        val size = markerPx()
        val v = TargetView(this, spec.label, spec.tint, spec.crosshair)
        val params = lp(size, size)
        params.x = (spec.x - size / 2f).toInt()
        params.y = (spec.y - size / 2f).toInt()
        val ref = MarkerRef(v, params, size, spec.onMoved)
        var sx = 0f
        var sy = 0f
        var px = 0
        var py = 0
        var before = ""
        var dragged = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; px = params.x; py = params.y
                    dragged = false
                    before = if (Prefs.multi) Session.script.toJson().toString() else ""
                }
                MotionEvent.ACTION_MOVE -> {
                    dragged = true
                    val (w, h) = screenSize()
                    val half = ref.size / 2
                    params.x = (px + (e.rawX - sx).toInt()).coerceIn(-half, w - half)
                    params.y = (py + (e.rawY - sy).toInt()).coerceIn(-half, h - half)
                    runCatching { wm.updateViewLayout(v, params) }
                    spec.onMoved(params.x + ref.size / 2f, params.y + ref.size / 2f)
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged && before.isNotEmpty()) Session.pushUndo(before)
                    Session.persist()
                }
            }
            true
        }
        markers.add(ref)
        runCatching { wm.addView(v, params) }
    }

    private fun toggleMarkersHidden() {
        markersHidden = !markersHidden
        applyMarkerState()
    }

    private fun toggleLock() {
        Prefs.markersLocked = !Prefs.markersLocked
        lockBtn?.setImageResource(if (Prefs.markersLocked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        applyMarkerState()
    }

    /** Markers are non-touchable while running, hidden or locked; invisible while hidden. */
    private fun applyMarkerState() {
        val touchable = !isBusy() && !markersHidden && !Prefs.markersLocked
        eyeBtn?.setImageResource(if (markersHidden) R.drawable.ic_eye_off else R.drawable.ic_eye)
        for (m in markers) {
            m.view.alpha = if (markersHidden) 0f else 1f
            m.params.flags = if (touchable) {
                m.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                m.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            runCatching { wm.updateViewLayout(m.view, m.params) }
        }
    }

    private fun addAction(type: ActionType, global: Int = 0) {
        if (isBusy()) return
        if (Session.script.actions.size >= MAX_ACTIONS) {
            toast(tr("Too many actions"))
            return
        }
        Session.checkpoint()
        val (w, h) = screenSize()
        val n = Session.script.actions.size
        val off = n % 12 * dp(12)
        val maxY = h - dp(30).toFloat()
        val cx = (w / 2f + off).coerceAtMost(w - dp(30).toFloat())
        val a = when (type) {
            ActionType.TAP -> ClickAction(
                ActionType.TAP, cx, (h / 2f + off).coerceAtMost(maxY),
                durationMs = Prefs.tapDurationMs, delayMs = Prefs.delayMs
            )
            ActionType.LONG_PRESS -> ClickAction(
                ActionType.LONG_PRESS, cx, (h / 2f + off).coerceAtMost(maxY),
                durationMs = Prefs.longPressMs, delayMs = Prefs.delayMs
            )
            ActionType.SWIPE -> ClickAction(
                ActionType.SWIPE, cx, (h * 0.65f + off).coerceAtMost(maxY),
                cx, (h * 0.35f + off).coerceAtMost(maxY),
                durationMs = Prefs.swipeDurationMs, delayMs = Prefs.delayMs
            )
            ActionType.SCROLL -> ClickAction(
                ActionType.SCROLL, cx, (h * 0.6f + off).coerceAtMost(maxY),
                durationMs = Prefs.swipeDurationMs, delayMs = Prefs.delayMs,
                dir = Prefs.scrollDir, distPct = Prefs.scrollDistPct
            )
            ActionType.PINCH -> ClickAction(
                ActionType.PINCH, cx, (h / 2f + off).coerceAtMost(maxY),
                durationMs = Prefs.swipeDurationMs, delayMs = Prefs.delayMs,
                dir = if (Prefs.pinchZoomIn) 1 else 0, distPct = Prefs.pinchSpanPct
            )
            ActionType.WAIT -> ClickAction(
                ActionType.WAIT, 0f, 0f,
                durationMs = 1L, delayMs = max(1000L, Prefs.delayMs)
            )
            ActionType.GLOBAL -> ClickAction(
                ActionType.GLOBAL, 0f, 0f,
                durationMs = 1L, delayMs = max(500L, Prefs.delayMs), global = global
            )
        }
        applyRandomDelay(a)
        Session.script.actions.add(a)
        Session.persist()
        refreshMarkers()
    }

    // ---------- recording ----------

    private fun startRecord() {
        if (isBusy()) return
        dismissMenu()
        Session.checkpoint()
        clearMarkers()
        val v = View(this)
        v.setBackgroundColor(0x33FF0000)
        var downT = 0L
        var lastUp = 0L
        var dx = 0f
        var dy = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downT = SystemClock.uptimeMillis(); dx = e.rawX; dy = e.rawY
                }
                MotionEvent.ACTION_UP -> {
                    val now = SystemClock.uptimeMillis()
                    val acts = Session.script.actions
                    if (acts.size >= MAX_ACTIONS) {
                        toast(tr("Too many actions"))
                    } else {
                        val dist = hypot(e.rawX - dx, e.rawY - dy)
                        val dur = now - downT
                        if (acts.isNotEmpty() && lastUp > 0L) {
                            acts.last().delayMs = (downT - lastUp).coerceAtLeast(0L)
                        }
                        if (dist < dp(16).toFloat()) {
                            if (dur >= 500L) {
                                acts.add(ClickAction(ActionType.LONG_PRESS, dx, dy, durationMs = dur.coerceIn(100L, 60_000L), delayMs = Prefs.delayMs))
                            } else {
                                acts.add(ClickAction(ActionType.TAP, dx, dy, durationMs = dur.coerceIn(1L, 500L), delayMs = Prefs.delayMs))
                            }
                        } else {
                            acts.add(ClickAction(ActionType.SWIPE, dx, dy, e.rawX, e.rawY, dur.coerceIn(50L, 3000L), Prefs.delayMs))
                        }
                        lastUp = now
                        Session.persist()
                    }
                }
            }
            true
        }
        if (runCatching { wm.addView(v, lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)) }.isFailure) {
            refreshMarkers()
            return
        }
        recordView = v
        setRecording(true)
        toast(tr("Recording: tap/swipe on screen. Press ■ to finish."))
        ui.post {
            val p = panel
            val l = panelLp
            if (p != null && l != null && recordView != null) {
                runCatching { wm.removeView(p) }
                runCatching { wm.addView(p, l) }
            }
        }
    }

    private fun stopRecord(refresh: Boolean = true) {
        val v = recordView ?: return
        runCatching { wm.removeView(v) }
        recordView = null
        setRecording(false)
        if (refresh) {
            toast("${tr("Recorded")} ${Session.script.actions.size}")
            refreshMarkers()
        }
    }

    // ---------- run control ----------

    fun toggleRun(dismissShade: Boolean = false) {
        if (isBusy()) stopRun() else startRun(dismissShade)
    }

    private fun singleAction(): ClickAction = singleActionBase().also {
        it.burst = Prefs.burstCount
        it.burstDelayMs = Prefs.burstDelayMs
        applyRandomDelay(it)
    }

    /** Random delay mode: the delay after the action becomes a range [A, B] picked again every time. */
    private fun applyRandomDelay(a: ClickAction) {
        if (!Prefs.delayRandom) return
        val lo0 = min(Prefs.delayMinMs, Prefs.delayMaxMs)
        val hi0 = max(Prefs.delayMinMs, Prefs.delayMaxMs)
        val floor = when (a.type) {
            ActionType.WAIT -> 1000L
            ActionType.GLOBAL -> 500L
            else -> 0L
        }
        val lo = max(lo0, floor)
        val hi = max(hi0, lo)
        a.delayMs = lo
        a.delayMaxMs = if (hi > lo) hi else 0L
    }

    /** Fixed delay, or a random value between delayMs and delayMaxMs. */
    private fun pickDelay(a: ClickAction): Long {
        val lo = a.delayMs
        val hi = a.delayMaxMs
        if (hi <= lo) return lo
        val span = hi - lo
        return lo + (rnd.nextDouble() * (span + 1)).toLong().coerceAtMost(span)
    }

    private fun singleActionBase(): ClickAction = when (Prefs.singleType) {
        0 -> ClickAction(
            ActionType.TAP, Prefs.singleX, Prefs.singleY,
            durationMs = Prefs.tapDurationMs, delayMs = Prefs.delayMs
        )
        1 -> ClickAction(
            ActionType.SWIPE, Prefs.singleX, Prefs.singleY, Prefs.singleX2, Prefs.singleY2,
            durationMs = Prefs.swipeDurationMs, delayMs = Prefs.delayMs
        )
        2 -> ClickAction(
            ActionType.SCROLL, Prefs.singleX, Prefs.singleY,
            durationMs = Prefs.swipeDurationMs, delayMs = Prefs.delayMs,
            dir = Prefs.scrollDir, distPct = Prefs.scrollDistPct
        )
        3 -> ClickAction(
            ActionType.LONG_PRESS, Prefs.singleX, Prefs.singleY,
            durationMs = Prefs.longPressMs, delayMs = Prefs.delayMs
        )
        else -> ClickAction(
            ActionType.PINCH, Prefs.singleX, Prefs.singleY,
            durationMs = Prefs.swipeDurationMs, delayMs = Prefs.delayMs,
            dir = if (Prefs.pinchZoomIn) 1 else 0, distPct = Prefs.pinchSpanPct
        )
    }

    private fun startRun(dismissShade: Boolean) {
        if (isBusy()) return
        if (recordView != null) stopRecord()
        dismissMenu()
        if (!Prefs.multi) sanitizeSingle()
        val list = if (Prefs.multi) Session.script.actions.map { it.copy() } else listOf(singleAction())
        if (list.isEmpty()) {
            toast(tr("No actions. Open the + menu or record with ● first."))
            return
        }
        steps = list
        if (dismissShade && Build.VERSION.SDK_INT >= 31) {
            runCatching { performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) }
        }
        val cd = Prefs.countdownSec
        if (cd > 0) {
            counting = true
            countRemaining = cd
            onBusyChanged()
            tickCountdown()
        } else {
            beginRun()
        }
    }

    private fun tickCountdown() {
        if (!counting) return
        safe("countdown") { tickCountdownInner() }
    }

    private fun tickCountdownInner() {
        if (countRemaining <= 0) {
            counting = false
            beginRun()
            return
        }
        showStartNumber(countRemaining)
        countRemaining--
        runHandler.postDelayed({ tickCountdown() }, 1000)
    }

    private fun beginRun() {
        running = true
        idx = 0
        repIdx = 0
        burstIdx = 0
        loopCount = 0
        stepSeq = 0
        runCount = 0
        failStreak = 0
        runStartAt = SystemClock.elapsedRealtime()
        showStartIcon(R.drawable.ic_stop)
        nextAt = SystemClock.elapsedRealtime() + 150L
        onBusyChanged()
        val stop = Prefs.stopAfterMs
        if (stop > 0) {
            runHandler.postDelayed({
                if (running) {
                    toast(tr("Auto-stopped (timer)"))
                    stopRun()
                }
            }, stop)
        }
        runHandler.postDelayed({ runStep() }, 150)
        startStatsTick()
    }

    fun stopRun() {
        if (!running && !counting) return
        running = false
        counting = false
        runHandler.removeCallbacksAndMessages(null)
        stepSeq++
        showStartIcon(R.drawable.ic_play)
        nextAt = 0L
        onBusyChanged()
    }

    private fun onBusyChanged() {
        styleStartBtn()
        updatePanelButtons()
        applyMarkerState()
        updateStats()
        updateNotification()
        refreshTile()
    }

    // ---------- stats ----------

    private val statsTick = object : Runnable {
        override fun run() {
            if (!running) return
            safe("stats") { updateStats() }
            ui.postDelayed(this, 200)
        }
    }

    private fun startStatsTick() {
        ui.removeCallbacks(statsTick)
        ui.post(statsTick)
    }

    /** Time left until the next action: 1:59 ... 0:01, then 9.8s ... 0.0s. */
    private fun fmtRemain(ms: Long): String {
        if (ms <= 0L) return "0.0s"
        if (ms < 10_000L) return String.format(Locale.US, "%.1fs", ms / 1000.0)
        val s = (ms + 999L) / 1000L
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    private fun updateStats() {
        val v = statsView ?: return
        if (running) {
            val rem = if (nextAt > 0L) (nextAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L) else 0L
            v.text = "$runCount · ${fmtRemain(rem)}"
        }
        v.visibility = if (running && !collapsed) View.VISIBLE else View.GONE
    }

    // ---------- notification + quick tile ----------

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (!isBusy() || !Prefs.notifStop) {
            runCatching { nm.cancel(NOTIF_ID) }
            return
        }
        runCatching {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Run state", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val stopPi = PendingIntent.getBroadcast(
                this, 1,
                Intent(this, StopReceiver::class.java).setAction(StopReceiver.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val action = Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_tile), tr("Stop"), stopPi
            ).build()
            val n = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(tr("Open Clicker is running"))
                .setContentText(tr("Tap Stop to end"))
                .setOngoing(true)
                .addAction(action)
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }

    private fun refreshTile() {
        runCatching {
            TileService.requestListeningState(this, ComponentName(this, ClickerTileService::class.java))
        }
    }

    // ---------- recording-independent gesture engine ----------

    private fun jit(v: Float, limit: Int): Float {
        val o = Prefs.randomOffsetPx
        val r = if (o > 0) v + rnd.nextInt(2 * o + 1) - o else v
        return r.coerceIn(0f, (limit - 1).coerceAtLeast(0).toFloat())
    }

    private fun swipePath(sx: Float, sy: Float, ex: Float, ey: Float, w: Int, h: Int): Path {
        val p = Path()
        p.moveTo(sx, sy)
        if (Prefs.humanSwipe) {
            val dx = ex - sx
            val dy = ey - sy
            val len = hypot(dx, dy)
            if (len > 1f) {
                val off = len * (0.04f + rnd.nextFloat() * 0.08f) * (if (rnd.nextBoolean()) 1f else -1f)
                val cx = ((sx + ex) / 2f + (-dy / len) * off).coerceIn(0f, (w - 1).toFloat())
                val cy = ((sy + ey) / 2f + (dx / len) * off).coerceIn(0f, (h - 1).toFloat())
                p.quadTo(cx, cy, ex, ey)
                return p
            }
        }
        p.lineTo(ex, ey)
        return p
    }

    private fun buildGesture(a: ClickAction): Built? {
        val (w, h) = screenSize()
        val maxDur = GestureDescription.getMaxGestureDuration()
        fun dur(base: Long, vary: Boolean): Long {
            var d = base
            if (vary && Prefs.humanSwipe) d = (d * (0.8 + rnd.nextDouble() * 0.4)).toLong()
            return d.coerceIn(1L, maxDur)
        }
        val b = GestureDescription.Builder()
        var total = 1L
        when (a.type) {
            ActionType.TAP, ActionType.LONG_PRESS -> {
                val path = Path()
                path.moveTo(jit(a.x, w), jit(a.y, h))
                total = dur(a.durationMs, false)
                b.addStroke(GestureDescription.StrokeDescription(path, 0L, total))
            }
            ActionType.SWIPE -> {
                val path = swipePath(jit(a.x, w), jit(a.y, h), jit(a.x2, w), jit(a.y2, h), w, h)
                total = dur(a.durationMs, true)
                b.addStroke(GestureDescription.StrokeDescription(path, 0L, total))
            }
            ActionType.SCROLL -> {
                val vertical = a.dir == ScrollDir.UP || a.dir == ScrollDir.DOWN
                val dist = a.distPct.coerceIn(1, 100) / 100f * (if (vertical) h else w)
                // the finger moves opposite to the direction the content scrolls to
                var ex = a.x
                var ey = a.y
                when (a.dir) {
                    ScrollDir.DOWN -> ey = a.y - dist
                    ScrollDir.UP -> ey = a.y + dist
                    ScrollDir.RIGHT -> ex = a.x - dist
                    else -> ex = a.x + dist
                }
                val path = swipePath(
                    jit(a.x, w), jit(a.y, h),
                    jit(ex.coerceIn(0f, (w - 1).toFloat()), w), jit(ey.coerceIn(0f, (h - 1).toFloat()), h), w, h
                )
                total = dur(a.durationMs, true)
                b.addStroke(GestureDescription.StrokeDescription(path, 0L, total))
            }
            ActionType.PINCH -> {
                val span = a.distPct.coerceIn(5, 100) / 100f * min(w, h)
                val big = span / 2f
                val small = max(dp(12).toFloat(), span * 0.08f).coerceAtMost(big)
                val s0 = if (a.dir == 1) small else big
                val s1 = if (a.dir == 1) big else small
                val k = 0.7071f
                fun cxv(v: Float) = v.coerceIn(0f, (w - 1).toFloat())
                fun cyv(v: Float) = v.coerceIn(0f, (h - 1).toFloat())
                val pa = Path()
                pa.moveTo(cxv(a.x - s0 * k), cyv(a.y - s0 * k))
                pa.lineTo(cxv(a.x - s1 * k), cyv(a.y - s1 * k))
                val pb = Path()
                pb.moveTo(cxv(a.x + s0 * k), cyv(a.y + s0 * k))
                pb.lineTo(cxv(a.x + s1 * k), cyv(a.y + s1 * k))
                total = dur(a.durationMs, false)
                b.addStroke(GestureDescription.StrokeDescription(pa, 0L, total))
                b.addStroke(GestureDescription.StrokeDescription(pb, 0L, total))
            }
            ActionType.WAIT, ActionType.GLOBAL -> return null
        }
        return Built(b.build(), total)
    }

    private fun globalCode(c: Int) = when (c) {
        GlobalAct.BACK -> GLOBAL_ACTION_BACK
        GlobalAct.HOME -> GLOBAL_ACTION_HOME
        GlobalAct.RECENTS -> GLOBAL_ACTION_RECENTS
        GlobalAct.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
        else -> GLOBAL_ACTION_QUICK_SETTINGS
    }

    private fun runStep() {
        if (!running) return
        try {
            doStep()
        } catch (e: Exception) {
            CrashGuard.log("runStep", e)
            toast("${tr("Stopped")}: ${e.message}")
            stopRun()
        }
    }

    private fun doStep() {
        if (idx !in steps.indices) {
            stopRun()
            return
        }
        val a = steps[idx]
        nextAt = 0L
        val token = ++stepSeq
        when (a.type) {
            ActionType.WAIT -> {
                advanceIf(token, a, true)
                return
            }
            ActionType.GLOBAL -> {
                val ok = runCatching { performGlobalAction(globalCode(a.global)) }.getOrDefault(false)
                advanceIf(token, a, ok)
                return
            }
            else -> {}
        }
        val built = buildGesture(a)
        if (built == null) {
            advanceIf(token, a, false)
            return
        }
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) =
                safe("gesture done") { advanceIf(token, a, true) }

            override fun onCancelled(gestureDescription: GestureDescription?) =
                safe("gesture cancelled") { advanceIf(token, a, false) }
        }
        val ok = runCatching { dispatchGesture(built.gesture, cb, null) }.getOrDefault(false)
        if (!ok) {
            advanceIf(token, a, false)
        } else {
            // safety net: if the system never calls back, do not hang forever
            runHandler.postDelayed({ safe("gesture watchdog") { advanceIf(token, a, false) } }, built.durMs + 2000L)
        }
    }

    private fun advanceIf(token: Int, a: ClickAction, success: Boolean) {
        if (!running || token != stepSeq) return
        stepSeq++
        if (success) {
            failStreak = 0
            runCount++
        } else {
            failStreak++
            if (failStreak >= 25) {
                toast(tr("Stopped: gestures keep failing"))
                stopRun()
                return
            }
        }
        val ri = Prefs.randomIntervalMs
        val extra = if (ri > 0) rnd.nextInt(2 * ri + 1) - ri else 0
        val minDelay = if (success) 1L else 100L

        // Several clicks "at a time": short delay between them, the normal delay only after the last one.
        val burstN = if (a.type == ActionType.WAIT || a.type == ActionType.GLOBAL) 1 else a.burst.coerceIn(1, 1000)
        burstIdx++
        if (burstIdx < burstN) {
            val bd = (a.burstDelayMs + extra).coerceAtLeast(minDelay)
            nextAt = SystemClock.elapsedRealtime() + bd
            runHandler.postDelayed({ runStep() }, bd)
            return
        }
        burstIdx = 0

        repIdx++
        if (repIdx >= a.repeat.coerceAtLeast(1)) {
            repIdx = 0
            idx++
            if (idx >= steps.size) {
                idx = 0
                loopCount++
                if (Prefs.loops > 0 && loopCount >= Prefs.loops) {
                    stopRun()
                    return
                }
            }
        }
        val d = (pickDelay(a) + extra).coerceAtLeast(minDelay)
        nextAt = SystemClock.elapsedRealtime() + d
        runHandler.postDelayed({ runStep() }, d)
    }
}
