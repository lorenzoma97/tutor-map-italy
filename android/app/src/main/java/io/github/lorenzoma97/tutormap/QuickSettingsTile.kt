package io.github.lorenzoma97.tutormap

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * Tile per il pannello Impostazioni Rapide (Quick Settings) di Android.
 * Permette di avviare e fermare il monitoraggio Tutor direttamente
 * dal pannello delle notifiche senza aprire l'app.
 *
 * Mostra lo stato corrente: "Monitoraggio attivo" / "Tocca per avviare"
 */
class QuickSettingsTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // Aggiorna lo stato del tile quando diventa visibile
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        if (LocationService.isRunning) {
            // Ferma il monitoraggio inviando l'azione di stop al servizio
            val intent = Intent(this, LocationService::class.java).apply {
                action = LocationService.ACTION_STOP
            }
            startService(intent)
        } else {
            // Avvia il monitoraggio come servizio in foreground
            val intent = Intent(this, LocationService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }

        // Aggiorna il tile dopo un breve ritardo per dare tempo
        // al servizio di cambiare stato
        android.os.Handler(mainLooper).postDelayed({ updateTile() }, 500)
    }

    /**
     * Aggiorna l'aspetto del tile in base allo stato del servizio.
     * Stato attivo: evidenziato con sottotitolo "Monitoraggio attivo"
     * Stato inattivo: spento con sottotitolo "Tocca per avviare"
     */
    private fun updateTile() {
        val tile = qsTile ?: return
        val running = LocationService.isRunning

        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Tutor Map"

        // Il sottotitolo esteso e' disponibile da Android Q (API 29) in poi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) "Monitoraggio attivo" else "Tocca per avviare"
        }

        tile.updateTile()
    }
}
