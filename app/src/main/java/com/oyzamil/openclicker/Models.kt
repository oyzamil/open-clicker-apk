package com.oyzamil.openclicker

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class ActionType { TAP, SWIPE, SCROLL, LONG_PRESS, WAIT, GLOBAL, PINCH }

/** Hard cap so a broken import or runaway editing can never create a giant script. */
const val MAX_ACTIONS = 500

/** Scroll direction = where the content scrolls TO (DOWN = reveal lower content). */
object ScrollDir {
    const val UP = 0
    const val DOWN = 1
    const val LEFT = 2
    const val RIGHT = 3
    fun arrow(d: Int) = when (d) { UP -> "↑"; DOWN -> "↓"; LEFT -> "←"; else -> "→" }
    fun name(d: Int) = when (d) { UP -> "Up"; DOWN -> "Down"; LEFT -> "Left"; else -> "Right" }
}

/** System-level actions that can be used as a step in a sequence. */
object GlobalAct {
    const val BACK = 0
    const val HOME = 1
    const val RECENTS = 2
    const val NOTIFICATIONS = 3
    const val QUICK = 4
    val labels = listOf("Back", "Home", "Recents", "Notifs", "Quick")
    fun symbol(c: Int) = when (c) { BACK -> "◁"; HOME -> "○"; RECENTS -> "▭"; NOTIFICATIONS -> "▾"; else -> "⚙" }
    fun name(c: Int) = when (c) {
        BACK -> "Back"; HOME -> "Home"; RECENTS -> "Recents"; NOTIFICATIONS -> "Notifications"; else -> "Quick settings"
    }
}

data class ClickAction(
    var type: ActionType = ActionType.TAP,
    var x: Float = 0f,
    var y: Float = 0f,
    var x2: Float = 0f,
    var y2: Float = 0f,
    /** Gesture duration. For LONG_PRESS this is the hold time. */
    var durationMs: Long = 10L,
    /** Delay AFTER this action, in milliseconds. For WAIT this is the wait time. */
    var delayMs: Long = 100L,
    /** How many times this action is performed before moving to the next one. */
    var repeat: Int = 1,
    /** SCROLL: ScrollDir value. PINCH: 1 = spread (zoom in), 0 = pinch (zoom out). */
    var dir: Int = ScrollDir.DOWN,
    /** SCROLL: distance as percent of screen height/width. PINCH: span as percent of the short screen side. */
    var distPct: Int = 50,
    /** GLOBAL only: GlobalAct value. */
    var global: Int = 0,
    /** Clicks (or gestures) performed at a time, back to back, before the normal delay starts. */
    var burst: Int = 1,
    /** Delay between the gestures inside one burst, ms. */
    var burstDelayMs: Long = 100L,
    /** 0 = fixed delay. If greater than delayMs, the delay after this action is random between delayMs and this. */
    var delayMaxMs: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name)
        .put("x", x.toDouble()).put("y", y.toDouble())
        .put("x2", x2.toDouble()).put("y2", y2.toDouble())
        .put("duration", durationMs)
        .put("delay", delayMs)
        .put("repeat", repeat)
        .put("dir", dir)
        .put("dist", distPct)
        .put("global", global)
        .put("burst", burst)
        .put("burstDelay", burstDelayMs)
        .put("delayMax", delayMaxMs)

    companion object {
        private fun f(o: JSONObject, k: String): Float {
            val v = o.optDouble(k, 0.0)
            return if (v.isNaN() || v.isInfinite()) 0f else v.toFloat().coerceIn(0f, 20000f)
        }

        fun fromJson(o: JSONObject) = ClickAction(
            type = runCatching { ActionType.valueOf(o.optString("type", "TAP")) }.getOrDefault(ActionType.TAP),
            x = f(o, "x"),
            y = f(o, "y"),
            x2 = f(o, "x2"),
            y2 = f(o, "y2"),
            durationMs = o.optLong("duration", 10L).coerceIn(1L, 60_000L),
            delayMs = o.optLong("delay", 100L).coerceIn(0L, 86_400_000L),
            repeat = o.optInt("repeat", 1).coerceIn(1, 100_000),
            dir = o.optInt("dir", ScrollDir.DOWN).coerceIn(0, 3),
            distPct = o.optInt("dist", 50).coerceIn(1, 100),
            global = o.optInt("global", 0).coerceIn(0, 4),
            burst = o.optInt("burst", 1).coerceIn(1, 1000),
            burstDelayMs = o.optLong("burstDelay", 100L).coerceIn(0L, 3_600_000L),
            delayMaxMs = o.optLong("delayMax", 0L).coerceIn(0L, 86_400_000L)
        )
    }
}

data class Script(
    var name: String = "current",
    val actions: MutableList<ClickAction> = mutableListOf()
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        actions.forEach { arr.put(it.toJson()) }
        return JSONObject().put("name", name).put("actions", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Script {
            val list = mutableListOf<ClickAction>()
            val arr = o.optJSONArray("actions") ?: JSONArray()
            for (i in 0 until minOf(arr.length(), MAX_ACTIONS)) list.add(ClickAction.fromJson(arr.getJSONObject(i)))
            return Script(o.optString("name", "imported"), list)
        }
    }
}

/** App-wide settings. */
object Prefs {
    private var p: SharedPreferences? = null
    fun init(c: Context) {
        if (p == null) p = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }
    private val sp: SharedPreferences get() = p!!

    private fun int(key: String, def: Int, lo: Int, hi: Int) = sp.getInt(key, def).coerceIn(lo, hi)
    private fun putInt(key: String, v: Int, lo: Int, hi: Int) { sp.edit().putInt(key, v.coerceIn(lo, hi)).apply() }
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) { sp.edit().putBoolean(key, v).apply() }

    var multi: Boolean
        get() = bool("multi", false)
        set(v) = putBool("multi", v)

    /** Delay between clicks in ms (single mode, and default for new multi actions). */
    var delayMs: Long
        get() = sp.getLong("delay", 100L).coerceIn(0L, 86_400_000L)
        set(v) { sp.edit().putLong("delay", v.coerceIn(0L, 86_400_000L)).apply() }

    /** Unit shown in the delay input: 0 = ms, 1 = sec, 2 = min. Delay itself is always stored in ms. */
    var delayUnit: Int
        get() = int("delayUnit", 0, 0, 2)
        set(v) = putInt("delayUnit", v, 0, 2)

    /** Duration of slide / scroll / pinch gestures in ms. */
    var swipeDurationMs: Long
        get() = sp.getLong("swipeDur", 300L).coerceIn(1L, 60_000L)
        set(v) { sp.edit().putLong("swipeDur", v.coerceIn(1L, 60_000L)).apply() }

    /** Scroll distance, percent of screen. */
    var scrollDistPct: Int
        get() = int("scrollDist", 50, 1, 100)
        set(v) = putInt("scrollDist", v, 1, 100)

    var scrollDir: Int
        get() = int("scrollDir", ScrollDir.DOWN, 0, 3)
        set(v) = putInt("scrollDir", v, 0, 3)

    /** Single-mode action: 0 click, 1 slide, 2 scroll, 3 hold, 4 pinch. */
    var singleType: Int
        get() = int("singleType", 0, 0, 4)
        set(v) = putInt("singleType", v, 0, 4)

    var singleX2: Float
        get() = sp.getFloat("sx2", 0f)
        set(v) { sp.edit().putFloat("sx2", v).apply() }

    var singleY2: Float
        get() = sp.getFloat("sy2", 0f)
        set(v) { sp.edit().putFloat("sy2", v).apply() }

    var tapDurationMs: Long
        get() = sp.getLong("tapDur", 10L).coerceIn(1L, 60_000L)
        set(v) { sp.edit().putLong("tapDur", v.coerceIn(1L, 60_000L)).apply() }

    /** Unit shown for the press duration of a click: 0 = ms, 1 = sec, 2 = min. */
    var tapDurationUnit: Int
        get() = int("tapDurationUnit", 0, 0, 2)
        set(v) = putInt("tapDurationUnit", v, 0, 2)

    /** Hold time of a long press, ms. */
    var longPressMs: Long
        get() = sp.getLong("longPress", 800L).coerceIn(100L, 60_000L)
        set(v) { sp.edit().putLong("longPress", v.coerceIn(100L, 60_000L)).apply() }

    /** Pinch: span as percent of the short screen side. */
    var pinchSpanPct: Int
        get() = int("pinchSpan", 40, 5, 100)
        set(v) = putInt("pinchSpan", v, 5, 100)

    /** Pinch direction: true = spread fingers (zoom in), false = pinch together (zoom out). */
    var pinchZoomIn: Boolean
        get() = bool("pinchZoomIn", true)
        set(v) = putBool("pinchZoomIn", v)

    var randomOffsetPx: Int
        get() = int("rndPos", 0, 0, 1000)
        set(v) = putInt("rndPos", v, 0, 1000)

    var randomIntervalMs: Int
        get() = int("rndInt", 0, 0, 600_000)
        set(v) = putInt("rndInt", v, 0, 600_000)

    /** Curved paths + slightly random speed for slide and scroll. */
    var humanSwipe: Boolean
        get() = bool("humanSwipe", false)
        set(v) = putBool("humanSwipe", v)

    /** How many times to perform the action / sequence. 0 = infinite. */
    var loops: Int
        get() = int("loops", 0, 0, 1_000_000_000)
        set(v) = putInt("loops", v, 0, 1_000_000_000)

    /** false = fixed delay, true = a random delay between A and B after every action. */
    var delayRandom: Boolean
        get() = bool("delayRandom", false)
        set(v) = putBool("delayRandom", v)

    var delayMinMs: Long
        get() = sp.getLong("delayMin", 1000L).coerceIn(0L, 86_400_000L)
        set(v) { sp.edit().putLong("delayMin", v.coerceIn(0L, 86_400_000L)).apply() }

    var delayMinUnit: Int
        get() = int("delayMinUnit", 1, 0, 2)
        set(v) = putInt("delayMinUnit", v, 0, 2)

    var delayMaxMs: Long
        get() = sp.getLong("delayMax", 3000L).coerceIn(0L, 86_400_000L)
        set(v) { sp.edit().putLong("delayMax", v.coerceIn(0L, 86_400_000L)).apply() }

    var delayMaxUnit: Int
        get() = int("delayMaxUnit", 1, 0, 2)
        set(v) = putInt("delayMaxUnit", v, 0, 2)

    /** Single mode: how many clicks (gestures) happen at a time before the main delay. */
    var burstCount: Int
        get() = int("burstCount", 1, 1, 1000)
        set(v) = putInt("burstCount", v, 1, 1000)

    /** Single mode: delay between the clicks inside one burst, ms. */
    var burstDelayMs: Long
        get() = sp.getLong("burstDelay", 100L).coerceIn(0L, 3_600_000L)
        set(v) { sp.edit().putLong("burstDelay", v.coerceIn(0L, 3_600_000L)).apply() }

    var burstDelayUnit: Int
        get() = int("burstDelayUnit", 0, 0, 2)
        set(v) = putInt("burstDelayUnit", v, 0, 2)

    /** Seconds of 3-2-1 countdown before a run starts. */
    var countdownSec: Int
        get() = int("countdown", 3, 0, 30)
        set(v) = putInt("countdown", v, 0, 30)

    /** Auto-stop after this many ms of running. 0 = off. */
    var stopAfterMs: Long
        get() = sp.getLong("stopAfter", 0L).coerceIn(0L, 86_400_000L)
        set(v) { sp.edit().putLong("stopAfter", v.coerceIn(0L, 86_400_000L)).apply() }

    var stopAfterUnit: Int
        get() = int("stopAfterUnit", 1, 0, 2)
        set(v) = putInt("stopAfterUnit", v, 0, 2)

    /** Kill switch: stop the clicker automatically when the screen turns off. Default ON. */
    var stopOnScreenOff: Boolean
        get() = bool("stopOnScreenOff", true)
        set(v) = putBool("stopOnScreenOff", v)

    /** Size of the on-screen target markers, in dp. */
    var markerSizeDp: Int
        get() = int("markerSize", 52, 28, 96)
        set(v) = putInt("markerSize", v, 28, 96)

    /** Locked markers cannot be dragged (touches pass through). */
    var markersLocked: Boolean
        get() = bool("markersLocked", false)
        set(v) = putBool("markersLocked", v)

    var panelOpacityPct: Int
        get() = int("panelOpacity", 95, 30, 100)
        set(v) = putInt("panelOpacity", v, 30, 100)

    var panelScalePct: Int
        get() = int("panelScale", 100, 60, 160)
        set(v) = putInt("panelScale", v, 60, 160)

    /** Volume Up + Volume Down together start / stop the clicker. */
    var volumeKeyToggle: Boolean
        get() = bool("volumeKey", false)
        set(v) = putBool("volumeKey", v)

    /** Show an ongoing notification with a Stop button while running. */
    var notifStop: Boolean
        get() = bool("notifStop", true)
        set(v) = putBool("notifStop", v)

    /** Switch the accessibility service back on by itself (needs the one-time ADB permission). */
    var autoEnable: Boolean
        get() = bool("autoEnable", true)
        set(v) = putBool("autoEnable", v)

    /** EXPERIMENTAL: keep the main thread alive after unexpected exceptions. Off by default. */
    var crashGuard: Boolean
        get() = bool("crashGuard", false)
        set(v) = putBool("crashGuard", v)

    /** Timestamp of the newest process-exit record already written to the log. */
    var lastExitLogged: Long
        get() = sp.getLong("lastExitLogged", 0L)
        set(v) { sp.edit().putLong("lastExitLogged", v).apply() }

    /** User confirmed the phone-specific "Autostart" switch is on (cannot be detected). */
    var autostartDone: Boolean
        get() = bool("autostartDone", false)
        set(v) = putBool("autostartDone", v)

    /** The battery-optimization dialog was already shown once automatically (do not nag). */
    var batteryAsked: Boolean
        get() = bool("batteryAsked", false)
        set(v) = putBool("batteryAsked", v)

    /** Notification permission was already requested once (do not nag). */
    var notifAsked: Boolean
        get() = bool("notifAsked", false)
        set(v) = putBool("notifAsked", v)

    /** 0 = English, 1 = Urdu. */
    var language: Int
        get() = int("language", 0, 0, 1)
        set(v) = putInt("language", v, 0, 1)

    /** 0 = dark, 1 = light, 2 = follow system. */
    var theme: Int
        get() = int("theme", 0, 0, 2)
        set(v) = putInt("theme", v, 0, 2)

    var singleX: Float
        get() = sp.getFloat("sx", 0f)
        set(v) { sp.edit().putFloat("sx", v).apply() }

    var singleY: Float
        get() = sp.getFloat("sy", 0f)
        set(v) { sp.edit().putFloat("sy", v).apply() }

    /** Apps that auto-show the panel when opened: package -> script name ("" = keep current). */
    fun autoAppMap(): MutableMap<String, String> {
        val out = linkedMapOf<String, String>()
        runCatching {
            val o = JSONObject(sp.getString("autoApps", "{}") ?: "{}")
            for (k in o.keys()) out[k] = o.optString(k, "")
        }
        return out
    }

    fun saveAutoAppMap(m: Map<String, String>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        sp.edit().putString("autoApps", o.toString()).apply()
    }
}

/** The working multi-target script, shared between activity and service. */
object Session {
    var script = Script()
    private var file: File? = null
    private val undoStack = ArrayDeque<String>()

    fun init(c: Context) {
        if (file != null) return
        file = File(c.applicationContext.filesDir, "current.json")
        file?.takeIf { it.exists() }?.let { f ->
            runCatching { script = Script.fromJson(JSONObject(f.readText())) }
        }
    }

    fun persist() {
        runCatching { file?.writeText(script.toJson().toString()) }
    }

    /** Save the current script so the next change can be undone. Call BEFORE mutating. */
    fun checkpoint() = pushUndo(script.toJson().toString())

    fun pushUndo(json: String) {
        undoStack.addLast(json)
        while (undoStack.size > 30) undoStack.removeFirst()
    }

    fun canUndo() = undoStack.isNotEmpty()

    fun undo(): Boolean {
        val j = undoStack.removeLastOrNull() ?: return false
        return runCatching {
            script = Script.fromJson(JSONObject(j))
            persist()
            true
        }.getOrDefault(false)
    }
}

object ScriptStore {
    private fun dir(c: Context) = File(c.filesDir, "scripts").apply { mkdirs() }
    private fun safe(n: String) = n.replace(Regex("[^A-Za-z0-9 _-]"), "_").take(60)

    fun names(c: Context): List<String> =
        dir(c).listFiles()?.filter { it.extension == "json" }?.map { it.nameWithoutExtension }?.sorted()
            ?: emptyList()

    fun save(c: Context, s: Script) {
        File(dir(c), safe(s.name) + ".json").writeText(s.toJson().toString())
    }

    fun load(c: Context, name: String): Script? =
        runCatching { Script.fromJson(JSONObject(File(dir(c), "$name.json").readText())) }.getOrNull()

    fun delete(c: Context, name: String) {
        File(dir(c), "$name.json").delete()
    }
}
