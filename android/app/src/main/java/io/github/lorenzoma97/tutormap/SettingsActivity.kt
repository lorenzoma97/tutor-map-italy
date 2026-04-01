package io.github.lorenzoma97.tutormap

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private val PREFS = "tutor_prefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val scroll = ScrollView(this).apply {
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Titolo
        layout.addView(TextView(this).apply {
            text = "Impostazioni"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(0xFF1a1a1a.toInt())
            setPadding(0, 0, 0, dp(24))
        })

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

            val switch = Switch(this).apply {
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
            text = "\nLe impostazioni vengono applicate al prossimo avvio del monitoraggio."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF999999.toInt())
            setPadding(0, dp(16), 0, 0)
        })

        scroll.addView(layout)
        setContentView(scroll)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Impostazioni"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
