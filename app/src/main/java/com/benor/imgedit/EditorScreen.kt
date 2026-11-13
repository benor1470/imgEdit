package com.benor.imgedit

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private const val FMT_PNG = 0
private const val FMT_JPG = 1
private const val FMT_SVG = 2

private class Opened(val id: String, val name: String, val doc: Doc)

@Composable
fun EditorScreen(st: EditorState, initialUri: Uri?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var textDialog by remember { mutableStateOf<TextEl?>(null) }
    var resizeDialog by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf<List<ProjectInfo>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var versionsOf by remember { mutableStateOf<ProjectInfo?>(null) }
    var confirmDelete by remember { mutableStateOf<ProjectInfo?>(null) }

    fun toast(id: Int) {
        Toast.makeText(ctx, id, Toast.LENGTH_SHORT).show()
    }

    /** Run slow work off the UI thread, then hand the result back. */
    fun <T> background(dispatcher: CoroutineDispatcher, work: () -> T, done: (T) -> Unit) {
        scope.launch {
            st.busy = true
            val r = withContext(dispatcher) { runCatching { work() } }
            st.busy = false
            if (r.isSuccess) done(r.getOrThrow()) else toast(R.string.error)
        }
    }

    fun <T> io(work: () -> T, done: (T) -> Unit) = background(Dispatchers.IO, work, done)

    /** Work on the project store; runs in order with the automatic saves. */
    fun <T> store(work: () -> T, done: (T) -> Unit) = background(Projects.dispatcher, work, done)

    /** A picture (or project file) from outside becomes a new project. */
    fun openUri(uri: Uri) {
        io({
            val d = Io.loadDoc(ctx, uri)
            val name = Io.projectName(ctx, uri)
            Opened(Projects.create(name), name, d)
        }) { st.openProject(it.id, it.name, it.doc, false) }
    }

    fun openVersion(p: ProjectInfo, v: VersionInfo) {
        st.saveVersion()
        store({ Projects.load(p.id, v.file) }) { st.openProject(p.id, p.name, it, true) }
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
        }) {
            st.saveVersion()
            toast(R.string.saved)
        }
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

    // Gallery content, reloaded whenever we are back on the home screen.
    LaunchedEffect(doc == null, refresh) {
        if (doc == null) {
            projects = withContext(Projects.dispatcher) { runCatching { Projects.list() }.getOrDefault(emptyList()) }
        }
    }

    // Tap on a selected text opens the text editor.
    val editId = st.editRequest
    LaunchedEffect(editId) {
        if (editId != null) {
            val e = st.doc?.find(editId)
            if (e is TextEl) textDialog = e
            st.editRequest = null
        }
    }

    BackHandler(enabled = doc != null) { st.close() }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (doc == null) {
            Gallery(
                projects = projects,
                onNew = { openLauncher.launch(arrayOf("image/*")) },
                onPaste = { paste() },
                onOpen = { p -> openVersion(p, p.versions.first()) },
                onVersions = { p -> versionsOf = p },
                onDelete = { p -> confirmDelete = p },
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                // ---- top bar: file actions ----
                BarRow {
                    Btn(R.string.home, Icons.Filled.Home) { st.close() }
                    Box {
                        Btn(R.string.save, Icons.Filled.Save) { saveMenu = true }
                        DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                            MenuItem(R.string.save_png, Icons.Filled.Image, onClick = {
                                saveMenu = false
                                pngLauncher.launch(Io.exportName(st.projectName, "png"))
                            })
                            MenuItem(R.string.save_jpg, Icons.Filled.Image, onClick = {
                                saveMenu = false
                                jpgLauncher.launch(Io.exportName(st.projectName, "jpg"))
                            })
                            MenuItem(R.string.save_project, Icons.Filled.Layers, onClick = {
                                saveMenu = false
                                svgLauncher.launch(Io.exportName(st.projectName, "svg"))
                            })
                            MenuItem(R.string.save_version, Icons.Filled.History, onClick = {
                                saveMenu = false
                                st.saveVersion()
                                toast(R.string.version_saved)
                            })
                        }
                    }
                    Btn(R.string.undo, Icons.AutoMirrored.Filled.Undo, enabled = st.undoStack.isNotEmpty()) { st.undo() }
                    Btn(R.string.redo, Icons.AutoMirrored.Filled.Redo, enabled = st.redoStack.isNotEmpty()) { st.redo() }
                    Btn(R.string.versions, Icons.Filled.History) {
                        val id = st.projectId
                        if (id != null) {
                            st.saveVersion()
                            store({ Projects.info(id) }) { versionsOf = it }
                        }
                    }
                    Btn(R.string.fit, Icons.Filled.FitScreen) { st.fit() }
                }

                // ---- canvas ----
                Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF2B2B2B))) {
                    EditorCanvas(st, Modifier.fillMaxSize())
                }

                if (showLayers) LayersPanel(doc, st.selectedId) { st.selectLayer(it) }

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
                        Btn(R.string.apply, Icons.Filled.Check, selected = true) { st.applyCrop() }
                        Btn(R.string.cancel, Icons.Filled.Close) { st.pickTool(Tool.Select) }
                    }
                    st.tool == Tool.ElCrop -> BarRow {
                        Btn(R.string.apply, Icons.Filled.Check, selected = true) { st.applyElCrop() }
                        Btn(R.string.cancel, Icons.Filled.Close) { st.endElTool() }
                    }
                    st.tool == Tool.Erase -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Btn(R.string.done, Icons.Filled.Check, selected = true) { st.pickTool(Tool.Select) }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                if (selected != null) {
                                    stringResource(R.string.erasing_from, layerLabel(selected, doc.els.firstOrNull() === selected))
                                } else {
                                    stringResource(R.string.erase_pick)
                                },
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            val maxW = max(40f, max(doc.w, doc.h) / 6f)
                            Slider(
                                value = st.brushWidth.coerceIn(1f, maxW),
                                onValueChange = { st.brushWidth = it },
                                valueRange = 1f..maxW,
                            )
                        }
                    }
                    selected != null -> BarRow {
                        if (selected is TextEl) Btn(R.string.edit, Icons.Filled.Edit, selected = true) { textDialog = selected }
                        Btn(R.string.eraser, EraserIcon) { st.pickTool(Tool.Erase) }
                        if (selected is ImageEl) Btn(R.string.crop_this, Icons.Filled.Crop) { st.startElCrop() }
                        Btn(R.string.delete, Icons.Filled.Delete) { st.deleteSelected() }
                        Btn(R.string.duplicate, Icons.Filled.ContentCopy) { st.duplicateSelected() }
                        Btn(R.string.forward, Icons.Filled.FlipToFront) { st.reorder(1) }
                        Btn(R.string.backward, Icons.Filled.FlipToBack) { st.reorder(-1) }
                        Btn(
                            if (selected.locked) R.string.unlock else R.string.lock,
                            if (selected.locked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                        ) { st.toggleLock() }
                    }
                }

                // ---- tools ----
                BarRow {
                    Btn(R.string.select, Icons.Filled.TouchApp, selected = st.tool == Tool.Select) { st.pickTool(Tool.Select) }
                    Btn(R.string.add_text, Icons.Filled.TextFields) { textDialog = st.newText() }
                    Btn(R.string.draw, Icons.Filled.Brush, selected = st.tool == Tool.Draw) { st.pickTool(Tool.Draw) }
                    Btn(R.string.eraser, EraserIcon, selected = st.tool == Tool.Erase) { st.pickTool(Tool.Erase) }
                    Btn(R.string.layers, Icons.Filled.Layers, selected = showLayers) { showLayers = !showLayers }
                    Btn(R.string.crop, Icons.Filled.Crop, selected = st.tool == Tool.Crop) { st.pickTool(Tool.Crop) }
                    Btn(R.string.rotate, Icons.Filled.RotateRight) { st.rotateAll(true) }
                    Btn(R.string.add_image, Icons.Filled.AddPhotoAlternate) { addLauncher.launch("image/*") }
                    Btn(R.string.paste, Icons.Filled.ContentPaste) { paste() }
                    Box {
                        Btn(R.string.more, Icons.Filled.MoreVert) { moreMenu = true }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            MenuItem(R.string.rotate_left, Icons.Filled.RotateLeft, onClick = {
                                moreMenu = false
                                st.rotateAll(false)
                            })
                            MenuItem(R.string.resize, Icons.Filled.AspectRatio, onClick = {
                                moreMenu = false
                                resizeDialog = true
                            })
                            MenuItem(R.string.merge_all, Icons.Filled.Layers, onClick = {
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

    val showVersions = versionsOf
    if (showVersions != null) {
        VersionsDialog(
            project = showVersions,
            onOpen = { v ->
                versionsOf = null
                openVersion(showVersions, v)
            },
            onDelete = { v ->
                store({
                    Projects.deleteVersion(showVersions.id, v)
                    Projects.info(showVersions.id)
                }) {
                    versionsOf = it
                    refresh++
                }
            },
            onClose = { versionsOf = null },
        )
    }

    val deleting = confirmDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    store({ Projects.delete(deleting.id) }) { refresh++ }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
            title = { Text(deleting.name) },
            text = { Text(stringResource(R.string.delete_project_q)) },
        )
    }
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

@Composable
fun BarRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
