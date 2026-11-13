package com.benor.imgedit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Project file format: a plain SVG. Every element is written as a normal SVG
 * node (<image>, <text>, <path>) so other programs can show and edit it, plus a
 * few "ie:" attributes that let this app read its own files back exactly.
 */
object Svg {
    private const val NS = "https://github.com/benor1470/imgEdit"

    private fun f(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun hex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

    private fun common(e: El): String {
        val x = e.xf
        val t = "transform=\"translate(${f(x.cx)} ${f(x.cy)}) rotate(${f(x.rot)}) scale(${f(x.sx)} ${f(x.sy)})\""
        return if (e.locked) "$t ie:locked=\"1\"" else t
    }

    fun write(doc: Doc, out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        w.write(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" " +
                "xmlns:ie=\"$NS\" width=\"${doc.w}\" height=\"${doc.h}\" viewBox=\"0 0 ${doc.w} ${doc.h}\">\n"
        )
        for (e in doc.els) {
            when (e) {
                is ImageEl -> {
                    val alpha = e.bitmap.hasAlpha()
                    val bytes = ByteArrayOutputStream()
                    if (alpha) e.bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                    else e.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, bytes)
                    val mime = if (alpha) "image/png" else "image/jpeg"
                    w.write(
                        "<image ie:type=\"image\" ${common(e)} x=\"${f(-e.halfW)}\" y=\"${f(-e.halfH)}\" " +
                            "width=\"${e.bitmap.width}\" height=\"${e.bitmap.height}\" preserveAspectRatio=\"none\" " +
                            "xlink:href=\"data:$mime;base64,"
                    )
                    w.write(Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
                    w.write("\"/>\n")
                }
                is TextEl -> {
                    w.write(
                        "<text ie:type=\"text\" ${common(e)} font-family=\"${esc(e.font)}\" font-size=\"${f(e.size)}\" " +
                            "fill=\"${hex(e.color)}\" text-anchor=\"middle\" xml:space=\"preserve\""
                    )
                    if (e.bold) w.write(" font-weight=\"bold\"")
                    if (e.italic) w.write(" font-style=\"italic\"")
                    w.write(">")
                    e.lines.forEachIndexed { i, line ->
                        w.write("<tspan x=\"0\" y=\"${f(e.baseline(i))}\">${esc(line)}</tspan>")
                    }
                    w.write("</text>\n")
                }
                is StrokeEl -> {
                    val raw = StringBuilder()
                    for (i in e.pts.indices) {
                        if (i > 0) raw.append(' ')
                        raw.append(f(e.pts[i]))
                    }
                    w.write(
                        "<path ie:type=\"stroke\" ${common(e)} ie:pts=\"$raw\" d=\"${pathData(e.pts)}\" fill=\"none\" " +
                            "stroke=\"${hex(e.color)}\" stroke-width=\"${f(e.width)}\" " +
                            "stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n"
                    )
                }
            }
        }
        w.write("</svg>\n")
        w.flush()
    }

    /** Same curve as [smoothPath], written as SVG path commands. */
    private fun pathData(p: FloatArray): String {
        if (p.size < 2) return ""
        val sb = StringBuilder("M${f(p[0])} ${f(p[1])}")
        if (p.size == 2) return sb.append(" L${f(p[0] + 0.01f)} ${f(p[1])}").toString()
        var i = 2
        while (i < p.size) {
            val px = p[i - 2]
            val py = p[i - 1]
            sb.append(" Q${f(px)} ${f(py)} ${f((px + p[i]) / 2f)} ${f((py + p[i + 1]) / 2f)}")
            i += 2
        }
        sb.append(" L${f(p[p.size - 2])} ${f(p[p.size - 1])}")
        return sb.toString()
    }

    private val numberRe = Regex("-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?")
    private val transformRe = Regex("(\\w+)\\(([^)]*)\\)")

    private fun numbers(s: String?): List<Float> =
        if (s == null) emptyList() else numberRe.findAll(s).map { it.value.toFloat() }.toList()

    private fun num(s: String?, default: Float = 0f): Float = numbers(s).firstOrNull() ?: default

    private fun parseTransform(s: String?): Xf {
        var xf = Xf(0f, 0f)
        if (s == null) return xf
        for (m in transformRe.findAll(s)) {
            val n = numbers(m.groupValues[2])
            if (n.isEmpty()) continue
            xf = when (m.groupValues[1]) {
                "translate" -> xf.copy(cx = n[0], cy = n.getOrElse(1) { 0f })
                "rotate" -> xf.copy(rot = n[0])
                "scale" -> xf.copy(sx = n[0], sy = n.getOrElse(1) { n[0] })
                else -> xf
            }
        }
        return xf
    }

    private fun parseColor(s: String?): Int = try {
        Color.parseColor(s ?: "#000000")
    } catch (e: IllegalArgumentException) {
        Color.BLACK
    }

    fun read(input: InputStream): Doc {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        var w = 0
        var h = 0
        val els = ArrayList<El>()
        var pendingText: TextEl? = null
        val lines = ArrayList<String>()
        var sb: StringBuilder? = null

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val ours = p.getAttributeValue(null, "ie:type") != null
                    val locked = p.getAttributeValue(null, "ie:locked") == "1"
                    when (p.name) {
                        "svg" -> {
                            w = num(p.getAttributeValue(null, "width")).toInt()
                            h = num(p.getAttributeValue(null, "height")).toInt()
                            if (w <= 0 || h <= 0) {
                                val vb = numbers(p.getAttributeValue(null, "viewBox"))
                                if (vb.size == 4) {
                                    w = vb[2].toInt()
                                    h = vb[3].toInt()
                                }
                            }
                        }
                        "image" -> {
                            val href = p.getAttributeValue(null, "xlink:href") ?: p.getAttributeValue(null, "href")
                            val comma = href?.indexOf(',') ?: -1
                            if (href != null && href.startsWith("data:") && comma > 0) {
                                val bytes = Base64.decode(href.substring(comma + 1), Base64.DEFAULT)
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bmp != null) {
                                    val xf = if (ours) {
                                        parseTransform(p.getAttributeValue(null, "transform"))
                                    } else {
                                        // Image written by another program: place it by x/y/width/height.
                                        val iw = num(p.getAttributeValue(null, "width"), bmp.width.toFloat())
                                        val ih = num(p.getAttributeValue(null, "height"), bmp.height.toFloat())
                                        val ix = num(p.getAttributeValue(null, "x"))
                                        val iy = num(p.getAttributeValue(null, "y"))
                                        Xf(ix + iw / 2f, iy + ih / 2f, iw / bmp.width, ih / bmp.height)
                                    }
                                    els.add(ImageEl(Ids.next(), xf, bmp, locked))
                                }
                            }
                        }
                        "path" -> if (ours) {
                            val pts = numbers(p.getAttributeValue(null, "ie:pts"))
                            if (pts.size >= 2 && pts.size % 2 == 0) {
                                els.add(
                                    StrokeEl(
                                        Ids.next(),
                                        parseTransform(p.getAttributeValue(null, "transform")),
                                        pts.toFloatArray(),
                                        parseColor(p.getAttributeValue(null, "stroke")),
                                        num(p.getAttributeValue(null, "stroke-width"), 4f),
                                        locked,
                                    )
                                )
                            }
                        }
                        "text" -> if (ours) {
                            lines.clear()
                            pendingText = TextEl(
                                Ids.next(),
                                parseTransform(p.getAttributeValue(null, "transform")),
                                "",
                                parseColor(p.getAttributeValue(null, "fill")),
                                num(p.getAttributeValue(null, "font-size"), 48f),
                                p.getAttributeValue(null, "font-family") ?: "sans-serif",
                                p.getAttributeValue(null, "font-weight") == "bold",
                                p.getAttributeValue(null, "font-style") == "italic",
                                locked,
                            )
                        }
                        "tspan" -> if (pendingText != null) sb = StringBuilder()
                    }
                }
                XmlPullParser.TEXT -> sb?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "tspan" -> {
                        val done = sb
                        if (done != null) lines.add(done.toString())
                        sb = null
                    }
                    "text" -> {
                        val t = pendingText
                        if (t != null && lines.isNotEmpty()) els.add(t.copy(text = lines.joinToString("\n")))
                        pendingText = null
                    }
                }
            }
            ev = p.next()
        }
        if (w <= 0 || h <= 0) throw IllegalArgumentException("SVG has no size")
        return Doc(w, h, els)
    }
}
