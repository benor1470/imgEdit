package com.benor.imgedit

import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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

@Composable
fun Btn(label: Int, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Choice(stringResource(label), selected, enabled, onClick = onClick)
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
                val preview = build(initial.copy(xf = Xf(0f, 0f), size = 100f), text.ifBlank { "Abc אבג" })
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

                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Choice(stringResource(R.string.align_left), align == ALIGN_LEFT) { align = ALIGN_LEFT }
                    Choice(stringResource(R.string.align_center), align == ALIGN_CENTER) { align = ALIGN_CENTER }
                    Choice(stringResource(R.string.align_right), align == ALIGN_RIGHT) { align = ALIGN_RIGHT }
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
            TextButton(onClick = onPaste) { Text(stringResource(R.string.paste)) }
            Button(onClick = onNew) { Text(stringResource(R.string.new_image)) }
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
                            TextButton(onClick = { onVersions(p) }) {
                                Text("${stringResource(R.string.versions)} (${p.versions.size})", maxLines = 1)
                            }
                            TextButton(onClick = { onDelete(p) }) {
                                Text(stringResource(R.string.delete), maxLines = 1)
                            }
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
                        TextButton(onClick = { onDelete(v) }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        },
    )
}
