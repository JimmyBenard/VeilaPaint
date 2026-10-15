package studio.nuvora.veila.ui

import android.graphics.ImageDecoder
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import java.io.File
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.nuvora.veila.R

val Ink = Color(0xFF080B14)
val Panel = Color(0xFF121725)
val Line = Color(0xFF2B3249)
val Cyan = Color(0xFF74E3F7)
val Violet = Color(0xFFC3A3FF)
val Muted = Color(0xFFA8AEC3)
val ButtonGlow =
    Brush.linearGradient(listOf(Color(0xFF8D2DAC), Color(0xFF5040B7), Color(0xFF14637F)))
val VeilaColors =
    darkColorScheme(
        primary = Cyan,
        onPrimary = Ink,
        secondary = Violet,
        background = Ink,
        surface = Panel,
        onSurface = Color(0xFFF3F1FC),
        onBackground = Color(0xFFF3F1FC),
        outline = Line,
    )

@Composable
fun Brand(compact: Boolean = false) {
    if (!compact) {
        Column {
            Image(
                painterResource(R.drawable.veila_logo),
                "Veila Paint",
                Modifier.width(248.dp).height(92.dp),
                contentScale = ContentScale.Fit,
            )
            Text("by Nuvora Studio", color = Muted, fontSize = 12.sp, letterSpacing = 1.sp)
        }
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painterResource(R.mipmap.ic_launcher),
            null,
            Modifier.size(if (compact) 36.dp else 52.dp),
            contentScale = ContentScale.Fit,
        )
        Column {
            Text(
                "Veila Paint",
                fontSize = if (compact) 18.sp else 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (!compact)
                Text("by Nuvora Studio", color = Muted, fontSize = 12.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
fun GlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick,
        modifier.heightIn(min = 52.dp),
        enabled = enabled,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
            ),
        contentPadding = PaddingValues(0.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .background(if (enabled) ButtonGlow else Brush.linearGradient(listOf(Line, Line)))
                .padding(horizontal = 22.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SmallButton(symbol: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick,
        Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
            contentDescription = label
        },
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(symbol, fontSize = 20.sp)
    }
}

@Composable
fun LocalImage(
    file: File,
    description: String?,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
) {
    val bitmap by
        produceState<ImageBitmap?>(null, file.absolutePath, preview) {
            value = null
            value =
                withContext(Dispatchers.IO) {
                    try {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            val limit = if (preview) 1600f else 384f
                            val ratio = minOf(1f, limit / maxOf(info.size.width, info.size.height))
                            decoder.setTargetSize(
                                maxOf(1, (info.size.width * ratio).toInt()),
                                maxOf(1, (info.size.height * ratio).toInt()),
                            )
                        }.asImageBitmap()
                    } catch (_: OutOfMemoryError) {
                        null
                    } catch (_: Exception) {
                        null
                    }
                }
        }
    Box(modifier.background(Color(0xFF0B0F1B)), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(it, description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } ?: Text("◇", color = Muted, fontSize = 24.sp)
    }
}

@Composable
fun TextEditDialog(title: String, value: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var input by remember { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                input,
                { input = it.take(100) },
                singleLine = true,
                label = { Text("Nom") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                {
                    onSave(input)
                    onDismiss()
                },
                enabled = input.isNotBlank(),
            ) {
                Text("Enregistrer")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun LayerNameDialog(
    title: String,
    value: String,
    names: List<String>,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var input by remember(value) { mutableStateOf(value) }
    var expanded by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedTextField(input, { input = it.take(100) }, label = { Text("Nom du calque") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    DropdownMenu(expanded, { expanded = false }) {
                        names.forEach { name ->
                            DropdownMenuItem({ Text(name) }, { input = name; expanded = false })
                        }
                    }
                }
                TextButton({ expanded = true }) { Text("Choisir dans la liste") }
                TextButton({ manage = true }) { Text("Gérer les noms") }
            }
        },
        confirmButton = { TextButton({ onSave(input); onDismiss() }, enabled = input.isNotBlank()) { Text("Appliquer") } },
        dismissButton = { TextButton(onDismiss) { Text("Annuler") } },
    )
    if (manage) LayerNameCatalogDialog(names, { manage = false }, onAdd, onRename, onDelete)
}

@Composable
private fun LayerNameCatalogDialog(
    names: List<String>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Noms de calques") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it.take(100) }, label = { Text("Nouveau nom") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    TextButton({ onAdd(newName); newName = "" }, enabled = newName.isNotBlank()) { Text("Ajouter") }
                }
                names.forEach { name ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), fontSize = 13.sp)
                        TextButton({ editing = name }) { Text("Modifier") }
                        TextButton({ removing = name }) { Text("Supprimer") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Fermer") } },
    )
    editing?.let { old ->
        TextEditDialog("Renommer le nom", old, { editing = null }) { onRename(old, it) }
    }
    removing?.let { name ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Supprimer ce nom ?") },
            text = { Text("Les calques existants ne seront pas modifiés.") },
            confirmButton = { TextButton({ onDelete(name); removing = null }) { Text("Supprimer") } },
            dismissButton = { TextButton({ removing = null }) { Text("Annuler") } })
    }
}

fun percent(value: Double) =
    if (value >= 1.0) "100 %"
    else
        String.format(
            java.util.Locale.FRANCE,
            "%.1f %%",
            floor(value.coerceAtLeast(0.0) * 1000) / 10,
        )

fun duration(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(java.util.Locale.ROOT, s / 3600, s / 60 % 60, s % 60)
}
