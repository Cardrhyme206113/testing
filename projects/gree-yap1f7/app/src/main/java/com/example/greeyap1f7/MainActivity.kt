package com.example.greeyap1f7

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.view.View
import android.view.HapticFeedbackConstants
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.Executors

/** Full-screen replica of the Gree YAP1F7 remote, controlled by local HTML/SVG. */
class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val worker = Executors.newSingleThreadExecutor()
    private val preferences by lazy { getSharedPreferences("gree_ir", MODE_PRIVATE) }
    private val ir by lazy { getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager }

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(42, 46, 50))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            addJavascriptInterface(RemoteBridge(), "Android")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)
    }

    inner class RemoteBridge {
        @JavascriptInterface fun hasIrEmitter(): Boolean = ir?.hasIrEmitter() == true

        /** Long-press the GREE logo to try another commonly supported Gree encoding. */
        @JavascriptInterface fun cycleModel(): String {
            val next = !preferences.getBoolean("yaw1f", true)
            preferences.edit().putBoolean("yaw1f", next).apply()
            return "IR variant: " + if (next) "YAW1F" else "YBOFB"
        }
        @JavascriptInterface fun send(jsonString: String) {
            if (jsonString.length > 3000) return
            val json = try { JSONObject(jsonString) } catch (_: Exception) { return }
            webView.post { webView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            worker.execute {
                val transmitter = ir
                if (transmitter == null || !transmitter.hasIrEmitter()) {
                    feedback("No IR emitter detected on this device", false)
                    return@execute
                }
                try {
                    val candidate = GreeEncoder.encode(json, preferences.getBoolean("yaw1f", true))
                    transmitter.transmit(candidate.frequency, candidate.pattern)
                    feedback("IR sent (AC confirmation unavailable)", true)
                } catch (ex: Exception) {
                    feedback("IR failed: ${ex.message ?: "unknown error"}", false)
                }
            }
        }
    }

    private fun feedback(message: String, ok: Boolean) {
        runOnUiThread {
            webView.evaluateJavascript(
                "window.irResponse(${JSONObject.quote(message)}, ${if (ok) "true" else "false"});", null)
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        webView.removeJavascriptInterface("Android")
        webView.destroy()
        super.onDestroy()
    }
}
