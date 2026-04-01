package io.github.lorenzoma97.tutormap

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar

class SettingsActivity : AppCompatActivity() {

    private val PREFS = "tutor_prefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // Root layout con toolbar + scroll
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // Toolbar con freccia indietro
        val toolbar = Toolbar(this).apply {
            setBackgroundColor(0xFF1a73e8.toInt())
            setTitleTextColor(Color.WHITE)
            title = "Impostazioni"
            setNavigationIcon(android.R.drawable.ic_menu_revert)
            setNavigationOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56))
        }
        root.addView(toolbar)

        val scroll = ScrollView(this).apply {
            setPadding(dp(24), dp(16), dp(24), dp(24))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Toggle switches
        val toggles = listOf(
            Triple("sound", "Avvisi vocali", "Annuncio vocale all'ingresso e uscita dai tratti Tutor"),
            Triple("overlay", "Overlay flottante", "Bollino con velocità e media sopra Google Maps"),
            Triple("maps_pin", "Pin su Maps", "Mostra la fine del tratto Tutor su Google Maps all'ingresso"),
            Triple("bt_autostart", "Auto-start Bluetooth", "Avvia automaticamente il monitoraggio quando ti connetti al Bluetooth dell'auto"),
        )

        for ((key, title, subtitle) in toggles) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(0xFF1a1a1a.toInt())
            })
            textCol.addView(TextView(this).apply {
                text = subtitle
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFF888888.toInt())
            })

            val switch = SwitchCompat(this).apply {
                isChecked = prefs.getBoolean(key, true)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(key, checked).apply()
                }
            }

            row.addView(textCol)
            row.addView(switch)
            layout.addView(row)

            // Divider
            layout.addView(android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
                    setMargins(0, dp(4), 0, dp(4))
                }
                setBackgroundColor(0xFFE0E0E0.toInt())
            })
        }

        // Info
        layout.addView(TextView(this).apply {
            text = "Le impostazioni vengono applicate al prossimo avvio del monitoraggio."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF999999.toInt())
            setPadding(0, dp(16), 0, 0)
        })

        scroll.addView(layout)
        root.addView(scroll)
        setContentView(root)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
