package dev.wadackel.limenty.core.input

import kotlin.math.roundToInt

/** A setting's whole-number range, walked in [step]s from [min]. */
data class SettingRange(val min: Int, val max: Int, val step: Int) {
    init {
        require(step > 0 && (max - min) % step == 0) { "$min..$max does not divide into steps of $step" }
    }

    /** The values between the ends, which is how a slider counts its stops. */
    val stopsBetween: Int get() = (max - min) / step - 1

    /** The nearest value on a step, inside the range. */
    fun snap(value: Int): Int = (min + ((value - min).toFloat() / step).roundToInt() * step).coerceIn(min, max)
}

/**
 * How the long press on the terminal and the arrow pad it opens are timed.
 *
 * [pressTimeMillis] times every long press on the terminal, whatever Long press is bound to: the
 * pad, a selection, a handle held before it is dragged all come from the same detection, so it
 * cannot be set for the pad alone. Null follows Android's Touch & hold delay, which someone may
 * have lengthened for accessibility and should keep until they choose otherwise. The rest only
 * mean something while Long press opens the pad.
 */
data class ArrowPadSettings(
    val pressTimeMillis: Int? = null,
    val repeatDelayMillis: Int = 400,
    val repeatIntervalMillis: Int = 220,
    val deadZoneDp: Int = 25,
) {
    val isDefault: Boolean get() = this == ArrowPadSettings()

    /** Each value moved onto its range and step, for values read from somewhere that did not check. */
    fun snapped(): ArrowPadSettings = ArrowPadSettings(
        pressTimeMillis = pressTimeMillis?.let(PressTime::snap),
        repeatDelayMillis = RepeatDelay.snap(repeatDelayMillis),
        repeatIntervalMillis = RepeatInterval.snap(repeatIntervalMillis),
        deadZoneDp = DeadZone.snap(deadZoneDp),
    )

    companion object {
        val PressTime = SettingRange(200, 1500, 50)
        val RepeatDelay = SettingRange(100, 1000, 50)
        val RepeatInterval = SettingRange(30, 500, 10)

        /**
         * Kept inside the dots that mark the four directions, so the pad's drawing holds for every
         * value. Not down to the touch slop: a repeat that has just stopped would start again the
         * moment the thumb drifted.
         */
        val DeadZone = SettingRange(10, 45, 5)
    }
}
