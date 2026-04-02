package io.github.lorenzoma97.tutormap

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.Preference

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple layout: just a fragment container
        val frameLayout = android.widget.FrameLayout(this).apply {
            id = android.R.id.content
        }
        setContentView(frameLayout)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = "tutor_prefs"
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Dark mode: apply immediately on change
            findPreference<ListPreference>("dark_mode")?.setOnPreferenceChangeListener { _, newValue ->
                applyDarkMode(newValue as String)
                true
            }

            // Update summaries for list preferences to show current value
            updateListSummary("alert_distance")
            updateListSummary("speed_threshold")
            updateListSummary("dark_mode")

            // Add trip history preference
            val tripPref = Preference(requireContext()).apply {
                key = "trip_history_view"
                title = "Storico viaggi"
                summary = "Visualizza gli ultimi viaggi"
                setOnPreferenceClickListener {
                    showTripHistory()
                    true
                }
            }
            // Add to the driving category
            findPreference<androidx.preference.PreferenceCategory>("pref_cat_driving")?.addPreference(tripPref)
                ?: preferenceScreen.addPreference(tripPref)
        }

        private fun updateListSummary(key: String) {
            val pref = findPreference<ListPreference>(key) ?: return
            pref.summary = pref.entry
            pref.setOnPreferenceChangeListener { preference, newValue ->
                val listPref = preference as ListPreference
                val index = listPref.findIndexOfValue(newValue as String)
                if (index >= 0) {
                    preference.summary = listPref.entries[index]
                }
                if (key == "dark_mode") applyDarkMode(newValue)
                true
            }
        }

        private fun applyDarkMode(value: String) {
            val mode = when (value) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        private fun showTripHistory() {
            val trips = TripHistoryManager.getAllTrips(requireContext())
            if (trips.isEmpty()) {
                com.google.android.material.snackbar.Snackbar.make(
                    requireView(), "Nessun viaggio registrato", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).show()
                return
            }

            val sb = StringBuilder()
            val dateFormat = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ITALIAN)
            for (trip in trips.take(10)) {
                val date = dateFormat.format(java.util.Date(trip.startTime))
                val duration = trip.durationMinutes
                sb.appendLine("$date — ${duration}min · ${String.format("%.1f", trip.distanceKm)}km · ${trip.tutorCount} tutor · media ${trip.avgSpeed} km/h")
            }

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Ultimi viaggi")
                .setMessage(sb.toString().trimEnd())
                .setPositiveButton("OK", null)
                .setNegativeButton("Cancella storico") { _, _ ->
                    TripHistoryManager.clearHistory(requireContext())
                    com.google.android.material.snackbar.Snackbar.make(
                        requireView(), "Storico cancellato", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                    ).show()
                }
                .show()
        }
    }
}
