package com.benor.imgedit

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class Tool { Select, Draw, Crop }

/** Drag handles shown around the selected element. */
enum class Handle { Right, Bottom, Corner, Rotate }

class EditorState {
    var doc by mutableStateOf<Doc?>(null)
    var selectedId by mutableStateOf<Long?>(null)
    var tool by mutableStateOf(Tool.Select)
    var crop by mutableStateOf<Rect?>(null)
    var brushColor by mutableStateOf(0xFFE53935.toInt())
    var brushWidth by mutableStateOf(12f)
    var busy by mutableStateOf(false)

    /** Project (in the app's own storage) the open document belongs to. */
    var projectId by mutableStateOf<String?>(null)
    var projectName by mutableStateOf("")

    /** The document as it was last written to the project store. */
    private var savedDoc: Doc? = null

    /** Set when the user taps an already selected text: the screen opens the text editor. */
    var editRequest by mutableStateOf<Long?>(null)

    /** Line being drawn right now, in document coordinates. */
    val stroke = mutableStateListOf<Offset>()

    // Screen position = document position * vScale + vOff
    var vScale by mutableStateOf(1f)
    var vOff by mutableStateOf(Offset.Zero)
    var canvasSize by mutableStateOf(IntSize.Zero)
    var density = 1f

    val undoStack = mutableStateListOf<Doc>()
    val redoStack = mutableStateListOf<Doc>()

    /** Remember the current document so the next change can be undone. */
    fun snapshot() {
        val d = doc ?: return
        undoStack.add(d)
        if (undoStack.size > 40) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun commit(d: Doc) {
        snapshot()
        doc = d
    }

    private fun afterHistoryJump(before: Doc?) {
        val d = doc
        if (d?.find(selectedId) == null) selectedId = null
        if (tool == Tool.Crop) pickTool(Tool.Select)
        if (before != null && d != null && (before.w != d.w || before.h != d.h)) fit()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val before = doc
        if (before != null) redoStack.add(before)
        doc = undoStack.removeAt(undoStack.size - 1)
        afterHistoryJump(before)
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val before = doc
        if (before != null) undoStack.add(before)
        doc = redoStack.removeAt(redoStack.size - 1)
        afterHistoryJump(before)
    }

    fun openProject(id: String, name: String, d: Doc, alreadySaved: Boolean) {
        saveVersion()
        projectId = id
        projectName = name
        setNew(d)
        savedDoc = if (alreadySaved) d else null
        saveVersion()
    }

    /** Store the current state as a new version of the project, if it changed since the last save. */
    fun saveVersion(): Boolean {
        val d = doc ?: return false
        val id = projectId ?: return false
        if (d === savedDoc) return false
        savedDoc = d
        Projects.async { Projects.save(id, d) }
        return true
    }

    /** Back to the gallery. */
    fun close() {
        saveVersion()
        doc = null
        savedDoc = null
        projectId = null
        selectedId = null
        editRequest = null
        undoStack.clear()
        redoStack.clear()
        stroke.clear()
        crop = null
        tool = Tool.Select
    }

    private fun setNew(d: Doc) {
        undoStack.clear()
        redoStack.clear()
        doc = d
        selectedId = null
        stroke.clear()
        crop = null
        tool = Tool.Select
        brushWidth = max(4f, max(d.w, d.h) / 150f)
        fit()
    }

    fun pickTool(t: Tool) {
        stroke.clear()
        val d = doc
        crop = if (t == Tool.Crop && d != null) Rect(0f, 0f, d.w.toFloat(), d.h.toFloat()) else null
        if (t != Tool.Select) selectedId = null
        tool = t
    }

    fun fit() {
        val d = doc ?: return
        val cw = canvasSize.width.toFloat()
        val ch = canvasSize.height.toFloat()
        if (cw <= 0f || ch <= 0f) return
        val s = min(cw / d.w, ch / d.h) * 0.94f
        vScale = s
        vOff = Offset((cw - d.w * s) / 2f, (ch - d.h * s) / 2f)
    }

    fun toDoc(p: Offset): Offset = (p - vOff) / vScale
    fun toScreen(x: Float, y: Float): Offset = Offset(x * vScale + vOff.x, y * vScale + vOff.y)

    /** Screen position of a handle of element [e]. */
    fun handlePos(e: El, h: Handle): Offset {
        val hw = e.halfW * abs(e.xf.sx)
        val hh = e.halfH * abs(e.xf.sy)
        val lx: Float
        val ly: Float
        when (h) {
            Handle.Right -> { lx = hw; ly = 0f }
            Handle.Bottom -> { lx = 0f; ly = hh }
            Handle.Corner -> { lx = hw; ly = hh }
            Handle.Rotate -> { lx = 0f; ly = -(hh + 40f * density / vScale) }
        }
        val rad = Math.toRadians(e.xf.rot.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return toScreen(e.xf.cx + lx * c - ly * s, e.xf.cy + lx * s + ly * c)
    }

    // ---- element operations ----

    fun addElement(e: El) {
        val d = doc ?: return
        commit(d.copy(els = d.els + e))
        pickTool(Tool.Select)
        selectedId = e.id
    }

    fun addBitmap(b: Bitmap) {
        val d = doc ?: return
        // Start no larger than 60% of the canvas so it is easy to grab.
        val k = min(1f, min(d.w * 0.6f / b.width, d.h * 0.6f / b.height))
        addElement(ImageEl(Ids.next(), Xf(d.w / 2f, d.h / 2f, k, k), b))
    }

    fun newText(text: String = ""): TextEl? {
        val d = doc ?: return null
        return TextEl(Ids.next(), Xf(d.w / 2f, d.h / 2f), text, brushColor, max(24f, min(d.w, d.h) / 10f))
    }

    /** Add a new text element or replace the one being edited. */
    fun putText(t: TextEl) {
        val d = doc ?: return
        if (d.find(t.id) != null) commit(d.replace(t)) else addElement(t)
    }

    fun deleteSelected() {
        val d = doc ?: return
        val id = selectedId ?: return
        commit(d.copy(els = d.els.filter { it.id != id }))
        selectedId = null
    }

    fun duplicateSelected() {
        val d = doc ?: return
        val e = d.find(selectedId) ?: return
        val shift = max(d.w, d.h) * 0.04f
        addElement(e.edit(xf = e.xf.copy(cx = e.xf.cx + shift, cy = e.xf.cy + shift), locked = false, id = Ids.next()))
    }

    fun toggleLock() {
        val d = doc ?: return
        val e = d.find(selectedId) ?: return
        commit(d.replace(e.edit(locked = !e.locked)))
    }

    /** Move the selected element one step up (+1) or down (-1) in the stack. */
    fun reorder(step: Int) {
        val d = doc ?: return
        val i = d.els.indexOfFirst { it.id == selectedId }
        val j = i + step
        if (i < 0 || j < 0 || j >= d.els.size) return
        val list = d.els.toMutableList()
        val tmp = list[i]
        list[i] = list[j]
        list[j] = tmp
        commit(d.copy(els = list))
    }

    fun finishStroke() {
        val d = doc
        if (d == null || stroke.isEmpty()) {
            stroke.clear()
            return
        }
        val pts = stroke.toList()
        stroke.clear()
        var minX = pts[0].x
        var maxX = pts[0].x
        var minY = pts[0].y
        var maxY = pts[0].y
        for (p in pts) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val arr = FloatArray(pts.size * 2)
        for (i in pts.indices) {
            arr[i * 2] = pts[i].x - cx
            arr[i * 2 + 1] = pts[i].y - cy
        }
        commit(d.copy(els = d.els + StrokeEl(Ids.next(), Xf(cx, cy), arr, brushColor, brushWidth)))
    }

    // ---- whole-canvas operations ----

    fun rotateAll(clockwise: Boolean) {
        val d = doc ?: return
        commit(Ops.rotate90(d, clockwise))
        if (tool == Tool.Crop) pickTool(Tool.Select)
        fit()
    }

    fun applyCrop() {
        val d = doc ?: return
        val r = crop ?: return
        commit(Ops.crop(d, r.left, r.top, r.right, r.bottom))
        pickTool(Tool.Select)
        fit()
    }

    fun resizeAll(w: Int, h: Int) {
        val d = doc ?: return
        commit(Ops.resize(d, w, h))
        fit()
    }
}
