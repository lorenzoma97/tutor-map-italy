package io.github.lorenzoma97.tutormap

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import android.content.res.Configuration
import org.json.JSONArray
import org.json.JSONObject
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
        const val ALERT_COOLDOWN_MS = 5 * 60 * 1000L
        const val DATA_URL = "https://lorenzoma97.github.io/tutor-map-italy/tutor_segments.json"
        const val ACTION_STOP = "io.github.lorenzoma97.tutormap.STOP"
        const val ACTION_WIDGET_TOGGLE = "io.github.lorenzoma97.tutormap.WIDGET_TOGGLE"
        const val WIDGET_UPDATE_ACTION = "io.github.lorenzoma97.tutormap.WIDGET_UPDATE"
        const val POSITION_BUFFER_SIZE = 10
        const val MIN_TRAVEL_DISTANCE_M = 50.0
        const val MAX_BEARING_DIFF = 60.0
        const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L
        const val IDLE_SPEED_THRESHOLD = 10.0
        @Volatile var isRunning = false
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var audioManager: AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var segments: List<TutorSegment> = emptyList()
    private val alertedSegments = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val activeSegments = java.util.concurrent.ConcurrentHashMap<String, ActiveSegmentInfo>()
    private val handler = Handler(Looper.getMainLooper())
    private val positionBuffer = ArrayDeque<LatLng>(POSITION_BUFFER_SIZE + 1)

    // Overlay
    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var overlaySpeed: TextView? = null
    private var overlayAvg: TextView? = null
    private var overlayInfo: TextView? = null
    private var overlayNextTutor: TextView? = null
    private var overlayDivider: View? = null
    private var overlayProgress: SegmentProgressBar? = null
    private var overlayBgDrawable: android.graphics.drawable.GradientDrawable? = null
    private var overlayVisible = false
    private var overlayTextPrimary = Color.parseColor("#1a1a1a")
    private var overlayTextSecondary = Color.parseColor("#666666")
    private var overlayTextMuted = Color.parseColor("#999999")
    private var overlayAccentColor = Color.parseColor("#1a73e8")

    // Speed
    private var currentSpeedKmh = 0.0
    private val speedBuffer = ArrayDeque<Double>(4) // smoothing ultimi 3 valori
    private var lastLocationTime = 0L
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var gpsAcquired = false

    // Debounce notifica persistente
    private var lastNotificationUpdate = 0L

    // GPS adattivo
    private var currentGpsMode = "far"

    // Idle detection
    private var lastMovingTime = 0L
    private var isIdlePaused = false

    // Trip statistics
    private var tripStartTime = 0L
    private var tripTotalDistance = 0.0
    private var tripTutorCount = 0
    private var tripMaxSpeed = 0.0
    private var tripSpeedSum = 0.0
    private var tripSpeedCount = 0

    // Audio focus
    private var audioFocusRequest: AudioFocusRequest? = null

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
        val type: String,
        val speedLimit: Int
    ) {
        val id get() = "$highway-$startName-$endName-$direction"
    }

    data class ActiveSegmentInfo(
        val seg: TutorSegment,
        val entryTime: Long,
        val entryLat: Double,
        val entryLng: Double,
        var totalDistance: Double = 0.0,
        var lastLat: Double = entryLat,
        var lastLng: Double = entryLng
    ) {
        fun avgSpeedKmh(): Double {
            val elapsedH = (System.currentTimeMillis() - entryTime) / 3600000.0
            return if (elapsedH > 0.001) totalDistance / elapsedH else 0.0
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
        if (!ttsReady) return
        if (!isSettingEnabled("sound", true)) return

        // Rilascia focus precedente se ancora attivo
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }

        // Audio focus: chiedi transient duck (Maps abbassa il volume, non si ferma)
        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()

        val result = audioManager.requestAudioFocus(focusReq)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = focusReq
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                }
            })
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "tutor_${System.currentTimeMillis()}")
        } else {
            // Maps sta parlando e non cede il focus — solo vibrazione
            Log.i(TAG, "Audio focus non ottenuto, skip TTS")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_WIDGET_TOGGLE) {
            if (isRunning) { stopSelf(); return START_NOT_STICKY }
        }

        val notification = buildPersistentNotification("Monitoraggio Tutor attivo")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startForeground(NOTIFICATION_PERSISTENT_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_PERSISTENT_ID, notification)
        }
        isRunning = true
        tripStartTime = System.currentTimeMillis()
        startLocationUpdates()
        showOverlay()
        sendBroadcast(Intent(WIDGET_UPDATE_ACTION).setPackage(packageName))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        saveTripData()
        sendBroadcast(Intent(WIDGET_UPDATE_ACTION).setPackage(packageName))
        super.onDestroy()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {}
        tts?.stop()
        tts?.shutdown()
        tts = null
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        hideOverlay()
    }

    private fun saveTripData() {
        if (tripStartTime <= 0) return
        try {
            val tripJson = JSONObject().apply {
                put("start", tripStartTime)
                put("end", System.currentTimeMillis())
                put("distance_km", String.format("%.1f", tripTotalDistance).toDouble())
                put("tutor_count", tripTutorCount)
                put("avg_speed", if (tripSpeedCount > 0) (tripSpeedSum / tripSpeedCount).toInt() else 0)
                put("max_speed", tripMaxSpeed.toInt())
            }
            val historyPrefs = getSharedPreferences("trip_history", MODE_PRIVATE)
            historyPrefs.edit().putString("last_trip", tripJson.toString()).apply()
            val existing = historyPrefs.getString("trips", "[]") ?: "[]"
            val arr = try { JSONArray(existing) } catch (_: Exception) { JSONArray() }
            arr.put(tripJson)
            while (arr.length() > 50) arr.remove(0)
            historyPrefs.edit().putString("trips", arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Errore salvataggio trip", e)
        }
    }

    // ============ Notification Channels ============

    private fun createNotificationChannels() {
        val persistent = NotificationChannel(
            CHANNEL_PERSISTENT, "Monitoraggio GPS", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifica persistente durante il monitoraggio"
            setShowBadge(false)
        }

        val alert = NotificationChannel(
            CHANNEL_ALERT, getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH
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
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }

    // ============ Location Updates ============

    private fun buildLocationRequest(): LocationRequest {
        val (interval, minInterval, minDist) = when {
            isIdlePaused -> Triple(30000L, 15000L, 100f)
            currentGpsMode == "inside" -> Triple(2000L, 1000L, 10f)
            currentGpsMode == "near" -> Triple(3000L, 2000L, 20f)
            else -> Triple(10000L, 5000L, 50f) // far
        }
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(minInterval)
            .setMinUpdateDistanceMeters(minDist)
            .build()
    }

    private fun updateLocationRequest() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            fusedLocationClient.requestLocationUpdates(buildLocationRequest(), locationCallback, Looper.getMainLooper())
            Log.i(TAG, "GPS mode: ${if (isIdlePaused) "idle" else currentGpsMode}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permessi GPS mancanti", e)
        }
    }

    private fun evaluateGpsMode(lat: Double, lng: Double) {
        val newMode = when {
            activeSegments.isNotEmpty() -> "inside"
            distToNearestSegment(lat, lng) < 10.0 -> "near"
            else -> "far"
        }
        if (newMode != currentGpsMode && !isIdlePaused) {
            currentGpsMode = newMode
            updateLocationRequest()
        }
    }

    private fun distToNearestSegment(lat: Double, lng: Double): Double {
        if (segments.isEmpty()) return Double.MAX_VALUE
        return segments.minOf { seg ->
            minOf(
                haversineKm(lat, lng, seg.startLat, seg.startLng),
                haversineKm(lat, lng, seg.endLat, seg.endLng)
            )
        }
    }

    private fun startLocationUpdates() {
        val request = buildLocationRequest()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val lat = loc.latitude
                val lng = loc.longitude
                val now = System.currentTimeMillis()

                // Calcola velocità corrente dal GPS
                var rawSpeed = 0.0
                if (loc.hasSpeed()) {
                    rawSpeed = loc.speed.toDouble() * 3.6
                } else if (lastLocationTime > 0) {
                    val dt = (now - lastLocationTime) / 1000.0
                    if (dt > 0.5) {
                        val dist = haversineKm(lastLat, lastLng, lat, lng) * 1000
                        rawSpeed = (dist / dt) * 3.6
                    }
                }

                // Speed smoothing: media mobile ultime 3 letture
                if (lastLocationTime > 0) {
                    speedBuffer.addLast(rawSpeed)
                    while (speedBuffer.size > 3) speedBuffer.removeFirst()
                    currentSpeedKmh = speedBuffer.average()
                }

                lastLat = lat
                lastLng = lng
                lastLocationTime = now
                if (!gpsAcquired) gpsAcquired = true

                // Feed position buffer per rilevamento direzione
                positionBuffer.addLast(LatLng(lat, lng))
                while (positionBuffer.size > POSITION_BUFFER_SIZE) positionBuffer.removeFirst()

                // Aggiorna distanza percorsa nei tratti attivi
                for ((_, info) in activeSegments) {
                    val step = haversineKm(info.lastLat, info.lastLng, lat, lng)
                    info.totalDistance += step
                    info.lastLat = lat
                    info.lastLng = lng
                }

                // Trip statistics
                if (lastLocationTime > 0) {
                    val stepKm = haversineKm(lastLat, lastLng, lat, lng)
                    tripTotalDistance += stepKm
                    if (currentSpeedKmh > tripMaxSpeed) tripMaxSpeed = currentSpeedKmh
                    tripSpeedSum += currentSpeedKmh
                    tripSpeedCount++
                }

                // Idle detection
                if (currentSpeedKmh > IDLE_SPEED_THRESHOLD) {
                    lastMovingTime = now
                    if (isIdlePaused) {
                        isIdlePaused = false
                        updateLocationRequest()
                    }
                } else if (lastMovingTime > 0 && now - lastMovingTime > IDLE_TIMEOUT_MS && !isIdlePaused) {
                    isIdlePaused = true
                    updateLocationRequest()
                }

                checkProximity(lat, lng)
                updateOverlay(lat, lng)
                updatePersistentNotification()

                // Adaptive GPS mode
                evaluateGpsMode(lat, lng)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Permessi GPS mancanti", e)
            stopSelf()
        }
    }

    private fun updatePersistentNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdate < 5000) return
        lastNotificationUpdate = now

        if (isIdlePaused) {
            val notification = buildPersistentNotification("In pausa — veicolo fermo")
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.notify(NOTIFICATION_PERSISTENT_ID, notification)
            return
        }

        val speedStr = if (gpsAcquired) "${currentSpeedKmh.toInt()} km/h" else "GPS in acquisizione..."
        val activeInfo = activeSegments.values.firstOrNull()
        val text = if (activeInfo != null) {
            val avg = activeInfo.avgSpeedKmh().toInt()
            val limit = activeInfo.seg.speedLimit
            "$speedStr · Media: $avg/$limit km/h · ${activeInfo.seg.highway}"
        } else {
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
            "$speedStr · ${segments.size} tratti$dirStr"
        }

        val notification = buildPersistentNotification(text)
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_PERSISTENT_ID, notification)
    }

    // ============ Proximity & Alerts ============

    private fun checkProximity(lat: Double, lng: Double) {
        if (segments.isEmpty()) return
        val now = System.currentTimeMillis()

        // 1. Controlla fine tratti attivi
        val ended = mutableListOf<String>()
        for ((segId, info) in activeSegments) {
            val distEnd = haversineKm(lat, lng, info.seg.endLat, info.seg.endLng)
            if (distEnd < END_RADIUS_KM) {
                ended.add(segId)
                showEndAlert(info)
            }
            val distStart = haversineKm(lat, lng, info.seg.startLat, info.seg.startLng)
            if (distStart > 15 && distEnd > 15) ended.add(segId)
        }
        ended.forEach { activeSegments.remove(it) }

        // 2. Controlla inizio nuovi tratti (solo direzione giusta)
        val alertRadius = getAlertRadius()
        val speedThreshold = getSharedPreferences("tutor_prefs", MODE_PRIVATE)
            .getString("speed_threshold", "0")?.toIntOrNull() ?: 0
        for (seg in segments) {
            val dist = haversineKm(lat, lng, seg.startLat, seg.startLng)
            if (dist < alertRadius && isMatchingDirection(seg)) {
                if (speedThreshold > 0 && currentSpeedKmh < speedThreshold) continue
                val lastAlert = alertedSegments[seg.id]
                if (lastAlert == null || now - lastAlert > ALERT_COOLDOWN_MS) {
                    alertedSegments[seg.id] = now
                    activeSegments[seg.id] = ActiveSegmentInfo(
                        seg = seg, entryTime = now,
                        entryLat = lat, entryLng = lng
                    )
                    tripTutorCount++
                    showProximityAlert(seg, dist)
                    openMapsPin(seg)
                }
            }
        }
    }

    private fun showProximityAlert(seg: TutorSegment, distKm: Double) {
        val distMeters = (distKm * 1000).toInt()
        val tipo30 = if (seg.type == "tutor_3.0") " [3.0]" else ""
        val title = "TUTOR tra ${distMeters}m · Limite ${seg.speedLimit} km/h"
        val body = "${seg.highway} · ${seg.startName} \u2192 ${seg.endName} · Dir. ${seg.direction}$tipo30"

        showNotification(title, body)

        val distVoce = if (distKm < 1) "${distMeters} metri" else "${String.format("%.1f", distKm)} chilometri"
        val kmVoce = if (seg.startKm != null && seg.endKm != null)
            ", dal chilometro ${seg.startKm.toInt()} al chilometro ${seg.endKm.toInt()}" else ""
        val tipoVoce = if (seg.type == "tutor_3.0") ", tutor 3.0" else ""
        speakText("Attenzione, tutor ${seg.highway}$tipoVoce tra $distVoce$kmVoce")

        Log.i(TAG, "ALERT: ${seg.id} a ${distMeters}m")
    }

    private fun showEndAlert(info: ActiveSegmentInfo) {
        val avg = info.avgSpeedKmh().toInt()
        val seg = info.seg
        val overLimit = avg > seg.speedLimit
        val suffix = if (overLimit) " \u26a0\ufe0f" else " \u2713"
        val title = "Tratto terminato · Media: $avg km/h$suffix"
        val body = "${seg.highway} ${seg.startName} \u2192 ${seg.endName} · Limite: ${seg.speedLimit} km/h"
        val vibration = if (overLimit) longArrayOf(0, 800, 200, 800) else longArrayOf(0, 200)

        showNotification(title, body, vibration)

        val giudizio = when {
            avg > seg.speedLimit -> ", attenzione, media sopra il limite"
            avg > seg.speedLimit - 10 -> ", media vicina al limite"
            else -> ""
        }
        speakText("Tratto tutor ${seg.highway} terminato, media ${avg} chilometri orari$giudizio")
        Log.i(TAG, "END: ${seg.id} avg=${avg} km/h")
    }

    private fun showNotification(title: String, body: String, vibrationPattern: LongArray = longArrayOf(0, 500, 200, 500)) {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(this, 1, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .setVibrate(vibrationPattern)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ALERT_ID, notification)
    }

    // ============ Floating Overlay ============

    private fun showOverlay() {
        if (!isSettingEnabled("overlay", true)) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Permesso overlay non concesso")
            return
        }

        val dp = { px: Int -> TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, px.toFloat(), resources.displayMetrics
        ).toInt() }

        // Night mode detection
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isNight = nightMode == Configuration.UI_MODE_NIGHT_YES

        // Theme colors
        overlayTextPrimary = if (isNight) Color.WHITE else Color.parseColor("#1a1a1a")
        overlayTextSecondary = if (isNight) Color.argb(255, 180, 180, 180) else Color.parseColor("#555555")
        overlayTextMuted = if (isNight) Color.argb(200, 120, 120, 120) else Color.parseColor("#999999")
        overlayAccentColor = Color.parseColor("#1a73e8")

        val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (isNight) Color.argb(245, 20, 20, 20) else Color.argb(250, 255, 255, 255))
            setStroke(dp(2), overlayAccentColor)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bgDrawable
            setPadding(dp(16), dp(12), dp(16), dp(12))
            clipToOutline = true
            minimumWidth = dp(180)
            elevation = dp(6).toFloat()
        }

        // --- Speed section ---
        val speedTv = TextView(this).apply {
            textSize = 28f
            setTextColor(overlayTextPrimary)
            text = "-- km/h"
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }

        val avgTv = TextView(this).apply {
            textSize = 13f
            setTextColor(overlayTextSecondary)
            text = ""
            gravity = Gravity.CENTER
        }

        val progressBar = SegmentProgressBar(this).apply {
            val h = dp(5)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h
            ).apply { setMargins(0, dp(4), 0, dp(2)) }
            visibility = View.GONE
        }

        // --- Divider ---
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { setMargins(0, dp(8), 0, dp(8)) }
            setBackgroundColor(if (isNight) Color.argb(40, 255, 255, 255) else Color.parseColor("#e0e0e0"))
        }

        // --- Tutor section ---
        val nextTutorTv = TextView(this).apply {
            textSize = 15f
            setTextColor(overlayTextPrimary)
            text = "Prossimo Tutor"
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }

        val infoTv = TextView(this).apply {
            textSize = 11f
            setTextColor(overlayTextMuted)
            text = ""
            gravity = Gravity.CENTER
        }

        layout.addView(speedTv)
        layout.addView(avgTv)
        layout.addView(progressBar)
        layout.addView(divider)
        layout.addView(nextTutorTv)
        layout.addView(infoTv)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(16)
            y = dp(100)
        }

        // Drag to move + double-tap to reset position
        var initX = 0
        var initY = 0
        var initTouchX = 0f
        var initTouchY = 0f
        var lastTapTime = 0L
        val defaultX = dp(16)
        val defaultY = dp(100)
        layout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initTouchX = event.rawX; initTouchY = event.rawY
                    // Double-tap detection
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 300) {
                        params.x = defaultX; params.y = defaultY
                        try { windowManager?.updateViewLayout(layout, params) } catch (_: Exception) {}
                    }
                    lastTapTime = now
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX - (event.rawX - initTouchX).toInt()
                    params.y = initY + (event.rawY - initTouchY).toInt()
                    try { windowManager?.updateViewLayout(layout, params) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(layout, params)
            overlayView = layout
            overlaySpeed = speedTv
            overlayAvg = avgTv
            overlayProgress = progressBar
            overlayDivider = divider
            overlayInfo = infoTv
            overlayNextTutor = nextTutorTv
            overlayBgDrawable = bgDrawable
            overlayVisible = true
            Log.i(TAG, "Overlay mostrato")
        } catch (e: Exception) {
            Log.e(TAG, "Errore overlay", e)
        }
    }

    private fun hideOverlay() {
        if (overlayVisible && overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (_: Exception) {}
            overlayView = null
            overlaySpeed = null
            overlayAvg = null
            overlayInfo = null
            overlayNextTutor = null
            overlayDivider = null
            overlayProgress = null
            overlayBgDrawable = null
            overlayVisible = false
        }
    }

    private fun updateOverlay(lat: Double, lng: Double) {
        if (!overlayVisible) return

        val strokeWidth = (2 * resources.displayMetrics.density).toInt()

        handler.post {
            if (overlayView == null) return@post

            // Velocita' corrente
            if (!gpsAcquired) {
                overlaySpeed?.text = "-- km/h"
                overlaySpeed?.setTextColor(overlayTextPrimary)
                overlayNextTutor?.text = "In attesa GPS..."
                overlayNextTutor?.setTextColor(overlayTextMuted)
                overlayInfo?.text = ""
                overlayBgDrawable?.setStroke(strokeWidth, overlayAccentColor)
                return@post
            }

            overlaySpeed?.text = "${currentSpeedKmh.toInt()} km/h"

            val activeInfo = activeSegments.values.firstOrNull()
            if (activeInfo != null) {
                // --- In tratto Tutor ---
                val avg = activeInfo.avgSpeedKmh().toInt()
                val limit = activeInfo.seg.speedLimit
                val distEnd = haversineKm(lat, lng, activeInfo.seg.endLat, activeInfo.seg.endLng)
                val segLen = haversineKm(activeInfo.seg.startLat, activeInfo.seg.startLng,
                    activeInfo.seg.endLat, activeInfo.seg.endLng)
                val distStr = if (distEnd < 1) "${(distEnd * 1000).toInt()}m"
                              else "${String.format("%.1f", distEnd)}km"
                val progress = if (segLen > 0) (1.0 - distEnd / segLen).coerceIn(0.0, 1.0) else 0.0

                // Colore in base a media vs limite
                val color = when {
                    avg > limit -> Color.rgb(230, 57, 53)       // rosso
                    avg > limit - 10 -> Color.rgb(255, 160, 0)  // arancione
                    else -> Color.rgb(52, 168, 83)              // verde
                }

                overlaySpeed?.setTextColor(color)
                overlayAvg?.text = "Media: $avg/$limit km/h"
                overlayAvg?.setTextColor(color)
                overlayProgress?.visibility = View.VISIBLE
                overlayProgress?.update(progress.toFloat(), color)

                overlayNextTutor?.text = "${activeInfo.seg.highway} · Fine tra $distStr"
                overlayNextTutor?.setTextColor(overlayTextPrimary)
                overlayInfo?.text = "${activeInfo.seg.startName} → ${activeInfo.seg.endName}"

                // Bordo colorato in base allo stato
                overlayBgDrawable?.setStroke(strokeWidth, color)
            } else {
                // --- Fuori da tratti Tutor ---
                overlaySpeed?.setTextColor(overlayTextPrimary)
                overlayAvg?.text = ""
                overlayProgress?.visibility = View.GONE

                // Trova il tratto piu' vicino nella direzione giusta
                var nearestDist = Double.MAX_VALUE
                var nearestSeg: TutorSegment? = null
                for (seg in segments) {
                    if (!isMatchingDirection(seg)) continue
                    val d = haversineKm(lat, lng, seg.startLat, seg.startLng)
                    if (d < nearestDist) { nearestDist = d; nearestSeg = seg }
                }

                if (nearestSeg != null && nearestDist < 50) {
                    val distStr = if (nearestDist < 1) "${(nearestDist * 1000).toInt()}m"
                                  else "${String.format("%.1f", nearestDist)}km"
                    overlayNextTutor?.text = "${nearestSeg.highway} tra $distStr"
                    overlayNextTutor?.setTextColor(overlayTextPrimary)
                    overlayInfo?.text = "${nearestSeg.startName} → ${nearestSeg.endName}"
                } else {
                    overlayNextTutor?.text = "Nessun Tutor vicino"
                    overlayNextTutor?.setTextColor(overlayTextMuted)
                    overlayInfo?.text = ""
                }

                // Bordo blu standard
                overlayBgDrawable?.setStroke(strokeWidth, overlayAccentColor)
            }
        }
    }

    // ============ Data Loading ============

    private fun loadSegmentsAsync() {
        Thread {
            // 1. Prova cache locale
            val cached = loadCachedSegments()
            if (cached.isNotEmpty()) {
                segments = cached
                Log.i(TAG, "Caricati ${cached.size} tratti da cache")
                if (isRunning) handler.post { updatePersistentNotification() }
            }

            // 2. Scarica da rete (aggiorna cache)
            try {
                val url = URL(DATA_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val list = parseSegments(json)
                if (list.isNotEmpty()) {
                    segments = list
                    Log.i(TAG, "Caricati ${list.size} tratti da rete")

                    // Salva cache
                    getSharedPreferences("tutor_cache", MODE_PRIVATE).edit()
                        .putString("segments_json", json)
                        .putLong("cache_time", System.currentTimeMillis())
                        .apply()

                    if (isRunning) handler.post { updatePersistentNotification() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore caricamento segmenti da rete", e)
                if (segments.isEmpty()) {
                    Log.w(TAG, "Nessun dato disponibile (cache vuota + rete fallita)")
                    if (isRunning) handler.post {
                        val mgr = getSystemService(NotificationManager::class.java)
                        mgr.notify(NOTIFICATION_PERSISTENT_ID,
                            buildPersistentNotification("Dati Tutor non disponibili"))
                    }
                }
            }
        }.start()
    }

    private fun loadCachedSegments(): List<TutorSegment> {
        return try {
            val prefs = getSharedPreferences("tutor_cache", MODE_PRIVATE)
            val json = prefs.getString("segments_json", null) ?: return emptyList()
            parseSegments(json)
        } catch (e: Exception) {
            Log.e(TAG, "Errore lettura cache", e)
            emptyList()
        }
    }

    private fun parseSegments(json: String): List<TutorSegment> {
        val arr = try { JSONArray(json) } catch (e: Exception) {
            Log.e(TAG, "JSON non valido", e); return emptyList()
        }
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
                    type = obj.optString("type", "standard"),
                    speedLimit = obj.optInt("speed_limit", 130)
                ))
            }
        }
        return list
    }

    // ============ Direction Detection ============

    private fun getUserBearing(): Double? {
        if (positionBuffer.size < 2) return null
        val oldest = positionBuffer.first()
        val newest = positionBuffer.last()
        val dist = haversineKm(oldest.lat, oldest.lng, newest.lat, newest.lng) * 1000
        if (dist < MIN_TRAVEL_DISTANCE_M) return null
        return calcBearing(oldest.lat, oldest.lng, newest.lat, newest.lng)
    }

    private fun isMatchingDirection(seg: TutorSegment): Boolean {
        val userBearing = getUserBearing() ?: return false // Nessun bearing = non alertare
        val segBearing = calcBearing(seg.startLat, seg.startLng, seg.endLat, seg.endLng)
        return bearingDiff(userBearing, segBearing) < MAX_BEARING_DIFF
    }

    // ============ Math ============

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        return R * 2 * asin(sqrt(a))
    }

    private fun calcBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val rlat1 = Math.toRadians(lat1)
        val rlat2 = Math.toRadians(lat2)
        val x = sin(dLon) * cos(rlat2)
        val y = cos(rlat1) * sin(rlat2) - sin(rlat1) * cos(rlat2) * cos(dLon)
        return (Math.toDegrees(atan2(x, y)) + 360) % 360
    }

    private fun bearingDiff(b1: Double, b2: Double): Double {
        val diff = abs(b1 - b2) % 360
        return if (diff > 180) 360 - diff else diff
    }

    // ============ Settings helper ============

    private fun isSettingEnabled(key: String, default: Boolean = true): Boolean {
        return getSharedPreferences("tutor_prefs", MODE_PRIVATE).getBoolean(key, default)
    }

    private fun getAlertRadius(): Double {
        return getSharedPreferences("tutor_prefs", MODE_PRIVATE)
            .getString("alert_distance", "2")?.toDoubleOrNull() ?: 2.0
    }

    // ============ Maps pin ============

    private fun openMapsPin(seg: TutorSegment) {
        if (!isSettingEnabled("maps_pin", true)) return
        try {
            // Usa notifica con azione per aprire Maps (funziona anche da background su Android 10+)
            val geoUri = Uri.parse("geo:${seg.endLat},${seg.endLng}?q=${seg.endLat},${seg.endLng}(Fine+Tutor+${seg.highway})")
            val mapsIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                setPackage("com.google.android.apps.maps")
            }
            val mapsPending = PendingIntent.getActivity(this, 2, mapsIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setContentTitle("Fine Tutor ${seg.highway}")
                .setContentText("Tocca per vedere su Maps: ${seg.endName}")
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(mapsPending)
                .build()

            val mgr = getSystemService(NotificationManager::class.java)
            mgr.notify(NOTIFICATION_ALERT_ID + 1, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Impossibile creare notifica Maps", e)
        }
    }

    // ============ Custom ProgressBar ============

    class SegmentProgressBar(context: Context) : View(context) {
        var progress = 0f // 0.0 - 1.0
        var barColor = Color.rgb(80, 220, 80)

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(100, 255, 255, 255)
        }
        private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val radius = 8f

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()

            // Background
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, bgPaint)

            // Filled portion
            fgPaint.color = barColor
            val fillW = (w * progress.coerceIn(0f, 1f))
            if (fillW > 0) {
                rect.set(0f, 0f, fillW, h)
                canvas.drawRoundRect(rect, radius, radius, fgPaint)
            }
        }

        fun update(progress: Float, color: Int) {
            this.progress = progress
            this.barColor = color
            invalidate()
        }
    }
}
