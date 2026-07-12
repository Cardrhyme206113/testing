from pathlib import Path

engine_path = Path("SharpLayerAndroid/app/src/main/java/com/cardrhyme/sharplayer/export/OptionDExportEngine.kt")
gradle_path = Path("SharpLayerAndroid/app/build.gradle.kts")

text = engine_path.read_text()

text = text.replace(
    "import com.cardrhyme.sharplayer.codec.StructureCodec\n",
    "import com.cardrhyme.sharplayer.codec.StructureCodec\n"
    "import com.arthenica.ffmpegkit.FFmpegKit\n"
    "import com.arthenica.ffmpegkit.ReturnCode\n",
)

text = text.replace(
    "        activeTransformer?.cancel()\n        activeTransformer = null\n",
    "        activeTransformer?.cancel()\n        activeTransformer = null\n        FFmpegKit.cancel()\n",
)

text = text.replace(
    "            var lastMeasured = Int.MAX_VALUE\n            var attempt = 0\n",
    "            var lastMeasured = Int.MAX_VALUE\n            var attempt = 0\n            var useSoftwareEncoder = false\n",
)

old_transcode = """                transcodeBase(
                    source = source,
                    output = base,
                    height = settings.outputHeight,
                    videoBitrate = requestedVideoKbps * 1_000,
                    audioBitrate = audioKbps * 1_000,
                    plan = encoderPlan,
                    attempt = attempt,
                    clipEndMs = null,
                    reportProgress = true,
                    onUpdate = onUpdate,
                )
"""
new_transcode = """                if (useSoftwareEncoder) {
                    transcodeSoftware(
                        source = source,
                        output = base,
                        height = settings.outputHeight,
                        videoBitrateKbps = requestedVideoKbps,
                        audioBitrateKbps = audioKbps,
                        frameRate = encoderPlan.frameRate,
                        attempt = attempt,
                        onUpdate = onUpdate,
                    )
                } else {
                    transcodeBase(
                        source = source,
                        output = base,
                        height = settings.outputHeight,
                        videoBitrate = requestedVideoKbps * 1_000,
                        audioBitrate = audioKbps * 1_000,
                        plan = encoderPlan,
                        attempt = attempt,
                        clipEndMs = null,
                        reportProgress = true,
                        onUpdate = onUpdate,
                    )
                }
"""
if old_transcode not in text:
    raise SystemExit("transcode call anchor not found")
text = text.replace(old_transcode, new_transcode)

text = text.replace(
    '"${encoderPlan.label} · $requestedVideoKbps kbps video",',
    '"${if (useSoftwareEncoder) "software x264" else encoderPlan.label} · $requestedVideoKbps kbps video",',
)

text = text.replace(
    '.put("encoderMode", encoderPlan.label)',
    '.put("encoderMode", if (useSoftwareEncoder) "software x264" else encoderPlan.label)',
)

switch_anchor = """                lastMeasured = measured

                if (measured <= ceil(target * 1.04).toInt()) {
"""
switch_replacement = """                lastMeasured = measured

                if (!useSoftwareEncoder && measured > ceil(target * 1.10).toInt()) {
                    useSoftwareEncoder = true
                    onUpdate(
                        Update(
                            0.43f,
                            "Software bitrate fallback",
                            "Hardware produced $measured kbps for a $target kbps target; retrying with bundled x264.",
                        )
                    )
                    continue
                }

                if (measured <= ceil(target * 1.04).toInt()) {
"""
if switch_anchor not in text:
    raise SystemExit("measurement anchor not found")
text = text.replace(switch_anchor, switch_replacement)

text = text.replace(
    '"Measured $measured kbps total · ${encoderPlan.frameRate} fps · " +',
    '"Measured $measured kbps total · ${encoderPlan.frameRate} fps · " +\n                                "${if (useSoftwareEncoder) "software x264 · " else ""}" +',
)

insert_anchor = """    private fun ensureNotCancelled() {
"""
software_function = r'''    private suspend fun transcodeSoftware(
        source: Uri,
        output: File,
        height: Int,
        videoBitrateKbps: Int,
        audioBitrateKbps: Int,
        frameRate: Int,
        attempt: Int,
        onUpdate: (Update) -> Unit,
    ) = withContext(Dispatchers.IO) {
        ensureNotCancelled()
        onUpdate(
            Update(
                0.43f,
                "Software bitrate fallback",
                "Attempt $attempt · copying source for x264",
            )
        )

        val input = File(output.parentFile, "ffmpeg-input.mp4")
        if (!input.exists() || input.length() == 0L) {
            context.contentResolver.openInputStream(source)?.use { sourceStream ->
                input.outputStream().use { destination -> sourceStream.copyTo(destination) }
            } ?: error("Could not open the selected video for FFmpeg.")
        }
        ensureNotCancelled()

        val bufferKbps = (videoBitrateKbps * 2).coerceAtLeast(80)
        val arguments = arrayOf(
            "-y",
            "-i", input.absolutePath,
            "-vf", "scale=-2:$height:flags=lanczos,fps=$frameRate",
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-tune", "zerolatency",
            "-pix_fmt", "yuv420p",
            "-b:v", "${videoBitrateKbps}k",
            "-minrate", "${videoBitrateKbps}k",
            "-maxrate", "${videoBitrateKbps}k",
            "-bufsize", "${bufferKbps}k",
            "-x264-params", "nal-hrd=cbr:force-cfr=1",
            "-c:a", "aac",
            "-ac", "1",
            "-ar", "22050",
            "-b:a", "${audioBitrateKbps}k",
            "-movflags", "+faststart",
            output.absolutePath,
        )

        onUpdate(
            Update(
                0.48f,
                "Software bitrate fallback",
                "Attempt $attempt · x264 ${videoBitrateKbps} kbps · this is slower than hardware",
            )
        )
        output.delete()
        val session = FFmpegKit.executeWithArguments(arguments)
        ensureNotCancelled()
        if (!ReturnCode.isSuccess(session.returnCode)) {
            val tail = session.allLogsAsString?.takeLast(1500).orEmpty()
            error("FFmpeg x264 failed (${session.returnCode}). $tail")
        }
        require(output.exists() && output.length() > 0L) {
            "FFmpeg x264 produced an empty MP4."
        }
    }

'''
if insert_anchor not in text:
    raise SystemExit("function insertion anchor not found")
text = text.replace(insert_anchor, software_function + insert_anchor)

engine_path.write_text(text)

gradle = gradle_path.read_text()
dependency = '    implementation(files("libs/ffmpeg-kit-full-gpl-7.0.aar"))\n'
if dependency not in gradle:
    gradle = gradle.replace(
        '    implementation("androidx.media3:media3-ui:1.10.0")\n',
        '    implementation("androidx.media3:media3-ui:1.10.0")\n\n' + dependency,
    )
gradle_path.write_text(gradle)
