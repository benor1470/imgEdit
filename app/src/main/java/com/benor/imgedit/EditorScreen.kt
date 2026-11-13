package com.benor.imgedit

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private val PALETTE: List<Int> = listOf(
    0xFF000000, 0xFFFFFFFF, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835,
    0xFF43A047, 0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFF6D4C41,
).map { it.toInt() }

private val FONTS = listOf("sans-serif", "serif", "monospace", "cursive", "sans-serif-condensed", "casual")

private const val FMT_PNG = 0
private const val FMT_JPG = 1
private const val FMT_SVG = 2

@Composable
fun EditorScreen(initialUri: Uri?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = remember { EditorState() }
    var textDialog by remember { mutableStateOf<TextEl?>(null) }
    var resizeDialog by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }

    fun toast(id: Int) {
        Toast.makeText(ctx, id, Toast.LENGTH_SHORT).show()
    }

    /** Run slow work off the UI thread, then hand the result back. */
    fun <T> io(work: () -> T, done: (T) -> Unit) {
        scope.launch {
            st.busy = true
            val r = withContext(Dispatchers.IO) { runCatching { work() } }
            st.busy = false
            if (r.isSuccess) done(r.getOrThrow()) else toast(R.string.error)
        }
    }

    fun openUri(uri: Uri) {
        io({ Io.loadDoc(ctx, uri) }) { st.setNew(it) }
    }

    fun addImageUri(uri: Uri) {
        io({ Io.loadBitmap(ctx, uri) }) { st.addBitmap(it) }
    }

    fun save(uri: Uri, fmt: Int) {
        val d = st.doc ?: return
        io({
            val out = ctx.contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("no stream")
            out.use {
                when (fmt) {
                    FMT_PNG -> Render.toBitmap(d, null).compress(Bitmap.CompressFormat.PNG, 100, it)
                    FMT_JPG -> Render.toBitmap(d, 0xFFFFFFFF.toInt()).compress(Bitmap.CompressFormat.JPEG, 92, it)
                    else -> Svg.write(d, it)
                }
            }
            true
        }) { toast(R.string.saved) }
    }

    fun paste() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val item = if (clip != null && clip.itemCount > 0) clip.getItemAt(0) else null
        val uri = item?.uri
        val text = item?.coerceToText(ctx)?.toString()
        if (uri != null) {
            if (st.doc == null) openUri(uri) else addImageUri(uri)
        } else if (!text.isNullOrBlank() && st.doc != null) {
            val t = st.newText(text.trim())
            if (t != null) st.putText(t)
        } else {
            toast(R.string.clipboard_empty)
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openUri(uri)
    }
    val addLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) addImageUri(uri)
    }
    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) save(uri, FMT_PNG)
    }
    val jpgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) save(uri, FMT_JPG)
    }
    val svgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri ->
        if (uri != null) save(uri, FMT_SVG)
    }

    LaunchedEffect(initialUri) {
        if (initialUri != null) openUri(initialUri)
    }

    val doc = st.doc
    val selected = doc?.find(st.selectedId)

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            // ---- top bar: file actions ----
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Btn(R.string.open) { openLauncher.launch(arrayOf("image/*")) }
                Box {
                    Btn(R.string.save, enabled = doc != null) { saveMenu = true }
                    DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.save_png)) }, onClick = {
                            saveMenu = false
                            pngLauncher.launch("imgEdit.png")
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.save_jpg)) }, onClick = {
                            saveMenu = false
                            jpgLauncher.launch("imgEdit.jpg")
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.save_project)) }, onClick = {
                            saveMenu = false
                            svgLauncher.launch("imgEdit.svg")
                        })
                    }
                }
                Btn(R.string.undo, enabled = st.undoStack.isNotEmpty()) { st.undo() }
                Btn(R.string.redo, enabled = st.redoStack.isNotEmpty()) { st.redo() }
                Btn(R.string.fit, enabled = doc != null) { st.fit() }
            }

            // ---- canvas ----
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF2B2B2B))) {
                if (doc == null) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.empty_hint), color = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { openLauncher.launch(arrayOf("image/*")) }) {
                            Text(stringResource(R.string.open_image))
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { paste() }) { Text(stringResource(R.string.paste)) }
                    }
                } else {
                    EditorCanvas(st, Modifier.fillMaxSize())
                }
            }

            if (doc != null) {
                // ---- context bar: depends on the tool / selection ----
                when {
                    st.tool == Tool.Draw -> Column(Modifier.fillMaxWidth()) {
                        ColorRow(st.brushColor) { st.brushColor = it }
                        val maxW = max(20f, max(doc.w, doc.h) / 15f)
                        Slider(
                            value = st.brushWidth.coerceIn(1f, maxW),
                            onValueChange = { st.brushWidth = it },
                            valueRange = 1f..maxW,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                    st.tool == Tool.Crop -> BarRow {
                        Btn(R.string.apply, selected = true) { st.applyCrop() }
                        Btn(R.string.cancel) { st.pickTool(Tool.Select) }
                    }
                    selected != null -> BarRow {
                        if (selected is TextEl) Btn(R.string.edit) { textDialog = selected }
                        Btn(R.string.delete) { st.deleteSelected() }
                        Btn(R.string.duplicate) { st.duplicateSelected() }
                        Btn(R.string.forward) { st.reorder(1) }
                        Btn(R.string.backward) { st.reorder(-1) }
                        Btn(if (selected.locked) R.string.unlock else R.string.lock) { st.toggleLock() }
                    }
                }

                // ---- tools ----
                BarRow {
                    Btn(R.string.select, selected = st.tool == Tool.Select) { st.pickTool(Tool.Select) }
                    Btn(R.string.draw, selected = st.tool == Tool.Draw) { st.pickTool(Tool.Draw) }
                    Btn(R.string.crop, selected = st.tool == Tool.Crop) { st.pickTool(Tool.Crop) }
                    Btn(R.string.rotate) { st.rotateAll(true) }
                    Btn(R.string.add_image) { addLauncher.launch("image/*") }
                    Btn(R.string.add_text) { textDialog = st.newText() }
                    Btn(R.string.paste) { paste() }
                    Box {
                        Btn(R.string.more) { moreMenu = true }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.rotate_left)) }, onClick = {
                                moreMenu = false
                                st.rotateAll(false)
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.resize)) }, onClick = {
                                moreMenu = false
                                resizeDialog = true
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.merge_all)) }, onClick = {
                                moreMenu = false
                                val d = st.doc
                                if (d != null) {
                                    io({ Ops.flatten(d) }) {
                                        st.commit(it)
                                        st.selectedId = null
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }

        if (st.busy) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }

    val editing = textDialog
    if (editing != null) {
        TextDialog(
            initial = editing,
            onDone = {
                textDialog = null
                st.putText(it)
            },
            onCancel = { textDialog = null },
        )
    }

    if (resizeDialog && doc != null) {
        ResizeDialog(
            w = doc.w,
            h = doc.h,
            onDone = { w, h ->
                resizeDialog = false
                st.resizeAll(w, h)
            },
            onCancel = { resizeDialog = false },
        )
    }
}

@Composable
private fun BarRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun Btn(label: Int, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    if (selected) {
        FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = pad) {
            Text(stringResource(label), maxLines = 1)
        }
    } else {
        TextButton(onClick = onClick, enabled = enabled, contentPadding = pad) {
            Text(stringResource(label), maxLines = 1)
        }
    }
}

@Composable
private fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
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

@Composable
private fun TextDialog(initial: TextEl, onDone: (TextEl) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial.text) }
    var color by remember { mutableStateOf(initial.color) }
    var font by remember { mutableStateOf(initial.font) }
    var bold by remember { mutableStateOf(initial.bold) }
    var italic by remember { mutableStateOf(initial.italic) }

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onDone(initial.copy(text = text, color = color, font = font, bold = bold, italic = italic)) },
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.text_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                ColorRow(color) { color = it }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    for (name in FONTS) {
                        if (name == font) {
                            FilledTonalButton(onClick = { font = name }) { Text(name) }
                        } else {
                            TextButton(onClick = { font = name }) { Text(name) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = bold, onCheckedChange = { bold = it })
                    Text(stringResource(R.string.bold))
                    Checkbox(checked = italic, onCheckedChange = { italic = it })
                    Text(stringResource(R.string.italic))
                }
            }
        },
    )
}

@Composable
private fun ResizeDialog(w: Int, h: Int, onDone: (Int, Int) -> Unit, onCancel: () -> Unit) {
    var ws by remember { mutableStateOf(w.toString()) }
    var hs by remember { mutableStateOf(h.toString()) }
    val nw = ws.toIntOrNull()
    val nh = hs.toIntOrNull()
    val valid = nw != null && nh != null && nw in 1..8192 && nh in 1..8192

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(enabled = valid, onClick = { if (nw != null && nh != null) onDone(nw, nh) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.resize)) },
        text = {
            Column {
                OutlinedTextField(
                    value = ws,
                    onValueChange = { ws = it },
                    label = { Text(stringResource(R.string.width)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = hs,
                    onValueChange = { hs = it },
                    label = { Text(stringResource(R.string.height)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        },
    )
}
