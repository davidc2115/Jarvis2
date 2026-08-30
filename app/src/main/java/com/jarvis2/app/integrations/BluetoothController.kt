package com.jarvis2.app.integrations

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Bluetooth control. Since Android 13, apps can request enable/disable via
 * [BluetoothAdapter.ACTION_REQUEST_ENABLE] (shows a system confirmation
 * dialog — there is no silent toggle anymore, by OS design, same as Wi-Fi).
 * Reading paired devices and connection state does not require that dialog.
 */
class BluetoothController(private val context: Context) {

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isSupported(): Boolean = adapter != null
    fun isEnabled(): Boolean = adapter?.isEnabled == true

    fun requestEnable() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun requestDisable() {
        // No programmatic disable API is exposed to third-party apps anymore
        // (removed for user-trust reasons); route to the system Bluetooth
        // settings panel instead, consistent with the Wi-Fi controller.
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    @SuppressLint("MissingPermission") // caller is responsible for BLUETOOTH_CONNECT at call time
    fun pairedDevices(): List<BluetoothDevice> = adapter?.bondedDevices?.toList().orEmpty()
}
