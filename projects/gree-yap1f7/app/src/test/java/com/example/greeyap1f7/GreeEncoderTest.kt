package com.example.greeyap1f7

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GreeEncoderTest {
    @Test fun knownGoodCandidateBytes() {
        val t = GreeEncoder.encode(
            GreeEncoder.State(power = true, temp = 30, mode = 4, fan = 3, swingV = 1),
            useYaw1f = true,
        )
        assertArrayEquals(intArrayOf(0x7C, 0x0E, 0x60, 0x50, 0x01, 0x20, 0, 0x60), t.bytes)
        assertEquals(38_000, t.frequency)
        assertEquals(140, t.pattern.size)
        assertEquals(9000, t.pattern[0])
        assertEquals(4500, t.pattern[1])
        assertEquals(19980, t.pattern.last())
    }

    @Test fun dryLocksFanAndAutoLocksTemperature() {
        val dry = GreeEncoder.encode(GreeEncoder.State(mode = 2, fan = 3), true)
        assertEquals(1, (dry.bytes[0] shr 4) and 3)
        val auto = GreeEncoder.encode(GreeEncoder.State(mode = 0, temp = 30), true)
        assertEquals(9, auto.bytes[1] and 15)
    }

    @Test fun modelSelectionChangesPowerDiscriminator() {
        val s = GreeEncoder.State(power = true)
        val y1 = GreeEncoder.encode(s, true)
        val y2 = GreeEncoder.encode(s, false)
        assertNotEquals(y1.bytes[2], y2.bytes[2])
    }

    @Test fun checksumUsesPublishedNibbleSums() {
        val t = GreeEncoder.encode(
            GreeEncoder.State(power = true, mode = 1, temp = 20, timerOff = 90),
            useYaw1f = true,
        )
        val b = t.bytes
        val sum = (10 + (0..3).sumOf { b[it] and 15 } + (4..6).sumOf { b[it] shr 4 }) and 15
        assertEquals(sum, b[7] shr 4)
        assertEquals(1, (b[1] shr 7) and 1)
    }
}
