package pl.szczerba.handguard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class Category { TOUCH, SCROLL, TYPING }

enum class EventKind(val category: Category) {
    CLICK(Category.TOUCH),
    LONG_CLICK(Category.TOUCH),
    SELECT(Category.TOUCH),
    SCROLL(Category.SCROLL),
    TEXT(Category.TYPING),
    KEY(Category.TYPING),
}

class DayStats(val date: String) {
    var clicks = 0L
    var longClicks = 0L
    var selects = 0L
    var scrolls = 0L
    var textChanges = 0L
    var keys = 0L
    var touchMs = 0L
    var scrollMs = 0L
    var typingMs = 0L
    var screenOnMs = 0L
    val perApp = HashMap<String, Long>()
    val perHour = LongArray(24)

    val activeMs: Long get() = touchMs + scrollMs + typingMs

    fun toJson(): JSONObject {
        val apps = JSONObject()
        for ((k, v) in perApp) apps.put(k, v)
        val hours = JSONArray()
        for (v in perHour) hours.put(v)
        return JSONObject()
            .put("date", date)
            .put("clicks", clicks)
            .put("longClicks", longClicks)
            .put("selects", selects)
            .put("scrolls", scrolls)
            .put("textChanges", textChanges)
            .put("keys", keys)
            .put("touchMs", touchMs)
            .put("scrollMs", scrollMs)
            .put("typingMs", typingMs)
            .put("activeMs", activeMs)
            .put("screenOnMs", screenOnMs)
            .put("perAppMs", apps)
            .put("perHourMs", hours)
    }

    companion object {
        fun fromJson(o: JSONObject): DayStats {
            val d = DayStats(o.getString("date"))
            d.clicks = o.optLong("clicks")
            d.longClicks = o.optLong("longClicks")
            d.selects = o.optLong("selects")
            d.scrolls = o.optLong("scrolls")
            d.textChanges = o.optLong("textChanges")
            d.keys = o.optLong("keys")
            d.touchMs = o.optLong("touchMs")
            d.scrollMs = o.optLong("scrollMs")
            d.typingMs = o.optLong("typingMs")
            d.screenOnMs = o.optLong("screenOnMs")
            o.optJSONObject("perAppMs")?.let { apps ->
                for (k in apps.keys()) d.perApp[k] = apps.optLong(k)
            }
            o.optJSONArray("perHourMs")?.let { hours ->
                for (i in 0 until minOf(24, hours.length())) d.perHour[i] = hours.optLong(i)
            }
            return d
        }
    }
}

/** A period when the service was NOT running while it should have been. */
data class Gap(val start: Long, val end: Long, val reason: String) {
    fun toJson(): JSONObject = JSONObject().put("start", start).put("end", end).put("reason", reason)
}

/**
 * All data lives here. The service and the screen run in the same process,
 * so both use this one object. Every public function is synchronized.
 */
object Store {
    /** No sign of life for longer than this, while the screen is on, counts as a gap. */
    const val GAP_MS = 2 * 60_000L

    /** Events closer together than this are one continuous burst of hand activity. */
    private const val BURST_MS = 2_000L

    /** Time credited for a single, isolated event. */
    private const val SINGLE_EVENT_MS = 400L

    private const val KEEP_DAYS = 30
    private const val KEEP_GAPS_MS = 14L * 24 * 3600_000L

    private const val K_LAST_ALIVE = "lastAlive"
    private const val K_SCREEN_OFF = "screenOff"
    private const val K_SHUTDOWN_AT = "shutdownAt"
    private const val K_DISABLED_AT = "disabledAt"

    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences
    private val days = HashMap<String, DayStats>()
    private val gaps = ArrayList<Gap>()
    private var initialized = false
    private var dirty = false
    private var lastEventAt = 0L
    private var screenOnSince = 0L

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        dir = app.filesDir
        prefs = app.getSharedPreferences("state", Context.MODE_PRIVATE)
        load()
        initialized = true
    }

    // ---------------------------------------------------------------- events

    @Synchronized
    fun recordEvent(now: Long, kind: EventKind, pkg: String?) {
        val d = day(now)
        when (kind) {
            EventKind.CLICK -> d.clicks++
            EventKind.LONG_CLICK -> d.longClicks++
            EventKind.SELECT -> d.selects++
            EventKind.SCROLL -> d.scrolls++
            EventKind.TEXT -> d.textChanges++
            EventKind.KEY -> d.keys++
        }
        val since = now - lastEventAt
        val delta = if (lastEventAt > 0 && since in 0..BURST_MS) since else SINGLE_EVENT_MS
        lastEventAt = now
        when (kind.category) {
            Category.TOUCH -> d.touchMs += delta
            Category.SCROLL -> d.scrollMs += delta
            Category.TYPING -> d.typingMs += delta
        }
        val app = pkg ?: "?"
        d.perApp[app] = (d.perApp[app] ?: 0L) + delta
        d.perHour[hourOf(now)] += delta
        dirty = true
    }

    // ------------------------------------------------------ service liveness

    /** Called when the service connects (after enabling, after a reboot, after being killed). */
    @Synchronized
    fun serviceStarted(now: Long, screenOn: Boolean) {
        val last = prefs.getLong(K_LAST_ALIVE, 0L)
        if (last > 0 && now - last > GAP_MS) {
            val shutdownAt = prefs.getLong(K_SHUTDOWN_AT, 0L)
            val disabledAt = prefs.getLong(K_DISABLED_AT, 0L)
            val wasScreenOff = prefs.getBoolean(K_SCREEN_OFF, false)
            val reason = when {
                shutdownAt > 0 && shutdownAt >= last - GAP_MS -> "telefon wyłączony / restart"
                disabledAt > 0 && disabledAt >= last - GAP_MS -> "usługa wyłączona ręcznie"
                wasScreenOff -> "usługa zatrzymana przez system (przy wygaszonym ekranie)"
                else -> "usługa zatrzymana przez system"
            }
            addGap(last, now, reason)
        }
        screenOnSince = if (screenOn) now else 0L
        prefs.edit()
            .putLong(K_LAST_ALIVE, now)
            .putBoolean(K_SCREEN_OFF, !screenOn)
            .remove(K_SHUTDOWN_AT)
            .remove(K_DISABLED_AT)
            .apply()
    }

    /**
     * Regular heartbeat. While the screen is off the phone sleeps and heartbeats
     * stop - that is normal and is NOT a gap. Only silence with the screen on is.
     */
    @Synchronized
    fun heartbeat(now: Long) {
        val last = prefs.getLong(K_LAST_ALIVE, 0L)
        val screenOff = prefs.getBoolean(K_SCREEN_OFF, false)
        if (last > 0 && !screenOff && now - last > GAP_MS) {
            addGap(last, now, "usługa nie dawała znaku życia (ekran włączony)")
        }
        prefs.edit().putLong(K_LAST_ALIVE, now).apply()
    }

    @Synchronized
    fun screenOff(now: Long) {
        heartbeat(now)
        if (screenOnSince > 0) addScreenOn(screenOnSince, now)
        screenOnSince = 0L
        prefs.edit().putBoolean(K_SCREEN_OFF, true).apply()
        flush(now)
    }

    @Synchronized
    fun screenOn(now: Long) {
        // The phone was asleep, so the silence before this moment is not a gap.
        screenOnSince = now
        prefs.edit().putLong(K_LAST_ALIVE, now).putBoolean(K_SCREEN_OFF, false).apply()
    }

    @Synchronized
    fun phoneShuttingDown(now: Long) {
        flush(now)
        prefs.edit().putLong(K_LAST_ALIVE, now).putLong(K_SHUTDOWN_AT, now).commit()
    }

    @Synchronized
    fun serviceStopped(now: Long) {
        flush(now)
        screenOnSince = 0L
        prefs.edit().putLong(K_LAST_ALIVE, now).putLong(K_DISABLED_AT, now).commit()
    }

    // ----------------------------------------------------------- persistence

    /** Moves screen-on time counted so far into the day totals and writes everything to disk. */
    @Synchronized
    fun flush(now: Long = System.currentTimeMillis()) {
        if (!initialized) return
        if (screenOnSince in 1 until now) {
            addScreenOn(screenOnSince, now)
            screenOnSince = now
        }
        if (!dirty) return
        val keys = days.keys.sorted()
        if (keys.size > KEEP_DAYS) keys.take(keys.size - KEEP_DAYS).forEach { days.remove(it) }
        val arr = JSONArray()
        for (k in days.keys.sorted()) arr.put(days.getValue(k).toJson())
        writeAtomic("days.json", arr.toString())
        dirty = false
    }

    /** Runs [block] with a consistent view of the data. Do not keep the references. */
    @Synchronized
    fun <T> read(block: (days: Map<String, DayStats>, gaps: List<Gap>) -> T): T = block(days, gaps)

    @Synchronized
    fun exportJson(): JSONObject {
        flush()
        val d = JSONArray()
        for (k in days.keys.sorted()) d.put(days.getValue(k).toJson())
        val g = JSONArray()
        for (gap in gaps) g.put(gap.toJson())
        return JSONObject()
            .put("app", "Hand Guard (telefon)")
            .put("exportedAt", System.currentTimeMillis())
            .put("days", d)
            .put("gaps", g)
    }

    // --------------------------------------------------------------- helpers

    fun dayKey(t: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    private fun day(t: Long): DayStats {
        val key = dayKey(t)
        return days.getOrPut(key) { DayStats(key) }
    }

    private fun hourOf(t: Long): Int =
        Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)

    private fun startOfNextDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    /** Adds screen-on time, split correctly across midnight. */
    private fun addScreenOn(from: Long, to: Long) {
        var s = from
        while (s < to) {
            val e = minOf(to, startOfNextDay(s))
            day(s).screenOnMs += e - s
            s = e
        }
        dirty = true
    }

    private fun addGap(start: Long, end: Long, reason: String) {
        gaps.add(Gap(start, end, reason))
        val cutoff = System.currentTimeMillis() - KEEP_GAPS_MS
        gaps.removeAll { it.end < cutoff }
        val arr = JSONArray()
        for (g in gaps) arr.put(g.toJson())
        writeAtomic("gaps.json", arr.toString())
    }

    private fun load() {
        try {
            val f = File(dir, "days.json")
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val d = DayStats.fromJson(arr.getJSONObject(i))
                    days[d.date] = d
                }
            }
        } catch (_: Exception) {
            // Corrupted file: start fresh rather than crash.
        }
        try {
            val f = File(dir, "gaps.json")
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    gaps.add(Gap(o.getLong("start"), o.getLong("end"), o.optString("reason")))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun writeAtomic(name: String, text: String) {
        try {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(File(dir, name))) tmp.delete()
        } catch (_: Exception) {
        }
    }
}
