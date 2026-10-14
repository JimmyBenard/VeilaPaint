package studio.nuvora.veila

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.lang.ref.WeakReference
import org.json.JSONArray
import kotlinx.coroutines.*
import studio.nuvora.veila.core.*
import studio.nuvora.veila.data.*
import studio.nuvora.veila.studio.*

enum class Screen {
    PROJECTS,
    PREPARATION,
    STUDIO,
    BOOKS,
    BOOK_EDITOR,
    BOOK_READER,
    VIDEOS,
}

class VeilaModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("veila.preferences", Application.MODE_PRIVATE)
    private val defaultLayerNames =
        listOf("Page blanche", "Croquis", "Croquis détaillé", "Colorisation 1er aplat",
            "Colorisation 2ème aplat", "Colorisation 3ème aplat", "Ombres", "Lumières", "Finitions")
    var layerNames by mutableStateOf(loadLayerNames())
        private set
    val repository = ProjectRepository(application)
    val bookRepository = BookRepository(application)
    val videoRepository = VideoRepository(application)
    val videoEditor = studio.nuvora.veila.video.VideoEditor(application, viewModelScope)
    var screen by mutableStateOf(Screen.PROJECTS)
        private set

    var projects by mutableStateOf<List<Project>>(emptyList())
        private set

    var books by mutableStateOf<List<Book>>(emptyList())
        private set

    var activeBook by mutableStateOf<Book?>(null)
        private set

    var draft by mutableStateOf(Project())
        private set

    var session by mutableStateOf<StudioSession?>(null)
        private set

    private var attachedView = WeakReference<RevealView>(null)
    var view: RevealView?
        get() = attachedView.get()
        set(value) {
            attachedView = WeakReference(value)
        }

    var busy by mutableStateOf<String?>(null)
        private set

    var message by mutableStateOf<String?>(null)
    var videos by mutableStateOf<List<VideoEntry>>(emptyList())
        private set

    private var original: LoadedProject? = null
    // FIFO dispatch of Book reads/writes prevents an older page position overwriting a newer one.
    private val bookIo = Dispatchers.IO.limitedParallelism(1)

    init {
        refresh()
        refreshBooks()
        refreshVideos()
    }

    private fun loadLayerNames(): List<String> =
        preferences.getString("layer_names", null)?.let { raw ->
            runCatching {
                JSONArray(raw).let { array ->
                    List(array.length()) { array.getString(it) }.filter { it.isNotBlank() }.distinct()
                }
            }.getOrNull()
        }?.takeIf { it.isNotEmpty() } ?: defaultLayerNames

    private fun persistLayerNames() {
        preferences.edit().putString("layer_names", JSONArray(layerNames).toString()).apply()
    }

    fun addLayerName(value: String) {
        val name = value.trim().take(100)
        if (name.isNotEmpty() && layerNames.none { it.equals(name, true) }) {
            layerNames = layerNames + name
            persistLayerNames()
        }
    }

    fun renameLayerName(old: String, value: String) {
        val name = value.trim().take(100)
        if (name.isNotEmpty() && layerNames.any { it == old }) {
            layerNames = layerNames.map { if (it == old) name else it }.distinct()
            persistLayerNames()
        }
    }

    fun deleteLayerName(value: String) {
        layerNames = layerNames.filterNot { it == value }
        persistLayerNames()
    }

    override fun onCleared() {
        // ON_PAUSE has already committed the stroke and paused the monotonic clock.
        // Shutdown drains outstanding writes while releasing the session's IO worker.
        videoEditor.close()
        session?.shutdown()
        attachedView.clear()
        super.onCleared()
    }

    private fun <T> work(
        label: String,
        task: () -> T,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        done: (T) -> Unit = {},
    ) {
        if (busy != null) return
        busy = label
        viewModelScope.launch {
            try {
                val result = withContext(dispatcher) { task() }
                done(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                message = e.message ?: "Cette opération n’a pas pu aboutir."
            } catch (_: OutOfMemoryError) {
                message = "Mémoire insuffisante. Essayez une toile plus petite."
            } finally {
                busy = null
            }
        }
    }

    private fun <T> bookWork(label: String, task: () -> T, done: (T) -> Unit = {}) =
        work(label, task, bookIo, done)

    fun refresh() =
        work("Ouverture des projets…", { repository.list() }) { (list, unreadable) ->
            projects = list
            if (unreadable > 0)
                message =
                    "$unreadable projet(s) illisible(s) conservés sur l’appareil. Aucun fichier n’a été supprimé."
        }

    fun refreshBooks() {
        viewModelScope.launch {
            try {
                val (items, unreadable) = withContext(bookIo) { bookRepository.list() }
                books = items
                if (unreadable > 0)
                    message = "$unreadable Book(s) illisible(s) ont été conservés sans suppression."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                message = e.message ?: "Impossible de lire les Books."
            }
        }
    }

    fun refreshVideos() {
        viewModelScope.launch {
            try {
                videos = withContext(Dispatchers.IO) { videoRepository.list() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                message = e.message ?: "Impossible de lire les vidéos."
            }
        }
    }

    fun openBooks() {
        screen = Screen.BOOKS
        refreshBooks()
    }

    fun openVideos() {
        screen = Screen.VIDEOS
        refreshVideos()
    }

    fun leaveBooks() {
        activeBook = null
        screen = Screen.PROJECTS
        refresh()
    }

    fun leaveVideos() {
        videoEditor.close()
        screen = Screen.PROJECTS
        refresh()
    }

    fun createBook(name: String) =
        bookWork("Création du Book…", { bookRepository.create(name) }) { created ->
            activeBook = created
            books = (books + created).sortedByDescending { it.modified }
            screen = Screen.BOOK_EDITOR
        }

    private fun openBook(book: Book, destination: Screen) =
        bookWork("Ouverture du Book…", { bookRepository.load(book.id) }) { loaded ->
            activeBook = loaded
            screen = destination
        }

    fun editBook(book: Book) = openBook(book, Screen.BOOK_EDITOR)

    fun readBook(book: Book) = openBook(book, Screen.BOOK_READER)

    fun closeBook() {
        activeBook = null
        screen = Screen.BOOKS
        refreshBooks()
    }

    private fun replaceBookInList(value: Book) {
        books = (books.filterNot { it.id == value.id } + value).sortedByDescending { it.modified }
    }

    private fun saveBook(value: Book, label: String = "Mise à jour du Book…") {
        val saved = value.copy(modified = System.currentTimeMillis())
        bookWork(label, {
            bookRepository.save(saved)
            saved
        }) {
            activeBook = it
            replaceBookInList(it)
        }
    }

    fun renameBook(name: String) {
        activeBook?.let { saveBook(it.copy(name = name.trim().take(100)), "Renommage du Book…") }
    }

    fun updateBookDescription(description: String) {
        activeBook?.let { saveBook(it.copy(description = description.take(2_000))) }
    }

    fun saveBookMetadata(name: String, description: String) {
        activeBook?.let {
            saveBook(
                it.copy(
                    name = name.trim().take(100).ifEmpty { "Nouveau Book" },
                    description = description.take(2_000),
                ),
                "Enregistrement du Book…",
            )
        }
    }

    fun finishBookEditing(name: String, description: String, read: Boolean = false) {
        val current = activeBook ?: return
        val next = current.copy(
            name = name.trim().take(100).ifEmpty { "Nouveau Book" },
            description = description.take(2_000),
            modified = System.currentTimeMillis(),
        )
        bookWork("Enregistrement du Book…", { bookRepository.save(next) }) {
            replaceBookInList(next)
            activeBook = if (read) next else null
            screen = if (read) Screen.BOOK_READER else Screen.BOOKS
        }
    }

    fun importBookPages(uris: List<Uri>, replaceId: String? = null) {
        val current = activeBook ?: return
        if (uris.isEmpty()) return
        if (replaceId == null && current.pages.size + uris.size > 500) {
            message = "500 pages maximum par Book."
            return
        }
        bookWork("Import des pages…", {
            val pages = current.pages.toMutableList()
            val removed = mutableListOf<BookPage>()
            val failures = mutableListOf<String>()
            uris.forEachIndexed { index, uri ->
                try {
                    val page = bookRepository.importPage(current, uri)
                    if (replaceId == null) pages += page
                    else {
                        val at = pages.indexOfFirst { it.id == replaceId }
                        require(at >= 0) { "Page introuvable." }
                        removed += pages[at]
                        pages[at] = page
                    }
                } catch (e: Exception) {
                    failures += "Image ${index + 1} : ${e.message}"
                }
            }
            val next =
                current.copy(
                    pages = pages,
                    currentPage = current.currentPage.coerceIn(0, pages.lastIndex.coerceAtLeast(0)),
                    modified = System.currentTimeMillis(),
                )
            bookRepository.save(next)
            removed.forEach { bookRepository.removeAssets(current, it.image, it.thumbnail) }
            next to failures
        }) { (updated, failures) ->
            activeBook = updated
            replaceBookInList(updated)
            if (failures.isNotEmpty()) message = failures.joinToString("\n")
        }
    }

    fun moveBookPage(index: Int, destination: Int) {
        val current = activeBook ?: return
        if (index !in current.pages.indices || destination !in current.pages.indices || index == destination)
            return
        val pages = current.pages.toMutableList().apply { add(destination, removeAt(index)) }
        val readingId = current.pages.getOrNull(current.safeCurrentPage)?.id
        saveBook(current.copy(pages = pages, currentPage = pages.indexOfFirst { it.id == readingId }.coerceAtLeast(0)))
    }

    fun removeBookPage(page: BookPage) {
        val current = activeBook ?: return
        val pages = current.pages.filterNot { it.id == page.id }
        val readingId = current.pages.getOrNull(current.safeCurrentPage)?.id
        val preservedPage = pages.indexOfFirst { it.id == readingId }
        val next =
            current.copy(
                pages = pages,
                currentPage = if (preservedPage >= 0) preservedPage else current.currentPage.coerceIn(0, pages.lastIndex.coerceAtLeast(0)),
                modified = System.currentTimeMillis(),
            )
        bookWork("Suppression de la page…", {
            bookRepository.save(next)
            bookRepository.removeAssets(current, page.image, page.thumbnail)
            next
        }) {
            activeBook = it
            replaceBookInList(it)
        }
    }

    fun importBookCover(uri: Uri) {
        val current = activeBook ?: return
        bookWork("Import de la couverture…", {
            val assets = bookRepository.importCover(current, uri)
            val next =
                current.copy(
                    coverImage = assets.image,
                    coverThumbnail = assets.thumbnail,
                    modified = System.currentTimeMillis(),
                )
            bookRepository.save(next)
            bookRepository.removeAssets(current, current.coverImage, current.coverThumbnail)
            next
        }) {
            activeBook = it
            replaceBookInList(it)
        }
    }

    fun clearBookCover() {
        val current = activeBook ?: return
        if (current.coverImage == null && current.coverThumbnail == null) return
        val next = current.copy(coverImage = null, coverThumbnail = null, modified = System.currentTimeMillis())
        bookWork("Suppression de la couverture…", {
            bookRepository.save(next)
            bookRepository.removeAssets(current, current.coverImage, current.coverThumbnail)
            next
        }) {
            activeBook = it
            replaceBookInList(it)
        }
    }

    fun setBookPage(index: Int) {
        if (busy != null || screen != Screen.BOOK_READER) return
        val current = activeBook ?: return
        val page = index.coerceIn(0, current.pages.lastIndex.coerceAtLeast(0))
        if (page == current.safeCurrentPage) return
        val next = current.copy(currentPage = page, modified = System.currentTimeMillis())
        activeBook = next
        replaceBookInList(next)
        viewModelScope.launch {
            try {
                withContext(bookIo) { bookRepository.save(next) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                message = e.message ?: "La position de lecture n’a pas été sauvegardée."
            }
        }
    }

    fun deleteBook(book: Book) =
        bookWork("Suppression du Book…", {
            bookRepository.delete(book)
            bookRepository.list().first
        }) {
            books = it
            if (activeBook?.id == book.id) activeBook = null
        }

    fun updateVideo(entry: VideoEntry) =
        work("Mise à jour de la vidéo…", { videoRepository.update(entry) }) { updated ->
            videos = (videos.filterNot { it.id == updated.id } + updated).sortedByDescending { it.modified }
        }

    fun deleteVideo(entry: VideoEntry) =
        work("Suppression de la vidéo…", {
            videoRepository.delete(entry)
            videoRepository.list()
        }) {
            videos = it
        }

    fun registerRecording(file: File, project: Project) {
        viewModelScope.launch {
            try {
                val entry = withContext(Dispatchers.IO) { videoRepository.register(file, project) }
                videos = (videos.filterNot { it.fileName == entry.fileName } + entry)
                    .sortedByDescending { it.modified }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                message = "Vidéo enregistrée, mais son index est incomplet : ${e.message}"
                refreshVideos()
            }
        }
    }

    fun newProject() {
        original = null
        draft = Project()
        screen = Screen.PREPARATION
    }

    fun updateDraft(transform: (Project) -> Project) {
        val activeId = draft.stages.getOrNull(draft.currentStep)?.id
        val updated = transform(draft)
        val preserved = activeId?.let { id -> updated.stages.indexOfFirst { it.id == id } } ?: -1
        draft =
            updated.copy(
                currentStep =
                    if (preserved >= 0) preserved
                    else updated.currentStep.coerceIn(0, updated.stages.lastIndex.coerceAtLeast(0)),
            )
    }

    fun renameDraftLayer(id: String, name: String) {
        updateDraft { p -> p.copy(stages = p.stages.map { if (it.id == id) it.copy(name = name.trim().take(100)) else it }) }
    }

    fun useAutomaticProjectCover() {
        val current = draft
        draft =
            current.copy(
                coverMode = ProjectCoverMode.AUTOMATIC,
                coverStageId = null,
                coverImage = null,
                coverThumbnail = null,
            )
        // Keep former assets: a discarded draft or the fallback atomic slot may still reference them.
    }

    fun useStageProjectCover(stageId: String) {
        val current = draft
        if (current.stages.none { it.id == stageId }) return
        draft =
            current.copy(
                coverMode = ProjectCoverMode.STAGE,
                coverStageId = stageId,
                coverImage = null,
                coverThumbnail = null,
            )
    }

    fun importProjectCover(uri: Uri) {
        val current = draft
        work("Import de l’image du projet…", {
            val assets = repository.importCover(current, uri)
            val next =
                current.copy(
                    coverMode = ProjectCoverMode.IMPORTED,
                    coverStageId = null,
                    coverImage = assets.image,
                    coverThumbnail = assets.thumbnail,
                )
            next
        }) {
            draft = it
        }
    }

    fun removeDraftStage(id: String) {
        val current = draft
        val activeId = current.stages.getOrNull(current.currentStep)?.id
        val stages = current.stages.filterNot { it.id == id }
        val activeIndex =
            activeId?.takeIf { it != id }?.let { kept -> stages.indexOfFirst { it.id == kept } }
                ?.takeIf { it >= 0 }
                ?: current.currentStep.coerceIn(0, stages.lastIndex.coerceAtLeast(0))
        draft =
            if (current.coverMode == ProjectCoverMode.STAGE && current.coverStageId == id)
                current.copy(
                    stages = stages,
                    currentStep = activeIndex,
                    coverMode = ProjectCoverMode.AUTOMATIC,
                    coverStageId = null,
                )
            else current.copy(
                stages = stages,
                currentStep = activeIndex,
            )
    }

    fun backFromPreparation() {
        screen = Screen.PROJECTS
        refresh()
    }

    fun prepare(project: Project) =
        work("Ouverture de la préparation…", { repository.load(project.id) }) {
            original = it
            draft = it.project
            screen = Screen.PREPARATION
        }

    /** 0.4 preserves masks by layer id and resamples them when the work area changes. */
    fun requiresReset() = false

    fun importUris(uris: List<Uri>, replaceId: String? = null) {
        val current = draft
        if (uris.isEmpty()) return
        if (replaceId == null && current.stages.size + uris.size > 20) {
            message = "20 étapes maximum. Aucune image de cette sélection n’a été importée."
            return
        }
        work(
            "Import des images…",
            {
                val stages = current.stages.toMutableList()
                val errors = mutableListOf<String>()
                var working = current
                for ((i, uri) in uris.withIndex()) try {
                    if (replaceId == null && stages.isEmpty()) {
                        val dimensions = repository.dimensions(uri)
                        working = working.copy(width = dimensions.first, height = dimensions.second,
                            camera = Camera(dimensions.first / 2f, dimensions.second / 2f))
                    }
                    val index =
                        replaceId?.let { id -> stages.indexOfFirst { it.id == id } } ?: stages.size
                    require(index >= 0)
                    val name =
                        if (replaceId != null) stages[index].name else defaultLayerName(stages.size)
                    val stage = repository.import(working, uri, name)
                    if (replaceId != null) stages[index] = stage else stages += stage
                } catch (e: Exception) {
                    errors += "Image ${i+1} : ${e.message}"
                }
                val coverWasReplaced = replaceId != null && current.coverStageId == replaceId
                working.copy(
                    stages = stages,
                    coverMode =
                        if (coverWasReplaced) ProjectCoverMode.AUTOMATIC else current.coverMode,
                    coverStageId = if (coverWasReplaced) null else current.coverStageId,
                ) to errors
            },
        ) { (p, errors) ->
            draft = p
            if (errors.isNotEmpty()) message = errors.joinToString("\n")
        }
    }

    fun enterPrepared(openStudio: Boolean = true) {
        val current = draft
        val previous = original
        work(
            if (openStudio) "Préparation de l’Atelier…" else "Enregistrement du projet…",
            {
                ProjectRules.validate(current.stages.size, current.width, current.height)
                val normalized = repository.resizeStages(current)
                val oldActiveId =
                    previous?.project?.stages?.getOrNull(previous.project.currentStep)?.id
                val activeIndex =
                    oldActiveId?.let { id -> normalized.stages.indexOfFirst { it.id == id } }
                        ?.takeIf { it >= 0 }
                        ?: normalized.currentStep.coerceIn(normalized.stages.indices)
                val layerEngines =
                    normalized.stages.associate { layer ->
                        val old = previous?.layerEngines?.get(layer.id)
                        layer.id to
                            if (old == null && previous == null && layer == normalized.stages.first())
                                emptyLayerSnapshot(normalized.width, normalized.height).copy(
                                    mask = ShortArray(normalized.width * normalized.height) { 1000 }
                                )
                            else if (old == null) emptyLayerSnapshot(normalized.width, normalized.height)
                            else resizeLayerSnapshot(old, normalized.width, normalized.height)
                    }
                val engine = layerEngines.getValue(normalized.stages[activeIndex].id)
                val dimensionsChanged =
                    previous != null &&
                        (previous.project.width != normalized.width ||
                            previous.project.height != normalized.height)
                val ready =
                    normalized.copy(
                        currentStep = activeIndex,
                        progress =
                            engine.mask.sumOf { it.toLong() }.toDouble() /
                                (engine.width.toLong() * engine.height * 1000),
                        overallProgress =
                            layerEngines.values.map { state ->
                                state.mask.sumOf { it.toLong() }.toDouble() /
                                    (state.width.toLong() * state.height * 1000)
                            }.average().takeIf { !it.isNaN() } ?: 0.0,
                        name = normalized.name.trim().ifEmpty { "Nouvelle révélation" },
                        camera =
                            if (previous == null || dimensionsChanged)
                                Camera(current.width / 2f, current.height / 2f)
                            else current.camera,
                        modified = System.currentTimeMillis(),
                    )
                repository.save(ready, layerEngines)
                val loaded = LoadedProject(ready, engine, layerEngines)
                loaded to if (openStudio) StudioSession(repository, loaded) else null
            },
        ) { (loaded, preparedSession) ->
            original = null
            if (openStudio) {
                session = preparedSession
                screen = Screen.STUDIO
            } else {
                projects = (projects.filterNot { p -> p.id == loaded.project.id } + loaded.project)
                    .sortedByDescending { p -> p.modified }
                screen = Screen.PROJECTS
            }
        }
    }

    fun open(project: Project) =
        work(
            "Ouverture de l’Atelier…",
            { StudioSession(repository, repository.load(project.id)) },
        ) {
            session = it
            screen = Screen.STUDIO
        }

    fun rename(p: Project, name: String) =
        work(
            "Renommage…",
            {
                repository.rename(p, name)
                repository.list().first
            },
        ) {
            projects = it
        }

    fun duplicate(p: Project) =
        work(
            "Duplication…",
            {
                repository.duplicate(p)
                repository.list().first
            },
        ) {
            projects = it
        }

    fun delete(p: Project) =
        work(
            "Suppression…",
            {
                repository.delete(p)
                repository.list().first
            },
        ) {
            projects = it
        }

    fun exitStudio(prepare: Boolean = false) {
        val surface = view ?: return
        if (busy != null) return
        busy = "Finalisation et sauvegarde…"
        surface.submit {
            surface.painter.recording?.stop()
            surface.painter.recording = null
            surface.session.flush { error ->
                Handler(Looper.getMainLooper()).post {
                    busy = null
                    if (error != null) {
                        surface.submit { surface.session.resume() }
                        message = error
                        return@post
                    }
                    val project = surface.session.project
                    surface.submit {
                        surface.painter.close()
                        surface.session.shutdown()
                    }
                    session = null
                    view = null
                    screen = Screen.PROJECTS
                    if (prepare) prepare(project) else refresh()
                    refreshVideos()
                }
            }
        }
    }

    fun exportVideo(file: File, uri: Uri) =
        work(
            "Export de la vidéo…",
            {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: error("Emplacement inaccessible.")
            },
        ) {
            message = "Vidéo exportée."
        }
}
