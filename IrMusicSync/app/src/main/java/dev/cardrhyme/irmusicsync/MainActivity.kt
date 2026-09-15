package dev.cardrhyme.irmusicsync

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.ConsumerIrManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var ir: ConsumerIrManager
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var status: TextView
    private lateinit var swatch: TextView
    private lateinit var sensitivity: SeekBar
    private lateinit var autoTimingLabel: TextView
    private lateinit var fadeOutSwitch: Switch
    private lateinit var fadeInSwitch: Switch
    private lateinit var fadeLevels: SeekBar
    private lateinit var fadeLevelsLabel: TextView
    private lateinit var commandGap: SeekBar
    private lateinit var commandGapLabel: TextView
    private lateinit var colorDelay: SeekBar
    private lateinit var colorDelayLabel: TextView
    private lateinit var captureButton: Button
    private lateinit var beatFlash: TextView
    private lateinit var sentFlash: TextView
    private var receiverRegistered = false

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

    private val beatEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CaptureService.ACTION_BEAT_DETECTED -> {
                    val rgb = intent.getIntExtra(CaptureService.EXTRA_COLOR_RGB, Color.WHITE)
                    flashIndicator(beatFlash, rgb)
                }
                CaptureService.ACTION_BEAT_SENT -> {
                    val rgb = intent.getIntExtra(CaptureService.EXTRA_COLOR_RGB, Color.WHITE)
                    flashIndicator(sentFlash, rgb)
                }
                CaptureService.ACTION_TIMING_UPDATED -> {
                    val cooldown = intent.getLongExtra(CaptureService.EXTRA_AUTO_COOLDOWN_MS, 0L)
                    if (cooldown > 0L) {
                        autoTimingLabel.text = "Automatic beat cooldown: ${cooldown} ms (last sequence + 5 ms)"
                    }
                }
            }
        }
    }

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

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(CaptureService.ACTION_BEAT_DETECTED)
                addAction(CaptureService.ACTION_BEAT_SENT)
                addAction(CaptureService.ACTION_TIMING_UPDATED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(beatEvents, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(beatEvents, filter)
            }
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            try { unregisterReceiver(beatEvents) } catch (_: Throwable) {}
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF101014.toInt()) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(120))
            setBackgroundColor(0xFF101014.toInt())
        }
        fun label(value: String, size: Float = 16f) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, dp(5), 0, dp(5))
        }

        content.addView(label("IR Music Sync", 26f))
        status = label(if (ir.hasIrEmitter()) "IR detected · ${activeProfile.label}" else "No Android IR emitter detected")
        content.addView(status)

        swatch = label("Warm-spectrum beat mode", 20f).apply {
            setBackgroundColor(0xFF222228.toInt())
            setPadding(dp(12), dp(20), dp(12), dp(20))
        }
        content.addView(swatch)

        content.addView(label("Beat-change sensitivity"))
        sensitivity = SeekBar(this).apply { max = 100; progress = 55 }
        content.addView(sensitivity)

        autoTimingLabel = label("Automatic beat cooldown: awaiting first sequence")
        content.addView(autoTimingLabel)

        fadeOutSwitch = Switch(this).apply {
            text = "Fade out before OFF"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        content.addView(fadeOutSwitch)

        fadeInSwitch = Switch(this).apply {
            text = "Fade in after ON"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        content.addView(fadeInSwitch)

        fadeLevelsLabel = label("Fade brightness steps each enabled direction: 2")
        content.addView(fadeLevelsLabel)
        fadeLevels = SeekBar(this).apply {
            max = 6
            progress = 2
            setOnSeekBarChangeListener(simpleListener {
                fadeLevelsLabel.text = "Fade brightness steps each enabled direction: $it"
            })
        }
        content.addView(fadeLevels)

        commandGapLabel = label("Gap after each IR command: 12 ms")
        content.addView(commandGapLabel)
        commandGap = SeekBar(this).apply {
            max = 45
            progress = 7
            setOnSeekBarChangeListener(simpleListener {
                commandGapLabel.text = "Gap after each IR command: ${5 + it} ms"
            })
        }
        content.addView(commandGap)

        colorDelayLabel = label("Dim new-color hold before OFF: 75 ms")
        content.addView(colorDelayLabel)
        colorDelay = SeekBar(this).apply {
            max = 275
            progress = 50
            setOnSeekBarChangeListener(simpleListener {
                colorDelayLabel.text = "Dim new-color hold before OFF: ${25 + it} ms"
            })
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
        content.addView(label("Beat detected: left tile flashes. Enabled fade-out steps run, the new color is selected while the strip is still ON, then it turns OFF. It turns back ON immediately; enabled fade-in steps run and the right tile flashes at completion. The next beat unlocks 5 ms later, and the displayed cooldown is measured from the completed sequence.", 13f))

        root.addView(ScrollView(this).apply { addView(content) }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        fun flashTile(textValue: String) = TextView(this).apply {
            text = textValue
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF24242A.toInt())
            alpha = 0.12f
        }

        beatFlash = flashTile("BEAT")
        sentFlash = flashTile("SENT")
        root.addView(beatFlash, FrameLayout.LayoutParams(dp(100), dp(64), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(16)
            bottomMargin = dp(16)
        })
        root.addView(sentFlash, FrameLayout.LayoutParams(dp(100), dp(64), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(16)
            bottomMargin = dp(16)
        })

        setContentView(root)
    }

    private fun flashIndicator(view: TextView, rgb: Int) {
        view.animate().cancel()
        val opaque = rgb or 0xFF000000.toInt()
        view.setBackgroundColor(opaque)
        view.setTextColor(if (Color.luminance(rgb) < 0.45) Color.WHITE else Color.BLACK)
        view.alpha = 1f
        view.animate().alpha(0.12f).setStartDelay(70).setDuration(190).start()
    }

    private fun simpleListener(onChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChanged(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
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
            putExtra(CaptureService.EXTRA_FADE_LEVELS, fadeLevels.progress)
            putExtra(CaptureService.EXTRA_COMMAND_GAP, 5 + commandGap.progress)
            putExtra(CaptureService.EXTRA_COLOR_DELAY, 25 + colorDelay.progress)
            putExtra(CaptureService.EXTRA_FADE_OUT_ENABLED, fadeOutSwitch.isChecked)
            putExtra(CaptureService.EXTRA_FADE_IN_ENABLED, fadeInSwitch.isChecked)
        }
        startForegroundService(serviceIntent)
        captureRunning = true
        captureButton.text = "STOP CAPTURE"
        autoTimingLabel.text = "Automatic beat cooldown: measuring first sequence…"
        status.text = "Capture started · fade out ${if (fadeOutSwitch.isChecked) "ON" else "OFF"} · fade in ${if (fadeInSwitch.isChecked) "ON" else "OFF"}"
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
