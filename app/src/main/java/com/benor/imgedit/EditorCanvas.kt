package com.benor.imgedit

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private enum class Drag { None, Move, Pan, HandleDrag, Draw, CropEdit }

private fun clamp(v: Float, lo: Float, hi: Float): Float = max(lo, min(v, hi))

@Composable
fun EditorCanvas(st: EditorState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    st.density = density

    val selPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF2196F3.toInt()
        }
    }
    val handleFill = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() } }
    val dimPaint = remember { Paint().apply { color = 0x99000000.toInt() } }
    val cropPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFFFFFFF.toInt()
        }
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged {
                val first = st.canvasSize.width == 0
                st.canvasSize = it
                if (first) st.fit()
            }
            .pointerInput(Unit) { editorGestures(st) }
    ) {
        val doc = st.doc ?: return@Canvas
        val vs = st.vScale
        val off = st.vOff
        val dw = doc.w.toFloat()
        val dh = doc.h.toFloat()

        drawIntoCanvas { cc ->
            val c = cc.nativeCanvas

            // The document itself
            c.save()
            c.translate(off.x, off.y)
            c.scale(vs, vs)
            c.clipRect(0f, 0f, dw, dh)
            c.drawRect(0f, 0f, dw, dh, Render.checker)
            for (e in doc.els) Render.drawEl(c, e)
            if (st.stroke.isNotEmpty()) {
                val arr = FloatArray(st.stroke.size * 2)
                st.stroke.forEachIndexed { i, p ->
                    arr[i * 2] = p.x
                    arr[i * 2 + 1] = p.y
                }
                c.drawPath(smoothPath(arr), strokePaint(st.brushColor, st.brushWidth))
            }
            c.restore()

            // Selection frame and handles, drawn at screen size
            val sel = doc.find(st.selectedId)
            if (sel != null && st.tool == Tool.Select) {
                val hw = sel.halfW
                val hh = sel.halfH
                val pts = floatArrayOf(-hw, -hh, hw, -hh, hw, hh, -hw, hh)
                sel.xf.matrix().mapPoints(pts)
                val path = Path()
                for (i in 0 until 4) {
                    val x = pts[i * 2] * vs + off.x
                    val y = pts[i * 2 + 1] * vs + off.y
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                selPaint.strokeWidth = 2f * density
                c.drawPath(path, selPaint)
                if (!sel.locked) {
                    val top = st.toScreen((pts[0] + pts[2]) / 2f, (pts[1] + pts[3]) / 2f)
                    val rotPos = st.handlePos(sel, Handle.Rotate)
                    c.drawLine(top.x, top.y, rotPos.x, rotPos.y, selPaint)
                    for (h in Handle.entries) {
                        val p = st.handlePos(sel, h)
                        c.drawCircle(p.x, p.y, 9f * density, handleFill)
                        c.drawCircle(p.x, p.y, 9f * density, selPaint)
                    }
                }
            }

            // Crop frame
            val crop = st.crop
            if (crop != null && st.tool == Tool.Crop) {
                val tl = st.toScreen(crop.left, crop.top)
                val br = st.toScreen(crop.right, crop.bottom)
                val w = size.width
                val h = size.height
                c.drawRect(0f, 0f, w, tl.y, dimPaint)
                c.drawRect(0f, br.y, w, h, dimPaint)
                c.drawRect(0f, tl.y, tl.x, br.y, dimPaint)
                c.drawRect(br.x, tl.y, w, br.y, dimPaint)
                cropPaint.strokeWidth = 2f * density
                c.drawRect(tl.x, tl.y, br.x, br.y, cropPaint)
                val r = 10f * density
                c.drawCircle(tl.x, tl.y, r, handleFill)
                c.drawCircle(br.x, tl.y, r, handleFill)
                c.drawCircle(br.x, br.y, r, handleFill)
                c.drawCircle(tl.x, br.y, r, handleFill)
            }
        }
    }
}

/**
 * All touch handling for the canvas.
 *  - one finger: depends on the tool (move element / drag handle / draw / edit crop / pan view)
 *  - two fingers: scale + rotate the selected element, or zoom + pan the view when nothing is selected
 */
private suspend fun PointerInputScope.editorGestures(st: EditorState) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val d0 = st.doc ?: return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        val hitR = 28.dp.toPx()
        val startDoc = st.toDoc(down.position)

        var drag = Drag.None
        var handle: Handle? = null
        var startXf: Xf? = null
        var cropPart = -1 // 0..3 = corner (TL, TR, BR, BL), 4 = move the whole frame

        when (st.tool) {
            Tool.Select -> {
                val sel = d0.find(st.selectedId)
                if (sel != null && !sel.locked) {
                    handle = Handle.entries.firstOrNull {
                        (st.handlePos(sel, it) - down.position).getDistance() < hitR
                    }
                }
                if (handle != null && sel != null) {
                    drag = Drag.HandleDrag
                    startXf = sel.xf
                } else {
                    val hit = d0.els.lastOrNull {
                        !it.locked && it.contains(startDoc.x, startDoc.y, 12.dp.toPx() / st.vScale)
                    }
                    if (hit != null) {
                        st.selectedId = hit.id
                        drag = Drag.Move
                    } else {
                        drag = Drag.Pan
                    }
                }
            }
            Tool.Draw -> {
                drag = Drag.Draw
                st.stroke.clear()
                st.stroke.add(startDoc)
            }
            Tool.Crop -> {
                val r = st.crop
                if (r != null) {
                    val corners = listOf(
                        st.toScreen(r.left, r.top), st.toScreen(r.right, r.top),
                        st.toScreen(r.right, r.bottom), st.toScreen(r.left, r.bottom),
                    )
                    cropPart = corners.indexOfFirst { (it - down.position).getDistance() < hitR }
                    if (cropPart < 0 && r.contains(startDoc)) cropPart = 4
                    drag = if (cropPart >= 0) Drag.CropEdit else Drag.Pan
                }
            }
        }

        var moved = false
        var multi = false
        var snapped = false

        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {
                if (!multi) {
                    multi = true
                    st.stroke.clear()
                }
                val zoom = ev.calculateZoom()
                val rotation = ev.calculateRotation()
                val pan = ev.calculatePan()
                val centroid = ev.calculateCentroid(useCurrent = true)
                val cur = st.doc
                val sel = cur?.find(st.selectedId)
                if (st.tool == Tool.Select && cur != null && sel != null && !sel.locked) {
                    if (!snapped) {
                        st.snapshot()
                        snapped = true
                    }
                    val x = sel.xf
                    st.doc = cur.replace(
                        sel.edit(
                            xf = x.copy(
                                cx = x.cx + pan.x / st.vScale,
                                cy = x.cy + pan.y / st.vScale,
                                sx = x.sx * zoom,
                                sy = x.sy * zoom,
                                rot = x.rot + rotation,
                            )
                        )
                    )
                } else if (centroid.isSpecified) {
                    val ns = clamp(st.vScale * zoom, 0.02f, 60f)
                    val z = ns / st.vScale
                    st.vOff = centroid - (centroid - pan - st.vOff) * z
                    st.vScale = ns
                }
            } else if (!multi) {
                val ch = pressed[0]
                if (!moved && (ch.position - down.position).getDistance() > slop) moved = true
                if (moved) {
                    val delta = ch.position - ch.previousPosition
                    val p = st.toDoc(ch.position)
                    val cur = st.doc
                    val sel = cur?.find(st.selectedId)
                    when (drag) {
                        Drag.Move -> if (cur != null && sel != null) {
                            if (!snapped) {
                                st.snapshot()
                                snapped = true
                            }
                            val x = sel.xf
                            st.doc = cur.replace(
                                sel.edit(xf = x.copy(cx = x.cx + delta.x / st.vScale, cy = x.cy + delta.y / st.vScale))
                            )
                        }
                        Drag.HandleDrag -> {
                            val x0 = startXf
                            val hd = handle
                            if (cur != null && sel != null && x0 != null && hd != null) {
                                if (!snapped) {
                                    st.snapshot()
                                    snapped = true
                                }
                                val vx = p.x - x0.cx
                                val vy = p.y - x0.cy
                                val rad = Math.toRadians(x0.rot.toDouble())
                                val co = cos(rad).toFloat()
                                val si = sin(rad).toFloat()
                                val lx = vx * co + vy * si
                                val ly = -vx * si + vy * co
                                val nx = when (hd) {
                                    Handle.Right -> x0.copy(sx = max(abs(lx) / sel.halfW, 0.01f))
                                    Handle.Bottom -> x0.copy(sy = max(abs(ly) / sel.halfH, 0.01f))
                                    Handle.Corner -> {
                                        val base = hypot(sel.halfW * x0.sx, sel.halfH * x0.sy)
                                        val k = max(hypot(vx, vy) / max(base, 0.001f), 0.01f)
                                        x0.copy(sx = x0.sx * k, sy = x0.sy * k)
                                    }
                                    Handle.Rotate -> {
                                        val deg = Math.toDegrees(atan2(vy, vx).toDouble()).toFloat() + 90f
                                        x0.copy(rot = deg)
                                    }
                                }
                                st.doc = cur.replace(sel.edit(xf = nx))
                            }
                        }
                        Drag.Pan -> st.vOff = st.vOff + delta
                        Drag.Draw -> st.stroke.add(p)
                        Drag.CropEdit -> {
                            val r = st.crop
                            if (r != null && cur != null) {
                                val w = cur.w.toFloat()
                                val h = cur.h.toFloat()
                                val ms = min(8f, min(w, h))
                                st.crop = when (cropPart) {
                                    0 -> Rect(clamp(p.x, 0f, r.right - ms), clamp(p.y, 0f, r.bottom - ms), r.right, r.bottom)
                                    1 -> Rect(r.left, clamp(p.y, 0f, r.bottom - ms), clamp(p.x, r.left + ms, w), r.bottom)
                                    2 -> Rect(r.left, r.top, clamp(p.x, r.left + ms, w), clamp(p.y, r.top + ms, h))
                                    3 -> Rect(clamp(p.x, 0f, r.right - ms), r.top, r.right, clamp(p.y, r.top + ms, h))
                                    4 -> r.translate(
                                        Offset(
                                            clamp(delta.x / st.vScale, -r.left, w - r.right),
                                            clamp(delta.y / st.vScale, -r.top, h - r.bottom),
                                        )
                                    )
                                    else -> r
                                }
                            }
                        }
                        Drag.None -> {}
                    }
                }
            }
            ev.changes.forEach { it.consume() }
        }

        // Finger(s) lifted
        if (drag == Drag.Draw) {
            if (multi) st.stroke.clear() else st.finishStroke()
        } else if (!moved && !multi && st.tool == Tool.Select && drag == Drag.Pan) {
            // Tap on nothing movable: clear the selection, or pick a locked element (the base picture).
            if (st.selectedId != null) {
                st.selectedId = null
            } else {
                val cur = st.doc
                val hit = cur?.els?.lastOrNull { it.contains(startDoc.x, startDoc.y, 0f) }
                st.selectedId = hit?.id
            }
        }
    }
}
