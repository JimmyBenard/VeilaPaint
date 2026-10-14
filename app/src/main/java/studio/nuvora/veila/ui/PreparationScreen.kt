package studio.nuvora.veila.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import studio.nuvora.veila.VeilaModel
import studio.nuvora.veila.core.ProjectRules
import studio.nuvora.veila.data.ProjectCoverMode
import studio.nuvora.veila.data.Stage

data class FormatPreset(val name: String, val width: Int, val height: Int)

val Presets =
    listOf(
        FormatPreset("Carré · 1:1", 1080, 1080),
        FormatPreset("Portrait · 4:5", 1080, 1350),
        FormatPreset("Story / Reel · 9:16", 1080, 1920),
        FormatPreset("Paysage · 16:9", 1920, 1080),
        FormatPreset("4K UHD · 16:9", 3840, 2160),
    )

@Composable
fun PreparationScreen(model: VeilaModel) {
    val draft = model.draft
    var preview by remember { mutableStateOf<Stage?>(null) }
    var naming by remember { mutableStateOf<Stage?>(null) }
    var replacing by remember { mutableStateOf<String?>(null) }
    var formats by remember { mutableStateOf(false) }
    var layerDimensions by remember { mutableStateOf(false) }
    var coverStages by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var openAfterSave by remember { mutableStateOf(true) }
    val files =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            model.importUris(it)
        }
    val gallery =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) {
            model.importUris(it)
        }
    val replace =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) model.importUris(listOf(uri), replacing)
        }
    val cover =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) model.importProjectCover(uri)
        }
    fun enter(openStudio: Boolean = true) {
        openAfterSave = openStudio
        if (model.requiresReset()) reset = true else model.enterPrepared(openStudio)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallButton("‹", "Retour aux projets") { model.backFromPreparation() }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text("Préparation", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text("Plan de travail commun · calques de dévoilement indépendants", color = Muted, fontSize = 12.sp)
            }
            TextButton({ enter(false) }, enabled = draft.stages.size in 2..20, modifier = Modifier.heightIn(min = 52.dp)) {
                Text("Enregistrer")
            }
            Button(
                { enter() },
                enabled = draft.stages.size in 2..20,
                modifier = Modifier.heightIn(min = 52.dp),
            ) {
                Text("Ouvrir l’Atelier")
            }
        }
        OutlinedTextField(
            draft.name,
            { value -> model.updateDraft { it.copy(name = value.take(100)) } },
            label = { Text("Nom du projet") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text("IMAGE DU PROJET", color = Cyan, fontSize = 12.sp, letterSpacing = 1.sp)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = draft.coverMode == ProjectCoverMode.AUTOMATIC,
                onClick = model::useAutomaticProjectCover,
                label = { Text("Automatique") },
            )
            Box {
                FilterChip(
                    selected = draft.coverMode == ProjectCoverMode.STAGE,
                    onClick = { coverStages = true },
                    label = { Text("Choisir un calque") },
                    enabled = draft.stages.isNotEmpty(),
                )
                DropdownMenu(coverStages, { coverStages = false }) {
                    draft.stages.forEachIndexed { index, stage ->
                        DropdownMenuItem(
                            { Text("%02d · %s".format(index + 1, stage.name)) },
                            {
                                model.useStageProjectCover(stage.id)
                                coverStages = false
                            },
                        )
                    }
                }
            }
            FilterChip(
                selected = draft.coverMode == ProjectCoverMode.IMPORTED,
                onClick = { cover.launch(arrayOf("image/*")) },
                label = { Text("Importer une image") },
            )
        }
        model.repository.coverThumbnail(draft)?.let { image ->
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LocalImage(image, "Image du projet", Modifier.size(width = 92.dp, height = 58.dp))
                Text(
                    when (draft.coverMode) {
                        ProjectCoverMode.AUTOMATIC -> "Premier calque utilisé automatiquement"
                        ProjectCoverMode.STAGE -> "Calque choisi comme couverture"
                        ProjectCoverMode.IMPORTED -> "Image de couverture importée"
                    },
                    Modifier.weight(1f),
                    color = Muted,
                    fontSize = 12.sp,
                )
                if (draft.coverMode == ProjectCoverMode.IMPORTED)
                    TextButton(model::useAutomaticProjectCover) { Text("Supprimer") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("PLAN DE TRAVAIL", color = Cyan, fontSize = 12.sp, letterSpacing = 1.sp)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box {
                OutlinedButton({ formats = true }, Modifier.heightIn(min = 48.dp)) {
                    Text("${draft.width} × ${draft.height}  ▾")
                }
                DropdownMenu(formats, { formats = false }) {
                    Presets.forEach { p ->
                        DropdownMenuItem(
                            { Text("${p.name}  ·  ${p.width} × ${p.height}") },
                            {
                                model.updateDraft { it.copy(width = p.width, height = p.height) }
                                formats = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        { Text("Dimensions personnalisées…") },
                        {
                            formats = false
                            custom = true
                        },
                    )
                }
            }
            TextButton(
                { model.updateDraft { it.copy(width = it.height, height = it.width) } },
                Modifier.heightIn(min = 48.dp),
            ) {
                Text("↔ Inverser")
            }
            Box {
                TextButton(
                    { layerDimensions = true },
                    enabled = draft.stages.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Dimensions d’un calque") }
                DropdownMenu(layerDimensions, { layerDimensions = false }) {
                    draft.stages.forEachIndexed { index, layer ->
                        DropdownMenuItem(
                            {
                                Text(
                                    "%02d · %s · %d × %d".format(
                                        index + 1,
                                        layer.name,
                                        layer.sourceWidth,
                                        layer.sourceHeight,
                                    )
                                )
                            },
                            {
                                model.updateDraft {
                                    it.copy(width = layer.sourceWidth, height = layer.sourceHeight)
                                }
                                layerDimensions = false
                            },
                        )
                    }
                }
            }
            Text(
                "${if(draft.width==draft.height)"Carré"else if(draft.width>draft.height)"Paysage"else"Portrait"} · Toile fixe",
                color = Muted,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                {
                    gallery.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                Modifier.weight(1f).height(56.dp),
                enabled = draft.stages.size < 20,
            ) {
                Text("＋ Galerie")
            }
            OutlinedButton(
                { files.launch(arrayOf("image/*")) },
                Modifier.weight(1f).height(56.dp),
                enabled = draft.stages.size < 20,
            ) {
                Text("＋ Fichiers")
            }
        }
        Text(
            "2 à 20 calques sur un même plan. Ajustement centré sans recadrage ; proportions conservées.",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(
                "CALQUES  ·  ${draft.stages.size} / 20",
                color = Cyan,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.weight(1f))
            Text("Chaque calque conserve son propre masque", color = Muted, fontSize = 11.sp)
        }
        if (draft.stages.isEmpty())
            Box(
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 20.dp)
                    .border(1.dp, Line, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text("01 → 02 → …", color = Violet, fontSize = 28.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("Croquis, line-art, couleurs, lumière…", fontWeight = FontWeight.Medium)
                    Text(
                        "Importez les calques de progression de votre œuvre.",
                        color = Muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        else
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(draft.stages, key = { _, s -> s.id }) { index, stage ->
                    var more by remember { mutableStateOf(false) }
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        border =
                            BorderStroke(1.dp, if (index == 0) Violet.copy(alpha = .55f) else Line),
                        color = Panel,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("%02d".format(index + 1), color = Muted, fontSize = 12.sp)
                            LocalImage(
                                model.repository.asset(draft, stage.thumbnail),
                                "Aperçu ${stage.name}",
                                Modifier.size(62.dp).clickable { preview = stage },
                            )
                            Column(Modifier.weight(1f).clickable { naming = stage }) {
                                Text(stage.name, fontWeight = FontWeight.Medium, maxLines = 2)
                                Text(
                                    "Masque de dévoilement indépendant",
                                    color = Muted,
                                    fontSize = 11.sp,
                                )
                            }
                            SmallButton("↑", "Monter ${stage.name}", index > 0) {
                                model.updateDraft { p ->
                                    p.copy(
                                        stages =
                                            p.stages.toMutableList().apply {
                                                add(index - 1, removeAt(index))
                                            }
                                    )
                                }
                            }
                            SmallButton(
                                "↓",
                                "Descendre ${stage.name}",
                                index < draft.stages.lastIndex,
                            ) {
                                model.updateDraft { p ->
                                    p.copy(
                                        stages =
                                            p.stages.toMutableList().apply {
                                                add(index + 1, removeAt(index))
                                            }
                                    )
                                }
                            }
                            Box {
                                SmallButton("⋮", "Actions pour le calque ${index+1}") { more = true }
                                DropdownMenu(more, { more = false }) {
                                    DropdownMenuItem(
                                        { Text("Aperçu") },
                                        {
                                            more = false
                                            preview = stage
                                        },
                                    )
                                    DropdownMenuItem(
                                        { Text("Nommer") },
                                        {
                                            more = false
                                            naming = stage
                                        },
                                    )
                                    DropdownMenuItem(
                                        { Text("Remplacer") },
                                        {
                                            more = false
                                            replacing = stage.id
                                            replace.launch(arrayOf("image/*"))
                                        },
                                    )
                                    DropdownMenuItem(
                                        { Text("Retirer") },
                                        {
                                            more = false
                                            model.removeDraftStage(stage.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }
    naming?.let { s ->
        LayerNameDialog(
            "Nom du calque", s.name, model.layerNames, { naming = null },
            { name -> model.renameDraftLayer(s.id, name) },
            model::addLayerName, model::renameLayerName, model::deleteLayerName,
        )
    }
    preview?.let { s ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(s.name) },
            text = {
                LocalImage(
                    model.repository.asset(draft, s.image),
                    s.name,
                    Modifier.fillMaxWidth().height(420.dp),
                    preview = true,
                )
            },
            confirmButton = { TextButton({ preview = null }) { Text("Fermer") } },
        )
    }
    if (custom) {
        var w by remember { mutableStateOf(draft.width.toString()) }
        var h by remember { mutableStateOf(draft.height.toString()) }
        val valid = runCatching { ProjectRules.validate(2, w.toInt(), h.toInt()) }.isSuccess
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text("Toile personnalisée") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        w,
                        { w = it.filter(Char::isDigit).take(5) },
                        label = { Text("Largeur en pixels") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        h,
                        { h = it.filter(Char::isDigit).take(5) },
                        label = { Text("Hauteur en pixels") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text(
                        "16 à 8192 px par côté, 8 mégapixels maximum.",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    {
                        model.updateDraft { it.copy(width = w.toInt(), height = h.toInt()) }
                        custom = false
                    },
                    enabled = valid,
                ) {
                    Text("Appliquer")
                }
            },
            dismissButton = { TextButton({ custom = false }) { Text("Annuler") } },
        )
    }
    if (reset)
        AlertDialog(
            onDismissRequest = { reset = false },
            title = { Text("Recommencer le dévoilement ?") },
            text = {
                Text(
                    "L’ordre, les images ou les dimensions ont changé. Le masque et l’historique seront réinitialisés. Le temps de travail sera conservé."
                )
            },
            confirmButton = {
                TextButton({
                    reset = false
                    model.enterPrepared(openAfterSave)
                }) {
                    Text("Recommencer")
                }
            },
            dismissButton = { TextButton({ reset = false }) { Text("Annuler") } },
        )
}
