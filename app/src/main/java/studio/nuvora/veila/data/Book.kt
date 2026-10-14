package studio.nuvora.veila.data

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class BookPage(
    val id: String = UUID.randomUUID().toString(),
    val image: String,
    val thumbnail: String,
    val width: Int,
    val height: Int,
)

/** A local presentation object. It never changes a project's reveal mask or stages. */
data class Book(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Nouveau Book",
    val description: String = "",
    val coverImage: String? = null,
    val coverThumbnail: String? = null,
    val pages: List<BookPage> = emptyList(),
    val currentPage: Int = 0,
    val created: Long = System.currentTimeMillis(),
    val modified: Long = System.currentTimeMillis(),
) {
    val safeCurrentPage: Int
        get() = currentPage.coerceIn(0, (pages.lastIndex).coerceAtLeast(0))

    fun json() =
        JSONObject().apply {
            put("schema", 1)
            put("id", id)
            put("name", name)
            put("description", description)
            put("coverImage", coverImage)
            put("coverThumbnail", coverThumbnail)
            put("currentPage", safeCurrentPage)
            put("created", created)
            put("modified", modified)
            put(
                "pages",
                JSONArray().apply {
                    pages.forEach { page ->
                        put(
                            JSONObject()
                                .put("id", page.id)
                                .put("image", page.image)
                                .put("thumbnail", page.thumbnail)
                                .put("width", page.width)
                                .put("height", page.height)
                        )
                    }
                },
            )
        }

    companion object {
        private val assetName = Regex("[A-Za-z0-9._-]+")

        fun fromJson(json: JSONObject): Book {
            require(json.getInt("schema") == 1)
            val id = json.getString("id").also(UUID::fromString)
            fun asset(key: String) = json.getString(key).also { require(it.matches(assetName)) }
            fun optionalAsset(key: String) =
                if (json.has(key) && !json.isNull(key)) asset(key) else null
            val array = json.getJSONArray("pages")
            require(array.length() <= 500) { "500 pages maximum par Book." }
            val pages =
                List(array.length()) { index ->
                    val page = array.getJSONObject(index)
                    BookPage(
                        id = page.getString("id").also(UUID::fromString),
                        image = page.getString("image").also { require(it.matches(assetName)) },
                        thumbnail = page.getString("thumbnail").also { require(it.matches(assetName)) },
                        width = page.getInt("width").also { require(it > 0) },
                        height = page.getInt("height").also { require(it > 0) },
                    )
                }
            require(pages.map { it.id }.distinct().size == pages.size) { "Pages dupliquées." }
            return Book(
                id = id,
                name = json.getString("name").trim().take(100).ifEmpty { "Nouveau Book" },
                description = json.optString("description").take(2_000),
                coverImage = optionalAsset("coverImage"),
                coverThumbnail = optionalAsset("coverThumbnail"),
                pages = pages,
                currentPage = json.optInt("currentPage", 0).coerceIn(0, pages.lastIndex.coerceAtLeast(0)),
                created = json.getLong("created"),
                modified = json.getLong("modified"),
            )
        }
    }
}
