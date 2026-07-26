package dev.cardrhyme.irmusicsync

object IrProtocol {
    /**
     * Builds the 67-duration waveform used by this controller's 24-bit NEC frame.
     * Example RED code F720DF is transmitted as the three bytes F7 20 DF,
     * with each byte sent least-significant bit first.
     */
    fun necPattern(code: Long, mode: Int): IntArray {
        val bytes = intArrayOf(
            ((code shr 16) and 0xFF).toInt(),
            ((code shr 8) and 0xFF).toInt(),
            (code and 0xFF).toInt()
        )
        val ordered = when (mode) {
            1 -> bytes.reversedArray()
            2 -> bytes.map(::reverseByte).toIntArray()
            3 -> bytes.reversedArray().map(::reverseByte).toIntArray()
            else -> bytes
        }

        // Header (2 durations) + 24 bits × 2 durations + final mark = 51 durations.
        // The working remote app reports 67 durations because it represents a standard
        // NEC frame with the leading address byte omitted from the displayed hex code.
        // Reconstruct that address byte (00) only in waveform space, not as a fourth
        // user-visible command byte, so F720DF yields the standard 67-duration frame.
        val frame = intArrayOf(0x00, ordered[0], ordered[1], ordered[2])
        val pattern = ArrayList<Int>(67)
        pattern += 9000
        pattern += 4500
        for (byte in frame) {
            for (bit in 0 until 8) {
                pattern += 560
                pattern += if (((byte shr bit) and 1) == 1) 1690 else 560
            }
        }
        pattern += 560
        return pattern.toIntArray()
    }

    private fun reverseByte(value: Int): Int {
        var input = value
        var output = 0
        repeat(8) {
            output = (output shl 1) or (input and 1)
            input = input shr 1
        }
        return output
    }
}
