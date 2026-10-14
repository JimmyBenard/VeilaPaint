package studio.nuvora.veila.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import org.json.JSONObject
import studio.nuvora.veila.core.AtomicSlots

/** Bounded local image store for Books; page images are loaded only by the reader. */
class BookRepository(private val context: Context) {
    val root = File(context.filesDir, "books").apply { mkdirs() }

    fun directory(id: String) = File(root, id.also(UUID::fromString)).apply { mkdirs() }

    fun asset(book: Book, filename: String) = File(directory(book.id), filename)

    fun thumbnail(book: Book): File? {
        val preferred = book.coverThumbnail ?: book.pages.firstOrNull()?.thumbnail
        return preferred?.let { asset(book, it).takeIf(File::isFile) }
            ?: book.pages.firstNotNullOfOrNull { asset(book, it.thumbnail).takeIf(File::isFile) }
    }

    @Synchronized
    fun list(): Pair<List<Book>, Int> {
        var unreadable = 0
        val books =
            root.listFiles().orEmpty().filter(File::isDirectory).mapNotNull { directory ->
                try {
                    val slot = AtomicSlots(directory).read() ?: return@mapNotNull null
                    Book.fromJson(JSONObject(String(slot.bytes, Charsets.UTF_8)))
                } catch (_: Exception) {
                    unreadable++
                    null
                }
            }.sortedByDescending { it.modified }
        return books to unreadable
    }

    @Synchronized
    fun load(id: String): Book {
        val value = AtomicSlots(directory(id)).read() ?: error("Book introuvable ou illisible.")
        return Book.fromJson(JSONObject(String(value.bytes, Charsets.UTF_8))).also {
            require(it.id == id) { "Identifiant du Book incohérent." }
        }
    }

    fun create(name: String): Book =
        Book(name = name.trim().take(100).ifEmpty { "Nouveau Book" }).also(::save)

    @Synchronized
    fun save(book: Book) {
        require(book.pages.size <= 500) { "500 pages maximum par Book." }
        AtomicSlots(directory(book.id)).write(book.copy(modified = System.currentTimeMillis()).json().toString().toByteArray())
    }

    @Synchronized
    fun delete(book: Book) {
        check(directory(book.id).deleteRecursively()) { "Suppression du Book impossible." }
    }

    data class ImportedImage(val image: String, val thumbnail: String, val width: Int, val height: Int)

    fun importPage(book: Book, uri: Uri): BookPage {
        val imported = importImage(book, uri, "page")
        return BookPage(
            id = UUID.randomUUID().toString(),
            image = imported.image,
            thumbnail = imported.thumbnail,
            width = imported.width,
            height = imported.height,
        )
    }

    fun importCover(book: Book, uri: Uri): ImportedImage = importImage(book, uri, "cover")

    fun removeAssets(book: Book, image: String?, thumbnail: String?) {
        image?.let { asset(book, it).delete() }
        thumbnail?.let { asset(book, it).delete() }
    }

    private fun importImage(book: Book, uri: Uri, kind: String): ImportedImage {
        val token = UUID.randomUUID().toString()
        val image = "$token.$kind"
        val thumb = "$token.$kind.thumb.png"
        val target = asset(book, image)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 96L * 1024 * 1024) { "Image trop lourde (96 Mo maximum)." }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: error("Impossible de lire l’image sélectionnée.")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image non prise en charge." }
            makeThumbnail(target, asset(book, thumb))
            return ImportedImage(image, thumb, bounds.outWidth, bounds.outHeight)
        } catch (error: Throwable) {
            target.delete()
            asset(book, thumb).delete()
            if (error is OutOfMemoryError) throw IOException("Mémoire insuffisante pour cette image.", error)
            throw error
        }
    }

    private fun makeThumbnail(source: File, destination: File) {
        val bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = minOf(1f, 480f / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(
                    maxOf(1, (info.size.width * scale).toInt()),
                    maxOf(1, (info.size.height * scale).toInt()),
                )
            }
        try {
            FileOutputStream(destination).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.fd.sync()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
