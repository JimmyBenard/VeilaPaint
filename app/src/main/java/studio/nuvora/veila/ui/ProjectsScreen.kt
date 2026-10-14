package studio.nuvora.veila.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import java.text.DateFormat
import java.util.Date
import studio.nuvora.veila.R
import studio.nuvora.veila.VeilaModel
import studio.nuvora.veila.data.Project

@Composable
fun ProjectsScreen(model: VeilaModel) {
    var rename by remember { mutableStateOf<Project?>(null) }
    var remove by remember { mutableStateOf<Project?>(null) }
    var help by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 700.dp
        val padding = if (wide) 32.dp else 18.dp
        Column(Modifier.fillMaxSize().padding(horizontal = padding)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Brand()
                Spacer(Modifier.weight(1f))
                TextButton(model::openBooks) {
                    Text("Books")
                }
                TextButton(model::openVideos) {
                    Text("Vidéos")
                }
                SmallButton("?", "Aide") { help = true }
            }
            Text(
                "Vos prochaines\nrévélations.",
                fontSize = if (wide) 40.sp else 32.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Text("Un trait après l’autre, faites apparaître la suite.", color = Muted)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PROJETS  ·  ${model.projects.size}",
                    color = Cyan,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.weight(1f))
                GlowButton(
                    "＋ Nouveau projet",
                    model::newProject,
                    Modifier.widthIn(min = 190.dp, max = 240.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            if (model.projects.isEmpty())
                Box(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .border(1.dp, Line, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier.padding(30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "REDESSINER  →  DÉVOILER  →  PROGRESSER",
                            color = Violet,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "Tout commence par votre plan de travail et ses calques.",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Importez 2 à 20 images d’une même œuvre,\npuis dévoilez la suivante avec le stylet ou un doigt.",
                            color = Muted,
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "Hors ligne · Sans compte · Sur votre tablette",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
            else
                LazyVerticalGrid(
                    GridCells.Adaptive(250.dp),
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(model.projects, key = { it.id }) { p ->
                        var menu by remember { mutableStateOf(false) }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, Line),
                            color = Panel,
                        ) {
                            Column {
                                model.repository.coverThumbnail(p)?.let { cover ->
                                    LocalImage(
                                        cover,
                                        "Aperçu de ${p.name}",
                                        Modifier.fillMaxWidth().height(190.dp).clickable {
                                            model.open(p)
                                        },
                                    )
                                }
                                    ?: Box(
                                        Modifier.fillMaxWidth().height(190.dp).background(Ink),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("◇", color = Muted, fontSize = 32.sp) }
                                Column(Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            p.name,
                                            Modifier.weight(1f).clickable { model.open(p) },
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                        )
                                        Box {
                                            SmallButton("⋮", "Actions pour ${p.name}") {
                                                menu = true
                                            }
                                            DropdownMenu(menu, { menu = false }) {
                                                DropdownMenuItem(
                                                    { Text("Ouvrir") },
                                                    {
                                                        menu = false
                                                        model.open(p)
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    { Text("Préparer les calques") },
                                                    {
                                                        menu = false
                                                        model.prepare(p)
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    { Text("Renommer") },
                                                    {
                                                        menu = false
                                                        rename = p
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    { Text("Dupliquer") },
                                                    {
                                                        menu = false
                                                        model.duplicate(p)
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    { Text("Supprimer") },
                                                    {
                                                        menu = false
                                                        remove = p
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        "Calque ${p.currentStep + 1} / ${p.stages.size} · ${p.width} × ${p.height}",
                                        color = Muted,
                                        fontSize = 12.sp,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    LinearProgressIndicator(
                                        progress = { p.overall.toFloat() },
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = Cyan,
                                        trackColor = Line,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row {
                                        Text(percent(p.overall), color = Cyan, fontSize = 12.sp)
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            duration(p.workMillis),
                                            color = Muted,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    Text(
                                        DateFormat.getDateTimeInstance(
                                                DateFormat.SHORT,
                                                DateFormat.SHORT,
                                            )
                                            .format(Date(p.modified)),
                                        color = Muted,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            Text(
                "NUVORA STUDIO  /  Veila Paint ${studio.nuvora.veila.BuildConfig.VERSION_NAME}",
                color = Muted,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
    rename?.let { p ->
        TextEditDialog("Renommer le projet", p.name, { rename = null }) { model.rename(p, it) }
    }
    remove?.let { p ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Supprimer « ${p.name} » ?") },
            text = {
                Text(
                    "Le projet et ses images seront supprimés de cet appareil. Les vidéos déjà enregistrées seront conservées."
                )
            },
            confirmButton = {
                TextButton({
                    remove = null
                    model.delete(p)
                }) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ remove = null }) { Text("Conserver") } },
        )
    }
    if (help)
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text("Votre stylet révèle la suite") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Image(
                        painterResource(R.drawable.nuvora_studio),
                        "Nuvora Studio",
                        Modifier.fillMaxWidth().height(72.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Text(
                        "Importez des calques alignés sur un plan de travail commun puis redessinez dans l’Atelier.\n\nUn doigt ou le stylet : dévoiler le calque actif.\nDeux doigts : zoom, déplacement et rotation.\nTrois doigts : annuler.\nQuatre doigts : rétablir.\n\nChaque nouveau trait ajoute de l’intensité. L’option « Dessiner avec 1 doigt » peut être désactivée dans l’Atelier.\n\nLes données restent sur cet appareil. La désinstallation les efface."
                    )
                }
            },
            confirmButton = { TextButton({ help = false }) { Text("C’est parti") } },
        )
}
