package studio.nuvora.veila.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import studio.nuvora.veila.VeilaModel
import studio.nuvora.veila.data.Book
import studio.nuvora.veila.data.BookPage
import studio.nuvora.veila.data.BookReaderPolicy

@Composable
fun BooksScreen(model: VeilaModel) {
    var create by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<Book?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 700.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 32.dp else 18.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallButton("‹", "Retour aux projets", onClick = model::leaveBooks)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("Books", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                    Text("Présentez vos illustrations page par page.", color = Muted, fontSize = 12.sp)
                }
                GlowButton("＋ Nouveau Book", { create = true }, Modifier.widthIn(min = 180.dp, max = 240.dp))
            }
            if (model.books.isEmpty())
                Box(
                    Modifier.weight(1f).fillMaxWidth().border(1.dp, Line, RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("BOOK", color = Violet, fontSize = 14.sp, letterSpacing = 2.sp)
                        Text("Vos images peuvent devenir un livre.", fontSize = 22.sp)
                        Text(
                            "Ajoutez des pages, une couverture et consultez-les avec un geste horizontal.",
                            color = Muted,
                        )
                    }
                }
            else
                LazyVerticalGrid(
                    GridCells.Adaptive(230.dp),
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 22.dp),
                ) {
                    gridItems(model.books, key = { it.id }) { book ->
                        var menu by remember { mutableStateOf(false) }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, Line),
                            color = Panel,
                        ) {
                            Column {
                                model.bookRepository.thumbnail(book)?.let { image ->
                                    LocalImage(
                                        image,
                                        "Couverture de ${book.name}",
                                        Modifier.fillMaxWidth().height(174.dp).clickable {
                                            model.readBook(book)
                                        },
                                    )
                                }
                                    ?: Box(
                                        Modifier.fillMaxWidth().height(174.dp).background(Ink),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("BOOK", color = Violet, letterSpacing = 2.sp) }
                                Column(Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            book.name,
                                            Modifier.weight(1f).clickable { model.readBook(book) },
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                        )
                                        Box {
                                            SmallButton("⋮", "Actions pour ${book.name}") { menu = true }
                                            DropdownMenu(menu, { menu = false }) {
                                                DropdownMenuItem(
                                                    text = { Text("Lire") },
                                                    onClick = {
                                                        menu = false
                                                        model.readBook(book)
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Modifier") },
                                                    onClick = {
                                                        menu = false
                                                        model.editBook(book)
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Supprimer") },
                                                    onClick = {
                                                        menu = false
                                                        remove = book
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        "${book.pages.size} page${if (book.pages.size > 1) "s" else ""}",
                                        color = Muted,
                                        fontSize = 12.sp,
                                    )
                                    Text(
                                        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(book.modified)),
                                        color = Muted,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }
    if (create) TextEditDialog("Nouveau Book", "", { create = false }) { model.createBook(it) }
    remove?.let { book ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Supprimer « ${book.name} » ?") },
            text = { Text("Le Book, sa couverture et ses pages seront supprimés de cet appareil.") },
            confirmButton = {
                TextButton({
                    remove = null
                    model.deleteBook(book)
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ remove = null }) { Text("Conserver") } },
        )
    }
}

@Composable
fun BookEditorScreen(model: VeilaModel) {
    val book = model.activeBook ?: return
    var title by rememberSaveable(book.id) { mutableStateOf(book.name) }
    var description by rememberSaveable(book.id) { mutableStateOf(book.description) }
    var preview by remember { mutableStateOf<BookPage?>(null) }
    var replace by remember { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf<BookPage?>(null) }
    val pages =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            model.importBookPages(uris)
        }
    val replacement =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { model.importBookPages(listOf(it), replace) }
        }
    val cover =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(model::importBookCover)
        }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallButton("‹", "Retour aux Books", onClick = model::closeBook)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text("Préparer le Book", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text("Les images restent indépendantes de l’Atelier.", color = Muted, fontSize = 12.sp)
            }
            OutlinedButton({ model.finishBookEditing(title, description) }, Modifier.heightIn(min = 48.dp)) { Text("Terminer") }
        }
        OutlinedTextField(title, { title = it.take(100) }, label = { Text("Nom du Book") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            description,
            { description = it.take(2_000) },
            label = { Text("Description facultative") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 92.dp),
            minLines = 2,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton({ model.saveBookMetadata(title, description) }, Modifier.heightIn(min = 48.dp)) {
                Text("Enregistrer")
            }
            OutlinedButton({ cover.launch(arrayOf("image/*")) }, Modifier.heightIn(min = 48.dp)) {
                Text("Couverture")
            }
            if (book.coverImage != null)
                TextButton(model::clearBookCover, Modifier.heightIn(min = 48.dp)) { Text("Retirer") }
            Spacer(Modifier.weight(1f))
            Button({ model.finishBookEditing(title, description, read = true) }, Modifier.heightIn(min = 48.dp)) { Text("Lire") }
        }
        model.bookRepository.thumbnail(book)?.let { image ->
            LocalImage(
                image,
                "Aperçu de couverture",
                Modifier.fillMaxWidth().height(88.dp).padding(top = 8.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("PAGES · ${book.pages.size}", color = Cyan, fontSize = 12.sp, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            GlowButton("＋ Ajouter", { pages.launch(arrayOf("image/*")) }, Modifier.widthIn(min = 150.dp, max = 220.dp))
        }
        if (book.pages.isEmpty())
            Box(
                Modifier.weight(1f).fillMaxWidth().border(1.dp, Line, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("Ajoutez les pages de votre Book.", color = Muted) }
        else
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                book.pages.forEachIndexed { index, page ->
                    item(key = page.id) {
                    Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Line), color = Panel) {
                        Row(
                            Modifier.fillMaxWidth().padding(9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("%03d".format(index + 1), color = Muted, fontSize = 12.sp)
                            LocalImage(
                                model.bookRepository.asset(book, page.thumbnail),
                                "Miniature page ${index + 1}",
                                Modifier.size(58.dp).clickable { preview = page },
                            )
                            Column(Modifier.weight(1f)) {
                                Text("Page ${index + 1}", fontWeight = FontWeight.Medium)
                                Text("${page.width} × ${page.height}", color = Muted, fontSize = 11.sp)
                            }
                            SmallButton("↑", "Monter la page ${index + 1}", index > 0) {
                                model.moveBookPage(index, index - 1)
                            }
                            SmallButton("↓", "Descendre la page ${index + 1}", index < book.pages.lastIndex) {
                                model.moveBookPage(index, index + 1)
                            }
                            Box {
                                var menu by remember { mutableStateOf(false) }
                                SmallButton("⋮", "Actions de la page ${index + 1}") { menu = true }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Ouvrir en grand") },
                                        onClick = { menu = false; preview = page },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Remplacer") },
                                        onClick = {
                                            menu = false
                                            replace = page.id
                                            replacement.launch(arrayOf("image/*"))
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Supprimer") },
                                        onClick = {
                                            menu = false
                                            remove = page
                                        },
                                    )
                                }
                            }
                        }
                    }
                    }
                }
            }
    }
    preview?.let { page ->
        BookPagePreview(model, book, page) { preview = null }
    }
    remove?.let { page ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Supprimer cette page ?") },
            text = { Text("Son image sera retirée du Book.") },
            confirmButton = {
                TextButton({
                    remove = null
                    model.removeBookPage(page)
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ remove = null }) { Text("Conserver") } },
        )
    }
}

@Composable
fun BookReaderScreen(model: VeilaModel) {
    val book = model.activeBook ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val doublePage = BookReaderPolicy.usesDoublePage(maxWidth.value, maxHeight.value, book.pages.size)
        val stride = BookReaderPolicy.stride(doublePage, book.pages.size)
        val density = LocalDensity.current.density
        var preview by remember { mutableStateOf<BookPage?>(null) }
        fun go(delta: Int) =
            model.setBookPage(
                BookReaderPolicy.pageAfterTurn(book.safeCurrentPage, delta, book.pages.size)
            )
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallButton("‹", "Retour aux Books", onClick = model::closeBook)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(book.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        if (book.pages.isEmpty()) "Aucune page" else "Page ${book.safeCurrentPage + 1} / ${book.pages.size}",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
                TextButton({ model.editBook(book) }, Modifier.heightIn(min = 48.dp)) { Text("Modifier") }
            }
            if (book.pages.isEmpty())
                Box(
                    Modifier.weight(1f).fillMaxWidth().border(1.dp, Line, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("Ce Book ne possède pas encore de page.", color = Muted) }
            else
                Box(
                    Modifier.weight(1f).fillMaxWidth().pointerInput(book.id, book.safeCurrentPage, doublePage, book.pages.size, density) {
                        awaitEachGesture {
                            val first = awaitFirstDown(requireUnconsumed = false)
                            val origin = first.position
                            var end = origin
                            var multiple = false
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed || it.previousPressed } > 1) multiple = true
                                event.changes.firstOrNull { it.id == first.id }?.let {
                                    end = it.position
                                    if (it.pressed) it.consume()
                                }
                            } while (event.changes.any { it.pressed })
                            val drag = (end - origin) / density
                            if (!multiple && kotlin.math.abs(drag.x) > kotlin.math.abs(drag.y)) {
                                model.setBookPage(
                                    BookReaderPolicy.pageAfterSwipe(
                                        book.safeCurrentPage,
                                        drag.x,
                                        doublePage,
                                        book.pages.size,
                                    )
                                )
                            }
                        }
                    },
                ) {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BookPageView(model, book, book.safeCurrentPage, Modifier.weight(1f).fillMaxHeight())
                        if (doublePage)
                            BookPageView(
                                model,
                                book,
                                book.safeCurrentPage + 1,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                    }
                }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton({ go(-stride) }, enabled = book.safeCurrentPage > 0, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("‹ Précédente")
                }
                Text(
                    if (doublePage) "Glissez à un doigt pour tourner deux pages" else "Glissez à un doigt pour tourner la page",
                    color = Muted,
                    fontSize = 11.sp,
                )
                OutlinedButton(
                    { go(stride) },
                    enabled = book.safeCurrentPage + stride < book.pages.size,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Suivante ›") }
            }
            TextButton(
                { preview = book.pages.getOrNull(book.safeCurrentPage) },
                enabled = book.pages.isNotEmpty(),
                modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
            ) { Text("Ouvrir la page en grand") }
        }
        preview?.let { page -> BookPagePreview(model, book, page) { preview = null } }
    }
}

@Composable
private fun BookPagePreview(model: VeilaModel, book: Book, page: BookPage, close: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = close,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = Ink) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${book.name} · Page ${book.pages.indexOf(page) + 1}", Modifier.weight(1f))
                    SmallButton("×", "Fermer la page", onClick = close)
                }
                LocalImage(model.bookRepository.asset(book, page.image), "Page en grand", Modifier.weight(1f).fillMaxWidth(), preview = true)
            }
        }
    }
}

@Composable
private fun BookPageView(model: VeilaModel, book: Book, index: Int, modifier: Modifier) {
    val page = book.pages.getOrNull(index)
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = Color(0xFF0B0F1B), border = BorderStroke(1.dp, Line)) {
        if (page == null)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Fin du Book", color = Muted)
            }
        else
            LocalImage(
                model.bookRepository.asset(book, page.image),
                "Page ${index + 1} de ${book.name}",
                Modifier.fillMaxSize(),
                preview = true,
            )
    }
}
