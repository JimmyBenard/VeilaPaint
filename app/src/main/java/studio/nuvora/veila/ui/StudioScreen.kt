package studio.nuvora.veila.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import android.view.WindowManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import studio.nuvora.veila.*
import studio.nuvora.veila.core.RecordingState
import studio.nuvora.veila.studio.*

enum class StudioPanel {
    STEPS,
    BRUSH,
    SETTINGS,
}

@Composable
fun StudioScreen(model: VeilaModel, session: StudioSession, activity: Activity) {
    val status by session.status.collectAsState()
    val surface = remember(session) { RevealView(activity, session).also { model.view = it } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val root = LocalView.current
    var panel by remember { mutableStateOf<StudioPanel?>(null) }
    var videos by rememberSaveable { mutableStateOf(false) }
    var confirmTimeReset by remember { mutableStateOf(false) }
    var confirmDateReset by remember { mutableStateOf(false) }
    var confirmLeaveRecording by remember { mutableStateOf(false) }
    fun requestExit() {
        if (status.recording == RecordingState.RECORDING) confirmLeaveRecording = true
        else model.exitStudio()
    }
    BackHandler { requestExit() }
    fun select(which: StudioPanel) {
        surface.submit {
            session.engine.end()
            session.router.reset()
            session.save()
        }
        panel = if (panel == which) null else which
    }
    DisposableEffect(surface, lifecycle) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    surface.onResume()
                    surface.submit { session.resume() }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    surface.submit {
                        surface.painter.recording?.stop(
                            "Vidéo finalisée à la mise en arrière-plan."
                        )
                        surface.painter.recording = null
                        session.pause()
                    }
                    surface.onPause()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            surface.onResume()
            surface.submit { session.resume() }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            surface.submit { surface.painter.close() }
            surface.onPause()
            if (model.view === surface) model.view = null
        }
    }
    LaunchedEffect(surface, status.project.workSpeed) {
        while (true) {
            delay((1000f / status.project.workSpeed).toLong().coerceAtLeast(50L))
            surface.submit { session.tick() }
        }
    }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth().background(Panel)) {
            val narrow = maxWidth < 700.dp
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SmallButton("‹", "Quitter et sauvegarder le projet") { requestExit() }
                    if (!narrow) Brand(compact = true)
                    Column(Modifier.weight(1f)) {
                        Text(
                            status.project.name,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            fontSize = 14.sp,
                        )
                        Text(
                            "Calque ${status.step+1} / ${status.project.stages.size} · ${status.project.stages[status.step].name}",
                            color = Muted,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                        Text("par Nuvora Studio", color = Muted, fontSize = 10.sp, letterSpacing = .5.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        when {
                            shouldShowRevealProgress(status.recording, status.progressVisible) ->
                                Text(
                                    percent(status.progress),
                                    color = Cyan,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            status.recording == RecordingState.PAUSED ->
                                Text("Ⅱ PAUSE", color = Color(0xFFFF8CB5), fontSize = 14.sp)
                            status.recording == RecordingState.RECORDING ->
                                Text("● REC", color = Color(0xFFFF8CB5), fontSize = 14.sp)
                        }
                        Text(
                            duration(status.time),
                            color = Cyan,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier =
                                Modifier.clickable { confirmTimeReset = true }.semantics {
                                    contentDescription = "Réinitialiser le temps simulé"
                                },
                        )
                        Text(
                            formatAtelierDateTime(activity, status.simulatedTime),
                            color = Cyan,
                            fontSize = 10.sp,
                            maxLines = 1,
                            modifier =
                                Modifier.clickable { confirmDateReset = true }.semantics {
                                    contentDescription = "Réinitialiser la date simulée"
                                },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SmallButton("↶", "Annuler le dernier trait", status.canUndo) {
                        surface.submit { session.undo() }
                    }
                    SmallButton("↷", "Rétablir le trait", status.canRedo) {
                        surface.submit { session.redo() }
                    }
                    TextButton({ select(StudioPanel.STEPS) }, Modifier.heightIn(min = 48.dp)) {
                        Text("Calques")
                    }
                    TextButton({ select(StudioPanel.BRUSH) }, Modifier.heightIn(min = 48.dp)) {
                        Text("Pinceau")
                    }
                    TextButton({ select(StudioPanel.SETTINGS) }, Modifier.heightIn(min = 48.dp)) {
                        Text("Paramètres")
                    }
                    VerticalDivider(Modifier.height(24.dp), color = Line)
                    FilterChip(
                        selected = status.project.eraserEnabled,
                        onClick = { surface.submit { session.setEraserEnabled(!status.project.eraserEnabled) } },
                        label = { Text("Gomme") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    if (status.recording == RecordingState.IDLE)
                        OutlinedButton(
                            {
                                surface.submit {
                                    try {
                                        session.engine.end()
                                        session.save()
                                        val recorder =
                                            StudioRecorder(activity, root, surface) {
                                                model.registerRecording(it, session.project)
                                            }
                                        recorder.start()
                                        surface.painter.recording = recorder
                                    } catch (e: Exception) {
                                        session.videoMessage =
                                            "Enregistrement indisponible : ${e.message}"
                                        session.publish()
                                    }
                                }
                            },
                            enabled =
                                status.ready &&
                                    !status.progressVisible &&
                                    !status.unrevealedOverlayVisible,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("● Enregistrer", color = Color(0xFFFF8CB5))
                        }
                    else {
                        OutlinedButton(
                            {
                                surface.submit {
                                    try {
                                        session.toggleRecordingByUser()
                                    } catch (e: Exception) {
                                        session.videoMessage = "Vidéo : ${e.message}"
                                        session.publish()
                                    }
                                }
                            },
                            enabled = status.recording != RecordingState.STOPPING,
                        ) {
                            Text(
                                if (status.recording == RecordingState.PAUSED) "Reprendre"
                                else "Pause"
                            )
                        }
                        OutlinedButton(
                            {
                                surface.submit {
                                    surface.painter.recording?.stop()
                                    surface.painter.recording = null
                                }
                            },
                            enabled = status.recording != RecordingState.STOPPING,
                        ) {
                            Text("Arrêter")
                        }
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
            if (!status.ready && status.error == null)
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(12.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = Panel,
                ) {
                    Text("Chargement des images…", Modifier.padding(12.dp), fontSize = 12.sp)
                }
            OutlinedButton(
                {
                    surface.submit {
                        session.camera.centerX = session.project.width / 2f
                        session.camera.centerY = session.project.height / 2f
                        session.camera.zoom = 1f
                        session.camera.rotation = 0f
                        session.save()
                    }
                },
                Modifier.align(Alignment.BottomStart).padding(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Panel),
            ) {
                Text("Recentrer · ${Math.toDegrees(status.rotation.toDouble()).toInt()}°")
            }
            panel?.let { selected ->
                val panelWidth = minOf(360.dp, maxWidth - 24.dp)
                Surface(
                    Modifier.align(Alignment.CenterEnd).width(panelWidth).fillMaxHeight(),
                    color = Panel,
                    border = BorderStroke(1.dp, Line),
                    shadowElevation = 12.dp,
                ) {
                    Column(
                        Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (selected) {
                                    StudioPanel.STEPS -> "Calques"
                                    StudioPanel.BRUSH -> "Pinceau"
                                    StudioPanel.SETTINGS -> "Paramètres"
                                },
                                Modifier.weight(1f),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            SmallButton("×", "Fermer le panneau") { panel = null }
                        }
                        when (selected) {
                            StudioPanel.STEPS -> StepsPanel(model, status)
                            StudioPanel.BRUSH -> BrushPanel(surface, status)
                            StudioPanel.SETTINGS ->
                                SettingsPanel(
                                    surface,
                                    status,
                                    { model.exitStudio(prepare = true) },
                                    {
                                        videos = true
                                        model.refreshVideos()
                                    },
                                )
                        }
                    }
                }
            }
            status.error?.let { error ->
                Surface(
                    Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 480.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = Panel,
                ) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        Text("Le dernier état valide reste conservé.", color = Muted)
                        Button({ surface.submit { session.save() } }) {
                            Text("Réessayer la sauvegarde")
                        }
                        TextButton({ model.exitStudio(prepare = true) }) {
                            Text("Revenir à la préparation")
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (status.drawing) "Trait en cours…"
                else if (status.saving) "Sauvegarde…" else "Enregistré",
                color = if (status.saving) Muted else Cyan,
                fontSize = 10.sp,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                if (status.video.isNotBlank()) status.video
                else if (status.project.fingerDrawingEnabled) "1 doigt ou stylet : dévoiler  ·  2 : naviguer  ·  3 : annuler  ·  4 : rétablir"
                else "Stylet : dévoiler  ·  2 doigts : naviguer  ·  3 : annuler  ·  4 : rétablir",
                color = Muted,
                fontSize = 10.sp,
                maxLines = 2,
            )
        }
    }
    if (videos) VideoLibrary(model) { videos = false }
    if (confirmTimeReset) {
        AlertDialog(
            onDismissRequest = { confirmTimeReset = false },
            title = { Text("Réinitialiser le temps simulé ?") },
            text = { Text("Le chronomètre reviendra à 00:00:00. La vitesse actuelle sera conservée.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmTimeReset = false
                    surface.submit { session.resetWorkTime() }
                }) { Text("Réinitialiser") }
            },
            dismissButton = { TextButton(onClick = { confirmTimeReset = false }) { Text("Annuler") } },
        )
    }
    if (confirmDateReset) {
        AlertDialog(
            onDismissRequest = { confirmDateReset = false },
            title = { Text("Réinitialiser la date simulée ?") },
            text = { Text("La date et l’heure seront alignées sur la tablette. Elles continueront ensuite à suivre la vitesse simulée.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDateReset = false
                    surface.submit { session.resetSimulatedDate() }
                }) { Text("Réinitialiser") }
            },
            dismissButton = { TextButton(onClick = { confirmDateReset = false }) { Text("Annuler") } },
        )
    }
    if (confirmLeaveRecording) {
        AlertDialog(
            onDismissRequest = { confirmLeaveRecording = false },
            title = { Text("Finaliser l’enregistrement ?") },
            text = {
                Text(
                    "Quitter l’Atelier finalise et sauvegarde la vidéo en cours. " +
                        "La capture ne peut pas continuer de façon fiable en arrière-plan."
                )
            },
            confirmButton = {
                TextButton({
                    confirmLeaveRecording = false
                    model.exitStudio()
                }) { Text("Finaliser et quitter") }
            },
            dismissButton = { TextButton({ confirmLeaveRecording = false }) { Text("Continuer") } },
        )
    }
}

@Composable
private fun StepsPanel(model: VeilaModel, status: StudioStatus) {
    var naming by remember { mutableStateOf<Pair<Int, String>?>(null) }
    Text(
        "Le calque actif est l’unique cible du pinceau. Les autres masques restent intacts.",
        color = Muted,
        fontSize = 12.sp,
    )
    status.project.stages.forEachIndexed { index, stage ->
        Row(
            Modifier.fillMaxWidth()
                .border(1.dp, if (index == status.step) Violet else Line, RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val layerProgress = status.layerProgress[stage.id] ?: 0.0
            Text(
                if (index == status.step) "●" else if (layerProgress >= 1.0) "✓" else "○",
                color = if (index == status.step || layerProgress >= 1.0) Cyan else Muted,
            )
            Column(Modifier.weight(1f).clickable { naming = index to stage.name }) {
                Text("%02d  %s".format(index + 1, stage.name), fontSize = 13.sp)
                if (index == status.step && shouldShowRevealProgress(status.recording, status.progressVisible))
                    Text(percent(status.progress), color = Cyan, fontSize = 12.sp)
            }
            TextButton(onClick = { model.view?.submit { model.view?.session?.selectLayer(index) } }) { Text("Ouvrir") }
        }
    }
    OutlinedButton(
        onClick = { model.view?.submit { model.view?.session?.resetActiveLayer() } },
        enabled = status.progress > 0.0,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text("Reset · masquer le calque actif")
    }
    naming?.let { (index, name) ->
        LayerNameDialog(
            "Nom du calque", name, model.layerNames, { naming = null },
            { next -> model.view?.submit { model.view?.session?.renameLayer(index, next) } },
            model::addLayerName, model::renameLayerName, model::deleteLayerName,
        )
    }
}

@Composable
private fun BrushPanel(view: RevealView, status: StudioStatus) {
    val brush = status.project.brush
    var sizeInput by remember(brush.size) { mutableStateOf(brush.size.toInt().toString()) }
    fun commitBrushSize() {
        val valid = (sizeInput.toIntOrNull() ?: brush.size.toInt()).coerceIn(1, 1600)
        sizeInput = valid.toString()
        view.submit { view.session.setBrushSize(valid.toFloat()) }
    }
    Text(
        "Chaque nouveau trait ajoute de la transparence. Le mode Gomme la retire du masque du calque actif.",
        color = Muted,
        fontSize = 12.sp,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Taille", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = sizeInput,
            onValueChange = { entered ->
                if (entered.length <= 4 && entered.all(Char::isDigit)) sizeInput = entered
            },
            modifier = Modifier.width(116.dp).onFocusChanged { focus -> if (!focus.isFocused) commitBrushSize() },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commitBrushSize() }),
            suffix = { Text("px", color = Muted, fontSize = 12.sp) },
        )
    }
    SettingSlider("Curseur de taille", "${brush.size.toInt()} px", brush.size, 1f..1600f) { v ->
        view.submit { view.session.setBrushSize(v) }
    }
    SettingSlider(
        "Intensité",
        "${(brush.intensity*100).toInt()} %",
        brush.intensity,
        .01f..1f,
        98,
        true,
    ) { v ->
        view.submit {
            view.session.project = view.session.project.copy(brush = brush.copy(intensity = v))
            view.session.publish()
        }
    }
    SettingSlider("Dureté", "${(brush.hardness*100).toInt()} %", brush.hardness, 0f..1f) { v ->
        view.submit {
            view.session.project = view.session.project.copy(brush = brush.copy(hardness = v))
            view.session.publish()
        }
    }
    SwitchRow("Pression → taille", brush.pressureSize) { v ->
        view.submit {
            view.session.project = view.session.project.copy(brush = brush.copy(pressureSize = v))
            view.session.save()
        }
    }
    SwitchRow("Pression → intensité", brush.pressureIntensity) { v ->
        view.submit {
            view.session.project =
                view.session.project.copy(brush = brush.copy(pressureIntensity = v))
            view.session.save()
        }
    }
    HorizontalDivider(color = Line)
    Text("ASSISTANCE DIRECTIONNELLE", color = Cyan, fontSize = 12.sp, letterSpacing = 1.sp)
    SwitchRow("Révélation directionnelle", brush.directionalReveal) { enabled ->
        view.submit {
            view.session.project =
                view.session.project.copy(brush = brush.copy(directionalReveal = enabled))
            view.session.save()
        }
    }
    if (brush.directionalReveal) {
        Text("Intensité de l’assistance", color = Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            studio.nuvora.veila.core.DirectionalStrength.entries.forEach { strength ->
                FilterChip(
                    selected = brush.directionalStrength == strength,
                    onClick = {
                        view.submit {
                            view.session.project =
                                view.session.project.copy(brush = brush.copy(directionalStrength = strength))
                            view.session.save()
                        }
                    },
                    label = {
                        Text(
                            when (strength) {
                                studio.nuvora.veila.core.DirectionalStrength.LOW -> "Faible"
                                studio.nuvora.veila.core.DirectionalStrength.MODERATE -> "Modérée"
                                studio.nuvora.veila.core.DirectionalStrength.HIGH -> "Forte"
                            }
                        )
                    },
                )
            }
        }
    }
    Text(
        "Le stylet privilégie les contours proches et parallèles au mouvement. " +
            "En zone incertaine, il conserve le comportement du pinceau classique.",
        color = Muted,
        fontSize = 11.sp,
    )
    HorizontalDivider(color = Line)
    Text("ISOLATION DES TRAITS", color = Cyan, fontSize = 12.sp, letterSpacing = 1.sp)
    SwitchRow("Isolation des traits", brush.strokeIsolation) { enabled ->
        view.submit {
            view.session.project =
                view.session.project.copy(
                    brush = brush.copy(
                        strokeIsolation = enabled,
                        strictStrokeIsolation = if (enabled) brush.strictStrokeIsolation else false,
                    ),
                )
            view.session.save()
        }
    }
    if (brush.strokeIsolation) {
        SwitchRow("Isolation stricte", brush.strictStrokeIsolation) { strict ->
            view.submit {
                view.session.project =
                    view.session.project.copy(brush = brush.copy(strictStrokeIsolation = strict))
                view.session.save()
            }
        }
        Text(
            "Le trait suivi est conservé pendant le mouvement. Le mode strict masque les " +
                "contours voisins et suspend l’aide dans les zones ambiguës.",
            color = Muted,
            fontSize = 11.sp,
        )
    }
    Text(
        "La taille reste liée à la toile. Le mode Gomme, près d’Enregistrer, inverse le dévoilement sur le seul calque actif.",
        color = Muted,
        fontSize = 12.sp,
    )
    ColorPalettePreview(view, status)
    OutlinedButton(
        {
            view.submit {
                view.session.project =
                    view.session.project.copy(brush = studio.nuvora.veila.core.Brush())
                view.session.save()
            }
        },
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text("Réinitialiser le pinceau")
    }
}

@Composable
private fun SettingsPanel(
    view: RevealView,
    status: StudioStatus,
    prepare: () -> Unit,
    videos: () -> Unit,
) {
    val p = status.project
    Text("PROJET", color = Cyan, fontSize = 12.sp, letterSpacing = 1.sp)
    Text("Plan de travail ${p.width} × ${p.height} px · ${p.stages.size} calques")
    Text("Temps de travail : ${duration(status.time)}", color = Muted, fontSize = 12.sp)
    Text("Vitesse simulée", color = Cyan, fontSize = 12.sp, letterSpacing = 1.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(.5f, 1f, 2f, 5f).forEach { speed ->
            FilterChip(
                selected = p.workSpeed == speed,
                onClick = { view.submit { view.session.setWorkSpeed(speed) } },
                label = { Text("×${if (speed % 1f == 0f) speed.toInt() else speed}") },
            )
        }
    }
    Text(
        "Le temps et l’horloge suivent cette vitesse et restent figés tant que la zone rouge ou le pourcentage sont affichés.",
        color = Muted,
        fontSize = 12.sp,
    )
    OutlinedButton(prepare, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Préparer le plan et les calques")
    }
    HorizontalDivider(color = Line)
    Text("ENREGISTREMENT", color = Cyan, fontSize = 12.sp, letterSpacing = 1.sp)
    val enabled = status.recording == RecordingState.IDLE
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(720, 1080).forEach { h ->
            FilterChip(
                p.videoHeight == h,
                {
                    view.submit {
                        view.session.project = p.copy(videoHeight = h)
                        view.session.save()
                    }
                },
                label = { Text("${h}p") },
                enabled = enabled,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(30, 60).forEach { fps ->
            FilterChip(
                p.videoFps == fps,
                {
                    view.submit {
                        view.session.project = p.copy(videoFps = fps)
                        view.session.save()
                    }
                },
                label = { Text("$fps FPS") },
                enabled = enabled,
            )
        }
    }
    SwitchRow("Inclure l’interface de l’Atelier", p.videoWithUi, enabled) { v ->
        view.submit {
            view.session.project = p.copy(videoWithUi = v)
            view.session.save()
        }
    }
    Text(
        "Toile seule par défaut. 60 FPS si compatible. La cadence baisse si nécessaire. Un changement d’orientation de l’écran ou une sortie finalise la vidéo. La rotation à deux doigts est enregistrée.",
        color = Muted,
        fontSize = 12.sp,
    )
    TextButton(videos, Modifier.heightIn(min = 48.dp), enabled = enabled) {
        Text("Voir et exporter les vidéos")
    }
    HorizontalDivider(color = Line)
    Text("ENTRÉES", color = Cyan, fontSize = 12.sp, letterSpacing = 1.sp)
    Text(status.inputDiagnostic, color = Cyan, fontSize = 12.sp)
    SwitchRow("Dessiner avec 1 doigt", p.fingerDrawingEnabled) { enabled ->
        view.submit {
            view.session.engine.end()
            view.session.router.reset()
            view.session.project = p.copy(fingerDrawingEnabled = enabled)
            view.session.router.fingerDrawingEnabled = enabled
            view.session.save()
        }
    }
    SwitchRow("Autoriser la souris pour les tests", p.mouseEnabled) { v ->
        view.submit {
            view.session.project = p.copy(mouseEnabled = v)
            view.session.router.mouseEnabled = v
            view.session.save()
        }
    }
    Text(
        "Activé : un seul doigt ou le stylet révèlent le calque actif. Deux doigts naviguent, trois annulent et quatre rétablissent. Désactivé : seul le stylet peut dessiner. Le mode souris reste facultatif.",
        color = Muted,
        fontSize = 12.sp,
    )
    Text(
        "Veila Paint ${BuildConfig.VERSION_NAME}\nNuvora Studio\nDonnées locales · Sans compte",
        color = Muted,
        fontSize = 12.sp,
    )
}

@Composable
private fun SettingSlider(
    title: String,
    display: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    monochromeTrack: Boolean = false,
    changed: (Float) -> Unit,
) {
    Column {
        Row {
            Text(title, Modifier.weight(1f), fontSize = 13.sp)
            Text(display, color = Cyan, fontSize = 12.sp)
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            if (monochromeTrack)
                Box(
                    Modifier.fillMaxWidth().height(8.dp).background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(Color.White, Color.Gray, Color.Black)
                        ),
                        RoundedCornerShape(4.dp),
                    )
                )
            Slider(
                value,
                changed,
                valueRange = range,
                steps = steps,
                colors =
                    if (monochromeTrack)
                        SliderDefaults.colors(
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                        )
                    else SliderDefaults.colors(),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = title },
            )
        }
    }
}

@Composable
private fun ColorPalettePreview(view: RevealView, status: StudioStatus) {
    val project = status.project
    var hue by rememberSaveable(project.id) { mutableFloatStateOf(project.paletteHue) }
    var saturation by rememberSaveable(project.id) { mutableFloatStateOf(project.paletteSaturation) }
    var light by rememberSaveable(project.id) { mutableFloatStateOf(project.paletteLight) }
    val preview = Color.hsv(hue, saturation, light)
    HorizontalDivider(color = Line)
    Text("PALETTE · APERÇU", color = Cyan, fontSize = 12.sp, letterSpacing = 1.sp)
    Text(
        "La couleur choisie n’agit ni sur l’image, ni sur le masque et reste mémorisée.",
        color = Muted,
        fontSize = 11.sp,
    )
    Box(
        Modifier.fillMaxWidth().height(54.dp).background(preview, RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .semantics { contentDescription = "Aperçu de la couleur sans effet sur le dessin" }
    )
    fun persist() = view.submit {
        view.session.project =
            view.session.project.copy(
                paletteHue = hue,
                paletteSaturation = saturation,
                paletteLight = light,
            )
        view.session.save()
    }
    SettingSlider("Teinte", "${hue.toInt()}°", hue, 0f..360f) { hue = it; persist() }
    SettingSlider("Saturation", "${(saturation * 100).toInt()} %", saturation, 0f..1f) {
        saturation = it; persist()
    }
    SettingSlider("Luminosité", "${(light * 100).toInt()} %", light, 0f..1f) { light = it; persist() }
}

@Composable
private fun SwitchRow(
    text: String,
    checked: Boolean,
    enabled: Boolean = true,
    changed: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f).padding(end = 12.dp), fontSize = 13.sp)
        Switch(
            checked,
            changed,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = text },
        )
    }
}
