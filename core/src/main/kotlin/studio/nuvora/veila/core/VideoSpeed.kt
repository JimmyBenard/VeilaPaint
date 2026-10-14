package studio.nuvora.veila.core

/** Export speed is independent from the project clock and mask. */
object VideoSpeed {
    const val MIN = 1
    const val MAX = 500
    const val OUTPUT_FPS = 30

    fun validate(speed: Int) =
        require(speed in MIN..MAX) { "La vitesse doit être comprise entre ×1 et ×500." }

    fun durationMillis(sourceMillis: Long, speed: Int): Long {
        validate(speed)
        require(sourceMillis >= 0)
        return sourceMillis / speed + if (sourceMillis % speed == 0L) 0L else 1L
    }
}
