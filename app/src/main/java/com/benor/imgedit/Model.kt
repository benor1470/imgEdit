package com.benor.imgedit

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.max

/** Placement of an element on the canvas: centre, scale per axis and rotation in degrees. */
data class Xf(
    val cx: Float,
    val cy: Float,
    val sx: Float = 1f,
    val sy: Float = 1f,
    val rot: Float = 0f,
) {
    fun matrix(): Matrix = Matrix().apply {
        postScale(sx, sy)
        postRotate(rot)
        postTranslate(cx, cy)
    }
}

object Ids {
    private var n = 0L
    fun next(): Long = ++n
}

/**
 * One independent element on the canvas. Elements are immutable, so the whole
 * document can be snapshotted for undo by keeping a reference to it.
 * Local coordinates are centred on (0,0) and span [-halfW..halfW] x [-halfH..halfH].
 */
/** One pass of the eraser. [pts] is x0,y0,x1,y1,... in the layer's local coordinates. */
class EraseStroke(val pts: FloatArray, val width: Float) {
    val path: Path by lazy { smoothPath(pts) }
    val paint: Paint by lazy {
        val p = strokePaint(0xFF000000.toInt(), width)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        p
    }
}

sealed class El {
    abstract val id: Long
    abstract val xf: Xf
    abstract val locked: Boolean
    abstract val halfW: Float
    abstract val halfH: Float

    /** Eraser strokes on this layer, in its local coordinates. They hide what is under them. */
    abstract val erase: List<EraseStroke>
    abstract fun edit(xf: Xf = this.xf, locked: Boolean = this.locked, id: Long = this.id): El
    abstract fun withErase(erase: List<EraseStroke>): El

    fun contains(x: Float, y: Float, slop: Float): Boolean {
        val inv = Matrix()
        if (!xf.matrix().invert(inv)) return false
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        val slopX = slop / max(abs(xf.sx), 0.0001f)
        val slopY = slop / max(abs(xf.sy), 0.0001f)
        return abs(p[0]) <= halfW + slopX && abs(p[1]) <= halfH + slopY
    }
}

data class ImageEl(
    override val id: Long,
    override val xf: Xf,
    val bitmap: Bitmap,
    override val locked: Boolean = false,
    override val erase: List<EraseStroke> = emptyList(),
) : El() {
    override val halfW: Float get() = bitmap.width / 2f
    override val halfH: Float get() = bitmap.height / 2f
    override fun edit(xf: Xf, locked: Boolean, id: Long): El = copy(xf = xf, locked = locked, id = id)
    override fun withErase(erase: List<EraseStroke>): El = copy(erase = erase)
}

const val LOOK_PLAIN = 0
const val LOOK_OUTLINE = 1
const val LOOK_SHADOW = 2
const val LOOK_BOX = 3

const val ALIGN_CENTER = 0
const val ALIGN_LEFT = 1
const val ALIGN_RIGHT = 2

data class TextEl(
    override val id: Long,
    override val xf: Xf,
    val text: String,
    val color: Int,
    val size: Float,
    val font: String = "sans-serif",
    val bold: Boolean = false,
    val italic: Boolean = false,
    override val locked: Boolean = false,
    /** One of LOOK_*: plain, outlined letters, drop shadow, or a filled box behind the text. */
    val look: Int = LOOK_PLAIN,
    /** Outline or box colour. */
    val color2: Int = 0xFF000000.toInt(),
    val align: Int = ALIGN_CENTER,
    override val erase: List<EraseStroke> = emptyList(),
) : El() {
    val paint: Paint by lazy {
        val face = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        p.textSize = size
        p.typeface = Fonts.typeface(font, face)
        p.textAlign = when (align) {
            ALIGN_LEFT -> Paint.Align.LEFT
            ALIGN_RIGHT -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        if (look == LOOK_SHADOW) p.setShadowLayer(size * 0.08f, size * 0.05f, size * 0.05f, 0xC0000000.toInt())
        p
    }

    /** Drawn under the letters when the look is "outline". */
    val outlinePaint: Paint? by lazy {
        if (look != LOOK_OUTLINE) {
            null
        } else {
            val p = Paint(paint)
            p.style = Paint.Style.STROKE
            p.strokeJoin = Paint.Join.ROUND
            p.strokeWidth = outlineWidth
            p.color = color2
            p
        }
    }

    val boxPaint: Paint? by lazy {
        if (look != LOOK_BOX) {
            null
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = color2
            p
        }
    }

    val outlineWidth: Float get() = size * 0.14f
    val lines: List<String> by lazy { text.split("\n") }
    val lineH: Float by lazy { paint.fontSpacing }

    private val pad: Float
        get() = when (look) {
            LOOK_BOX -> size * 0.3f
            LOOK_OUTLINE -> size * 0.07f
            else -> 0f
        }
    val textHalfW: Float by lazy { max(lines.maxOf { paint.measureText(it) }, size * 0.5f) / 2f }
    val textHalfH: Float by lazy { lines.size * lineH / 2f }
    override val halfW: Float by lazy { textHalfW + pad }
    override val halfH: Float by lazy { textHalfH + pad * 0.5f }

    /** X of the text anchor in local coordinates, matching the alignment. */
    val anchorX: Float
        get() = when (align) {
            ALIGN_LEFT -> -textHalfW
            ALIGN_RIGHT -> textHalfW
            else -> 0f
        }

    /** Baseline of line [i] in local coordinates. */
    fun baseline(i: Int): Float = -textHalfH + i * lineH - paint.ascent()

    override fun edit(xf: Xf, locked: Boolean, id: Long): El = copy(xf = xf, locked = locked, id = id)
    override fun withErase(erase: List<EraseStroke>): El = copy(erase = erase)
}

/** Free-hand line. [pts] is x0,y0,x1,y1,... in local coordinates. */
data class StrokeEl(
    override val id: Long,
    override val xf: Xf,
    val pts: FloatArray,
    val color: Int,
    val width: Float,
    override val locked: Boolean = false,
    override val erase: List<EraseStroke> = emptyList(),
) : El() {
    val path: Path by lazy { smoothPath(pts) }
    val paint: Paint by lazy { strokePaint(color, width) }
    override val halfW: Float by lazy { extent(0) }
    override val halfH: Float by lazy { extent(1) }

    private fun extent(axis: Int): Float {
        var m = 0f
        var i = axis
        while (i < pts.size) {
            m = max(m, abs(pts[i]))
            i += 2
        }
        return m + width / 2f
    }

    override fun edit(xf: Xf, locked: Boolean, id: Long): El = copy(xf = xf, locked = locked, id = id)
    override fun withErase(erase: List<EraseStroke>): El = copy(erase = erase)
}

fun strokePaint(color: Int, width: Float): Paint {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.style = Paint.Style.STROKE
    p.strokeCap = Paint.Cap.ROUND
    p.strokeJoin = Paint.Join.ROUND
    p.color = color
    p.strokeWidth = width
    return p
}

/** Smooth line through the points, using the mid-points as curve joints. */
fun smoothPath(pts: FloatArray): Path {
    val path = Path()
    if (pts.size < 2) return path
    path.moveTo(pts[0], pts[1])
    if (pts.size == 2) {
        path.lineTo(pts[0] + 0.01f, pts[1])
        return path
    }
    var i = 2
    while (i < pts.size) {
        val px = pts[i - 2]
        val py = pts[i - 1]
        path.quadTo(px, py, (px + pts[i]) / 2f, (py + pts[i + 1]) / 2f)
        i += 2
    }
    path.lineTo(pts[pts.size - 2], pts[pts.size - 1])
    return path
}

data class Doc(val w: Int, val h: Int, val els: List<El>) {
    fun find(id: Long?): El? = if (id == null) null else els.firstOrNull { it.id == id }
    fun replace(e: El): Doc = copy(els = els.map { if (it.id == e.id) e else it })
}
