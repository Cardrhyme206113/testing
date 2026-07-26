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
    private lateinit var intervalLabel: TextView
    private lateinit var fadeLevels: SeekBar
    private lateinit var fadeLevelsLabel: TextView
    private lateinit var commandGap: SeekBar
    private lateinit var commandGapLabel: TextView
    private lateinit var colorDelay: SeekBar
    private lateinit var colorDelayLabel: TextView
    private lateinit var captureButton: Button

    private data class LedColor(val name: String, val rgb: Int, val code: Long)
    private data class IrProfile(val carrier: Int, val mode: Int, val repeats: Int) {
        val label: String get() = "${carrier / 1000} kHz · mode ${mode + 1} · ${repeats}x"
    }

    private val colors = listOf(
        LedColor("Red", 0xFF0000, 0xF720DFL),
        LedColor("Orange", 0xFF4000, 0xF710EFL),
        LedColor("Light orange", 0xFF7000, 0xF730CFL),
        LedColor("Amber", 0xFFAA00, 0xF708F7L),
        LedColor("Yellow", 0xFFFF00, 0xF728D7L),
        LedColor("Green", 0x00FF00, 0xF7A05FL),
        LedColor("Purple", 0x7030A0, 0xF748B7L),
        LedColor("Violet", 0xA040FF, 0xF76897L),
        LedColor("White", 0xFFFFFF, 0xF7E01FL)
    )

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

        swatch = label("Warm-spectrum beat mode", 20f).apply {
            setBackgroundColor(0xFF222228.toInt())
            setPadding(24, 40, 24, 40)
        }
        content.addView(swatch)

        content.addView(label("Beat-change sensitivity"))
        sensitivity = SeekBar(this).apply { max = 100; progress = 55 }
        content.addView(sensitivity)

        intervalLabel = label("Minimum beat cooldown: 200 ms")
        content.addView(intervalLabel)
        interval = SeekBar(this).apply {
            max = 800
            progress = 0
            setOnSeekBarChangeListener(simpleListener { intervalLabel.text = "Minimum beat cooldown: ${200 + it} ms" })
        }
        content.addView(interval)

        fadeLevelsLabel = label("Fade brightness steps each way: 2")
        content.addView(fadeLevelsLabel)
        fadeLevels = SeekBar(this).apply {
            max = 6
            progress = 2
            setOnSeekBarChangeListener(simpleListener { fadeLevelsLabel.text = "Fade brightness steps each way: $it" })
        }
        content.addView(fadeLevels)

        commandGapLabel = label("Gap after each IR command: 12 ms")
        content.addView(commandGapLabel)
        commandGap = SeekBar(this).apply {
            max = 45
            progress = 7
            setOnSeekBarChangeListener(simpleListener { commandGapLabel.text = "Gap after each IR command: ${5 + it} ms" })
        }
        content.addView(commandGap)

        colorDelayLabel = label("Delay before showing new color: 75 ms")
        content.addView(colorDelayLabel)
        colorDelay = SeekBar(this).apply {
            max = 275
            progress = 50
            setOnSeekBarChangeListener(simpleListener { colorDelayLabel.text = "Delay before showing new color: ${25 + it} ms" })
        }
        content.addView(colorDelay)

        captureButton = Button(this).apply {
            text = "START DEVICE AUDIO CAPTURE"
            setOnClickListener {
                if (captureRunning) stopCaptureService() else requestPlaybackCapture()
            }
        }
        content.addView(captureButton)

        content.addView(label("Manual warm-color test"))
        val grid = GridLayout(this).apply { columnCount = 3 }
        colors.forEach { color ->
            grid.addView(Button(this).apply {
                text = color.name
                setTextColor(if (Color.luminance(color.rgb) < 0.4) Color.WHITE else Color.BLACK)
                setBackgroundColor(color.rgb or 0xFF000000.toInt())
                setOnClickListener {
                    transmit(color.code)
                    showColor(color)
                }
            }, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
        content.addView(grid)
        content.addView(label("On a real note/beat change: fade down by the selected step count, OFF, wait for cooldown, ON, fade up by the same count, then show the new warm-spectrum color after the selected delay.", 13f))

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun simpleListener(onChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChanged(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun requestPlaybackCapture() {
        if (!ir.hasIrEmitter()) { status.text = "No IR emitter"; return }
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
            putExtra(CaptureService.EXTRA_FADE_LEVELS, fadeLevels.progress)
            putExtra(CaptureService.EXTRA_COMMAND_GAP, 5 + commandGap.progress)
            putExtra(CaptureService.EXTRA_COLOR_DELAY, 25 + colorDelay.progress)
        }
        startForegroundService(serviceIntent)
        captureRunning = true
        captureButton.text = "STOP CAPTURE"
        status.text = "Capture started · ${fadeLevels.progress} steps · ${5 + commandGap.progress} ms gap"
    }

    private fun stopCaptureService() {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        captureRunning = false
        captureButton.text = "START DEVICE AUDIO CAPTURE"
        status.text = "Capture stopped"
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

    private fun transmit(code: Long) {
        try {
            repeat(activeProfile.repeats) {
                ir.transmit(activeProfile.carrier, IrProtocol.necPattern(code, activeProfile.mode))
                if (activeProfile.repeats > 1) Thread.sleep(35)
            }
        } catch (t: Throwable) {
            status.text = "IR error: ${t.message ?: t.javaClass.simpleName}"
        }
    }
}
