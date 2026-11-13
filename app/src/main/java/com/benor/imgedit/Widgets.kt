package com.benor.imgedit

import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.min

val PALETTE: List<Int> = listOf(
    0xFF000000, 0xFFFFFFFF, 0xFF9E9E9E, 0xFFE53935, 0xFFD81B60, 0xFFFB8C00, 0xFFFDD835,
    0xFFC0CA33, 0xFF43A047, 0xFF00897B, 0xFF00ACC1, 0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFF6D4C41,
).map { it.toInt() }

private fun formatTime(t: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))

/** Toolbar button: icon with a small label under it. */
@Composable
fun Btn(label: Int, icon: ImageVector, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val text = stringResource(label)
    Column(
        Modifier
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = text, modifier = Modifier.size(22.dp))
        Text(text, maxLines = 1, style = MaterialTheme.typography.labelSmall)
    }
}

/** Menu row with an icon. */
@Composable
fun MenuItem(label: Int, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/** Small text button with an icon in front. */
@Composable
fun IconTextButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, maxLines = 1)
    }
}

/** Button that looks filled when it is the chosen option. */
@Composable
fun Choice(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    font: FontFamily? = null,
    onClick: () -> Unit,
) {
    val pad = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    if (selected) {
        FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = pad) {
            Text(label, maxLines = 1, fontFamily = font)
        }
    } else {
        TextButton(onClick = onClick, enabled = enabled, contentPadding = pad) {
            Text(label, maxLines = 1, fontFamily = font)
        }
    }
}

@Composable
fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
        for (c in PALETTE) {
            val on = c == selected
            Box(
                Modifier
                    .padding(4.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        if (on) 3.dp else 1.dp,
                        if (on) MaterialTheme.colorScheme.primary else Color.Gray,
                        CircleShape,
                    )
                    .clickable { onPick(c) }
            )
        }
    }
}

/** Write or edit a text element: content, colours, font and look, with a live preview. */
@Composable
fun TextDialog(initial: TextEl, onDone: (TextEl) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial.text) }
    var color by remember { mutableStateOf(initial.color) }
    var color2 by remember { mutableStateOf(initial.color2) }
    var font by remember { mutableStateOf(initial.font) }
    var bold by remember { mutableStateOf(initial.bold) }
    var italic by remember { mutableStateOf(initial.italic) }
    var look by remember { mutableStateOf(initial.look) }
    var align by remember { mutableStateOf(initial.align) }

    fun build(base: TextEl, body: String): TextEl = base.copy(
        text = body, color = color, color2 = color2, font = font,
        bold = bold, italic = italic, look = look, align = align,
    )

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onDone(build(initial, text)) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.text_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                // Live preview, drawn by the same code that draws the canvas.
                val preview = build(initial.copy(xf = Xf(0f, 0f), size = 100f), text.ifBlank { "Abc" })
                Canvas(
                    Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF8A8A8A))
                ) {
                    drawIntoCanvas {
                        val c = it.nativeCanvas
                        val k = min(
                            size.width / (preview.halfW * 2f + 40f),
                            size.height / (preview.halfH * 2f + 40f),
                        )
                        c.save()
                        c.translate(size.width / 2f, size.height / 2f)
                        c.scale(k, k)
                        Render.drawEl(c, preview)
                        c.restore()
                    }
                }
                Spacer(Modifier.height(8.dp))

                Text(stringResource(R.string.text_color), style = MaterialTheme.typography.labelMedium)
                ColorRow(color) { color = it }

                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    for (f in Fonts.all) {
                        val family = remember(f.id) { FontFamily(Fonts.typeface(f.id, Typeface.NORMAL)) }
                        Choice(f.label, f.id == font, font = family) { font = f.id }
                    }
                }

                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Choice(stringResource(R.string.look_plain), look == LOOK_PLAIN) { look = LOOK_PLAIN }
                    Choice(stringResource(R.string.look_outline), look == LOOK_OUTLINE) { look = LOOK_OUTLINE }
                    Choice(stringResource(R.string.look_shadow), look == LOOK_SHADOW) { look = LOOK_SHADOW }
                    Choice(stringResource(R.string.look_box), look == LOOK_BOX) { look = LOOK_BOX }
                }
                if (look == LOOK_OUTLINE || look == LOOK_BOX) {
                    Text(stringResource(R.string.second_color), style = MaterialTheme.typography.labelMedium)
                    ColorRow(color2) { color2 = it }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = bold, onCheckedChange = { bold = it })
                    Text(stringResource(R.string.bold))
                    Checkbox(checked = italic, onCheckedChange = { italic = it })
                    Text(stringResource(R.string.italic))
                }

                // Alignment only changes anything when there is more than one line.
                if (text.trim().contains('\n')) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        Choice(stringResource(R.string.align_left), align == ALIGN_LEFT) { align = ALIGN_LEFT }
                        Choice(stringResource(R.string.align_center), align == ALIGN_CENTER) { align = ALIGN_CENTER }
                        Choice(stringResource(R.string.align_right), align == ALIGN_RIGHT) { align = ALIGN_RIGHT }
                    }
                }
            }
        },
    )
}

/** Thumbnail loaded from a file in the background. */
@Composable
fun Thumb(file: File, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(initialValue = null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
        }
    }
    val b = bmp
    if (b != null) {
        Image(bitmap = b, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(Color(0xFF555555)))
    }
}

/** Home screen: every picture the user worked on, newest first. */
@Composable
fun Gallery(
    projects: List<ProjectInfo>,
    onNew: () -> Unit,
    onPaste: () -> Unit,
    onOpen: (ProjectInfo) -> Unit,
    onVersions: (ProjectInfo) -> Unit,
    onDelete: (ProjectInfo) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.my_images),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconTextButton(stringResource(R.string.paste), Icons.Filled.ContentPaste, onPaste)
            Button(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.new_image))
            }
        }
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_projects), textAlign = TextAlign.Center)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(projects.size, key = { projects[it].id }) { i ->
                    val p = projects[i]
                    val latest = p.versions.first()
                    Column(
                        Modifier
                            .padding(6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onOpen(p) }
                    ) {
                        Thumb(latest.thumb, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(
                            p.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp),
                        )
                        Text(
                            formatTime(latest.time),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        Row {
                            IconTextButton("${p.versions.size}", Icons.Filled.History) { onVersions(p) }
                            Spacer(Modifier.weight(1f))
                            IconTextButton("", Icons.Filled.Delete) { onDelete(p) }
                        }
                    }
                }
            }
        }
    }
}

/** History of one picture: pick a version to open it. */
@Composable
fun VersionsDialog(
    project: ProjectInfo,
    onOpen: (VersionInfo) -> Unit,
    onDelete: (VersionInfo) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
        title = { Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                items(project.versions.size, key = { project.versions[it].time }) { i ->
                    val v = project.versions[i]
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(v) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Thumb(v.thumb, Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)))
                        Text(
                            formatTime(v.time),
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        IconTextButton("", Icons.Filled.Delete) { onDelete(v) }
                    }
                }
            }
        },
    )
}

/** Eraser icon (the Material icon set has none). */
val EraserIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Eraser", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(
                "M16.24,3.56l4.95,4.94c0.78,0.79 0.78,2.05 0,2.84L12,20.53a4.008,4.008 0 0 1 -5.66,0L2.81,17" +
                    "c-0.78,-0.79 -0.78,-2.05 0,-2.84l10.6,-10.6c0.79,-0.78 2.05,-0.78 2.83,0z" +
                    "M4.22,15.58l3.54,3.53c0.78,0.79 2.04,0.79 2.83,0l3.53,-3.53l-4.95,-4.95l-4.95,4.95z"
            ),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.Black),
        )
        .build()
}

/** Short name of a layer for the layers list. */
@Composable
fun layerLabel(e: El, isBottom: Boolean): String = when (e) {
    is ImageEl -> stringResource(if (isBottom) R.string.layer_background else R.string.layer_picture)
    is TextEl -> e.text.replace('\n', ' ').take(30)
    is StrokeEl -> stringResource(R.string.layer_line)
}

/** Every element is a layer; the list shows them top first and picks the one to work on. */
@Composable
fun LayersPanel(doc: Doc, selectedId: Long?, onSelect: (Long) -> Unit) {
    val n = doc.els.size
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 170.dp)) {
        items(n, key = { doc.els[n - 1 - it].id }) { row ->
            val index = n - 1 - row
            val e = doc.els[index]
            val on = e.id == selectedId
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { onSelect(e.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = when (e) {
                    is ImageEl -> Icons.Filled.Image
                    is TextEl -> Icons.Filled.TextFields
                    is StrokeEl -> Icons.Filled.Brush
                }
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    layerLabel(e, index == 0),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                if (e.erase.isNotEmpty()) Icon(EraserIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                if (e.locked) Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}
