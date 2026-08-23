package com.cardrhyme.motionserver

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

object RuntimeState {
    const val PORT = 8765

    @Volatile var running = false
    @Volatile var startedElapsedMs = 0L
    @Volatile var movementId = 0L
    @Volatile var lastMovementWallMs = 0L
    @Volatile var lastMovementElapsedMs = 0L
    @Volatile var movementStrength = 0f
    @Volatile var batteryPercent = -1
    @Volatile var lastError: String? = null

    @Volatile var accelRegistered = false
    @Volatile var gyroRegistered = false
    @Volatile var accelSensorName = ""
    @Volatile var gyroSensorName = ""
    @Volatile var accelEvents = 0L
    @Volatile var gyroEvents = 0L
    @Volatile var lastSensorEventElapsedMs = 0L
    @Volatile var accelDelta = 0f
    @Volatile var gyroMagnitude = 0f
    @Volatile var accelThreshold = 0f
    @Volatile var gyroThreshold = 0f
    @Volatile var motionEvidence = 0f

    fun resetForServiceStart() {
        running = true
        startedElapsedMs = SystemClock.elapsedRealtime()
        lastError = null
        accelRegistered = false
        gyroRegistered = false
        accelEvents = 0L
        gyroEvents = 0L
        lastSensorEventElapsedMs = 0L
        accelDelta = 0f
        gyroMagnitude = 0f
        accelThreshold = 0f
        gyroThreshold = 0f
        motionEvidence = 0f
    }

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

    fun debugJson(): String {
        val now = SystemClock.elapsedRealtime()
        val sensorAge = if (lastSensorEventElapsedMs == 0L) -1L else (now - lastSensorEventElapsedMs).coerceAtLeast(0L)
        val error = lastError?.let { "\"${jsonEscape(it)}\"" } ?: "null"
        return """{
  "accel_registered": $accelRegistered,
  "gyro_registered": $gyroRegistered,
  "accel_sensor": "${jsonEscape(accelSensorName)}",
  "gyro_sensor": "${jsonEscape(gyroSensorName)}",
  "accel_events": $accelEvents,
  "gyro_events": $gyroEvents,
  "last_sensor_event_age_ms": $sensorAge,
  "accel_delta": ${f(accelDelta)},
  "gyro": ${f(gyroMagnitude)},
  "accel_threshold": ${f(accelThreshold)},
  "gyro_threshold": ${f(gyroThreshold)},
  "evidence": ${f(motionEvidence)},
  "movement_id": $movementId,
  "error": $error
}"""
    }

    private fun f(value: Float): String = String.format(Locale.US, "%.6f", value)

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
        } catch (_: Exception) {}

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
