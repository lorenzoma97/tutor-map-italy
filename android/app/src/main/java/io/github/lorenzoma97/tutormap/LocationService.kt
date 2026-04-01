package io.github.lorenzoma97.tutormap

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.*

class LocationService : Service() {

    companion object {
        const val TAG = "TutorLocationService"
        const val CHANNEL_PERSISTENT = "tutor_persistent"
        const val CHANNEL_ALERT = "tutor_alert"
        const val NOTIFICATION_PERSISTENT_ID = 1
        const val NOTIFICATION_ALERT_ID = 2
        const val ALERT_RADIUS_KM = 2.0
        const val END_RADIUS_KM = 0.8
        const val ALERT_COOLDOWN_MS = 5 * 60 * 1000L // 5 minuti tra alert stesso tratto
        const val DATA_URL = "https://lorenzoma97.github.io/tutor-map-italy/tutor_segments.json"
        const val ACTION_STOP = "io.github.lorenzoma97.tutormap.STOP"
        const val POSITION_BUFFER_SIZE = 10
        const val MIN_TRAVEL_DISTANCE_M = 50.0
        const val MAX_BEARING_DIFF = 60.0 // gradi di tolleranza
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var segments: List<TutorSegment> = emptyList()
    private val alertedSegments = mutableMapOf<String, Long>() // segId -> timestamp
    private val activeSegments = mutableMapOf<String, TutorSegment>() // tratti in corso
    private val handler = Handler(Looper.getMainLooper())
    private val positionBuffer = ArrayDeque<LatLng>(POSITION_BUFFER_SIZE + 1) // buffer traiettoria

    data class LatLng(val lat: Double, val lng: Double)

    data class TutorSegment(
        val highway: String,
        val startName: String,
        val endName: String,
        val startKm: Double?,
        val endKm: Double?,
        val direction: String,
        val startLat: Double,
        val startLng: Double,
        val endLat: Double,
        val endLng: Double,
        val type: String
    ) {
        val id get() = "$highway-$startName-$direction"
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannels()
        initTts()
        loadSegmentsAsync()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.ITALIAN
                ttsReady = true
                Log.i(TAG, "TTS inizializzato")
            }
        }
    }

    private fun speakText(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "tutor_alert_${System.currentTimeMillis()}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildPersistentNotification("Monitoraggio Tutor attivo")
        startForeground(NOTIFICATION_PERSISTENT_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        startLocationUpdates()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {}
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun createNotificationChannels() {
        val persistent = NotificationChannel(
            CHANNEL_PERSISTENT,
            "Monitoraggio GPS",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifica persistente durante il monitoraggio"
            setShowBadge(false)
        }

        val alert = NotificationChannel(
            CHANNEL_ALERT,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_description)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(persistent)
        mgr.createNotificationChannel(alert)
    }

    private fun buildPersistentNotification(text: String): Notification {
        val stopIntent = Intent(this, LocationService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_PERSISTENT)
            .setContentTitle("Tutor Map")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(2000L)
            .setMinUpdateDistanceMeters(50f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return

                // Feed position buffer per rilevamento direzione
                positionBuffer.addLast(LatLng(loc.latitude, loc.longitude))
                while (positionBuffer.size > POSITION_BUFFER_SIZE) positionBuffer.removeFirst()

                checkProximity(loc.latitude, loc.longitude)

                // Aggiorna notifica persistente con direzione
                val bearing = getUserBearing()
                val dirStr = if (bearing != null) {
                    val dir = when {
                        bearing >= 315 || bearing < 45 -> "N"
                        bearing >= 45 && bearing < 135 -> "E"
                        bearing >= 135 && bearing < 225 -> "S"
                        else -> "O"
                    }
                    " · Dir. $dir"
                } else ""
                val notification = buildPersistentNotification(
                    "GPS attivo · ${segments.size} tratti$dirStr"
                )
                val mgr = getSystemService(NotificationManager::class.java)
                mgr.notify(NOTIFICATION_PERSISTENT_ID, notification)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Permessi GPS mancanti", e)
            stopSelf()
        }
    }

    private fun checkProximity(lat: Double, lng: Double) {
        if (segments.isEmpty()) return

        val now = System.currentTimeMillis()

        // 1. Controlla fine tratti attivi
        val ended = mutableListOf<String>()
        for ((segId, seg) in activeSegments) {
            val distEnd = haversineKm(lat, lng, seg.endLat, seg.endLng)
            if (distEnd < END_RADIUS_KM) {
                ended.add(segId)
                showEndAlert(seg)
            }
            // Auto-rimuovi se troppo lontano
            val distStart = haversineKm(lat, lng, seg.startLat, seg.startLng)
            if (distStart > 15 && distEnd > 15) {
                ended.add(segId)
            }
        }
        ended.forEach { activeSegments.remove(it) }

        // 2. Controlla inizio nuovi tratti (solo nella direzione giusta)
        for (seg in segments) {
            val dist = haversineKm(lat, lng, seg.startLat, seg.startLng)
            if (dist < ALERT_RADIUS_KM && isMatchingDirection(seg)) {
                val lastAlert = alertedSegments[seg.id]
                if (lastAlert == null || now - lastAlert > ALERT_COOLDOWN_MS) {
                    alertedSegments[seg.id] = now
                    activeSegments[seg.id] = seg
                    showProximityAlert(seg, dist)
                }
            }
        }
    }

    private fun showProximityAlert(seg: TutorSegment, distKm: Double) {
        val distMeters = (distKm * 1000).toInt()
        val tipo30 = if (seg.type == "tutor_3.0") " [3.0]" else ""
        val title = "TUTOR tra ${distMeters}m"
        val body = "${seg.highway} · ${seg.startName} → ${seg.endName} · Dir. ${seg.direction}$tipo30"

        showNotification(title, body)

        // Voce: prima km, poi citta'
        val distVoce = if (distKm < 1) "${distMeters} metri" else "${String.format("%.1f", distKm)} chilometri"
        val kmVoce = if (seg.startKm != null && seg.endKm != null)
            ", dal chilometro ${seg.startKm.toInt()} al chilometro ${seg.endKm.toInt()}" else ""
        val tipoVoce = if (seg.type == "tutor_3.0") ", tutor 3.0" else ""
        speakText("Attenzione, tutor ${seg.highway}$tipoVoce tra $distVoce$kmVoce, da ${seg.startName} a ${seg.endName}")

        Log.i(TAG, "ALERT: ${seg.id} a ${distMeters}m")
    }

    private fun showEndAlert(seg: TutorSegment) {
        showNotification(
            "Tratto terminato",
            "${seg.highway} ${seg.startName} → ${seg.endName} completato"
        )
        speakText("Tratto tutor ${seg.highway} terminato")
        Log.i(TAG, "END: ${seg.id}")
    }

    private fun showNotification(title: String, body: String) {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(this, 1, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ALERT_ID, notification)
    }

    private fun loadSegmentsAsync() {
        Thread {
            try {
                val url = URL(DATA_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val arr = JSONArray(json)
                val list = mutableListOf<TutorSegment>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val startCoords = obj.optJSONArray("start_coords")
                    val endCoords = obj.optJSONArray("end_coords")
                    if (startCoords != null && startCoords.length() >= 2 &&
                        endCoords != null && endCoords.length() >= 2) {
                        list.add(TutorSegment(
                            highway = obj.optString("highway", ""),
                            startName = obj.optString("start_name", ""),
                            endName = obj.optString("end_name", ""),
                            startKm = if (obj.has("start_km")) obj.optDouble("start_km") else null,
                            endKm = if (obj.has("end_km")) obj.optDouble("end_km") else null,
                            direction = obj.optString("direction", ""),
                            startLat = startCoords.getDouble(0),
                            startLng = startCoords.getDouble(1),
                            endLat = endCoords.getDouble(0),
                            endLng = endCoords.getDouble(1),
                            type = obj.optString("type", "standard")
                        ))
                    }
                }
                segments = list
                Log.i(TAG, "Caricati ${list.size} tratti Tutor")

                handler.post {
                    val notification = buildPersistentNotification(
                        "GPS attivo · ${list.size} tratti monitorati"
                    )
                    val mgr = getSystemService(NotificationManager::class.java)
                    mgr.notify(NOTIFICATION_PERSISTENT_ID, notification)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore caricamento segmenti", e)
            }
        }.start()
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        return R * 2 * asin(sqrt(a))
    }

    /** Calcola bearing in gradi (0-360) da punto A a punto B */
    private fun calcBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val rlat1 = Math.toRadians(lat1)
        val rlat2 = Math.toRadians(lat2)
        val x = sin(dLon) * cos(rlat2)
        val y = cos(rlat1) * sin(rlat2) - sin(rlat1) * cos(rlat2) * cos(dLon)
        return (Math.toDegrees(atan2(x, y)) + 360) % 360
    }

    /** Bearing dell'utente basata sulla traiettoria degli ultimi N punti GPS */
    private fun getUserBearing(): Double? {
        if (positionBuffer.size < 2) return null
        val oldest = positionBuffer.first()
        val newest = positionBuffer.last()
        val dist = haversineKm(oldest.lat, oldest.lng, newest.lat, newest.lng) * 1000
        if (dist < MIN_TRAVEL_DISTANCE_M) return null // fermo o quasi
        return calcBearing(oldest.lat, oldest.lng, newest.lat, newest.lng)
    }

    /** Differenza angolare minima tra due bearing (0-180) */
    private fun bearingDiff(b1: Double, b2: Double): Double {
        val diff = abs(b1 - b2) % 360
        return if (diff > 180) 360 - diff else diff
    }

    /** Verifica se il tratto è nella direzione di marcia dell'utente */
    private fun isMatchingDirection(seg: TutorSegment): Boolean {
        val userBearing = getUserBearing() ?: return true // se non sappiamo, mostra (meglio falso positivo)
        val segBearing = calcBearing(seg.startLat, seg.startLng, seg.endLat, seg.endLng)
        return bearingDiff(userBearing, segBearing) < MAX_BEARING_DIFF
    }
}
