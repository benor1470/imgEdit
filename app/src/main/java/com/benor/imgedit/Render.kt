package com.benor.imgedit

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object Render {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Grey/white squares shown behind transparent areas. */
    val checker: Paint by lazy {
        val s = 16
        val b = Bitmap.createBitmap(s * 2, s * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFFFFFFFF.toInt())
        val p = Paint()
        p.color = 0xFFD8D8D8.toInt()
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        c.drawRect(s.toFloat(), s.toFloat(), s * 2f, s * 2f, p)
        val out = Paint()
        out.shader = BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        out
    }

    fun drawEl(c: Canvas, e: El) {
        c.save()
        c.translate(e.xf.cx, e.xf.cy)
        c.rotate(e.xf.rot)
        c.scale(e.xf.sx, e.xf.sy)
        if (e.erase.isEmpty()) {
            drawContent(c, e)
        } else {
            // Draw the layer on its own, punch the erased strokes out, then put it on the canvas.
            val pad = max(e.halfW, e.halfH) * 0.25f + 8f
            c.saveLayer(-e.halfW - pad, -e.halfH - pad, e.halfW + pad, e.halfH + pad, null)
            drawContent(c, e)
            for (s in e.erase) c.drawPath(s.path, s.paint)
            c.restore()
        }
        c.restore()
    }

    private fun drawContent(c: Canvas, e: El) {
        when (e) {
            is ImageEl -> c.drawBitmap(e.bitmap, -e.halfW, -e.halfH, bitmapPaint)
            is TextEl -> {
                val box = e.boxPaint
                if (box != null) {
                    val r = e.size * 0.2f
                    c.drawRoundRect(-e.halfW, -e.halfH, e.halfW, e.halfH, r, r, box)
                }
                val outline = e.outlinePaint
                e.lines.forEachIndexed { i, line ->
                    if (outline != null) c.drawText(line, e.anchorX, e.baseline(i), outline)
                    c.drawText(line, e.anchorX, e.baseline(i), e.paint)
                }
            }
            is StrokeEl -> c.drawPath(e.path, e.paint)
        }
    }

    /** Small preview of the document on a white background. */
    fun thumb(doc: Doc, maxDim: Int): Bitmap {
        val k = minOf(1f, maxDim.toFloat() / max(doc.w, doc.h))
        val b = Bitmap.createBitmap(max(1, (doc.w * k).toInt()), max(1, (doc.h * k).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFFFFFFFF.toInt())
        c.scale(k, k)
        for (e in doc.els) drawEl(c, e)
        return b
    }

    /** Flatten the document to one bitmap. [background] null keeps transparency. */
    fun toBitmap(doc: Doc, background: Int?): Bitmap {
        val b = Bitmap.createBitmap(doc.w, doc.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (background != null) c.drawColor(background)
        for (e in doc.els) drawEl(c, e)
        return b
    }
}

/** Operations that act on the whole canvas. */
object Ops {
    fun rotate90(d: Doc, clockwise: Boolean): Doc {
        val els = d.els.map { e ->
            val x = e.xf
            if (clockwise) e.edit(xf = x.copy(cx = d.h - x.cy, cy = x.cx, rot = x.rot + 90f))
            else e.edit(xf = x.copy(cx = x.cy, cy = d.w - x.cx, rot = x.rot - 90f))
        }
        return Doc(d.h, d.w, els)
    }

    fun crop(d: Doc, left: Float, top: Float, right: Float, bottom: Float): Doc {
        val l = left.roundToInt()
        val t = top.roundToInt()
        val w = max(1, right.roundToInt() - l)
        val h = max(1, bottom.roundToInt() - t)
        val els = d.els.map { e -> e.edit(xf = e.xf.copy(cx = e.xf.cx - l, cy = e.xf.cy - t)) }
        return Doc(w, h, els)
    }

    /** Stretch the whole canvas to a new size. */
    fun resize(d: Doc, nw: Int, nh: Int): Doc {
        val fx = nw / d.w.toFloat()
        val fy = nh / d.h.toFloat()
        val els = d.els.map { e ->
            val x = e.xf
            val r = ((x.rot % 180f) + 180f) % 180f
            val (ex, ey) = when {
                r < 1f || r > 179f -> fx to fy
                abs(r - 90f) < 1f -> fy to fx
                else -> sqrt(fx * fy).let { it to it }
            }
            e.edit(xf = x.copy(cx = x.cx * fx, cy = x.cy * fy, sx = x.sx * ex, sy = x.sy * ey))
        }
        return Doc(nw, nh, els)
    }

    fun flatten(d: Doc): Doc {
        val b = Render.toBitmap(d, null)
        return Doc(d.w, d.h, listOf(ImageEl(Ids.next(), Xf(d.w / 2f, d.h / 2f), b, locked = true)))
    }
}
