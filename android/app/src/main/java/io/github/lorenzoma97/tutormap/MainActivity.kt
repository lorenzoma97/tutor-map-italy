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
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.AdapterView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    companion object {
        const val PERMISSION_REQUEST_CODE = 100
        const val OVERLAY_REQUEST_CODE = 101
        const val MAP_URL = "https://lorenzoma97.github.io/tutor-map-italy/"
    }

    private lateinit var webView: WebView
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var drawerLayout: DrawerLayout
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
        drawerLayout = findViewById(R.id.drawerLayout)

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

        // Hamburger menu button
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // Setup drawer contents
        setupDrawer()

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
        updateDrawerStats()
    }

    // ============ Drawer Setup ============

    private fun setupDrawer() {
        val prefs = getSharedPreferences("tutor_prefs", MODE_PRIVATE)

        // --- Filters: Direction chips ---
        val chipGroup = findViewById<ChipGroup>(R.id.drawerDirectionChips)
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val chip = findViewById<Chip>(checkedIds[0])
            val dir = when (chip.id) {
                R.id.chipNord -> "Nord"
                R.id.chipSud -> "Sud"
                R.id.chipEst -> "Est"
                R.id.chipOvest -> "Ovest"
                R.id.chipAuto -> "auto"
                else -> "all"
            }
            if (webReady) {
                webView.evaluateJavascript("setDirectionFilterFromNative('$dir');", null)
            }
        }

        // --- Filters: Tutor 3.0 switch ---
        val tutor30Switch = findViewById<SwitchMaterial>(R.id.drawerTutor30Switch)
        tutor30Switch.setOnCheckedChangeListener { _, isChecked ->
            if (webReady) {
                webView.evaluateJavascript(
                    "document.getElementById('filterTutor30').checked=$isChecked; applyFilters();", null)
            }
        }

        // --- Filters: Highway spinner ---
        setupHighwaySpinner()

        // --- Settings: Dark mode ---
        val darkModeSpinner = findViewById<Spinner>(R.id.drawerDarkModeSpinner)
        val darkModeLabel = findViewById<TextView>(R.id.drawerDarkModeLabel)
        val darkEntries = resources.getStringArray(R.array.dark_mode_entries)
        val darkValues = resources.getStringArray(R.array.dark_mode_values)
        darkModeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, darkEntries)
        val currentDark = prefs.getString("dark_mode", "system") ?: "system"
        val currentDarkIdx = darkValues.indexOf(currentDark).coerceAtLeast(0)
        darkModeSpinner.setSelection(currentDarkIdx)
        darkModeLabel.text = darkEntries[currentDarkIdx]
        darkModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var initialized = false
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!initialized) { initialized = true; return }
                val value = darkValues[pos]
                prefs.edit().putString("dark_mode", value).apply()
                darkModeLabel.text = darkEntries[pos]
                AppCompatDelegate.setDefaultNightMode(when (value) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                })
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // --- Settings: Alert distance ---
        val alertSpinner = findViewById<Spinner>(R.id.drawerAlertDistanceSpinner)
        val alertEntries = resources.getStringArray(R.array.alert_distance_entries)
        val alertValues = resources.getStringArray(R.array.alert_distance_values)
        alertSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, alertEntries)
        val currentAlert = prefs.getString("alert_distance", "2") ?: "2"
        alertSpinner.setSelection(alertValues.indexOf(currentAlert).coerceAtLeast(0))
        alertSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var initialized = false
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!initialized) { initialized = true; return }
                prefs.edit().putString("alert_distance", alertValues[pos]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // --- Settings: Speed threshold ---
        val speedSpinner = findViewById<Spinner>(R.id.drawerSpeedThresholdSpinner)
        val speedEntries = resources.getStringArray(R.array.speed_threshold_entries)
        val speedValues = resources.getStringArray(R.array.speed_threshold_values)
        speedSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, speedEntries)
        val currentSpeed = prefs.getString("speed_threshold", "0") ?: "0"
        speedSpinner.setSelection(speedValues.indexOf(currentSpeed).coerceAtLeast(0))
        speedSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var initialized = false
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!initialized) { initialized = true; return }
                prefs.edit().putString("speed_threshold", speedValues[pos]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // --- Settings: Toggles ---
        val soundSwitch = findViewById<SwitchMaterial>(R.id.drawerSoundSwitch)
        soundSwitch.isChecked = prefs.getBoolean("sound", true)
        soundSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("sound", isChecked).apply()
        }

        val overlaySwitch = findViewById<SwitchMaterial>(R.id.drawerOverlaySwitch)
        overlaySwitch.isChecked = prefs.getBoolean("overlay", true)
        overlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("overlay", isChecked).apply()
        }

        val mapsPinSwitch = findViewById<SwitchMaterial>(R.id.drawerMapsPinSwitch)
        mapsPinSwitch.isChecked = prefs.getBoolean("maps_pin", true)
        mapsPinSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("maps_pin", isChecked).apply()
        }

        val btSwitch = findViewById<SwitchMaterial>(R.id.drawerBtSwitch)
        btSwitch.isChecked = prefs.getBoolean("bt_autostart", true)
        btSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("bt_autostart", isChecked).apply()
        }

        // --- Trip history ---
        findViewById<View>(R.id.drawerTripHistory).setOnClickListener {
            drawerLayout.closeDrawers()
            showTripHistory()
        }

        // --- Info ---
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            findViewById<TextView>(R.id.drawerVersion).text = "Tutor Map v${pInfo.versionName}"
        } catch (_: Exception) {
            findViewById<TextView>(R.id.drawerVersion).text = "Tutor Map"
        }

        updateDrawerStats()
    }

    private fun setupHighwaySpinner() {
        val spinner = findViewById<Spinner>(R.id.drawerHighwaySpinner)
        // We populate the highway list after the WebView fetches data
        // For now, set a placeholder
        val defaultList = listOf("Tutte le autostrade")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, defaultList)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var initialized = false
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!initialized) { initialized = true; return }
                val selected = parent?.getItemAtPosition(pos)?.toString() ?: return
                val value = if (selected == "Tutte le autostrade") "all"
                            else selected.substringBefore(" (").trim()
                if (webReady) {
                    webView.evaluateJavascript(
                        "document.getElementById('filterHighway').value='$value'; applyFilters();", null)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateDrawerStats() {
        val (trips, km, tutors) = TripHistoryManager.getWeeklyStats(this)
        val tripCount = TripHistoryManager.getAllTrips(this).size
        findViewById<TextView>(R.id.drawerTripCount)?.text = "$tripCount viaggi"
        findViewById<TextView>(R.id.drawerWeeklyStats)?.text =
            if (trips > 0) "Settimana: $trips viaggi · ${String.format("%.0f", km)} km · $tutors tutor"
            else "Nessun viaggio questa settimana"

        // Data age info
        val cacheTime = getSharedPreferences("tutor_cache", MODE_PRIVATE).getLong("cache_time", 0)
        val dataInfo = if (cacheTime > 0) {
            val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.ITALIAN)
            "Dati aggiornati: ${dateFormat.format(java.util.Date(cacheTime))}"
        } else "Dati: in attesa di aggiornamento"
        findViewById<TextView>(R.id.drawerDataInfo)?.text = dataInfo
    }

    private fun showTripHistory() {
        val trips = TripHistoryManager.getAllTrips(this)
        if (trips.isEmpty()) {
            Snackbar.make(findViewById(android.R.id.content),
                "Nessun viaggio registrato", Snackbar.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()
        val dateFormat = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ITALIAN)
        for (trip in trips.take(10)) {
            val date = dateFormat.format(java.util.Date(trip.startTime))
            sb.appendLine("$date — ${trip.durationMinutes}min · ${String.format("%.1f", trip.distanceKm)}km · ${trip.tutorCount} tutor · media ${trip.avgSpeed} km/h")
        }

        AlertDialog.Builder(this)
            .setTitle("Ultimi viaggi")
            .setMessage(sb.toString().trimEnd())
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancella storico") { _, _ ->
                TripHistoryManager.clearHistory(this)
                updateDrawerStats()
                Snackbar.make(findViewById(android.R.id.content),
                    "Storico cancellato", Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    // ============ FAB State ============

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

    // ============ WebView Setup ============

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
                // Populate highway spinner from web data
                populateHighwaySpinnerFromWeb()
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

    private fun populateHighwaySpinnerFromWeb() {
        // Extract highway list from the WebView's loaded data
        webView.evaluateJavascript(
            "(function(){ var opts = document.getElementById('filterHighway').options; var r = []; for(var i=0;i<opts.length;i++) r.push(opts[i].text); return JSON.stringify(r); })()"
        ) { result ->
            try {
                val json = result.trim('"').replace("\\\"", "\"")
                val arr = org.json.JSONArray(json)
                val items = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    items.add(arr.getString(i))
                }
                if (items.isNotEmpty()) {
                    val spinner = findViewById<Spinner>(R.id.drawerHighwaySpinner)
                    spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
                }
            } catch (e: Exception) {
                Log.w("TutorMap", "Failed to populate highway spinner", e)
            }
        }
    }

    // ============ Permissions & Monitoring ============

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
        updateDrawerStats()
    }

    // ============ FAB animation for web sheet ============

    private fun animateFab(sheetOpen: Boolean) {
        if (sheetOpen) {
            val targetY = dpToPx(48f)
            val layoutY = fab.top.toFloat()
            if (layoutY > targetY) {
                fab.animate()
                    .translationY(-(layoutY - targetY))
                    .setDuration(300)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        } else {
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
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
