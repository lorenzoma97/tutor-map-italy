package io.github.lorenzoma97.tutormap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gestore singleton per lo storico dei viaggi.
 * Salva e recupera i dati dei viaggi tramite SharedPreferences
 * con serializzazione JSON. Mantiene un massimo di 50 viaggi.
 */
object TripHistoryManager {

    private const val PREFS_NAME = "trip_history"
    private const val KEY_TRIPS = "trips"
    private const val KEY_LAST_TRIP = "last_trip"
    private const val MAX_TRIPS = 50

    /**
     * Rappresenta un singolo viaggio con le sue statistiche.
     *
     * @property startTime timestamp di inizio viaggio (millisecondi)
     * @property endTime timestamp di fine viaggio (millisecondi)
     * @property distanceKm distanza percorsa in chilometri
     * @property tutorCount numero di tutor attraversati
     * @property avgSpeed velocita' media in km/h
     * @property maxSpeed velocita' massima registrata in km/h
     */
    data class Trip(
        val startTime: Long,
        val endTime: Long,
        val distanceKm: Double,
        val tutorCount: Int,
        val avgSpeed: Int,
        val maxSpeed: Int
    ) {
        /** Durata del viaggio in minuti */
        val durationMinutes: Long get() = (endTime - startTime) / 60000

        /** Serializza il viaggio in formato JSON */
        fun toJson(): JSONObject = JSONObject().apply {
            put("start", startTime)
            put("end", endTime)
            put("distance_km", distanceKm)
            put("tutor_count", tutorCount)
            put("avg_speed", avgSpeed)
            put("max_speed", maxSpeed)
        }

        companion object {
            /** Deserializza un viaggio da un oggetto JSON */
            fun fromJson(json: JSONObject): Trip = Trip(
                startTime = json.optLong("start", 0),
                endTime = json.optLong("end", 0),
                distanceKm = json.optDouble("distance_km", 0.0),
                tutorCount = json.optInt("tutor_count", 0),
                avgSpeed = json.optInt("avg_speed", 0),
                maxSpeed = json.optInt("max_speed", 0)
            )
        }
    }

    /**
     * Restituisce l'ultimo viaggio registrato, oppure null se non presente.
     */
    fun getLastTrip(context: Context): Trip? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LAST_TRIP, null) ?: return null
        return try {
            Trip.fromJson(JSONObject(json))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Restituisce tutti i viaggi salvati, ordinati dal piu' recente al meno recente.
     * Massimo [MAX_TRIPS] viaggi vengono mantenuti in memoria.
     */
    fun getAllTrips(context: Context): List<Trip> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_TRIPS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { Trip.fromJson(arr.getJSONObject(it)) }.reversed()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Calcola le statistiche settimanali: numero viaggi, km totali, tutor attraversati.
     *
     * @return Triple(numeroViaggi, kmTotali, tutorTotali) degli ultimi 7 giorni
     */
    fun getWeeklyStats(context: Context): Triple<Int, Double, Int> {
        val oneWeekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        val trips = getAllTrips(context).filter { it.startTime > oneWeekAgo }
        val totalTrips = trips.size
        val totalKm = trips.sumOf { it.distanceKm }
        val totalTutors = trips.sumOf { it.tutorCount }
        return Triple(totalTrips, totalKm, totalTutors)
    }

    /**
     * Cancella tutto lo storico dei viaggi.
     * Rimuove sia la lista completa che l'ultimo viaggio salvato.
     */
    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_TRIPS)
            .remove(KEY_LAST_TRIP)
            .apply()
    }
}
