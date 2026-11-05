package pl.szczerba.handguard

import android.content.Context
import android.content.pm.PackageManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Builds the text report shown on the main screen. */
object Report {

    fun build(context: Context): String {
        Store.flush()
        val pm = context.packageManager
        return Store.read { days, gaps ->
            val sb = StringBuilder()
            val now = System.currentTimeMillis()
            val todayKey = Store.dayKey(now)
            val today = days[todayKey] ?: DayStats(todayKey)

            sb.append("DZIŚ ").append(todayKey).append('\n')
            sb.append("  aktywność łącznie: ").append(mss(today.activeMs)).append(" (m:ss)\n")
            sb.append("    przewijanie ").append(mss(today.scrollMs))
                .append(" | pisanie ").append(mss(today.typingMs))
                .append(" | dotknięcia ").append(mss(today.touchMs)).append('\n')
            sb.append("  zdarzeń: kliknięć ").append(today.clicks)
                .append(", długich ").append(today.longClicks)
                .append(", zaznaczeń ").append(today.selects)
                .append(", przewinięć ").append(today.scrolls)
                .append(", zmian tekstu ").append(today.textChanges)
                .append(", klawiszy fiz. ").append(today.keys).append('\n')
            sb.append("  ekran włączony: ").append(hmmss(today.screenOnMs)).append(" (h:mm:ss)\n")

            val top = today.perApp.entries.sortedByDescending { it.value }.take(6)
            if (top.isNotEmpty()) {
                sb.append("  najbardziej męczące aplikacje:\n")
                for ((pkg, ms) in top) {
                    sb.append("    ").append(mss(ms).padStart(6)).append("  ").append(label(pm, pkg)).append('\n')
                }
            }

            val hours = (0 until 24).filter { today.perHour[it] > 0 }
            if (hours.isNotEmpty()) {
                sb.append("  wg godzin: ")
                sb.append(hours.joinToString(" | ") { "%02dh %s".format(it, mss(today.perHour[it])) })
                sb.append('\n')
            }

            sb.append("\nOSTATNIE 7 DNI (aktywność | ekran)\n")
            val cal = Calendar.getInstance()
            for (i in 0 until 7) {
                val key = Store.dayKey(cal.timeInMillis)
                val d = days[key]
                if (d != null) {
                    sb.append("  ").append(key).append("  ").append(mss(d.activeMs).padStart(6))
                        .append(" | ").append(hmmss(d.screenOnMs)).append('\n')
                }
                cal.add(Calendar.DAY_OF_MONTH, -1)
            }

            sb.append("\nLUKI OCHRONY (usługa nie działała; wygaszony ekran się NIE liczy)\n")
            val weekAgo = now - 7L * 24 * 3600_000L
            val recent = gaps.filter { it.end >= weekAgo }.sortedByDescending { it.start }
            if (recent.isEmpty()) {
                sb.append("  brak – usługa działała cały czas\n")
            } else {
                val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.US)
                for (g in recent.take(30)) {
                    sb.append("  ").append(fmt.format(Date(g.start))).append(" -> ")
                        .append(fmt.format(Date(g.end)))
                        .append("  (").append(hmmss(g.end - g.start)).append(")\n")
                        .append("      ").append(g.reason).append('\n')
                }
            }
            sb.toString()
        }
    }

    private fun label(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    fun mss(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    fun hmmss(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    }
}
