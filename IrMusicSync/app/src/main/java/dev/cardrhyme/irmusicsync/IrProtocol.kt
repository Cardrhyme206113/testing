package dev.cardrhyme.irmusicsync

object IrProtocol {
    fun necPattern(code: Long, mode: Int): IntArray {
        val bytes = intArrayOf(
            ((code shr 24) and 0xFF).toInt(),
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
        val pattern = ArrayList<Int>(67)
        pattern += 9000
        pattern += 4500
        for (byte in ordered) {
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
