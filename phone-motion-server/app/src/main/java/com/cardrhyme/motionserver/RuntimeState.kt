package com.cardrhyme.motionserver

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface

object RuntimeState {
    const val PORT = 8765
    const val RECOMMENDED_POLL_MS = 200
    const val DEAD_AFTER_MS = 5_000

    @Volatile var running = false
    @Volatile var startedElapsedMs = 0L
    @Volatile var movementId = 0L
    @Volatile var lastMovementWallMs = 0L
    @Volatile var lastMovementElapsedMs = 0L
    @Volatile var movementStrength = 0f
    @Volatile var motionActive = false
    @Volatile var batteryPercent = -1
    @Volatile var charging = false
    @Volatile var batteryTempC = Float.NaN
    @Volatile var lastError: String? = null

    @Synchronized
    fun recordMovement(strength: Float) {
        movementId += 1
        lastMovementWallMs = System.currentTimeMillis()
        lastMovementElapsedMs = SystemClock.elapsedRealtime()
        movementStrength = strength
    }

    fun stateJson(@Suppress("UNUSED_PARAMETER") context: Context): String {
        return "{\"alive\":true,\"battery\":$batteryPercent,\"movement_id\":$movementId}"
    }
}

object NetworkUtil {
    fun lanIpv4(context: Context): String? {
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork
            val props = network?.let { cm.getLinkProperties(it) }
            props?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
                ?.let { return it }
        } catch (_: Exception) {
        }

        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
