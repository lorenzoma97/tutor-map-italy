package io.github.lorenzoma97.tutormap

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

class LocationService : Service() {

    companion object {
        const val TAG = "TutorLocationService"
        const val CHANNEL_PERSISTENT = "tutor_persistent"
        const val CHANNEL_ALERT = "tutor_alert"
        const val NOTIFICATION_PERSISTENT_ID = 1
        const val NOTIFICATION_ALERT_ID = 2
        const val ALERT_RADIUS_KM = 2.0
        const val ALERT_COOLDOWN_MS = 5 * 60 * 1000L // 5 minuti tra alert stesso tratto
        const val DATA_URL = "https://lorenzoma97.github.io/tutor-map-italy/tutor_segments.json"
        const val ACTION_STOP = "io.github.lorenzoma97.tutormap.STOP"
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var segments: List<TutorSegment> = emptyList()
    private val alertedSegments = mutableMapOf<String, Long>() // segId -> timestamp
    private val handler = Handler(Looper.getMainLooper())

    data class TutorSegment(
        val highway: String,
        val startName: String,
        val endName: String,
        val direction: String,
        val startLat: Double,
        val startLng: Double,
        val type: String
    ) {
        val id get() = "$highway-$startName-$direction"
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannels()
        loadSegmentsAsync()
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
                checkProximity(loc.latitude, loc.longitude)

                // Aggiorna notifica persistente con posizione
                val notification = buildPersistentNotification(
                    "GPS attivo · ${segments.size} tratti monitorati"
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
        for (seg in segments) {
            val dist = haversineKm(lat, lng, seg.startLat, seg.startLng)
            if (dist < ALERT_RADIUS_KM) {
                val lastAlert = alertedSegments[seg.id]
                if (lastAlert == null || now - lastAlert > ALERT_COOLDOWN_MS) {
                    alertedSegments[seg.id] = now
                    showProximityAlert(seg, dist)
                }
            }
        }
    }

    private fun showProximityAlert(seg: TutorSegment, distKm: Double) {
        val distMeters = (distKm * 1000).toInt()
        val title = "⚠️ TUTOR tra ${distMeters}m"
        val body = "${seg.highway} · ${seg.startName} → ${seg.endName} · Dir. ${seg.direction}" +
                if (seg.type == "tutor_3.0") " [3.0]" else ""

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

        Log.i(TAG, "ALERT: ${seg.id} a ${distMeters}m")
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
                    if (startCoords != null && startCoords.length() >= 2) {
                        list.add(TutorSegment(
                            highway = obj.optString("highway", ""),
                            startName = obj.optString("start_name", ""),
                            endName = obj.optString("end_name", ""),
                            direction = obj.optString("direction", ""),
                            startLat = startCoords.getDouble(0),
                            startLng = startCoords.getDouble(1),
                            type = obj.optString("type", "standard")
                        ))
                    }
                }
                segments = list
                Log.i(TAG, "Caricati ${list.size} tratti Tutor")

                // Aggiorna notifica
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
}
