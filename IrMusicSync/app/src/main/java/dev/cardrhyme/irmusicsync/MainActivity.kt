package dev.cardrhyme.irmusicsync

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.ConsumerIrManager
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.widget.*
import kotlin.math.*

class MainActivity : Activity() {
    private lateinit var ir: ConsumerIrManager
    private var visualizer: Visualizer? = null
    private var running = false
    private var lastColor = -1
    private var lastSend = 0L
    private lateinit var status: TextView
    private lateinit var swatch: TextView
    private lateinit var interval: SeekBar
    private lateinit var sensitivity: SeekBar

    data class LedColor(val name: String, val rgb: Int, val nec: Long)

    // Most common 24-key analog RGB controller protocol: NEC, 38 kHz.
    private val colors = listOf(
        LedColor("Red", 0xFF0000, 0xF720DF), LedColor("Orange", 0xFF4000, 0xF710EF),
        LedColor("Light orange", 0xFF7000, 0xF730CF), LedColor("Amber", 0xFFAA00, 0xF708F7),
        LedColor("Yellow", 0xFFFF00, 0xF728D7), LedColor("Green", 0x00FF00, 0xF7A05F),
        LedColor("Lime", 0x70FF40, 0xF7906F), LedColor("Cyan", 0x00FFFF, 0xF7B04F),
        LedColor("Sky", 0x35BFFF, 0xF78877), LedColor("Blue", 0x0000FF, 0xF7609F),
        LedColor("Deep blue", 0x002080, 0xF750AF), LedColor("Light blue", 0x4080FF, 0xF7708F),
        LedColor("Purple", 0x7030A0, 0xF748B7), LedColor("Violet", 0xA040FF, 0xF76897),
        LedColor("Magenta", 0xFF40C0, 0xF7D02F), LedColor("White", 0xFFFFFF, 0xF7E01F)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as ConsumerIrManager
        buildUi()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32); setBackgroundColor(0xFF101014.toInt())
        }
        fun text(s: String, size: Float = 16f) = TextView(this).apply { text=s; textSize=size; setTextColor(Color.WHITE); setPadding(0,10,0,10) }
        root.addView(text("IR Music Sync", 26f))
        status = text(if (ir.hasIrEmitter()) "IR emitter detected" else "No Android IR emitter detected")
        root.addView(status)
        swatch = text("Current color", 20f).apply { setBackgroundColor(0xFF222228.toInt()); setPadding(24,40,24,40) }
        root.addView(swatch)
        root.addView(text("Sensitivity")); sensitivity = SeekBar(this).apply { max=100; progress=45 }; root.addView(sensitivity)
        root.addView(text("Minimum color interval (80–500 ms)")); interval = SeekBar(this).apply { max=420; progress=100 }; root.addView(interval)
        val toggle = Button(this).apply { text="START"; setOnClickListener { if (running) stopSync(this) else startSync(this) } }
        root.addView(toggle)
        root.addView(text("Test all controller colors"))
        val grid = GridLayout(this).apply { columnCount=4 }
        colors.forEachIndexed { i,c ->
            grid.addView(Button(this).apply {
                text=(i+1).toString(); setTextColor(if (Color.luminance(c.rgb)<0.4) Color.WHITE else Color.BLACK)
                setBackgroundColor(c.rgb or 0xFF000000.toInt()); setOnClickListener { send(c); show(c, 0.0, 0.0) }
            }, GridLayout.LayoutParams().apply { width=0; columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f) })
        }
        root.addView(grid)
        root.addView(text("Uses Android's output-mix Visualizer (session 0). Some apps/ROMs may block capture. Codes are the common 24-key NEC set; test buttons first.", 13f))
        setContentView(root)
    }

    private fun startSync(button: Button) {
        if (!ir.hasIrEmitter()) { status.text="No IR emitter"; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7); return
        }
        try {
            visualizer = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object: Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, data: ByteArray?, rate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) { fft?.let { analyze(it, rate) } }
                }, Visualizer.getMaxCaptureRate()/2, false, true)
                enabled = true
            }
            running=true; button.text="STOP"; status.text="Listening to device audio…"
        } catch (e: Exception) { status.text="Audio capture failed: ${e.message}" }
    }

    private fun stopSync(button: Button) {
        visualizer?.release(); visualizer=null; running=false; button.text="START"; status.text="Stopped"
    }

    private fun analyze(fft: ByteArray, samplingRateMilliHz: Int) {
        val bins = fft.size/2
        var total=0.0; var weighted=0.0; var peak=0.0
        val sampleRate = samplingRateMilliHz/1000.0
        for (k in 1 until bins) {
            val re=fft[2*k].toDouble(); val im=fft[2*k+1].toDouble(); val mag=sqrt(re*re+im*im)
            val freq=k*sampleRate/fft.size
            total += mag; weighted += mag*freq; peak=max(peak,mag)
        }
        val threshold = 70.0 - sensitivity.progress*0.55
        if (total < threshold) return
        val centroid=(weighted/total).coerceIn(20.0, 18000.0)
        val thinness=(peak/(total/bins+1e-6)).coerceIn(1.0,25.0)
        val logPos=(ln(centroid/35.0)/ln(18000.0/35.0)).coerceIn(0.0,1.0)
        val shifted=(logPos + (thinness-5.0)*0.012).coerceIn(0.0,0.999)
        var index=(shifted*colors.size).toInt()
        // Small hysteresis prevents chatter near boundaries.
        if (lastColor >= 0 && abs(index-lastColor)==1 && thinness<7.0) index=lastColor
        val now=System.currentTimeMillis(); val minDelay=80L+interval.progress
        if (index != lastColor && now-lastSend >= minDelay) {
            val c=colors[index]; send(c); lastColor=index; lastSend=now
            runOnUiThread { show(c, centroid, thinness) }
        }
    }

    private fun show(c: LedColor, hz: Double, thin: Double) {
        swatch.text="${c.name}\n${hz.roundToInt()} Hz · thinness ${"%.1f".format(thin)}"
        swatch.setBackgroundColor(c.rgb or 0xFF000000.toInt())
        swatch.setTextColor(if (Color.luminance(c.rgb)<0.45) Color.WHITE else Color.BLACK)
    }

    private fun send(c: LedColor) {
        try { ir.transmit(38_000, necPattern(c.nec)) } catch (e: Exception) { runOnUiThread { status.text="IR error: ${e.message}" } }
    }

    private fun necPattern(code: Long): IntArray {
        val p=ArrayList<Int>(67); p += 9000; p += 4500
        // NEC sends each byte least-significant bit first.
        for (byteIndex in 0 until 4) {
            val b=((code shr (24-byteIndex*8)) and 0xFF).toInt()
            for (bit in 0 until 8) { p += 560; p += if (((b shr bit) and 1)==1) 1690 else 560 }
        }
        p += 560; return p.toIntArray()
    }

    override fun onDestroy() { visualizer?.release(); super.onDestroy() }
}
