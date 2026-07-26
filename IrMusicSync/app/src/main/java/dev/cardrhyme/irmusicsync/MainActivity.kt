package dev.cardrhyme.irmusicsync

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.ConsumerIrManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.*

class MainActivity : Activity() {
    private lateinit var ir: ConsumerIrManager
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var status: TextView
    private lateinit var swatch: TextView
    private lateinit var sensitivity: SeekBar
    private lateinit var interval: SeekBar
    private lateinit var captureButton: Button

    private data class LedColor(val name: String, val rgb: Int, val code: Long)
    private data class IrProfile(val carrier: Int, val mode: Int, val repeats: Int) {
        val label: String get() = "${carrier / 1000} kHz · mode ${mode + 1} · ${repeats}x"
    }

    private val colors = listOf(
        LedColor("Red", 0xFF0000, 0x00F720DFL),
        LedColor("Orange", 0xFF4000, 0x00F710EFL),
        LedColor("Light orange", 0xFF7000, 0x00F730CFL),
        LedColor("Amber", 0xFFAA00, 0x00F708F7L),
        LedColor("Yellow", 0xFFFF00, 0x00F728D7L),
        LedColor("Green", 0x00FF00, 0x00F7A05FL),
        LedColor("Lime", 0x70FF40, 0x00F7906FL),
        LedColor("Cyan", 0x00FFFF, 0x00F7B04FL),
        LedColor("Sky", 0x35BFFF, 0x00F78877L),
        LedColor("Blue green", 0x0080A0, 0x00F7A857L),
        LedColor("Blue", 0x0000FF, 0x00F7609FL),
        LedColor("Deep blue", 0x002080, 0x00F750AFL),
        LedColor("Light blue", 0x4080FF, 0x00F7708FL),
        LedColor("Purple", 0x7030A0, 0x00F748B7L),
        LedColor("Violet", 0xA040FF, 0x00F76897L),
        LedColor("White", 0xFFFFFF, 0x00F7E01FL)
    )

    private val scannerProfiles = buildList {
        for (carrier in listOf(36_000, 38_000, 40_000)) {
            for (mode in 0..3) {
                for (repeats in 1..3) add(IrProfile(carrier, mode, repeats))
            }
        }
    }
    private var scanIndex = 0
    private var activeProfile = IrProfile(38_000, 0, 1)
    private var captureRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as ConsumerIrManager
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        loadProfile()
        buildUi()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
        }
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
            setBackgroundColor(0xFF101014.toInt())
        }
        fun label(value: String, size: Float = 16f) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 10)
        }

        content.addView(label("IR Music Sync", 26f))
        status = label(if (ir.hasIrEmitter()) "IR detected · ${activeProfile.label}" else "No Android IR emitter detected")
        content.addView(status)

        content.addView(Button(this).apply {
            text = "TEST NEXT IR PROFILE"
            setOnClickListener { testNextProfile() }
        })
        content.addView(Button(this).apply {
            text = "THAT PROFILE WORKED — SAVE IT"
            setOnClickListener { saveCurrentProfile() }
        })
        content.addView(label("Aim directly at the receiver. Press TEST until the strip reacts, then SAVE.", 13f))

        swatch = label("Current color", 20f).apply {
            setBackgroundColor(0xFF222228.toInt())
            setPadding(24, 40, 24, 40)
        }
        content.addView(swatch)

        content.addView(label("Sensitivity"))
        sensitivity = SeekBar(this).apply { max = 100; progress = 55 }
        content.addView(sensitivity)

        content.addView(label("Minimum color interval (80–500 ms)"))
        interval = SeekBar(this).apply { max = 420; progress = 120 }
        content.addView(interval)

        captureButton = Button(this).apply {
            text = "START DEVICE AUDIO CAPTURE"
            setOnClickListener {
                if (captureRunning) stopCaptureService() else requestPlaybackCapture()
            }
        }
        content.addView(captureButton)

        content.addView(label("Manual color test"))
        val grid = GridLayout(this).apply { columnCount = 4 }
        colors.forEachIndexed { index, color ->
            grid.addView(Button(this).apply {
                text = (index + 1).toString()
                setTextColor(if (Color.luminance(color.rgb) < 0.4) Color.WHITE else Color.BLACK)
                setBackgroundColor(color.rgb or 0xFF000000.toInt())
                setOnClickListener {
                    transmit(color.code, activeProfile)
                    showColor(color)
                }
            }, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
        content.addView(grid)
        content.addView(label("START opens Android's screen-share prompt. The foreground service captures playback audio only; apps may opt out.", 13f))

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun requestPlaybackCapture() {
        if (!ir.hasIrEmitter()) {
            status.text = "No IR emitter"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            return
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), 1001)
    }

    @Deprecated("Deprecated API retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1001) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "Screen/audio capture permission denied"
            return
        }

        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            putExtra(CaptureService.EXTRA_SENSITIVITY, sensitivity.progress)
            putExtra(CaptureService.EXTRA_INTERVAL, interval.progress)
        }
        startForegroundService(serviceIntent)
        captureRunning = true
        captureButton.text = "STOP CAPTURE"
        status.text = "Capture service started · ${activeProfile.label}"
    }

    private fun stopCaptureService() {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        captureRunning = false
        captureButton.text = "START DEVICE AUDIO CAPTURE"
        status.text = "Capture stopped"
    }

    private fun testNextProfile() {
        if (!ir.hasIrEmitter()) return
        activeProfile = scannerProfiles[scanIndex % scannerProfiles.size]
        scanIndex++
        transmit(0x00F7C03FL, activeProfile)
        status.text = "Testing ${activeProfile.label} ($scanIndex/${scannerProfiles.size})"
    }

    private fun saveCurrentProfile() {
        getSharedPreferences("ir_profile", MODE_PRIVATE).edit()
            .putInt("carrier", activeProfile.carrier)
            .putInt("mode", activeProfile.mode)
            .putInt("repeats", activeProfile.repeats)
            .apply()
        status.text = "Saved ${activeProfile.label}"
    }

    private fun loadProfile() {
        val prefs = getSharedPreferences("ir_profile", MODE_PRIVATE)
        activeProfile = IrProfile(
            prefs.getInt("carrier", 38_000),
            prefs.getInt("mode", 0),
            prefs.getInt("repeats", 1)
        )
    }

    private fun showColor(color: LedColor) {
        swatch.text = color.name
        swatch.setBackgroundColor(color.rgb or 0xFF000000.toInt())
        swatch.setTextColor(if (Color.luminance(color.rgb) < 0.45) Color.WHITE else Color.BLACK)
    }

    private fun transmit(code: Long, profile: IrProfile) {
        try {
            repeat(profile.repeats) {
                ir.transmit(profile.carrier, IrProtocol.necPattern(code, profile.mode))
                if (profile.repeats > 1) Thread.sleep(35)
            }
        } catch (t: Throwable) {
            status.text = "IR error: ${t.message ?: t.javaClass.simpleName}"
        }
    }
}
