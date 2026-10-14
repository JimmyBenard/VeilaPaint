package studio.nuvora.veila.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import studio.nuvora.veila.VeilaModel
import studio.nuvora.veila.core.VideoSpeed
import studio.nuvora.veila.data.VideoEntry
import studio.nuvora.veila.video.VideoEditState

@Composable
fun VideoLibrary(model: VeilaModel, close: () -> Unit) {
    val edit by model.videoEditor.state.collectAsState()
    if (edit.source != null) {
        VideoEditorDialog(model, edit) { model.videoEditor.close() }
        return
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Vidéos de l’Atelier") },
        text = {
            Box(Modifier.heightIn(max = 500.dp)) { VideoLibraryContent(model) }
        },
        confirmButton = { TextButton(close) { Text("Fermer") } },
    )
}

@Composable
fun VideoLibraryScreen(model: VeilaModel) {
    val edit by model.videoEditor.state.collectAsState()
    if (edit.source != null) {
        VideoEditorDialog(model, edit) { model.videoEditor.close() }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallButton("‹", "Retour aux projets", onClick = model::leaveVideos)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text("Vidéos", fontSize = 25.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text("Vos enregistrements de l’Atelier.", color = Muted, fontSize = 12.sp)
            }
        }
        VideoLibraryContent(model, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun VideoLibraryContent(model: VeilaModel, modifier: Modifier = Modifier) {
    var edit by remember { mutableStateOf<VideoEntry?>(null) }
    var remove by remember { mutableStateOf<VideoEntry?>(null) }
    if (model.videos.isEmpty())
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Vos enregistrements de l’Atelier apparaîtront ici.", color = Muted)
        }
    else
        LazyColumn(
            modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            items(model.videos, key = { it.id }) { entry ->
                val source = model.videoRepository.file(entry)
                Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Line), color = Panel) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        model.videoRepository.thumbnail(entry)?.let { thumbnail ->
                            LocalImage(
                                thumbnail,
                                "Miniature de ${entry.name}",
                                Modifier.size(width = 116.dp, height = 72.dp),
                            )
                        }
                            ?: Box(
                                Modifier.size(width = 116.dp, height = 72.dp).background(Ink),
                                contentAlignment = Alignment.Center,
                            ) { Text("▶", color = Cyan, fontSize = 28.sp) }
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 2)
                            val project = model.session?.project?.takeIf { it.id == entry.projectId }?.name
                                ?: model.projects.firstOrNull { it.id == entry.projectId }?.name
                            Text("Projet : ${project ?: if (entry.projectId == null) "Non associé" else "Projet supprimé ou indisponible"}", color = Muted, fontSize = 12.sp)
                            Text(
                                "${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(entry.created))} · ${videoDuration(entry.durationMillis)} · ${if (entry.width > 0) "${entry.width} × ${entry.height}" else "Format inconnu"}",
                                color = Muted,
                                fontSize = 11.sp,
                            )
                            Text(
                                when {
                                    !source.isFile -> "Fichier vidéo absent"
                                    entry.durationMillis <= 0 || entry.width <= 0 -> "${fileSize(source.length())} · format illisible ou non reconnu"
                                    else -> "${fileSize(source.length())} · prêt à lire"
                                },
                                color = if (source.isFile && entry.durationMillis > 0) Muted else MaterialTheme.colorScheme.error,
                                fontSize = 11.sp,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    { model.videoEditor.open(source) },
                                    enabled = source.isFile,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Lire / aperçu") }
                                TextButton({ edit = entry }, Modifier.heightIn(min = 48.dp)) { Text("Modifier") }
                                TextButton({ remove = entry }, Modifier.heightIn(min = 48.dp)) {
                                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    edit?.let { entry ->
        VideoMetadataDialog(model, entry) { edit = null }
    }
    remove?.let { entry ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Supprimer définitivement cette vidéo ?") },
            text = { Text("Le fichier MP4 et sa miniature seront supprimés du stockage Veila Paint.") },
            confirmButton = {
                TextButton({
                    remove = null
                    model.deleteVideo(entry)
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ remove = null }) { Text("Conserver") } },
        )
    }
}

@Composable
private fun VideoMetadataDialog(model: VeilaModel, entry: VideoEntry, close: () -> Unit) {
    var name by remember(entry.id) { mutableStateOf(entry.name) }
    var description by remember(entry.id) { mutableStateOf(entry.description) }
    var projects by remember { mutableStateOf(false) }
    var projectId by remember(entry.id) { mutableStateOf(entry.projectId) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Modifier la vidéo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text("Nom") }, singleLine = true)
                OutlinedTextField(
                    description,
                    { description = it.take(2_000) },
                    label = { Text("Description facultative") },
                    minLines = 2,
                )
                Box {
                    OutlinedButton({ projects = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(
                            model.projects.firstOrNull { it.id == projectId }?.name ?: "Projet non associé"
                        )
                    }
                    DropdownMenu(projects, { projects = false }) {
                        DropdownMenuItem(
                            text = { Text("Aucun projet") },
                            onClick = {
                                projectId = null
                                projects = false
                            },
                        )
                        model.projects.forEach { project ->
                            DropdownMenuItem(
                                text = { Text(project.name) },
                                onClick = {
                                    projectId = project.id
                                    projects = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                model.updateVideo(entry.copy(name = name, description = description, projectId = projectId))
                close()
            }, enabled = name.isNotBlank()) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(close) { Text("Annuler") } },
    )
}

@Composable
private fun VideoEditorDialog(model: VeilaModel, state: VideoEditState, close: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var input by
        rememberSaveable(state.source?.path, state.speed) { mutableStateOf(state.speed.toString()) }
    var playerError by remember(state.preview) { mutableStateOf<String?>(null) }
    var expandedPreview by rememberSaveable { mutableStateOf(false) }
    val inputValid = input.toIntOrNull() in VideoSpeed.MIN..VideoSpeed.MAX
    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri
            ->
            if (uri != null) exportPath?.let { model.exportVideo(File(it), uri) }
        }
    DisposableEffect(lifecycle, model.videoEditor) {
        val observer = LifecycleEventObserver { _, event ->
            if (
                event == Lifecycle.Event.ON_STOP &&
                    (context as? Activity)?.isChangingConfigurations != true
            )
                model.videoEditor.background()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = Panel) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val previewHeight =
                    if (expandedPreview) (maxHeight - 110.dp).coerceAtLeast(240.dp)
                    else (maxHeight * .48f).coerceIn(130.dp, 460.dp)
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Aperçu avant export", fontSize = 22.sp)
                            Text(
                                state.source?.name.orEmpty(),
                                color = Muted,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                        TextButton(
                            onClick = { expandedPreview = !expandedPreview },
                            enabled = state.preview != null,
                        ) { Text(if (expandedPreview) "Réduire" else "Agrandir") }
                        SmallButton("×", "Fermer l’aperçu vidéo", onClick = close)
                    }
                    Box(
                        Modifier.fillMaxWidth()
                            .height(previewHeight)
                            .background(Color.Black, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val preview = state.preview
                        if (preview != null) {
                            VideoPreview(preview, Modifier.fillMaxSize()) { playerError = it }
                        } else {
                            Column(
                                Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (state.preparing || state.info == null && state.error == null)
                                    CircularProgressIndicator(color = Cyan)
                                Text(
                                    if (state.preparing) "Préparation de l’aperçu ×${state.speed}…"
                                    else if (state.info == null) "Chargement de la vidéo…"
                                    else "Préparez l’aperçu pour voir la vitesse choisie.",
                                    color = Muted,
                                )
                            }
                        }
                    }
                    Text("Vitesse ×${state.speed}", color = Cyan, fontSize = 20.sp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Slider(
                            state.speed.toFloat(),
                            { model.videoEditor.setSpeed(it.roundToInt()) },
                            valueRange = 1f..500f,
                            steps = 498,
                            enabled = !state.preparing,
                            modifier =
                                Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                    contentDescription = "Vitesse de la vidéo, de 1 à 500"
                                },
                        )
                        OutlinedTextField(
                            input,
                            { value ->
                                if (value.length <= 3 && value.all(Char::isDigit)) {
                                    input = value
                                    value
                                        .toIntOrNull()
                                        ?.takeIf { it in 1..500 }
                                        ?.let { model.videoEditor.setSpeed(it) }
                                }
                            },
                            label = { Text("×1 à ×500") },
                            enabled = !state.preparing,
                            isError = !inputValid,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(125.dp),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(1, 2, 10, 25, 50, 100, 200, 500).forEach { speed ->
                            FilterChip(
                                state.speed == speed,
                                { model.videoEditor.setSpeed(speed) },
                                label = { Text("×$speed") },
                                enabled = !state.preparing,
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                    state.info?.let { info ->
                        Text(
                            "Original : ${videoDuration(info.duration)}  ·  Après accélération : ${videoDuration(VideoSpeed.durationMillis(info.duration, state.speed))} environ",
                            color = Muted,
                            fontSize = 13.sp,
                        )
                    }
                    if (state.preparing) {
                        if (state.progress != null)
                            LinearProgressIndicator(
                                progress = { state.progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = Cyan,
                            )
                        else
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = Cyan,
                            )
                        TextButton({ model.videoEditor.cancel() }) {
                            Text("Annuler la préparation")
                        }
                    } else if (state.preview == null && state.info != null) {
                        Button(
                            { model.videoEditor.prepare() },
                            enabled = inputValid,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Préparer l’aperçu ×${state.speed}")
                        }
                    }
                    (state.error ?: playerError)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "L’export reprend exactement cette version. Touchez l’aperçu pour afficher lecture, pause et déplacement dans la vidéo.",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            {
                                state.preview?.let {
                                    exportPath = it.absolutePath
                                    export.launch(state.exportName)
                                }
                            },
                            enabled = state.preview != null && inputValid && playerError == null,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Exporter le MP4")
                        }
                        OutlinedButton(
                            {
                                state.preview?.let { file ->
                                    try {
                                        val uri =
                                            FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.files",
                                                file,
                                            )
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND)
                                                    .setType("video/mp4")
                                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                                    .addFlags(
                                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                    ),
                                                "Partager la vidéo",
                                            )
                                        )
                                    } catch (e: Exception) {
                                        playerError = "Partage indisponible : ${e.message}"
                                    }
                                }
                            },
                            enabled = state.preview != null && inputValid && playerError == null,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Partager")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoPreview(file: File, modifier: Modifier, error: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val onError by rememberUpdatedState(error)
    var position by rememberSaveable(file.path) { mutableIntStateOf(0) }
    val player =
        remember(file.path, context) {
            VideoView(context).apply {
                contentDescription = "Aperçu vidéo avec commandes de lecture"
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setOnPreparedListener { media ->
                    media.isLooping = false
                    seekTo(position.coerceAtMost(duration.coerceAtLeast(0)))
                    // Render the first frame and leave playback under the user's control.
                    if (position == 0) seekTo(1)
                }
                setOnErrorListener { _, what, extra ->
                    onError("Lecture indisponible sur cet appareil ($what/$extra).")
                    true
                }
                setVideoURI(Uri.fromFile(file))
            }
        }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                position = player.currentPosition
                player.pause()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            position = player.currentPosition
            player.stopPlayback()
            lifecycle.removeObserver(observer)
        }
    }
    AndroidView(factory = { player }, modifier = modifier)
}

private fun videoDuration(ms: Long): String =
    if (ms <= 0) "Durée inconnue"
    else if (ms < 60_000) String.format(Locale.FRANCE, "%.2f s", ms / 1000.0)
    else "%d min %02d s".format(ms / 60_000, ms / 1000 % 60)

private fun fileSize(bytes: Long): String =
    when {
        bytes >= 1_000_000_000L -> String.format(Locale.FRANCE, "%.2f Go", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format(Locale.FRANCE, "%.1f Mo", bytes / 1_000_000.0)
        bytes >= 1_000L -> String.format(Locale.FRANCE, "%.0f Ko", bytes / 1_000.0)
        else -> "$bytes o"
    }
