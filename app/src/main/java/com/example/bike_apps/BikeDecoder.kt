package com.example.bike_apps

/**
 * Decoded values from one E5/E6/E7 record triple.
 *
 * See BikeDecoder for field provenance and confidence notes - summary:
 * - minute/second/distance/workoutTimeSeconds/speed/rpm/resistance (E5):
 *   confirmed against real sessions (monotonicity, wrap behavior,
 *   freeze-on-stop, cross-referenced against a public write-up of the
 *   same protocol family).
 * - avgSpeed/maxSpeed/avgRpmRaw (E6): reverse-engineered structurally,
 *   not independently confirmed against a reference device.
 * - powerLive/powerAvg/powerMax (E7): name is a placeholder - ruled out
 *   as simple power (watts) by a resistance comparison that went the
 *   wrong direction. Real meaning still unknown.
 * - unknownE6: raw passthrough, unidentified.
 * - No calories field exists in this protocol at all.
 */
data class BikeData(
    val minute: Int,
    val second: Int,
    val distanceMiles: Double,
    val workoutTimeSeconds: Int,
    val speedMph: Double,
    val rpm: Int,
    val resistance: Int,
    val avgSpeedMph: Double,
    val maxSpeedMph: Double,
    val avgRpmRaw: Int,
    val unknownE6: Int,
    val powerLive: Int,
    val powerAvg: Int,
    val powerMax: Int,
)

/**
 * Decodes the F9 D5/E5/E6/E7 "workout" record triple. Port of
 * bike_decoder.py - see that file's docstring for the full history of how
 * these offsets were reverse-engineered and what's still unconfirmed.
 *
 * IMPORTANT: almost every multi-byte field here is a genuine 16-bit
 * big-endian value (high byte first) - NOT a single byte. This only
 * became visible once a session pushed hard enough that a field actually
 * exceeded 255 and needed its second byte; at lower effort the high byte
 * sits at 0 and a single-byte read looks fine. Every field below reads
 * both bytes for exactly that reason, even ones that haven't yet been
 * observed needing it (avgRpmRaw, the power_* fields) - don't assume
 * those are safe at higher effort than what's been tested so far.
 *
 * "second" is the one field NOT combined with its preceding byte as a
 * raw 16-bit number - it's independently confirmed to wrap at 60, so the
 * preceding byte is whole MINUTES on the device's persistent clock
 * (confirmed NOT reset per BLE connection), not a high byte.
 */
object BikeDecoder {

    fun decode(e5: ByteArray, e6: ByteArray, e7: ByteArray): BikeData? {

        if (e5.size < 20 || e6.size < 20 || e7.size < 20) {
            return null
        }

        fun u(b: Byte): Int = b.toInt() and 0xFF

        return BikeData(
            minute = u(e5[3]),
            second = u(e5[4]),

            distanceMiles = (u(e5[5]) * 256 + u(e5[6])) / 100.0,

            workoutTimeSeconds = u(e5[7]) * 256 + u(e5[8]),

            speedMph = (u(e5[9]) * 256 + u(e5[10])) / 10.0,

            rpm = u(e5[11]) * 256 + u(e5[12]),

            resistance = u(e5[13]),

            avgSpeedMph = (u(e6[3]) * 256 + u(e6[4])) / 10.0,

            maxSpeedMph = (u(e6[5]) * 256 + u(e6[6])) / 10.0,

            avgRpmRaw = u(e6[8]),

            unknownE6 = u(e6[18]),

            powerLive = u(e7[9]),
            powerAvg = u(e7[10]),
            powerMax = u(e7[11]),
        )
    }
}