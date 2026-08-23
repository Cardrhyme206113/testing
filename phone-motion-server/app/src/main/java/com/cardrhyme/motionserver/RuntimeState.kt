package com.cardrhyme.motionserver

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID

object RuntimeState {
    const val PORT = 8765
    const val RECOMMENDED_POLL_MS = 200
    const val DEAD_AFTER_MS = 5_000

    val bootId: String = UUID.randomUUID().toString()

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

    fun stateJson(context: Context): String {
        val nowElapsed = SystemClock.elapsedRealtime()
        val age = if (lastMovementElapsedMs == 0L) -1L else (nowElapsed - lastMovementElapsedMs).coerceAtLeast(0L)
        val uptime = if (startedElapsedMs == 0L) 0L else (nowElapsed - startedElapsedMs).coerceAtLeast(0L)
        val ip = NetworkUtil.lanIpv4(context) ?: ""
        val temp = if (batteryTempC.isNaN()) "null" else String.format(Locale.US, "%.1f", batteryTempC)
        val strength = String.format(Locale.US, "%.3f", movementStrength)
        val error = lastError?.let { "\"${jsonEscape(it)}\"" } ?: "null"

        return """{
  "api_version": 1,
  "alive": true,
  "server_time_ms": ${System.currentTimeMillis()},
  "service_uptime_ms": $uptime,
  "boot_id": "$bootId",
  "lan_ip": "$ip",
  "port": $PORT,
  "recommended_poll_ms": $RECOMMENDED_POLL_MS,
  "dead_after_ms": $DEAD_AFTER_MS,
  "battery": {
    "percent": $batteryPercent,
    "charging": $charging,
    "temperature_c": $temp
  },
  "movement": {
    "id": $movementId,
    "active": $motionActive,
    "detected_at_ms": $lastMovementWallMs,
    "age_ms": $age,
    "strength": $strength
  },
  "error": $error
}"""
    }

    private fun jsonEscape(value: String): String = buildString(value.length + 8) {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
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
