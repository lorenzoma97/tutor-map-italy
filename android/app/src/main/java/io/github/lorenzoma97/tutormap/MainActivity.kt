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
import android.view.View
import android.webkit.*
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class MainActivity : AppCompatActivity() {

    companion object {
        const val PERMISSION_REQUEST_CODE = 100
        const val OVERLAY_REQUEST_CODE = 101
        const val MAP_URL = "https://lorenzoma97.github.io/tutor-map-italy/"
    }

    private lateinit var webView: WebView
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var statusText: TextView
    private var monitoring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        fab = findViewById(R.id.fabMonitor)
        statusText = findViewById(R.id.statusText)

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

        findViewById<android.widget.ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Prefetch dati segmenti Tutor
        prefetchSegments()

        // Controlla Google Play Services
        checkPlayServices()

        // Controlla ottimizzazione batteria al primo avvio
        checkBatteryOptimization()
    }

    private fun updateFabState() {
        if (monitoring) {
            fab.text = "STOP"
            fab.setIconResource(android.R.drawable.ic_media_pause)
            fab.backgroundTintList = ColorStateList.valueOf(0xFFFF6D00.toInt())
            fab.setTextColor(0xFFFFFFFF.toInt())
            fab.iconTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            statusText.text = "GPS attivo"
            statusText.visibility = View.VISIBLE
        } else {
            fab.text = "AVVIA"
            fab.setIconResource(android.R.drawable.ic_media_play)
            fab.backgroundTintList = ColorStateList.valueOf(0xFF34a853.toInt())
            fab.setTextColor(0xFFFFFFFF.toInt())
            fab.iconTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            statusText.text = ""
            statusText.visibility = View.GONE
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
        }

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
                Toast.makeText(this, "Permesso GPS necessario per il monitoraggio", Toast.LENGTH_LONG).show()
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
                Log.w("TutorMap", "Prefetch segmenti fallito (verrà riprovato al monitoraggio)", e)
            }
        }.start()
    }

    private fun checkPlayServices() {
        val gapi = GoogleApiAvailability.getInstance()
        val result = gapi.isGooglePlayServicesAvailable(this)
        if (result != ConnectionResult.SUCCESS) {
            if (gapi.isUserResolvableError(result)) {
                gapi.getErrorDialog(this, result, 9000)?.show()
            } else {
                Toast.makeText(
                    this,
                    "Questo dispositivo non supporta Google Play Services.\nIl monitoraggio GPS potrebbe non funzionare.",
                    Toast.LENGTH_LONG
                ).show()
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
        monitoring = true
        updateFabState()
    }

    private fun stopMonitoring() {
        val intent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_STOP
        }
        startService(intent)
        monitoring = false
        updateFabState()
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
