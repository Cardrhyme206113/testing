package com.example.greeyap1f7

import org.json.JSONObject

/**
 * Candidate 8-byte Gree state encoder, ported from documented Gree/Kelvinator layout.
 * It is NOT confirmed that the YAP1F7 handset uses these exact IR frames.
 */
internal object GreeEncoder {
    data class State(
        val power: Boolean = false,
        val temp: Int = 25,
        val mode: Int = 0,
        val fan: Int = 0,
        val swingV: Int = 0,
        val swingH: Int = 0,
        val sleep: Boolean = false,
        val turbo: Boolean = false,
        val light: Boolean = true,
        val wifi: Boolean = false,
        val ifeel: Boolean = false,
        val displayTemp: Int = 0,
        val timerOff: Int = 0,
    )

    data class Transmission(val frequency: Int, val pattern: IntArray, val bytes: IntArray)

    fun encode(json: JSONObject, useYaw1f: Boolean): Transmission = encode(
        State(
            power = json.optBoolean("power", false),
            temp = json.optInt("temp", 25),
            mode = json.optInt("mode", 0),
            fan = json.optInt("fan", 0),
            swingV = json.optInt("swingV", 0),
            swingH = json.optInt("swingH", 0),
            sleep = json.optBoolean("sleep", false),
            turbo = json.optBoolean("turbo", false),
            light = json.optBoolean("light", true),
            wifi = json.optBoolean("wifi", false),
            ifeel = json.optBoolean("ifeel", false),
            displayTemp = json.optInt("displayTemp", 0),
            timerOff = json.optInt("timerOff", 0),
        ), useYaw1f,
    )

    fun encode(state: State, useYaw1f: Boolean): Transmission {
        val raw = IntArray(8)
        val mode = state.mode.coerceIn(0, 4)
        val temp = if (mode == 0) 25 else state.temp.coerceIn(16, 30)
        val fan = if (mode == 2) 1 else state.fan.coerceIn(0, 3)
        val swingV = state.swingV.coerceIn(0, 11)
        val swingAuto = swingV == 1 || swingV == 7 || swingV == 9 || swingV == 11
        val swingH = state.swingH.coerceIn(0, 6)
        val timer = state.timerOff.coerceIn(0, 1440)
        val timerHour = timer / 60

        // Byte 0: mode (3b), power, fan (2b), swing auto, sleep.
        raw[0] = mode or (b(state.power) shl 3) or (fan shl 4) or
            (b(swingAuto) shl 6) or (b(state.sleep) shl 7)
        // Byte 1: temperature, half-hour, BCD tens of hour, timer enabled.
        raw[1] = (temp - 16) or (b(timer % 60 >= 30) shl 4) or
            ((timerHour / 10) shl 5) or (b(timer >= 30) shl 7)
        // Byte 2: BCD hours, turbo, light, model discriminator, X-FAN (unused).
        raw[2] = (timerHour % 10) or (b(state.turbo) shl 4) or
            (b(state.light) shl 5) or (b(useYaw1f && state.power) shl 6)
        raw[3] = 0x50 // Gree reserved nibble
        raw[4] = swingV or (swingH shl 4)
        raw[5] = state.displayTemp.coerceIn(0, 3) or (b(state.ifeel) shl 2) or
            (4 shl 3) or (b(state.wifi) shl 6)
        raw[6] = 0
        // Kelvinator/Gree checksum: low nibbles bytes 0-3, high nibbles 4-6.
        val sum = (10 + (0..3).sumOf { raw[it] and 0xF } +
            (4..6).sumOf { raw[it] shr 4 }) and 0xF
        raw[7] = sum shl 4

        val pulses = ArrayList<Int>(140)
        fun pair(mark: Int, space: Int) { pulses.add(mark); pulses.add(space) }
        fun bit(v: Int) = pair(620, if (v == 1) 1600 else 540)
        fun byte(v: Int) { for (i in 0..7) bit((v shr i) and 1) }

        pair(9000, 4500)
        for (i in 0..3) byte(raw[i])
        bit(0); bit(1); bit(0)  // 3-bit footer, LSB first
        pair(620, 19980)
        for (i in 4..7) byte(raw[i])
        pair(620, 19980)
        return Transmission(38_000, pulses.toIntArray(), raw)
    }

    private fun b(v: Boolean): Int = if (v) 1 else 0
}
