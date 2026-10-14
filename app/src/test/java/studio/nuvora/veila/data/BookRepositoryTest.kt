package studio.nuvora.veila.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: BookRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.resolve("books").deleteRecursively()
        repository = BookRepository(context)
    }

    private fun image(width: Int, height: Int, color: Int = Color.MAGENTA): Uri {
        val file = File(context.cacheDir, "book-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    @Test
    fun createPagesCoverAndReadingPositionSurviveRestore() {
        val book = repository.create("Kasumi")
        val first = repository.importPage(book, image(1200, 800))
        val second = repository.importPage(book, image(800, 1200, Color.CYAN))
        val cover = repository.importCover(book, image(900, 900, Color.YELLOW))
        val saved =
            book.copy(
                description = "Études progressives",
                pages = listOf(first, second),
                coverImage = cover.image,
                coverThumbnail = cover.thumbnail,
                currentPage = 1,
            )
        repository.save(saved)
        val restored = repository.load(book.id)
        assertEquals("Kasumi", restored.name)
        assertEquals(2, restored.pages.size)
        assertEquals(1, restored.safeCurrentPage)
        assertEquals(1200, restored.pages.first().width)
        assertTrue(repository.thumbnail(restored)!!.isFile)
        assertTrue(repository.asset(restored, restored.pages[1].image).isFile)
    }

    @Test
    fun pageReorderingAndDeletionKeepTheBookReadable() {
        val book = repository.create("Pages")
        val first = repository.importPage(book, image(100, 200))
        val second = repository.importPage(book, image(200, 100))
        val reordered = book.copy(pages = listOf(second, first), currentPage = 1)
        repository.save(reordered)
        repository.removeAssets(reordered, first.image, first.thumbnail)
        val withoutFirst = reordered.copy(pages = listOf(second), currentPage = 0)
        repository.save(withoutFirst)
        val restored = repository.load(book.id)
        assertEquals(listOf(second.id), restored.pages.map { it.id })
        assertFalse(repository.asset(restored, first.image).exists())
        assertTrue(repository.asset(restored, second.thumbnail).isFile)
    }

    @Test
    fun deleteRemovesOnlyTheRequestedBook() {
        val first = repository.create("Premier")
        val second = repository.create("Second")
        repository.delete(first)
        assertEquals(listOf(second.id), repository.list().first.map { it.id })
    }

    @Test
    fun replacingAPageAndClearingCoverKeepsOtherPagesAndPosition() {
        val book = repository.create("Portraits")
        val first = repository.importPage(book, image(120, 240))
        val last = repository.importPage(book, image(240, 120))
        val cover = repository.importCover(book, image(200, 200))
        repository.save(book.copy(pages = listOf(first, last), currentPage = 1, coverImage = cover.image, coverThumbnail = cover.thumbnail))
        val replacement = repository.importPage(book, image(360, 180, Color.BLUE))
        val changed = book.copy(name = "Book modifié", description = "Essai", pages = listOf(first, replacement), currentPage = 1)
        repository.save(changed)
        repository.removeAssets(book, last.image, last.thumbnail)
        repository.removeAssets(book, cover.image, cover.thumbnail)
        val restored = repository.load(book.id)
        assertEquals("Book modifié", restored.name)
        assertEquals(1, restored.safeCurrentPage)
        assertEquals(replacement.id, restored.pages[1].id)
        assertNull(restored.coverImage)
        assertEquals(repository.asset(book, first.thumbnail), repository.thumbnail(restored))
    }

    @Test
    fun corruptedPageImportLeavesExistingBookUntouched() {
        val book = repository.create("Conservé")
        val source = File(context.cacheDir, "bad-page.png").apply { writeText("not an image") }
        assertThrows(Exception::class.java) { repository.importPage(book, Uri.fromFile(source)) }
        assertEquals(book.id, repository.load(book.id).id)
        assertTrue(repository.load(book.id).pages.isEmpty())
        assertTrue(repository.directory(book.id).listFiles()!!.none { it.name.endsWith(".page") })
    }
}
