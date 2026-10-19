package com.oyzamil.openclicker

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ComponentName
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Collections
import java.util.Locale

private object P {
    var dark = true
    val bg: Int get() = if (dark) 0xFF0F1117.toInt() else 0xFFF3F4F8.toInt()
    val card: Int get() = if (dark) 0xFF1A1D26.toInt() else 0xFFFFFFFF.toInt()
    val cardStroke: Int get() = if (dark) 0 else 0xFFE1E4EC.toInt()
    val field: Int get() = if (dark) 0xFF262A36.toInt() else 0xFFE7EAF1.toInt()
    val segTrack: Int get() = if (dark) 0xFF12141B.toInt() else 0xFFDDE1EA.toInt()
    val segThumb: Int get() = if (dark) 0xFF3B4152.toInt() else 0xFFFFFFFF.toInt()
    val text: Int get() = if (dark) 0xFFF2F4F8.toInt() else 0xFF14161C.toInt()
    val sub: Int get() = if (dark) 0xFF9AA3B2.toInt() else 0xFF5B6475.toInt()
    val accent = 0xFFFF7A00.toInt()
    val accent2 = 0xFFFF4D1A.toInt()
    val ok = 0xFF2ECC71.toInt()
    val bad = 0xFFFF5252.toInt()
    val blue = 0xFF1E88E5.toInt()
    val amber = 0xFFFB8C00.toInt()
    val green = 0xFF43A047.toInt()
    val teal = 0xFF00897B.toInt()
    val pink = 0xFFD81B60.toInt()
}

class MainActivity : Activity() {

    private class Seg(val root: LinearLayout, val refresh: () -> Unit)

    private lateinit var focusHolder: LinearLayout
    private lateinit var dot: View
    private lateinit var statusText: TextView
    private lateinit var statusHint: TextView
    private lateinit var panelBtn: TextView
    private lateinit var setupCard: LinearLayout
    private lateinit var singleCard: LinearLayout
    private lateinit var actionsCard: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var autoBox: LinearLayout
    private lateinit var countText: TextView
    private lateinit var convText: TextView
    private lateinit var singleHint: TextView
    private lateinit var modeSeg: Seg
    private lateinit var typeSeg: Seg
    private lateinit var dirSeg: Seg
    private lateinit var pinchSeg: Seg
    private lateinit var delayInput: TimeInput
    private lateinit var batteryBtn: TextView
    private lateinit var stayStatus: TextView
    private lateinit var stayCmd: TextView
    private lateinit var stayCmdRow: LinearLayout
    private lateinit var batteryRow: View
    private lateinit var autostartRow: View
    private var accessRows: List<View> = emptyList()
    private val stepRows = mutableListOf<Pair<View, TextView>>()
    private var resumedAt = 0L
    private lateinit var stayNowBtn: TextView
    private val reqExport = 1
    private val reqImport = 2
    private val reqNotif = 7
    private val factors = longArrayOf(1L, 1000L, 60000L)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tr(s: String) = I18n.tr(s)

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.init(this)
        Session.init(this)
        CrashGuard.installLogger(this)
        CrashGuard.logExitReasons(this)
        val dark = when (Prefs.theme) {
            0 -> true
            1 -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        P.dark = dark
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        window.statusBarColor = P.bg
        window.navigationBarColor = P.bg
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(build())
        applyChrome()
        focusHolder.post { focusHolder.requestFocus() }
    }

    @Suppress("DEPRECATION")
    private fun applyChrome() {
        if (!P.dark) {
            var f = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = f
        }
        if (Prefs.language == 1) window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    override fun onResume() {
        super.onResume()
        resumedAt = SystemClock.elapsedRealtime()
        updateStatus()
        scheduleStatusRefresh()
        askFirstRun()
        updateBattery()
        updateStay()
        refreshList()
        refreshAutoApps()
        tryAutoEnable()
        // the service may have changed mode / script (auto-show profile, undo, ...)
        if (::modeSeg.isInitialized && ::singleHint.isInitialized) {
            modeSeg.refresh()
            applyModeVisibility()
            styleSingle()
        }
    }

    override fun onPause() {
        super.onPause()
        ClickerService.instance?.onSettingsChanged()
    }

    /** startActivity that can never crash the process (a dead process = "Not working" service). */
    private fun safeStart(i: Intent) {
        runCatching { startActivity(i) }.onFailure { CrashGuard.log("startActivity", it) }
    }

    @Suppress("DEPRECATION")
    private fun safeStartForResult(i: Intent, code: Int) {
        runCatching { startActivityForResult(i, code) }.onFailure {
            CrashGuard.log("startActivityForResult", it)
            toast("No app found to handle this")
        }
    }

    /** If Android switched the service off and the ADB permission was granted, switch it back on. */
    private fun tryAutoEnable() {
        if (ClickerService.instance != null) return
        if (!Prefs.autoEnable || !AccessibilityEnabler.canWrite(this)) return
        AccessibilityEnabler.ensureEnabled(this, repair = false)
        val deco = window.decorView
        deco.postDelayed({
            if (!isFinishing && ClickerService.instance == null) {
                AccessibilityEnabler.ensureEnabled(this, repair = true)
            }
        }, 2500)
        deco.postDelayed({ if (!isFinishing) updateStatus() }, 1500)
        deco.postDelayed({ if (!isFinishing) updateStatus() }, 4000)
    }

    private fun notifGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** First launch: ask for notifications, then for "no battery restrictions" (one dialog at a time, once). */
    private fun askFirstRun() {
        if (Build.VERSION.SDK_INT >= 33 && Prefs.notifStop && !Prefs.notifAsked && !notifGranted()) {
            Prefs.notifAsked = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif)
            return
        }
        if (!Prefs.batteryAsked && !batteryExempt()) {
            Prefs.batteryAsked = true
            window.decorView.postDelayed({ if (!isFinishing) requestBatteryExemption() }, 400)
        }
    }

    /** "No restrictions": whitelisted from battery optimization AND not put in "Restricted" background mode. */
    private fun batteryExempt(): Boolean = runCatching {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        pm.isIgnoringBatteryOptimizations(packageName) &&
            (Build.VERSION.SDK_INT < 28 || !am.isBackgroundRestricted)
    }.getOrDefault(false)

    /** Phones with their own Autostart screen (Xiaomi, Oppo/Realme, Vivo, Infinix/Tecno, Huawei ...). */
    private fun oemHasAutostart(): Boolean {
        val m = Build.MANUFACTURER.lowercase(Locale.US)
        return listOf(
            "xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "iqoo",
            "infinix", "tecno", "itel", "transsion", "huawei", "honor", "asus", "letv"
        ).any { m.contains(it) }
    }

    /** The app's own battery page (Allow background usage / Unrestricted). */
    private fun openBatteryDetails() {
        val ok = runCatching {
            startActivity(
                Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", Uri.parse("package:$packageName"))
            )
        }.isSuccess
        if (!ok) {
            safeStart(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    /** The service needs a few seconds to (re)connect; do not show alarms while it is starting. */
    private fun scheduleStatusRefresh() {
        val d = window.decorView
        for (t in longArrayOf(1000L, 3000L, 6000L, 9000L)) {
            d.postDelayed({ if (!isFinishing) updateStatus() }, t)
        }
    }

    // ---------- small UI toolkit ----------

    private fun rr(color: Int, radius: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun hbox() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun card(): LinearLayout = vbox().apply {
        background = if (P.dark) rr(P.card, 18) else rr(P.card, 18, P.cardStroke, 1)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun lpm(bottom: Int = 12) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(bottom) }

    private fun weight(left: Int = 0, right: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(left)
            rightMargin = dp(right)
        }

    /** All visible text goes through tr() so the whole UI follows the chosen language. */
    private fun text(t: String, sp: Float = 14f, color: Int = P.text, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = tr(t)
        v.textSize = sp
        v.setTextColor(color)
        if (bold) v.typeface = Typeface.DEFAULT_BOLD
        return v
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit): TextView {
        val b = text(label, 14f, if (filled) Color.WHITE else P.accent, true)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(14), dp(12), dp(14), dp(12))
        val bg: GradientDrawable = if (filled) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(P.accent, P.accent2))
                .apply { cornerRadius = dp(12).toFloat() }
        } else {
            rr(Color.TRANSPARENT, 12, P.accent, 1)
        }
        b.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)
        b.setOnClickListener { onClick() }
        return b
    }

    /** Icon-only button; long-press shows its name. */
    private fun iconButton(res: Int, label: String, filled: Boolean = false, onClick: () -> Unit): ImageView {
        val v = ImageView(this)
        v.setImageResource(res)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        v.setColorFilter(if (filled) Color.WHITE else P.accent)
        v.setPadding(dp(11), dp(11), dp(11), dp(11))
        val bg: GradientDrawable = if (filled) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(P.accent, P.accent2))
                .apply { cornerRadius = dp(12).toFloat() }
        } else {
            rr(Color.TRANSPARENT, 12, P.accent, 1)
        }
        v.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)
        v.contentDescription = tr(label)
        v.tooltipText = tr(label)
        v.setOnClickListener { onClick() }
        return v
    }

    private fun iconW(left: Int = 0, right: Int = 0) =
        LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            leftMargin = dp(left)
            rightMargin = dp(right)
        }

    private fun EditText.onChange(cb: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = cb(s?.toString() ?: "")
        })
    }

    private fun hideKeyboardAndClear(v: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
        if (::focusHolder.isInitialized) focusHolder.requestFocus()
    }

    private fun field(initial: String): EditText {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText(initial)
        e.setTextColor(P.text)
        e.setHintTextColor(P.sub)
        e.background = rr(P.field, 12)
        e.setPadding(dp(12), dp(10), dp(12), dp(10))
        e.imeOptions = EditorInfo.IME_ACTION_DONE
        e.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboardAndClear(v)
                true
            } else {
                false
            }
        }
        return e
    }

    private fun toast(t: String) = Toast.makeText(this, tr(t), Toast.LENGTH_SHORT).show()

    private fun segmented(
        labels: List<String>,
        selected: () -> Int,
        neutral: Boolean = false,
        textSp: Float = 14f,
        onSelect: (Int) -> Unit
    ): Seg {
        val root = hbox()
        root.background = rr(if (neutral) P.segTrack else P.field, 14)
        root.setPadding(dp(4), dp(4), dp(4), dp(4))
        val views = labels.mapIndexed { i, l ->
            val v = text(l, textSp, P.sub, true)
            v.gravity = Gravity.CENTER
            v.setPadding(dp(4), dp(10), dp(4), dp(10))
            v.setOnClickListener { onSelect(i) }
            v
        }
        views.forEach { root.addView(it, weight()) }
        val refresh = {
            views.forEachIndexed { i, v ->
                val sel = i == selected()
                v.background = if (sel) rr(if (neutral) P.segThumb else P.accent, 10) else null
                v.setTextColor(if (sel) (if (neutral) P.text else Color.WHITE) else P.sub)
            }
        }
        refresh()
        return Seg(root, refresh)
    }

    /** Segmented control that keeps its own selection (used in dialogs). */
    private fun segBound(labels: List<String>, initial: Int, textSp: Float = 14f, onChange: (Int) -> Unit): Seg {
        var sel = initial
        lateinit var s: Seg
        s = segmented(labels, { sel }, neutral = true, textSp = textSp) { i ->
            sel = i
            s.refresh()
            onChange(i)
        }
        return s
    }

    private fun checkRow(
        parent: LinearLayout, label: String, hint: String?,
        get: () -> Boolean, set: (Boolean) -> Unit
    ): CheckBox {
        val cb = CheckBox(this)
        cb.text = tr(label)
        cb.setTextColor(P.text)
        cb.textSize = 14f
        cb.buttonTintList = ColorStateList.valueOf(P.accent)
        cb.isChecked = get()
        cb.setOnCheckedChangeListener { _, c -> set(c) }
        parent.addView(cb)
        if (hint != null) {
            val h = text(hint, 12f, P.sub)
            h.setPadding(0, dp(2), 0, dp(12))
            parent.addView(h)
        }
        return cb
    }

    private fun slider(
        parent: LinearLayout, title: String, min: Int, max: Int, unit: String,
        get: () -> Int, onChange: (Int) -> Unit, onDone: () -> Unit
    ) {
        val head = hbox()
        head.addView(text(title, 14f, P.text, true), weight())
        val value = text("${get()} $unit", 13f, P.accent, true)
        head.addView(value)
        parent.addView(head, lpm(4))
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = get() - min
        sb.progressTintList = ColorStateList.valueOf(P.accent)
        sb.thumbTintList = ColorStateList.valueOf(P.accent)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                onChange(min + progress)
                value.text = "${get()} $unit"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) = onDone()
        })
        parent.addView(sb, lpm(10))
    }

    // ---------- time helpers ----------

    private fun fmt(ms: Long, unit: Int): String {
        val v = ms.toDouble() / factors[unit]
        return if (v == Math.floor(v)) v.toLong().toString()
        else String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
    }

    private fun pickUnit(ms: Long): Int = when {
        ms > 0 && ms % 60000L == 0L -> 2
        ms >= 1000 && ms % 1000L == 0L -> 1
        else -> 0
    }

    private fun humanMs(ms: Long): String = when {
        ms > 0 && ms % 60000L == 0L -> "${ms / 60000} min"
        ms >= 1000 && ms % 1000L == 0L -> "${ms / 1000} s"
        else -> "$ms ms"
    }

    /**
     * Number input with a segmented unit selector (ms / sec / min) shown BELOW the input.
     * The value is always reported in milliseconds.
     */
    private inner class TimeInput(ms0: Long, unit0: Int, big: Boolean, private val onChanged: (Long, Int) -> Unit) {
        val root = vbox()
        private val edit = field("")
        private var unit = unit0.coerceIn(0, 2)
        private var guard = false
        private lateinit var seg: Seg

        init {
            edit.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (big) {
                edit.textSize = 26f
                edit.typeface = Typeface.DEFAULT_BOLD
                edit.gravity = Gravity.CENTER
            }
            guard = true
            edit.setText(fmt(ms0, unit))
            guard = false
            edit.onChange { if (!guard) onChanged(getMs(), unit) }
            seg = segmented(listOf("ms", "sec", "min"), { unit }, neutral = true) { i ->
                if (i != unit) {
                    unit = i
                    seg.refresh()
                    onChanged(getMs(), unit)
                }
            }
            root.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(
                seg.root,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) }
            )
        }

        fun unitIdx() = unit

        fun getMs(): Long {
            val v = edit.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            if (v.isNaN() || v.isInfinite()) return 0L
            return Math.round(v * factors[unit]).coerceIn(0L, 86_400_000L)
        }

        fun setMs(ms: Long, autoUnit: Boolean) {
            if (autoUnit) unit = pickUnit(ms)
            guard = true
            seg.refresh()
            edit.setText(fmt(ms, unit))
            guard = false
            onChanged(ms, unit)
        }
    }

    // ---------- screen ----------

    private fun build(): View {
        val sv = ScrollView(this)
        sv.setBackgroundColor(P.bg)
        val root = vbox()
        root.setPadding(dp(16), dp(20), dp(16), dp(32))
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        focusHolder = root

        root.addView(header(), lpm(16))
        root.addView(statusCard(), lpm())
        setupCard = buildSetupCard()
        root.addView(setupCard, lpm())
        root.addView(stayCard(), lpm())
        root.addView(modeCard(), lpm())
        singleCard = singleCard()
        root.addView(singleCard, lpm())
        actionsCard = actionsCard()
        root.addView(actionsCard, lpm())
        root.addView(timingCard(), lpm())
        root.addView(timerCard(), lpm())
        root.addView(advancedCard(), lpm())
        root.addView(indicatorsCard(), lpm())
        root.addView(panelCard(), lpm())
        root.addView(controlsCard(), lpm())
        root.addView(safetyCard(), lpm())
        root.addView(autoAppsCard(), lpm())
        root.addView(appearanceCard(), lpm())

        applyModeVisibility()
        sv.addView(root)
        return sv
    }

    private fun header(): View {
        val row = hbox()
        row.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(P.accent, P.accent2)
        ).apply { cornerRadius = dp(20).toFloat() }
        row.setPadding(dp(16), dp(16), dp(16), dp(16))

        val iv = ImageView(this)
        iv.setImageResource(R.drawable.ic_launcher_art)
        iv.clipToOutline = true
        iv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
            }
        }
        row.addView(iv, LinearLayout.LayoutParams(dp(64), dp(64)))

        val col = vbox()
        col.setPadding(dp(14), 0, dp(14), 0)
        col.addView(text("Open Clicker", 22f, Color.WHITE, true))
        col.addView(text("Click · Hold · Slide · Scroll · Pinch", 13f, 0xCCFFFFFF.toInt()))
        row.addView(col)
        return row
    }

    private fun statusCard(): View {
        val c = card()
        val row = hbox()
        dot = View(this)
        row.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))
        statusText = text("", 15f, P.text, true)
        statusText.setPadding(dp(10), 0, dp(10), 0)
        row.addView(statusText)
        c.addView(row, lpm(8))
        statusHint = text("", 12f, P.sub)
        statusHint.visibility = View.GONE
        c.addView(statusHint, lpm(10))

        panelBtn = button("Show floating panel", true) {
            val s = ClickerService.instance
            if (s == null) {
                if (AccessibilityEnabler.ensureEnabled(this, repair = true, force = true)) {
                    toast("Enabling…")
                    window.decorView.postDelayed({ if (!isFinishing) updateStatus() }, 1500)
                } else {
                    toast("Enable the accessibility service first")
                    openAccessibility()
                }
            } else {
                if (s.isPanelVisible()) {
                    s.hidePanel()
                } else {
                    s.showPanel()
                    s.onSettingsChanged()
                    moveTaskToBack(true)
                }
                updateStatus()
            }
        }
        c.addView(panelBtn, lpm(0))
        return c
    }

    private fun serviceStarting(): Boolean =
        ClickerService.instance == null && enabledInSettings() &&
            SystemClock.elapsedRealtime() - resumedAt < 8000L

    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        val on = ClickerService.instance != null
        val starting = serviceStarting()
        dot.background = oval(
            when {
                on -> P.ok
                starting -> P.amber
                else -> P.bad
            }
        )
        statusText.text = tr(
            when {
                on -> "Service active"
                starting -> "Service starting…"
                enabledInSettings() -> "Enabled but not running. Toggle it OFF and ON in Accessibility."
                else -> "Service not enabled"
            }
        )
        val visible = ClickerService.instance?.isPanelVisible() == true
        panelBtn.text = tr(if (visible) "Hide floating panel" else "Show floating panel")
        updateWhyStopped(on || starting)
        updateSetup()
    }

    /** When the service is not running, tell the user WHY the app process died last time. */
    private fun updateWhyStopped(runningOrStarting: Boolean) {
        val e = if (runningOrStarting) null else CrashGuard.recentExits(this, 1).firstOrNull()
        if (e == null) {
            statusHint.visibility = View.GONE
            return
        }
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(e.whenMs))
        statusHint.text = "${tr("Last stop")} ($time): ${tr(exitMessage(e.reason))}"
        statusHint.visibility = View.VISIBLE
    }

    private fun exitMessage(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE ->
            "The app crashed. Please use Share error log."
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Android closed the app because memory was low."
        ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED ->
            "The app was closed from Recents / Force stop. Android then switches the service off."
        ApplicationExitInfo.REASON_ANR -> "The app was not responding."
        ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_OTHER ->
            "A phone cleaner / battery manager closed the app. Turn on Autostart and set Battery to No restrictions."
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Closed because a permission changed."
        else -> "The app was closed by Android."
    }

    /** Opens our service's own page in Accessibility settings (one tap to switch it ON). */
    private fun openAccessibility() {
        val ok = runCatching { startActivity(AccessibilityEnabler.detailsIntent(this)) }.isSuccess
        if (!ok) safeStart(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun enabledInSettings(): Boolean = AccessibilityEnabler.isEnabledInSettings(this)

    private fun buildSetupCard(): LinearLayout {
        val c = card()
        c.addView(text("Setup", 16f, P.text, true), lpm(8))
        stepRows.clear()
        val access = mutableListOf<View>()
        if (Build.VERSION.SDK_INT >= 33) {
            access.add(
                step(
                    c, "Allow restricted settings",
                    "App info → ⋮ (top right) → Allow restricted settings. Needed once if the app was installed from an APK file.",
                    "Open App info"
                ) {
                    safeStart(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            )
        }
        access.add(
            step(
                c, "Enable accessibility service",
                "Installed apps → Open Clicker → turn ON. If Settings says 'malfunctioning', switch it OFF and then ON again.",
                "Open Accessibility"
            ) {
                openAccessibility()
            }
        )
        accessRows = access
        batteryRow = step(
            c, "Battery: no restrictions",
            "Set Open Clicker to 'No restrictions' (Unrestricted). Tap Allow and confirm. On some phones also set it under App info → Battery. Without this the battery manager closes the app and the accessibility service stops.",
            "Allow", "Battery settings", { openBatteryDetails() }
        ) { requestBatteryExemption() }
        autostartRow = step(
            c, "Autostart / background start",
            "Your phone has its own switch that stops apps from restarting after the system closes them. Turn it ON for Open Clicker.",
            "Open autostart settings", "Done",
            {
                Prefs.autostartDone = true
                updateSetup()
            }
        ) { openAutostart() }
        return c
    }

    /** A setup row with one button (trailing lambda) and an optional second button. */
    private fun step(
        parent: LinearLayout, title: String, desc: String, label: String,
        label2: String? = null, action2: (() -> Unit)? = null,
        action: () -> Unit
    ): View {
        val row = hbox()
        row.gravity = Gravity.TOP
        val badge = text("•", 13f, Color.WHITE, true)
        badge.gravity = Gravity.CENTER
        badge.background = oval(P.accent)
        row.addView(badge, LinearLayout.LayoutParams(dp(26), dp(26)))
        val col = vbox()
        col.setPadding(dp(12), 0, dp(12), 0)
        col.addView(text(title, 14f, P.text, true))
        val d = text(desc, 12f, P.sub)
        d.setPadding(0, dp(2), 0, dp(8))
        col.addView(d)
        if (label2 != null && action2 != null) {
            val buttons = hbox()
            buttons.addView(button(label, false, action), weight(0, 4))
            buttons.addView(button(label2, false, action2), weight(4, 0))
            col.addView(buttons)
        } else {
            col.addView(button(label, false, action))
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row, lpm(12))
        stepRows.add(row to badge)
        return row
    }

    /** Only show the steps that are still missing; hide the whole card when everything is fine. */
    private fun updateSetup() {
        if (!::setupCard.isInitialized || !::batteryRow.isInitialized || !::autostartRow.isInitialized) return
        val on = ClickerService.instance != null
        val needAccess = !on && !serviceStarting()
        val needBattery = !batteryExempt()
        val needAutostart = oemHasAutostart() && !Prefs.autostartDone
        accessRows.forEach { it.visibility = if (needAccess) View.VISIBLE else View.GONE }
        batteryRow.visibility = if (needBattery) View.VISIBLE else View.GONE
        autostartRow.visibility = if (needAutostart) View.VISIBLE else View.GONE
        var n = 1
        for ((row, badge) in stepRows) {
            if (row.visibility == View.VISIBLE) badge.text = (n++).toString()
        }
        setupCard.visibility = if (needAccess || needBattery || needAutostart) View.VISIBLE else View.GONE
    }

    // ---------- stay enabled (self re-enable via WRITE_SECURE_SETTINGS) ----------

    private fun stayCard(): View {
        val c = card()
        c.addView(text("Stay enabled", 16f, P.text, true))
        val hint = text(
            "Android can turn the accessibility service off by itself (force-stop, battery cleaner, update). Grant one permission once over ADB and the app switches it back on automatically, so you never visit Accessibility settings again.",
            12f, P.sub
        )
        hint.setPadding(0, dp(2), 0, dp(10))
        c.addView(hint)

        stayStatus = text("", 14f, P.text, true)
        c.addView(stayStatus, lpm(8))

        stayCmd = text(AccessibilityEnabler.adbCommand(this), 12f, P.accent, true)
        stayCmd.typeface = Typeface.MONOSPACE
        stayCmd.setTextIsSelectable(true)
        stayCmd.background = rr(P.field, 10)
        stayCmd.setPadding(dp(10), dp(10), dp(10), dp(10))
        stayCmdRow = hbox()
        stayCmdRow.addView(stayCmd, weight(0, 8))
        stayCmdRow.addView(
            iconButton(R.drawable.ic_copy, "Copy ADB command") {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("adb", AccessibilityEnabler.adbCommand(this)))
                toast("Copied")
            },
            LinearLayout.LayoutParams(dp(46), dp(46))
        )
        c.addView(stayCmdRow, lpm(8))

        stayNowBtn = button("Enable service now", true) {
            val ok = AccessibilityEnabler.ensureEnabled(this, repair = true, force = true)
            toast(if (ok) "Enabling…" else "Needs the one-time ADB permission first")
            window.decorView.postDelayed({ if (!isFinishing) updateStatus() }, 1500)
        }
        c.addView(stayNowBtn, lpm(8))

        checkRow(
            c, "Re-enable automatically",
            "Turns the service back on after a force-stop, update or reboot. Untick it if you want to switch the service off yourself.",
            { Prefs.autoEnable }, { Prefs.autoEnable = it }
        )
        updateStay()
        return c
    }

    private fun updateStay() {
        if (!::stayStatus.isInitialized) return
        val granted = AccessibilityEnabler.canWrite(this)
        stayStatus.text = tr(if (granted) "Auto-enable is active ✓" else "Auto-enable is not set up")
        stayStatus.setTextColor(if (granted) P.ok else P.sub)
        stayCmdRow.visibility = if (granted) View.GONE else View.VISIBLE
        stayNowBtn.visibility = if (granted) View.VISIBLE else View.GONE
    }

    // ---------- mode + single action ----------

    private fun modeCard(): View {
        val c = card()
        c.addView(text("Mode", 16f, P.text, true), lpm(10))
        modeSeg = segmented(listOf("Single target", "Multi target"), { if (Prefs.multi) 1 else 0 }) { i ->
            Prefs.multi = (i == 1)
            modeSeg.refresh()
            applyModeVisibility()
            ClickerService.instance?.onSettingsChanged()
        }
        c.addView(modeSeg.root, lpm(0))
        return c
    }

    private fun applyModeVisibility() {
        singleCard.visibility = if (Prefs.multi) View.GONE else View.VISIBLE
        actionsCard.visibility = if (Prefs.multi) View.VISIBLE else View.GONE
    }

    private fun singleCard(): LinearLayout {
        val c = card()
        c.addView(text("Action", 16f, P.text, true), lpm(10))
        typeSeg = segmented(
            listOf("Click", "Slide", "Scroll", "Hold", "Pinch"), { Prefs.singleType }, textSp = 13f
        ) { i ->
            Prefs.singleType = i
            styleSingle()
            ClickerService.instance?.onSettingsChanged()
        }
        c.addView(typeSeg.root, lpm(8))

        dirSeg = segmented(
            listOf("↑ Up", "↓ Down", "← Left", "→ Right"), { Prefs.scrollDir }, neutral = true, textSp = 13f
        ) { i ->
            Prefs.scrollDir = i
            styleSingle()
            ClickerService.instance?.onSettingsChanged()
        }
        c.addView(dirSeg.root, lpm(8))

        pinchSeg = segmented(
            listOf("Zoom in", "Zoom out"), { if (Prefs.pinchZoomIn) 0 else 1 }, neutral = true
        ) { i ->
            Prefs.pinchZoomIn = (i == 0)
            styleSingle()
        }
        c.addView(pinchSeg.root, lpm(8))

        singleHint = text("", 12f, P.sub)
        c.addView(singleHint)
        styleSingle()
        return c
    }

    private fun styleSingle() {
        typeSeg.refresh()
        dirSeg.refresh()
        pinchSeg.refresh()
        val t = Prefs.singleType
        dirSeg.root.visibility = if (t == 2) View.VISIBLE else View.GONE
        pinchSeg.root.visibility = if (t == 4) View.VISIBLE else View.GONE
        singleHint.text = tr(
            when (t) {
                0 -> "Drag the ◎ marker to the point you want to click."
                1 -> "Drag S (start) and E (end). The finger slides from S to E."
                2 -> "Drag the marker to where the scroll starts. Direction is where the content moves to (↓ shows lower content)."
                3 -> "Drag the H marker. The finger holds there for the Hold time set in Advanced."
                else -> "Drag the P marker to the center of the pinch. Zoom in spreads two fingers, Zoom out brings them together."
            }
        )
    }

    // ---------- delay ----------

    private fun timingCard(): View {
        val c = card()
        c.addView(text("Delay between actions", 16f, P.text, true))
        val hint = text("Choose ms, sec or min. It is converted to milliseconds automatically.", 12f, P.sub)
        hint.setPadding(0, dp(2), 0, dp(12))
        c.addView(hint)

        convText = text("", 13f, P.accent, true)
        convText.gravity = Gravity.CENTER

        delayInput = TimeInput(Prefs.delayMs, Prefs.delayUnit, true) { ms, unit ->
            Prefs.delayMs = ms
            Prefs.delayUnit = unit
            updateConv(ms)
        }
        c.addView(delayInput.root, lpm(10))

        val row = hbox()
        row.addView(button("−", false) { stepDelay(-1) }, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(convText, weight(8, 8))
        row.addView(button("+", false) { stepDelay(1) }, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        c.addView(row, lpm(12))
        updateConv(Prefs.delayMs)

        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        val chips = hbox()
        val presets = listOf(10L, 50L, 100L, 250L, 500L, 1000L, 2000L, 60000L, 180000L, 300000L)
        for (ms in presets) {
            val chip = text(humanMs(ms), 13f, P.text, true)
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(14), dp(8), dp(14), dp(8))
            chip.background = rr(P.field, 20)
            chip.setOnClickListener {
                delayInput.setMs(ms, true)
                hideKeyboardAndClear(chip)
            }
            chips.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
            )
        }
        scroll.addView(chips)
        c.addView(scroll)
        return c
    }

    private fun updateConv(ms: Long) {
        convText.text = "= ${String.format(Locale.US, "%,d", ms)} ms"
    }

    private fun stepDelay(dir: Int) {
        val cur = delayInput.getMs()
        val step = when (delayInput.unitIdx()) {
            0 -> if (cur < 100) 10L else if (cur < 1000) 50L else 100L
            1 -> if (cur < 1000) 100L else 1000L
            else -> if (cur < 60000) 10000L else 60000L
        }
        delayInput.setMs((cur + dir * step).coerceAtLeast(0L), false)
    }

    // ---------- timer ----------

    private fun timerCard(): View {
        val c = card()
        c.addView(text("Timer", 16f, P.text, true), lpm(4))
        numRow(c, "Start countdown (seconds, 0 = off)", { Prefs.countdownSec.toLong() }, { Prefs.countdownSec = it.coerceIn(0L, 30L).toInt() })
        val gap = text("Auto-stop after (0 = never)", 14f, P.sub)
        gap.setPadding(0, dp(14), 0, dp(6))
        c.addView(gap)
        val ti = TimeInput(Prefs.stopAfterMs, Prefs.stopAfterUnit, false) { ms, unit ->
            Prefs.stopAfterMs = ms
            Prefs.stopAfterUnit = unit
        }
        c.addView(ti.root)
        return c
    }

    // ---------- advanced ----------

    private fun advancedCard(): View {
        val c = card()
        c.addView(text("Advanced", 16f, P.text, true), lpm(4))
        numRow(c, "Repeat count (0 = infinite)", { Prefs.loops.toLong() }, { Prefs.loops = it.coerceIn(0L, 1_000_000_000L).toInt() })
        val hint = text("Single mode: how many times the action is performed. Multi mode: how many times the whole sequence runs.", 11f, P.sub)
        hint.setPadding(0, dp(4), 0, dp(4))
        c.addView(hint)
        numRow(c, "Tap duration (ms)", { Prefs.tapDurationMs }, { Prefs.tapDurationMs = it })
        numRow(c, "Hold time (ms)", { Prefs.longPressMs }, { Prefs.longPressMs = it })
        numRow(c, "Slide / scroll / pinch duration (ms)", { Prefs.swipeDurationMs }, { Prefs.swipeDurationMs = it })
        numRow(c, "Scroll distance (% of screen)", { Prefs.scrollDistPct.toLong() }, { Prefs.scrollDistPct = it.coerceIn(1L, 100L).toInt() })
        numRow(c, "Pinch span (% of screen)", { Prefs.pinchSpanPct.toLong() }, { Prefs.pinchSpanPct = it.coerceIn(5L, 100L).toInt() })
        numRow(c, "Random position ± (px)", { Prefs.randomOffsetPx.toLong() }, { Prefs.randomOffsetPx = it.coerceIn(0L, 1000L).toInt() })
        numRow(c, "Random interval ± (ms)", { Prefs.randomIntervalMs.toLong() }, { Prefs.randomIntervalMs = it.coerceIn(0L, 600_000L).toInt() })
        val sp = View(this)
        c.addView(sp, LinearLayout.LayoutParams(1, dp(10)))
        checkRow(
            c, "Human-like slide & scroll",
            "Curved path and slightly random speed so gestures look less robotic.",
            { Prefs.humanSwipe }, { Prefs.humanSwipe = it }
        )
        return c
    }

    private fun numRow(parent: LinearLayout, label: String, get: () -> Long, set: (Long) -> Unit) {
        val row = hbox()
        row.setPadding(0, dp(8), 0, 0)
        val t = text(label, 14f, P.sub)
        val e = field(get().toString())
        e.gravity = Gravity.END
        e.onChange { set(it.toLongOrNull() ?: 0L) }
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(e, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
        })
        parent.addView(row)
    }

    // ---------- indicators / panel ----------

    private fun indicatorsCard(): View {
        val c = card()
        c.addView(text("Indicators", 16f, P.text, true), lpm(8))
        slider(
            c, "Indicator size", 28, 96, "dp",
            { Prefs.markerSizeDp },
            { Prefs.markerSizeDp = it; ClickerService.instance?.onMarkerSizeChanged() },
            {}
        )
        val hint = text("Show the floating panel to preview the size live.", 12f, P.sub)
        hint.setPadding(0, 0, 0, dp(12))
        c.addView(hint)
        checkRow(
            c, "Lock indicators",
            "Locked indicators cannot be dragged by accident. You can also use the 🔒 button on the panel.",
            { Prefs.markersLocked },
            { Prefs.markersLocked = it; ClickerService.instance?.onSettingsChanged() }
        )
        return c
    }

    private fun panelCard(): View {
        val c = card()
        c.addView(text("Floating panel", 16f, P.text, true), lpm(8))
        slider(
            c, "Opacity", 30, 100, "%",
            { Prefs.panelOpacityPct },
            { Prefs.panelOpacityPct = it; ClickerService.instance?.onPanelStyleChanged(false) },
            {}
        )
        slider(
            c, "Size", 60, 160, "%",
            { Prefs.panelScalePct },
            { Prefs.panelScalePct = it },
            { ClickerService.instance?.onPanelStyleChanged(true) }
        )
        val hint = text("Tap the ⠿ handle to minimize or maximize the panel. Drag it to move.", 12f, P.sub)
        c.addView(hint)
        return c
    }

    // ---------- controls ----------

    private fun controlsCard(): View {
        val c = card()
        c.addView(text("Controls", 16f, P.text, true), lpm(8))
        checkRow(
            c, "Volume Up + Down together = start / stop",
            "Hold both volume keys at once. Android lists this as observing key presses when you enable the service.",
            { Prefs.volumeKeyToggle },
            { Prefs.volumeKeyToggle = it; ClickerService.instance?.onSettingsChanged() }
        )
        checkRow(
            c, "Show Stop notification while running",
            "An ongoing notification with a Stop button.",
            { Prefs.notifStop },
            {
                Prefs.notifStop = it
                if (it && Build.VERSION.SDK_INT >= 33 && !notifGranted()) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif)
                }
            }
        )
        val tile = text(
            "Quick Settings tile: swipe down twice, tap the edit (pencil) icon and drag 'Open Clicker' into your tiles.",
            12f, P.sub
        )
        c.addView(tile)
        return c
    }

    // ---------- safety ----------

    private fun safetyCard(): View {
        val c = card()
        c.addView(text("Safety & reliability", 16f, P.text, true), lpm(8))
        checkRow(
            c, "Auto-stop when screen turns off",
            "Kill switch. If an infinite loop hangs the phone, locking the screen stops the clicker. Turn OFF to keep clicking with the screen off.",
            { Prefs.stopOnScreenOff }, { Prefs.stopOnScreenOff = it }
        )
        checkRow(
            c, "Crash guard (experimental)",
            "Keeps the main thread alive after unexpected errors. Leave OFF unless the service keeps crashing. Takes effect after the service restarts.",
            { Prefs.crashGuard }, { Prefs.crashGuard = it }
        )
        c.addView(text("Keep service alive", 14f, P.text, true))
        val hint2 = text(
            "If Android keeps turning the accessibility service off, remove battery limits. On Xiaomi / Oppo / Vivo / Samsung also enable Autostart and lock the app in Recents.",
            12f, P.sub
        )
        hint2.setPadding(0, dp(2), 0, dp(8))
        c.addView(hint2)
        batteryBtn = button("", false) { requestBatteryExemption() }
        c.addView(batteryBtn, lpm(8))
        c.addView(button("Open autostart settings", false) { openAutostart() }, lpm(8))
        c.addView(button("Share error log", false) { shareErrorLog() })
        updateBattery()
        return c
    }

    private fun shareErrorLog() {
        val log = CrashGuard.readLog()
        if (log.isBlank()) {
            toast("No errors logged")
            return
        }
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_SUBJECT, "Open Clicker error log")
        send.putExtra(Intent.EXTRA_TEXT, log.takeLast(30_000))
        safeStart(Intent.createChooser(send, tr("Share error log")))
    }

    private fun updateBattery() {
        if (::batteryBtn.isInitialized) {
            batteryBtn.text = tr(if (batteryExempt()) "Battery: unrestricted ✓" else "Disable battery optimization")
        }
        updateSetup()
    }

    private fun requestBatteryExemption() {
        val first = runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure { CrashGuard.log("battery intent", it) }
        if (first.isFailure) {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    /** Many phones (Xiaomi, Oppo/Realme, Vivo, Infinix/Tecno, Huawei...) have their own autostart screen. */
    private fun openAutostart() {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.transsion.phonemaster" to "com.cyin.himgr.autostart.AutoStartActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity"
        )
        for ((pkg, cls) in candidates) {
            val ok = runCatching {
                startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
        toast("Allow Autostart and Background activity for Open Clicker.")
        safeStart(
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    // ---------- auto-show apps ----------

    private fun autoAppsCard(): View {
        val c = card()
        val head = hbox()
        head.addView(text("Auto-show in apps", 16f, P.text, true), weight())
        head.addView(iconButton(R.drawable.ic_add, "Add app") { pickApp() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        c.addView(head)
        val hint = text(
            "The floating panel appears by itself when one of these apps opens. Optionally load a saved script for it.",
            12f, P.sub
        )
        hint.setPadding(0, dp(2), 0, dp(10))
        c.addView(hint)
        autoBox = vbox()
        c.addView(autoBox, lpm(0))
        return c
    }

    private fun appLabel(pkg: String): String =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)

    private fun refreshAutoApps() {
        if (!::autoBox.isInitialized) return
        autoBox.removeAllViews()
        val map = Prefs.autoAppMap()
        if (map.isEmpty()) {
            autoBox.addView(text("No apps added.", 13f, P.sub))
            return
        }
        for ((pkg, script) in map) {
            val row = vbox()
            row.background = rr(P.field, 12)
            row.setPadding(dp(12), dp(10), dp(12), dp(10))
            row.addView(text(appLabel(pkg), 14f, P.text, true))
            val sub = if (script.isEmpty()) tr("Keep current script") else "${tr("Script")}: $script"
            row.addView(text(sub, 12f, P.sub))
            row.setOnClickListener { autoAppDialog(pkg) }
            autoBox.addView(row, lpm(8))
        }
    }

    private fun pickApp() {
        toast("Loading apps…")
        Thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = runCatching {
                pm.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .filter { it.first != packageName }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (apps.isEmpty()) {
                    toast("No apps found")
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle(tr("Choose app"))
                    .setItems(apps.map { it.second }.toTypedArray()) { _, which ->
                        val m = Prefs.autoAppMap()
                        m[apps[which].first] = ""
                        Prefs.saveAutoAppMap(m)
                        refreshAutoApps()
                    }
                    .setNegativeButton(tr("Cancel"), null)
                    .show()
            }
        }.start()
    }

    private fun autoAppDialog(pkg: String) {
        val items = arrayOf(tr("Choose script"), tr("Keep current script"), tr("Remove"))
        AlertDialog.Builder(this)
            .setTitle(appLabel(pkg))
            .setItems(items) { _, which ->
                val m = Prefs.autoAppMap()
                when (which) {
                    0 -> {
                        val names = ScriptStore.names(this)
                        if (names.isEmpty()) {
                            toast("No saved scripts")
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle(tr("Saved scripts"))
                                .setItems(names.toTypedArray()) { _, w ->
                                    val mm = Prefs.autoAppMap()
                                    mm[pkg] = names[w]
                                    Prefs.saveAutoAppMap(mm)
                                    refreshAutoApps()
                                }
                                .show()
                        }
                    }
                    1 -> {
                        m[pkg] = ""
                        Prefs.saveAutoAppMap(m)
                        refreshAutoApps()
                    }
                    else -> {
                        m.remove(pkg)
                        Prefs.saveAutoAppMap(m)
                        refreshAutoApps()
                    }
                }
            }
            .show()
    }

    // ---------- appearance ----------

    private fun appearanceCard(): View {
        val c = card()
        c.addView(text("Appearance", 16f, P.text, true), lpm(10))
        c.addView(text("Language", 13f, P.sub), lpm(4))
        val lang = segmented(listOf("English", "اردو"), { Prefs.language }, neutral = true) { i ->
            if (i != Prefs.language) {
                Prefs.language = i
                recreate()
            }
        }
        c.addView(lang.root, lpm(12))
        c.addView(text("Theme", 13f, P.sub), lpm(4))
        val theme = segmented(listOf("Dark", "Light", "System"), { Prefs.theme }, neutral = true) { i ->
            if (i != Prefs.theme) {
                Prefs.theme = i
                recreate()
            }
        }
        c.addView(theme.root, lpm(0))
        return c
    }

    // ---------- actions list (multi mode) ----------

    private fun actionsCard(): LinearLayout {
        val c = card()

        val head = hbox()
        head.addView(text("Actions", 16f, P.text, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        countText = text("0", 13f, P.accent, true)
        head.addView(countText)
        c.addView(head)
        val hint = text("Open the + menu on the floating panel to add Click, Hold, Slide, Scroll, Pinch, Wait or system buttons. Record with ●. Tap a row to edit.", 12f, P.sub)
        hint.setPadding(0, dp(2), 0, dp(8))
        c.addView(hint)

        listBox = vbox()
        c.addView(listBox, lpm(8))

        val ra = hbox()
        ra.addView(iconButton(R.drawable.ic_undo, "Undo") { undo() }, iconW(0, 4))
        ra.addView(iconButton(R.drawable.ic_delete, "Clear all") {
            AlertDialog.Builder(this)
                .setTitle(tr("Clear all actions?"))
                .setPositiveButton(tr("Clear")) { _, _ ->
                    Session.checkpoint()
                    Session.script.actions.clear()
                    changed()
                }
                .setNegativeButton(tr("Cancel"), null)
                .show()
        }, iconW(4, 4))
        ra.addView(iconButton(R.drawable.ic_save, "Save") {
            promptName { n ->
                ScriptStore.save(this, Script(n, Session.script.actions.map { it.copy() }.toMutableList()))
                toast("Saved")
            }
        }, iconW(4, 4))
        ra.addView(iconButton(R.drawable.ic_folder_open, "Load") { loadDialog() }, iconW(4, 0))
        c.addView(ra, lpm(8))

        val rb = hbox()
        rb.addView(iconButton(R.drawable.ic_upload, "Export") {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "application/json"
            i.putExtra(Intent.EXTRA_TITLE, "clicker-script.json")
            safeStartForResult(i, reqExport)
        }, iconW(0, 4))
        rb.addView(iconButton(R.drawable.ic_download, "Import") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            safeStartForResult(i, reqImport)
        }, iconW(4, 4))
        rb.addView(iconButton(R.drawable.ic_share, "Share") { shareScript() }, iconW(4, 4))
        rb.addView(iconButton(R.drawable.ic_paste, "Paste") { pasteScript() }, iconW(4, 0))
        c.addView(rb, lpm(0))
        return c
    }

    /** Persist + refresh everything after the script changed. */
    private fun changed() {
        Session.persist()
        refreshList()
        ClickerService.instance?.refreshMarkers()
    }

    private fun undo() {
        if (Session.undo()) {
            refreshList()
            ClickerService.instance?.refreshMarkers()
            toast("Undone")
        } else {
            toast("Nothing to undo")
        }
    }

    private fun miniIcon(res: Int, enabled: Boolean, onClick: () -> Unit): ImageView {
        val v = ImageView(this)
        v.setImageResource(res)
        v.setColorFilter(P.accent)
        v.alpha = if (enabled) 1f else 0.25f
        v.setPadding(dp(6), dp(8), dp(6), dp(8))
        if (enabled) v.setOnClickListener { onClick() }
        return v
    }

    private fun refreshList() {
        if (!::listBox.isInitialized) return
        listBox.removeAllViews()
        val acts = Session.script.actions
        countText.text = "${acts.size} ${tr("total")}"
        if (acts.isEmpty()) {
            listBox.addView(text("No actions yet.", 13f, P.sub))
            return
        }
        acts.forEachIndexed { i, a ->
            val row = hbox()
            row.background = rr(P.field, 12)
            row.setPadding(dp(10), dp(8), dp(6), dp(8))
            val badge = text("${i + 1}", 13f, Color.WHITE, true)
            badge.gravity = Gravity.CENTER
            badge.background = oval(
                when (a.type) {
                    ActionType.TAP -> P.blue
                    ActionType.SWIPE -> P.amber
                    ActionType.SCROLL -> P.green
                    ActionType.LONG_PRESS -> P.teal
                    ActionType.PINCH -> P.pink
                    ActionType.WAIT -> 0xFF78909C.toInt()
                    ActionType.GLOBAL -> 0xFF5E35B1.toInt()
                }
            )
            row.addView(badge, LinearLayout.LayoutParams(dp(28), dp(28)))

            val col = vbox()
            col.setPadding(dp(10), 0, dp(6), 0)
            val pos = "(${a.x.toInt()}, ${a.y.toInt()})"
            val title = when (a.type) {
                ActionType.TAP -> "${tr("Click")}  $pos"
                ActionType.LONG_PRESS -> "${tr("Hold")}  $pos  ${a.durationMs} ms"
                ActionType.SWIPE -> "${tr("Slide")}  $pos → (${a.x2.toInt()}, ${a.y2.toInt()})"
                ActionType.SCROLL -> "${tr("Scroll")} ${ScrollDir.arrow(a.dir)} ${a.distPct}%  $pos"
                ActionType.PINCH -> "${tr("Pinch")} ${tr(if (a.dir == 1) "Zoom in" else "Zoom out")}  $pos"
                ActionType.WAIT -> "${tr("Wait")}  ${humanMs(a.delayMs)}"
                ActionType.GLOBAL -> "${GlobalAct.symbol(a.global)} ${tr(GlobalAct.name(a.global))}"
            }
            col.addView(text(title, 13f, P.text, true))
            val rep = if (a.repeat > 1) "  ·  ×${a.repeat}" else ""
            val subtitle = when (a.type) {
                ActionType.WAIT -> rep.removePrefix("  ·  ")
                ActionType.GLOBAL -> "${tr("delay")} ${humanMs(a.delayMs)}$rep"
                ActionType.LONG_PRESS -> "${tr("delay")} ${humanMs(a.delayMs)}$rep"
                else -> "${tr("duration")} ${a.durationMs} ms  ·  ${tr("delay")} ${humanMs(a.delayMs)}$rep"
            }
            if (subtitle.isNotEmpty()) col.addView(text(subtitle, 11f, P.sub))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(miniIcon(R.drawable.ic_arrow_up, i > 0) { moveAction(i, -1) }, LinearLayout.LayoutParams(dp(32), dp(36)))
            row.addView(miniIcon(R.drawable.ic_arrow_down, i < acts.size - 1) { moveAction(i, 1) }, LinearLayout.LayoutParams(dp(32), dp(36)))
            row.addView(miniIcon(R.drawable.ic_copy, acts.size < MAX_ACTIONS) { duplicateAction(i) }, LinearLayout.LayoutParams(dp(32), dp(36)))
            row.setOnClickListener { editDialog(i) }
            listBox.addView(row, lpm(8))
        }
    }

    private fun moveAction(i: Int, d: Int) {
        val acts = Session.script.actions
        val j = i + d
        if (i !in acts.indices || j !in acts.indices) return
        Session.checkpoint()
        Collections.swap(acts, i, j)
        changed()
    }

    private fun duplicateAction(i: Int) {
        val acts = Session.script.actions
        if (i !in acts.indices || acts.size >= MAX_ACTIONS) return
        Session.checkpoint()
        val c = acts[i].copy()
        if (c.type != ActionType.WAIT && c.type != ActionType.GLOBAL) {
            c.x += dp(24)
            c.y += dp(24)
            c.x2 += dp(24)
            c.y2 += dp(24)
        }
        acts.add(i + 1, c)
        changed()
    }

    // ---------- dialogs ----------

    private fun editDialog(i: Int) {
        val acts = Session.script.actions
        if (i !in acts.indices) return
        val a = acts[i]
        val box = vbox()
        box.setPadding(dp(20), dp(8), dp(20), 0)

        fun label(t: String) {
            val v = text(t, 12f, P.sub)
            v.setPadding(0, dp(10), 0, dp(2))
            box.addView(v)
        }

        fun input(label: String, v: Number): EditText {
            label(label)
            val e = EditText(this)
            e.inputType = InputType.TYPE_CLASS_NUMBER
            e.setText(v.toString())
            box.addView(e)
            return e
        }

        val hasPos = a.type != ActionType.WAIT && a.type != ActionType.GLOBAL
        var ex: EditText? = null
        var ey: EditText? = null
        var ex2: EditText? = null
        var ey2: EditText? = null
        var edist: EditText? = null
        var ed: EditText? = null
        var dirSel = a.dir
        var globalSel = a.global

        if (hasPos) {
            ex = input("X", a.x.toInt())
            ey = input("Y", a.y.toInt())
        }
        if (a.type == ActionType.SWIPE) {
            ex2 = input("X end", a.x2.toInt())
            ey2 = input("Y end", a.y2.toInt())
        }
        if (a.type == ActionType.SCROLL) {
            label("Scroll direction")
            box.addView(segBound(listOf("↑ Up", "↓ Down", "← Left", "→ Right"), a.dir, 13f) { dirSel = it }.root)
            edist = input("Distance (% of screen)", a.distPct)
        }
        if (a.type == ActionType.PINCH) {
            label("Pinch type")
            box.addView(segBound(listOf("Zoom in", "Zoom out"), if (a.dir == 1) 0 else 1) { dirSel = if (it == 0) 1 else 0 }.root)
            edist = input("Span (% of screen)", a.distPct)
        }
        if (a.type == ActionType.GLOBAL) {
            label("System button")
            box.addView(segBound(GlobalAct.labels, a.global, 12f) { globalSel = it }.root)
        }
        if (hasPos) {
            ed = input(if (a.type == ActionType.LONG_PRESS) "Hold time (ms)" else "Duration (ms)", a.durationMs)
        }
        val er = input("Repeat (times before next action)", a.repeat)
        label(if (a.type == ActionType.WAIT) "Wait duration" else "Delay after this action")
        val delayTi = TimeInput(a.delayMs, pickUnit(a.delayMs), false) { _, _ -> }
        box.addView(delayTi.root)

        val scroll = ScrollView(this)
        scroll.addView(box)

        AlertDialog.Builder(this)
            .setTitle("${tr("Action")} ${i + 1}")
            .setView(scroll)
            .setPositiveButton(tr("Save")) { _, _ ->
                Session.checkpoint()
                ex?.let { a.x = it.text.toString().toFloatOrNull() ?: a.x }
                ey?.let { a.y = it.text.toString().toFloatOrNull() ?: a.y }
                ex2?.let { a.x2 = it.text.toString().toFloatOrNull() ?: a.x2 }
                ey2?.let { a.y2 = it.text.toString().toFloatOrNull() ?: a.y2 }
                if (a.type == ActionType.SCROLL || a.type == ActionType.PINCH) a.dir = dirSel.coerceIn(0, 3)
                if (a.type == ActionType.GLOBAL) a.global = globalSel.coerceIn(0, 4)
                edist?.let { a.distPct = (it.text.toString().toIntOrNull() ?: a.distPct).coerceIn(1, 100) }
                ed?.let { a.durationMs = (it.text.toString().toLongOrNull() ?: a.durationMs).coerceIn(1L, 60_000L) }
                a.repeat = (er.text.toString().toIntOrNull() ?: a.repeat).coerceIn(1, 100_000)
                a.delayMs = delayTi.getMs()
                changed()
            }
            .setNeutralButton(tr("Delete")) { _, _ ->
                Session.checkpoint()
                acts.removeAt(i)
                changed()
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun promptName(onName: (String) -> Unit) {
        val e = EditText(this)
        e.hint = tr("Script name")
        AlertDialog.Builder(this)
            .setTitle(tr("Save script"))
            .setView(e)
            .setPositiveButton(tr("Save")) { _, _ ->
                val n = e.text.toString().trim()
                if (n.isNotEmpty()) onName(n)
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun loadDialog() {
        val names = ScriptStore.names(this)
        if (names.isEmpty()) {
            toast("No saved scripts")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Saved scripts"))
            .setItems(names.toTypedArray()) { _, which ->
                val n = names[which]
                AlertDialog.Builder(this)
                    .setTitle(n)
                    .setPositiveButton(tr("Load")) { _, _ ->
                        val s = ScriptStore.load(this, n)
                        if (s == null) {
                            toast("Load failed")
                        } else {
                            Session.checkpoint()
                            Session.script = s
                            changed()
                        }
                    }
                    .setNeutralButton(tr("Delete")) { _, _ -> ScriptStore.delete(this, n) }
                    .setNegativeButton(tr("Cancel"), null)
                    .show()
            }
            .show()
    }

    private fun shareScript() {
        if (Session.script.actions.isEmpty()) {
            toast("No actions yet.")
            return
        }
        val json = Session.script.toJson().toString(2)
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_SUBJECT, "Open Clicker script")
        send.putExtra(Intent.EXTRA_TEXT, json)
        safeStart(Intent.createChooser(send, tr("Share script")))
    }

    private fun pasteScript() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        if (text.isNullOrBlank()) {
            toast("Clipboard is empty")
            return
        }
        try {
            val s = Script.fromJson(JSONObject(text.trim()))
            if (s.actions.isEmpty()) {
                toast("Clipboard does not contain a valid script")
                return
            }
            Session.checkpoint()
            Session.script = s
            changed()
            toast("Imported")
        } catch (e: Exception) {
            toast("Clipboard does not contain a valid script")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            if (requestCode == reqExport) {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(Session.script.toJson().toString(2).toByteArray())
                }
                toast("Exported")
            } else if (requestCode == reqImport) {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                val s = Script.fromJson(JSONObject(text))
                Session.checkpoint()
                Session.script = s
                changed()
                toast("Imported")
            }
        } catch (e: Exception) {
            Toast.makeText(this, "${tr("Failed")}: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
