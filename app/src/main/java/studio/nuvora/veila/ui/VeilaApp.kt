package studio.nuvora.veila.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import studio.nuvora.veila.*

@Composable
fun VeilaApp(model: VeilaModel, activity: Activity) {
    MaterialTheme(colorScheme = VeilaColors) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                when (model.screen) {
                    Screen.PROJECTS -> ProjectsScreen(model)
                    Screen.PREPARATION -> PreparationScreen(model)
                    Screen.STUDIO -> model.session?.let { StudioScreen(model, it, activity) }
                    Screen.BOOKS -> BooksScreen(model)
                    Screen.BOOK_EDITOR -> BookEditorScreen(model)
                    Screen.BOOK_READER -> BookReaderScreen(model)
                    Screen.VIDEOS -> VideoLibraryScreen(model)
                }
                model.busy?.let {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(it) },
                        text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
                        confirmButton = {},
                    )
                }
                model.message?.let { message ->
                    AlertDialog(
                        onDismissRequest = { model.message = null },
                        title = { Text("Veila Paint") },
                        text = { Text(message) },
                        confirmButton = { TextButton({ model.message = null }) { Text("Compris") } },
                    )
                }
            }
        }
    }
    BackHandler(model.screen == Screen.PREPARATION && model.busy == null) {
        model.backFromPreparation()
    }
    BackHandler(model.screen == Screen.STUDIO && model.busy == null) { model.exitStudio() }
    BackHandler(
        (model.screen == Screen.BOOK_EDITOR || model.screen == Screen.BOOK_READER) &&
            model.busy == null
    ) { model.closeBook() }
    BackHandler(model.screen == Screen.BOOKS && model.busy == null) { model.leaveBooks() }
    BackHandler(model.screen == Screen.VIDEOS && model.busy == null) { model.leaveVideos() }
}
