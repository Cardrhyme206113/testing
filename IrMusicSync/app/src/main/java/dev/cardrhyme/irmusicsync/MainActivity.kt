package dev.cardrhyme.irmusicsync

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.ConsumerIrManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.*
import kotlin.concurrent.thread
import kotlin.math.*

class MainActivity : Activity() {
    private lateinit var ir: ConsumerIrManager
    private lateinit var projectionManager: MediaProjectionManager
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var running = false
    private var lastColor = -1
    private var lastSend = 0L

    private lateinit var status: TextView
    private lateinit var swatch: TextView
    private lateinit var interval: SeekBar
    private lateinit var sensitivity: SeekBar
    private lateinit var toggle: Button

    private data class LedColor(val name: String, val rgb: Int, val code: Long)
    private data class IrProfile(val carrier: Int, val mode: Int, val repeats: Int) {
        val label: String get() = "${carrier / 1000} kHz · mode ${mode + 1} · ${repeats}x"
    }

    private val colors = listOf(
        LedColor("Red", 0xFF0000, 0x00F720DF),
        LedColor("Orange", 0xFF4000, 0x00F710EF),
        LedColor("Light orange", 0xFF7000, 0x00F730CF),
        LedColor("Amber", 0xFFFF00, 0x00F708F7),
        LedColor("Yellow", 0xFFFF00, 0x00F728D7),
        LedColor("Green", 0x00FF00, 0x00F7A05F),
        LedColor("Lime", 0x70FF40, 0x00F7906F),
        LedColor("Cyan", 0x00FFFF, 0x00F7B04F),
        LedColor("Sky", 0x35BFFF, 0x00F78877),
        LedColor("Blue green", 0x0080A0, 0x00F7A857),
        LedColor("Blue", 0x0000FF, 0x00F7609F),
        LedColor("Deep blue", 0x002080, 0x00F750AF),
        LedColor("Light blue", 0x4080FF, 0x00F7708F),
        LedColor("Purple", 0x7030A0, 0x00F748B7),
        LedColor("Violet", 0xA040FF, 0x00F76897),
        LedColor("White", 0xFFFFFF, 0x00F7E01F)
    )

    private val scannerProfiles = buildList {
        for (carrier in listOf(36_000, 38_000, 40_000))
            for (mode in 0..3)
                for (repeats in 1..3)
                    add(IrProfile(carrier, mode, repeats))
    }
    private var scanIndex = 0
    private var activeProfile = IrProfile(38_000, 0, 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as ConsumerIrManager
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        loadProfile()
        buildUi()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(0xFF101014.toInt())
        }
        fun text(s: String, size: Float = 16f) = TextView(this).apply {
            text = s; textSize = size; setTextColor(Color.WHITE); setPadding(0, 10, 0, 10)
        }

        root.addView(text("IR Music Sync", 26f))
        status = text(if (ir.hasIrEmitter()) "IR detected · ${activeProfile.label}" else "No Android IR emitter detected")
        root.addView(status)

        root.addView(Button(this).apply {
            text = "TEST NEXT IR PROFILE"
            setOnClickListener { testNextProfile() }
        })
        root.addView(Button(this).apply {
            text = "THAT PROFILE WORKED — SAVE IT"
            setOnClickListener { saveCurrentProfile() }
        })
        root.addView(text("Aim at the receiver. Keep pressing TEST until the strip reacts, then press SAVE.", 13f))

        swatch = text("Current color", 20f).apply {
            setBackgroundColor(0xFF222228.toInt()); setPadding(24, 40, 24, 40)
        }
        root.addView(swatch)
        root.addView(text("Sensitivity"))
        sensitivity = SeekBar(this).apply { max = 100; progress = 45 }
        root.addView(sensitivity)
        root.addView(text("Minimum color interval (80–500 ms)"))
        interval = SeekBar(this).apply { max = 420; progress = 100 }
        root.addView(interval)

        toggle = Button(this).apply {
            text = "START AUDIO CAPTURE"
            setOnClickListener { if (running) stopCapture() else requestPlaybackCapture() }
        }
        root.addView(toggle)

        root.addView(text("Manual color test"))
        val grid = GridLayout(this).apply { columnCount = 4 }
        colors.forEachIndexed { i, c ->
            grid.addView(Button(this).apply {
                text = (i + 1).toString()
                setTextColor(if (Color.luminance(c.rgb) < 0.4) Color.WHITE else Color.BLACK)
                setBackgroundColor(c.rgb or 0xFF000000.toInt())
                setOnClickListener { send(c); show(c, 0.0, 0.0) }
            }, GridLayout.LayoutParams().apply {
                width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
        root.addView(grid)
        root.addView(text("START opens Android's screen-share prompt, but only playback audio is analyzed. Apps may opt out of capture.", 13f))
        setContentView(root)
    }

    private fun requestPlaybackCapture() {
        if (!ir.hasIrEmitter()) { status.text = "No IR emitter"; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7); return
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), 1001)
    }

    @Deprecated("Deprecated in Android API but still valid for Activity result handling")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1001) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "Screen/audio capture permission denied"
            return
        }
        try {
            projection = projectionManager.getMediaProjection(resultCode, data)
            startPlaybackCapture()
        } catch (e: Exception) {
            status.text = "Capture setup failed: ${e.message}"
        }
    }

    private fun startPlaybackCapture() {
        val mediaProjection = projection ?: return
        val sampleRate = 44_100
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minBuffer * 2, 8192))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        recorder?.startRecording()
        running = true
        toggle.text = "STOP"
        status.text = "Capturing device playback audio…"
        captureThread = thread(name = "playback-analyzer") {
            val pcm = ShortArray(2048)
            while (running) {
                val count = recorder?.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING) ?: break
                if (count > 256) analyzePcm(pcm, count, sampleRate)
            }
        }
    }

    private fun analyzePcm(samples: ShortArray, count: Int, sampleRate: Int) {
        var rmsSum = 0.0
        for (i in 0 until count) {
            val v = samples[i] / 32768.0
            rmsSum += v * v
        }
        val rms = sqrt(rmsSum / count)
        val threshold = 0.055 - sensitivity.progress * 0.00048
        if (rms < threshold.coerceAtLeast(0.003)) return

        val frequencies = doubleArrayOf(45.0, 70.0, 105.0, 160.0, 240.0, 360.0, 540.0, 800.0, 1200.0, 1800.0, 2700.0, 4000.0, 6000.0, 8500.0, 12000.0, 16000.0)
        val energies = DoubleArray(frequencies.size)
        for (fIndex in frequencies.indices) {
            val omega = 2.0 * Math.PI * frequencies[fIndex] / sampleRate
            var re = 0.0; var im = 0.0
            val limit = min(count, 1024)
            for (i in 0 until limit) {
                val window = 0.5 - 0.5 * cos(2.0 * Math.PI * i / (limit - 1))
                val sample = samples[i] * window
                re += sample * cos(omega * i)
                im -= sample * sin(omega * i)
            }
            energies[fIndex] = re * re + im * im
        }

        var total = energies.sum()
        if (total <= 0.0) return
        var weighted = 0.0
        var peak = 0.0
        for (i in energies.indices) {
            weighted += energies[i] * frequencies[i]
            peak = max(peak, energies[i])
        }
        val centroid = (weighted / total).coerceIn(35.0, 16_000.0)
        val thinness = (peak / (total / energies.size)).coerceIn(1.0, 16.0)
        val logPos = (ln(centroid / 35.0) / ln(16_000.0 / 35.0)).coerceIn(0.0, 1.0)
        val shifted = (logPos + (thinness - 4.0) * 0.018).coerceIn(0.0, 0.999)
        var index = (shifted * colors.size).toInt()
        if (lastColor >= 0 && abs(index - lastColor) == 1 && thinness < 6.0) index = lastColor

        val now = System.currentTimeMillis()
        val minDelay = 80L + interval.progress
        if (index != lastColor && now - lastSend >= minDelay) {
            val c = colors[index]
            send(c)
            lastColor = index
            lastSend = now
            runOnUiThread { show(c, centroid, thinness) }
        }
    }

    private fun testNextProfile() {
        if (!ir.hasIrEmitter()) return
        activeProfile = scannerProfiles[scanIndex % scannerProfiles.size]
        scanIndex++
        val testCode = 0x00F7C03F // common ON command
        transmit(testCode, activeProfile)
        status.text = "Testing ${activeProfile.label} (${scanIndex}/${scannerProfiles.size})"
    }

    private fun saveCurrentProfile() {
        getPreferences(MODE_PRIVATE).edit()
            .putInt("carrier", activeProfile.carrier)
            .putInt("mode", activeProfile.mode)
            .putInt("repeats", activeProfile.repeats)
            .apply()
        status.text = "Saved ${activeProfile.label}"
    }

    private fun loadProfile() {
        val p = getPreferences(MODE_PRIVATE)
        activeProfile = IrProfile(p.getInt("carrier", 38_000), p.getInt("mode", 0), p.getInt("repeats", 1))
    }

    private fun show(c: LedColor, hz: Double, thin: Double) {
        swatch.text = "${c.name}\n${hz.roundToInt()} Hz · thinness ${"%.1f".format(thin)}"
        swatch.setBackgroundColor(c.rgb or 0xFF000000.toInt())
        swatch.setTextColor(if (Color.luminance(c.rgb) < 0.45) Color.WHITE else Color.BLACK)
    }

    private fun send(c: LedColor) = transmit(c.code, activeProfile)

    private fun transmit(code: Long, profile: IrProfile) {
        try {
            repeat(profile.repeats) {
                ir.transmit(profile.carrier, necPattern(code, profile.mode))
                if (profile.repeats > 1) Thread.sleep(35)
            }
        } catch (e: Exception) {
            runOnUiThread { status.text = "IR error: ${e.message}" }
        }
    }

    private fun necPattern(code: Long, mode: Int): IntArray {
        val bytes = intArrayOf(
            ((code shr 24) and 0xFF).toInt(),
            ((code shr 16) and 0xFF).toInt(),
            ((code shr 8) and 0xFF).toInt(),
            (code and 0xFF).toInt()
        )
        val ordered = when (mode) {
            1 -> bytes.reversedArray()
            2 -> bytes.map { reverseByte(it) }.toIntArray()
            3 -> bytes.reversedArray().map { reverseByte(it) }.toIntArray()
            else -> bytes
        }
        val p = ArrayList<Int>(67)
        p += 9000; p += 4500
        for (b in ordered) {
            for (bit in 0 until 8) {
                p += 560
                p += if (((b shr bit) and 1) == 1) 1690 else 560
            }
        }
        p += 560
        return p.toIntArray()
    }

    private fun reverseByte(v: Int): Int {
        var x = v; var out = 0
        repeat(8) { out = (out shl 1) or (x and 1); x = x shr 1 }
        return out
    }

    private fun stopCapture() {
        running = false
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        projection?.stop(); projection = null
        toggle.text = "START AUDIO CAPTURE"
        status.text = "Stopped"
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
