package com.jarvis2.app.integrations

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

/**
 * Wi-Fi state reading works normally. Since Android 10 (API 29), apps can
 * no longer flip Wi-Fi on/off programmatically (`WifiManager.setWifiEnabled`
 * is a documented no-op for third-party apps from that version on) — Google
 * removed it to stop apps silently draining battery/toggling radios behind
 * the user's back. The only OS-sanctioned way to ask for a change is to
 * open the Settings panel (Android 10+) or the Wi-Fi settings screen
 * (older versions), which is what this class does; the user makes the
 * final tap. This is documented so nobody mistakes the panel-launch for a
 * bug — it's the platform's own restriction, not a shortcut we chose.
 */
class WifiController(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun isEnabled(): Boolean = wifiManager.isWifiEnabled

    fun openWifiSettingsPanel() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
