package io.github.lorenzoma97.tutormap

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.webkit.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    companion object {
        const val PERMISSION_REQUEST_CODE = 100
        const val OVERLAY_REQUEST_CODE = 101
        const val MAP_URL = "https://lorenzoma97.github.io/tutor-map-italy/"
    }

    private lateinit var webView: WebView
    private lateinit var fab: ExtendedFloatingActionButton
    private var monitoring = false
    private var webReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Dark mode
        val darkPref = getSharedPreferences("tutor_prefs", MODE_PRIVATE).getString("dark_mode", "system")
        AppCompatDelegate.setDefaultNightMode(when (darkPref) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        fab = findViewById(R.id.fabMonitor)

        setupWebView()
        webView.loadUrl(MAP_URL)

        // Ripristina stato monitoraggio (sopravvive a rotazione)
        if (LocationService.isRunning) {
            monitoring = true
            updateFabState()
        }

        fab.setOnClickListener {
            if (monitoring) {
                stopMonitoring()
            } else {
                requestPermissionsAndStart()
            }
        }

        findViewById<android.widget.ImageButton>(R.id.btnSettings).apply {
            setImageResource(R.drawable.ic_settings_gear)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }

        // Prefetch dati segmenti Tutor
        prefetchSegments()

        // Controlla Google Play Services
        checkPlayServices()

        // Onboarding primo avvio
        showOnboardingIfNeeded()

        // Controlla ottimizzazione batteria al primo avvio
        checkBatteryOptimization()

        // TTS availability check
        var ttsCheck: android.speech.tts.TextToSpeech? = null
        ttsCheck = android.speech.tts.TextToSpeech(this) { status ->
            if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                runOnUiThread {
                    Snackbar.make(findViewById(android.R.id.content),
                        "Avvisi vocali non disponibili", Snackbar.LENGTH_LONG).show()
                }
            }
            ttsCheck?.shutdown()
            ttsCheck = null
        }
    }

    override fun onResume() {
        super.onResume()
        monitoring = LocationService.isRunning
        updateFabState()
    }

    private fun updateFabState() {
        if (monitoring) {
            fab.shrink()
            fab.postDelayed({
                fab.text = "STOP"
                fab.setIconResource(R.drawable.ic_stop)
                fab.backgroundTintList = ColorStateList.valueOf(0xFFFF6D00.toInt())
                fab.setTextColor(0xFFFFFFFF.toInt())
                fab.iconTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                fab.extend()
            }, 200)
        } else {
            fab.shrink()
            fab.postDelayed({
                fab.text = "AVVIA"
                fab.setIconResource(R.drawable.ic_play)
                fab.backgroundTintList = ColorStateList.valueOf(0xFF34a853.toInt())
                fab.setTextColor(0xFFFFFFFF.toInt())
                fab.iconTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                fab.extend()
            }, 200)
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.addJavascriptInterface(SheetBridge(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return if (url.contains("lorenzoma97.github.io")) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {}
                    true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webReady = true
                // Nasconde il FAB web (il FAB Android lo sostituisce)
                view?.evaluateJavascript(
                    "document.getElementById('fab').style.display='none';", null
                )
                // Se il monitoraggio era già attivo (ripristino), attiva GPS web
                // ma nascondi HUD/alert web (l'overlay nativo gestisce le info)
                if (monitoring) {
                    view?.evaluateJavascript(
                        "if(!compassActive) startCompass();" +
                        "document.getElementById('hud').style.display='none';" +
                        "document.getElementById('alertBanner').style.display='none';", null
                    )
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?,
                                         error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    view?.loadData(
                        "<html><body style='display:flex;align-items:center;justify-content:center;height:100vh;font-family:sans-serif;color:#666'>" +
                        "<div style='text-align:center'><h2>Mappa non disponibile</h2><p>Verifica la connessione internet</p></div></body></html>",
                        "text/html", "UTF-8"
                    )
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            checkOverlayAndStart()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val locationGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (locationGranted) {
                checkOverlayAndStart()
            } else {
                Snackbar.make(findViewById(android.R.id.content), "Permesso GPS necessario", Snackbar.LENGTH_LONG)
                    .setAction("Impostazioni") {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    }.show()
            }
        }
    }

    private fun checkOverlayAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay su altre app")
                .setMessage("Per mostrare velocità e media sopra Google Maps, concedi il permesso di overlay.")
                .setPositiveButton("Concedi") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, OVERLAY_REQUEST_CODE)
                }
                .setNegativeButton("Dopo") { _, _ -> startMonitoring() }
                .show()
        } else {
            startMonitoring()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_REQUEST_CODE) {
            startMonitoring()
        }
    }

    private fun prefetchSegments() {
        // Controlla età cache e avvisa se vecchia
        val prefs = getSharedPreferences("tutor_cache", MODE_PRIVATE)
        val cacheTime = prefs.getLong("cache_time", 0)
        if (cacheTime > 0) {
            val daysOld = (System.currentTimeMillis() - cacheTime) / (1000 * 60 * 60 * 24)
            if (daysOld > 7) {
                Snackbar.make(findViewById(android.R.id.content),
                    "Dati Tutor aggiornati $daysOld giorni fa", Snackbar.LENGTH_LONG).show()
            }
        }

        Thread {
            try {
                val url = java.net.URL(LocationService.DATA_URL)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                getSharedPreferences("tutor_cache", MODE_PRIVATE).edit()
                    .putString("segments_json", json)
                    .putLong("cache_time", System.currentTimeMillis())
                    .apply()
                Log.i("TutorMap", "Prefetch segmenti completato")
            } catch (e: Exception) {
                Log.w("TutorMap", "Prefetch segmenti fallito", e)
                if (cacheTime > 0) {
                    runOnUiThread {
                        Snackbar.make(findViewById(android.R.id.content),
                            "Modalità offline — dati dalla cache", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun showOnboardingIfNeeded() {
        val prefs = getSharedPreferences("tutor_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("onboarding_done", false)) return

        AlertDialog.Builder(this)
            .setTitle("Tutor Map")
            .setMessage(
                "Quest'app ti avvisa quando ti avvicini ai tratti Tutor in autostrada.\n\n" +
                "1. Premi AVVIA\n" +
                "2. Apri Google Maps per navigare\n" +
                "3. Riceverai avvisi vocali e un overlay con velocità e media\n\n" +
                "L'app funziona in background mentre usi Maps."
            )
            .setPositiveButton("Ho capito") { _, _ ->
                prefs.edit().putBoolean("onboarding_done", true).apply()
            }
            .setCancelable(false)
            .show()
    }

    private fun checkPlayServices() {
        val gapi = GoogleApiAvailability.getInstance()
        val result = gapi.isGooglePlayServicesAvailable(this)
        if (result != ConnectionResult.SUCCESS) {
            if (gapi.isUserResolvableError(result)) {
                gapi.getErrorDialog(this, result, 9000)?.show()
            } else {
                Snackbar.make(findViewById(android.R.id.content),
                    "Google Play Services non disponibile", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        val prefs = getSharedPreferences("tutor_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return

        AlertDialog.Builder(this)
            .setTitle("Ottimizzazione batteria")
            .setMessage(
                "Per funzionare in background mentre usi Google Maps, " +
                "Tutor Map deve essere esclusa dall'ottimizzazione batteria.\n\n" +
                "Senza questa impostazione, Android potrebbe chiudere il monitoraggio dopo pochi minuti."
            )
            .setPositiveButton("Disattiva ottimizzazione") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton("Dopo") { _, _ ->
                prefs.edit().putBoolean("battery_asked", true).apply()
            }
            .show()
    }

    private fun startMonitoring() {
        val intent = Intent(this, LocationService::class.java)
        ContextCompat.startForegroundService(this, intent)
        if (webReady) {
            // Avvia GPS web per marker sulla mappa, ma nascondi HUD e alert web
            // (le info vengono mostrate dall'overlay nativo di LocationService)
            webView.evaluateJavascript(
                "if(!compassActive) startCompass();" +
                "document.getElementById('hud').style.display='none';" +
                "document.getElementById('alertBanner').style.display='none';", null)
        }
        monitoring = true
        updateFabState()
        sendBroadcast(Intent("io.github.lorenzoma97.tutormap.WIDGET_UPDATE").setPackage(packageName))
    }

    private fun stopMonitoring() {
        val intent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_STOP
        }
        startService(intent)
        if (webReady) {
            webView.evaluateJavascript("if(compassActive) stopCompass();", null)
        }
        monitoring = false
        updateFabState()
        sendBroadcast(Intent("io.github.lorenzoma97.tutormap.WIDGET_UPDATE").setPackage(packageName))
        // Trip summary dopo che il service salva i dati
        fab.postDelayed({ showTripSummary() }, 1000)
    }

    private fun showTripSummary() {
        val trip = TripHistoryManager.getLastTrip(this) ?: return
        if (trip.durationMinutes < 1) return
        val duration = if (trip.durationMinutes >= 60)
            "${trip.durationMinutes / 60}h ${trip.durationMinutes % 60}min"
        else "${trip.durationMinutes} min"
        AlertDialog.Builder(this)
            .setTitle("Riepilogo viaggio")
            .setMessage("Durata: $duration\nDistanza: ${String.format("%.1f", trip.distanceKm)} km\nTutor attraversati: ${trip.tutorCount}\nVelocità media: ${trip.avgSpeed} km/h\nVelocità massima: ${trip.maxSpeed} km/h")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun animateFab(sheetOpen: Boolean) {
        if (sheetOpen) {
            // Move FAB to top area (below status bar + some margin)
            val targetY = dpToPx(48f) // 48dp from top
            // Use fab.top (layout position, unaffected by translationY) to avoid
            // stacking translations when called mid-animation or multiple times.
            val layoutY = fab.top.toFloat()
            if (layoutY > targetY) {
                fab.animate()
                    .translationY(-(layoutY - targetY))
                    .setDuration(300)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        } else {
            // Return to original layout position
            fab.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    inner class SheetBridge {
        @android.webkit.JavascriptInterface
        fun onSheetToggle(isOpen: Boolean) {
            runOnUiThread {
                animateFab(isOpen)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
