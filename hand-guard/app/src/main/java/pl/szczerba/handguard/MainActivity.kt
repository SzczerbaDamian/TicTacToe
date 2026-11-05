package pl.szczerba.handguard

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var restrictedHint: TextView
    private lateinit var report: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)

        val pad = dp(16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        column.addView(status)

        restrictedHint = TextView(this).apply {
            textSize = 14f
            text = "Pozycja Hand Guard w Ułatwieniach dostępu jest szara? " +
                "Stuknij ją raz (pojawi się „Restricted setting”), potem tutaj " +
                "„Odblokuj ustawienia z ograniczeniami” → ⋮ (prawy górny róg) → " +
                "„Allow restricted settings”. Na koncie z Family Link może to wymagać zgody rodzica."
            setPadding(0, 0, 0, dp(8))
        }
        column.addView(restrictedHint)

        column.addView(button("1. Ułatwienia dostępu: włącz usługę") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        column.addView(button("Odblokuj ustawienia z ograniczeniami") { openAppDetails() })
        column.addView(button("2. Bateria: bez ograniczeń") { requestBatteryExemption() })
        column.addView(button("3. Autostart (Xiaomi)") { openXiaomiAutostart() })
        column.addView(button("Zapisz dane teraz") {
            Store.flush()
            refresh()
            Toast.makeText(this, "Zapisano", Toast.LENGTH_SHORT).show()
        })
        column.addView(button("Udostępnij dane (JSON)") { shareJson() })

        column.addView(TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            text = "Wskazówki dla HyperOS: (a) w widoku ostatnich aplikacji przytrzymaj kartę " +
                "tej aplikacji i wybierz kłódkę (blokada), (b) bateria: Bez ograniczeń, " +
                "(c) autostart: włączony. Bez tego system może ubijać usługę w tle. " +
                "Usługa NIE czyta treści ekranu ani tego, co piszesz."
            setPadding(0, dp(12), 0, dp(12))
        })

        report = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        column.addView(report)

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = isServiceEnabled()
        status.text = if (enabled) "Usługa: DZIAŁA ✓" else "Usługa: WYŁĄCZONA – włącz ją w kroku 1"
        status.setTextColor(if (enabled) Color.rgb(0x2E, 0x7D, 0x32) else Color.rgb(0xC6, 0x28, 0x28))
        restrictedHint.visibility = if (enabled) View.GONE else View.VISIBLE
        report.text = Report.build(this)
    }

    private fun isServiceEnabled(): Boolean {
        val list = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, HandGuardService::class.java)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun openAppDetails() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            } catch (_: Exception) {
            }
        }
        // Already exempt, or the dialog is unavailable: HyperOS keeps the
        // "No restrictions" option in the app's own battery settings.
        Toast.makeText(this, "Bateria → Bez ograniczeń", Toast.LENGTH_LONG).show()
        openAppDetails()
    }

    private fun openXiaomiAutostart() {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        openAppDetails()
    }

    private fun shareJson() {
        val json = Store.exportJson().toString(2)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Hand Guard – dane")
            .putExtra(Intent.EXTRA_TEXT, json)
        startActivity(Intent.createChooser(send, "Udostępnij dane"))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
