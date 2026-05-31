package com.bdavidgm.sharedmusic.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Gestiona un hotspot local *best-effort* mediante [WifiManager.startLocalOnlyHotspot].
 *
 * Limitaciones reales de Android que el usuario debe conocer:
 * - El SSID y la clave los genera el sistema (no se pueden fijar).
 * - La concurrencia estación+AP (necesaria para el repetidor) depende del
 *   dispositivo. Si no está soportada, conviene compartir red por el hotspot del
 *   sistema y usar el relay a nivel de aplicación.
 */
class HotspotManager(private val context: Context) {

    data class HotspotInfo(val ssid: String?, val passphrase: String?)

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingPermission")
    fun start(
        onReady: (HotspotInfo) -> Unit,
        onFailed: (Int) -> Unit
    ) {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.startLocalOnlyHotspot(
            object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    onReady(extractInfo(res))
                }

                override fun onFailed(reason: Int) {
                    onFailed(reason)
                }
            },
            null
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Suppress("DEPRECATION")
    private fun extractInfo(res: WifiManager.LocalOnlyHotspotReservation): HotspotInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = res.softApConfiguration
            HotspotInfo(ssid = config.ssid, passphrase = config.passphrase)
        } else {
            val config = res.wifiConfiguration
            HotspotInfo(ssid = config?.SSID, passphrase = config?.preSharedKey)
        }
    }

    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
    }
}
