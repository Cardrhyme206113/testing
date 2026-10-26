package dev.cardrhyme.irmusicsync

object IrProtocol {
    /**
     * Encodes the controller's NEC value exactly like the working generic remote app.
     * A displayed code such as F720DF is a 32-bit value with a leading 00 byte:
     * 00 F7 20 DF. The captured waveform sends each displayed byte MSB-first and
     * contains 67 durations total.
     */
    fun necPattern(code: Long, @Suppress("UNUSED_PARAMETER") mode: Int): IntArray {
        val value = code and 0x00FFFFFFL
        val frame = longArrayOf(
            0x00,
            (value shr 16) and 0xFF,
            (value shr 8) and 0xFF,
            value and 0xFF
        )

        val pattern = ArrayList<Int>(67)
        pattern += 9100
        pattern += 4500
        for (byte in frame) {
            for (bit in 7 downTo 0) {
                pattern += 550
                pattern += if (((byte shr bit) and 1L) == 1L) 1700 else 600
            }
        }
        pattern += 550
        return pattern.toIntArray()
    }
}
