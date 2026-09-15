package com.cardrhyme.screentranslator

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object OcrModelStore {
    data class Paths(val detector: String, val recognizer: String)

    private const val HF = "https://huggingface.co/PaddlePaddle/"
    private const val DET_URL = HF + "PP-OCRv6_small_det_onnx/resolve/main/inference.onnx?download=true"
    private const val REC_URL = HF + "PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx?download=true"

    suspend fun ensureSmallModels(context: Context, status: (String) -> Unit): Paths =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "ppocrv6-small-v1").apply { mkdirs() }
            val det = File(dir, "det.onnx")
            val rec = File(dir, "rec.onnx")
            coroutineScope {
                listOf(
                    async { ensure(det, DET_URL, 1_000_000L, "Detector", status) },
                    async { ensure(rec, REC_URL, 10_000_000L, "Recognizer", status) },
                ).awaitAll()
            }
            Paths(det.absolutePath, rec.absolutePath)
        }

    private fun ensure(
        destination: File,
        address: String,
        minimumBytes: Long,
        label: String,
        status: (String) -> Unit,
    ) {
        if (destination.isFile && destination.length() >= minimumBytes) return
        val part = File(destination.parentFile, destination.name + ".part")
        part.delete()
        status("Downloading $label…")

        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "ScreenTranslator/0.2")
        try {
            connection.connect()
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(262_144)
                    var copied = 0L
                    var reported = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (copied - reported >= 2_000_000L) {
                            reported = copied
                            val percent = if (total > 0L) " ${copied * 100L / total}%" else ""
                            status(label + percent)
                        }
                    }
                }
            }
            check(part.length() >= minimumBytes) { "$label download incomplete" }
            destination.delete()
            check(part.renameTo(destination)) { "Could not save $label" }
        } finally {
            connection.disconnect()
            if (!destination.exists()) part.delete()
        }
    }
}
