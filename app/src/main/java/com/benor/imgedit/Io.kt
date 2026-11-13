package com.benor.imgedit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.max

object Io {
    private const val MAX_DIM = 4096

    /** Decode a picture as a normal (software) bitmap, shrunk if it is huge. */
    fun loadBitmap(ctx: Context, uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val m = max(w, h)
            if (m > MAX_DIM) {
                val k = MAX_DIM.toFloat() / m
                decoder.setTargetSize(max(1, (w * k).toInt()), max(1, (h * k).toInt()))
            }
        }
    }

    fun docFromBitmap(b: Bitmap): Doc =
        Doc(b.width, b.height, listOf(ImageEl(Ids.next(), Xf(b.width / 2f, b.height / 2f), b, locked = true)))

    private fun loadSvg(ctx: Context, uri: Uri): Doc =
        ctx.contentResolver.openInputStream(uri)!!.use { Svg.read(it) }

    /** Open either a picture or a project (SVG) file. */
    fun loadDoc(ctx: Context, uri: Uri): Doc {
        val type = ctx.contentResolver.getType(uri) ?: ""
        val name = uri.lastPathSegment ?: ""
        if (type.contains("svg") || name.endsWith(".svg", ignoreCase = true)) return loadSvg(ctx, uri)
        return try {
            docFromBitmap(loadBitmap(ctx, uri))
        } catch (e: Exception) {
            loadSvg(ctx, uri)
        }
    }
}
