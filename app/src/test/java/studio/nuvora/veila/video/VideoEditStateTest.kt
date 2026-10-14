package studio.nuvora.veila.video

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class VideoEditStateTest {
    private val original = File("original.mp4")
    private val accelerated = File("preview-x200.mp4")
    private val base = VideoEditState(source = original, info = VideoInfo(3_600_000, 1280, 720))

    @Test fun originalIsPreviewedAtOneTimes() = assertEquals(original, base.preview)

    @Test
    fun exportRequiresPreviewMatchingCurrentSpeed() {
        val rendered =
            base.copy(
                speed = 200,
                prepared = accelerated,
                preparedSpeed = 200,
                preparedInfo = VideoInfo(18_000, 1280, 720),
            )
        assertSame(accelerated, rendered.preview)
        assertEquals(18_000L, rendered.previewInfo!!.duration)
        assertNull(rendered.copy(speed = 100).preview)
        assertNull(rendered.copy(preparing = true).preview)
        assertEquals("original-x200.mp4", rendered.exportName)
    }

    @Test
    fun invalidSourceCannotBeExported() {
        assertNull(base.copy(info = null).preview)
    }
}
