package studio.nuvora.veila.core

import org.junit.Assert.*
import org.junit.Test

class VideoSpeedTest {
    @Test
    fun acceleratedSpeedsProduceExactCeilingDuration() {
        assertEquals(18_000L, VideoSpeed.durationMillis(3_600_000, 200))
        assertEquals(7_200L, VideoSpeed.durationMillis(3_600_000, 500))
        assertEquals(3_600_000L, VideoSpeed.durationMillis(3_600_000, 1))
    }

    @Test
    fun intermediateSpeedsAndShortVideosHaveFiniteDurations() {
        assertEquals(144_000L, VideoSpeed.durationMillis(3_600_000, 25))
        assertEquals(1L, VideoSpeed.durationMillis(10, 200))
        assertEquals(0L, VideoSpeed.durationMillis(0, 200))
        assertTrue(VideoSpeed.durationMillis(Long.MAX_VALUE, 200) > 0)
    }

    @Test
    fun outOfRangeSpeedsAreRejected() {
        for (speed in listOf(-1, 0, 501)) assertThrows(IllegalArgumentException::class.java) {
            VideoSpeed.durationMillis(1000, speed)
        }
    }
}
