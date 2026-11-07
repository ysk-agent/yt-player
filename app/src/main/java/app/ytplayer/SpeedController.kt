package app.ytplayer

import kotlin.math.pow

/**
 * Logarithmic playback-speed control.
 *
 * The API is an integer level; the effective rate is 2^(level/12), so equal level
 * steps are equal speed ratios (one level = one semitone of pitch). Level 0 is 1.0x.
 * The rate is clamped to [0.8, 2.8]: level -4 gives raw 0.794 -> 0.8 and
 * level 18 gives raw 2.828 -> 2.8.
 */
object SpeedController {
    const val MIN_LEVEL = -4
    const val MAX_LEVEL = 18
    const val MIN_RATE = 0.8f
    const val MAX_RATE = 2.8f

    @Volatile
    var level: Int = 0
        private set

    fun rateForLevel(l: Int): Float =
        2.0.pow(l.coerceIn(MIN_LEVEL, MAX_LEVEL) / 12.0).toFloat().coerceIn(MIN_RATE, MAX_RATE)

    fun currentRate(): Float = rateForLevel(level)

    /** Sets the level (clamped) and returns the effective rate. */
    fun setLevel(l: Int): Float {
        level = l.coerceIn(MIN_LEVEL, MAX_LEVEL)
        return currentRate()
    }

    /** Steps the level by [deltaLevels] and returns the effective rate. */
    fun adjust(deltaLevels: Int): Float = setLevel(level + deltaLevels)
}
