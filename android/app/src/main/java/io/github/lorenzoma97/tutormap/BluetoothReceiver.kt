package io.github.lorenzoma97.tutormap

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

class BluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return

        val prefs = context.getSharedPreferences("tutor_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("bt_autostart", true)) return

        // Controlla se abbiamo i permessi GPS
        val hasLocation = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocation) {
            Log.w("TutorBT", "Permessi GPS mancanti, skip auto-start")
            return
        }

        Log.i("TutorBT", "Bluetooth connesso, avvio monitoraggio Tutor")
        val serviceIntent = Intent(context, LocationService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
