package studio.nuvora.veila.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: VideoRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.resolve("videos").deleteRecursively()
        context.filesDir.resolve("video_catalog").deleteRecursively()
        repository = VideoRepository(context)
    }

    @Test
    fun existingRecordingIsAdoptedThenCanBeRenamedAndDeleted() {
        val raw = File(context.filesDir, "videos/legacy.mp4").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(0, 1, 2, 3))
        }
        val adopted = repository.list().single()
        assertEquals(raw.name, adopted.fileName)
        assertEquals(0L, adopted.durationMillis)
        val renamed = repository.update(adopted.copy(name = "Speed Drawing Kasumi", description = "Essai"))
        assertEquals("Speed Drawing Kasumi", repository.list().single().name)
        repository.delete(renamed)
        assertFalse(raw.exists())
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun missingFileMetadataStaysVisibleAndCanBeCleanedUp() {
        val raw = File(context.filesDir, "videos/missing.mp4").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val entry = repository.list().single()
        assertTrue(raw.delete())
        assertEquals(entry.id, repository.list().single().id)
        repository.delete(entry)
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun recordingProjectAssociationAndDescriptionSurviveRenamingAndReload() {
        val project = Project(name = "Kasumi")
        val raw = File(context.filesDir, "videos/project-recording.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val created = repository.register(raw, project)
        assertEquals(project.id, created.projectId)
        val changed = repository.update(created.copy(name = "Kasumi — essai", description = "Étude"))
        val restored = VideoRepository(context).list().single()
        assertEquals(changed.id, restored.id)
        assertEquals(project.id, restored.projectId)
        assertEquals("Étude", restored.description)
        assertTrue(raw.isFile)
    }
}
