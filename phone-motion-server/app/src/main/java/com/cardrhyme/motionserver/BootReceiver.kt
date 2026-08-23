package com.cardrhyme.motionserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences("motion_server", Context.MODE_PRIVATE)
            .getBoolean("enabled", true)
        if (enabled) {
            try {
                context.startForegroundService(Intent(context, MotionService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
