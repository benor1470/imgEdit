package com.benor.imgedit

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface

/**
 * Fonts offered in the text dialog: a few system families plus every .ttf/.otf
 * found in assets/fonts (drop a file there to add a font).
 */
object Fonts {
    class F(val id: String, val label: String, val asset: String?)

    private val system = listOf(
        F("sans-serif", "Sans", null),
        F("serif", "Serif", null),
        F("monospace", "Mono", null),
        F("sans-serif-condensed", "Condensed", null),
        F("sans-serif-light", "Light", null),
        F("sans-serif-black", "Black", null),
    )

    @Volatile
    var all: List<F> = system
        private set

    private var assets: AssetManager? = null
    private val cache = HashMap<String, Typeface>()

    fun init(ctx: Context) {
        val am = ctx.applicationContext.assets
        assets = am
        val bundled = try {
            (am.list("fonts") ?: emptyArray())
                .filter { it.endsWith(".ttf", true) || it.endsWith(".otf", true) }
                .sorted()
                .map { F(it.substringBeforeLast('.'), it.substringBeforeLast('.'), "fonts/$it") }
        } catch (e: Exception) {
            emptyList()
        }
        all = bundled + system
    }

    fun typeface(id: String, style: Int): Typeface = synchronized(cache) {
        cache.getOrPut("$id/$style") {
            val f = all.firstOrNull { it.id == id }
            val am = assets
            val base = if (f?.asset != null && am != null) {
                try {
                    Typeface.createFromAsset(am, f.asset)
                } catch (e: Exception) {
                    Typeface.DEFAULT
                }
            } else {
                Typeface.create(id, Typeface.NORMAL)
            }
            Typeface.create(base, style)
        }
    }
}
