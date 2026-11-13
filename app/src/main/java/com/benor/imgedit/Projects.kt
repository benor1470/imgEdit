package com.benor.imgedit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.Executors

class VersionInfo(val file: File, val thumb: File, val time: Long)

/** [versions] are sorted newest first and never empty. */
class ProjectInfo(val id: String, val name: String, val versions: List<VersionInfo>)

/**
 * Every picture the user works on is kept as a project inside the app's private
 * storage, with a list of saved versions:
 *
 *   files/projects/<id>/name.txt
 *   files/projects/<id>/assets/a_123.jpg     pictures, stored once and shared by all versions
 *   files/projects/<id>/v_<time>.svg         one small file per version, linking to the assets
 *   files/projects/<id>/v_<time>.jpg         thumbnail of that version
 *
 * All access goes through [dispatcher] (one thread), so saves and listings never overlap.
 */
object Projects {
    private const val KEEP_VERSIONS = 40

    private val executor = Executors.newSingleThreadExecutor()
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private lateinit var root: File

    /** Run [block] on the store thread without waiting for it. */
    fun async(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (e: Throwable) {
                android.util.Log.e("imgEdit", "project store", e)
            }
        }
    }

    /** Which asset file already holds a given bitmap ("<projectId>/<fileName>"). */
    private val assetNames = WeakHashMap<Bitmap, String>()

    fun init(ctx: Context) {
        root = File(ctx.applicationContext.filesDir, "projects")
        root.mkdirs()
    }

    fun create(name: String): String {
        var id = "p${System.currentTimeMillis()}"
        while (File(root, id).exists()) id += "x"
        val dir = File(root, id)
        dir.mkdirs()
        File(dir, "name.txt").writeText(name)
        return id
    }

    private fun versionsOf(dir: File): List<VersionInfo> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("v_") && it.name.endsWith(".svg") }
            .mapNotNull { f ->
                val t = f.name.removePrefix("v_").removeSuffix(".svg").toLongOrNull() ?: return@mapNotNull null
                VersionInfo(f, File(dir, "v_$t.jpg"), t)
            }
            .sortedByDescending { it.time }

    fun info(id: String): ProjectInfo? {
        val dir = File(root, id)
        val versions = versionsOf(dir)
        if (versions.isEmpty()) return null
        val nameFile = File(dir, "name.txt")
        val name = if (nameFile.exists()) nameFile.readText().trim() else id
        return ProjectInfo(id, name, versions)
    }

    fun list(): List<ProjectInfo> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { info(it.name) }
            .sortedByDescending { it.versions.first().time }

    private fun assetFor(id: String, assetsDir: File, b: Bitmap): String {
        val known = assetNames[b]
        if (known != null && known.startsWith("$id/")) {
            val name = known.substring(id.length + 1)
            if (File(assetsDir, name).exists()) return name
        }
        val alpha = b.hasAlpha()
        var name: String
        do {
            name = "a_${System.nanoTime()}." + if (alpha) "png" else "jpg"
        } while (File(assetsDir, name).exists())
        File(assetsDir, name).outputStream().use {
            if (alpha) b.compress(Bitmap.CompressFormat.PNG, 100, it)
            else b.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }
        assetNames[b] = "$id/$name"
        return name
    }

    /** Store [doc] as a new version of project [id]. */
    fun save(id: String, doc: Doc) {
        val dir = File(root, id)
        val assetsDir = File(dir, "assets")
        assetsDir.mkdirs()
        var t = System.currentTimeMillis()
        while (File(dir, "v_$t.svg").exists()) t++
        val tmp = File(dir, "v_$t.tmp")
        tmp.outputStream().use { out ->
            Svg.write(doc, out) { img -> "assets/" + assetFor(id, assetsDir, img.bitmap) }
        }
        File(dir, "v_$t.jpg").outputStream().use {
            Render.thumb(doc, 400).compress(Bitmap.CompressFormat.JPEG, 82, it)
        }
        tmp.renameTo(File(dir, "v_$t.svg"))
        prune(dir)
    }

    fun load(id: String, version: File): Doc {
        val dir = File(root, id)
        return version.inputStream().use { input ->
            Svg.read(input) { href ->
                val f = File(dir, href)
                val b = if (href.startsWith("assets/") && f.exists()) BitmapFactory.decodeFile(f.path) else null
                if (b != null) assetNames[b] = "$id/${f.name}"
                b
            }
        }
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    fun deleteVersion(id: String, v: VersionInfo) {
        v.file.delete()
        v.thumb.delete()
        prune(File(root, id))
    }

    /** Drop the oldest versions beyond the limit, then any picture no version uses any more. */
    private fun prune(dir: File) {
        val versions = versionsOf(dir)
        versions.drop(KEEP_VERSIONS).forEach {
            it.file.delete()
            it.thumb.delete()
        }
        val kept = versions.take(KEEP_VERSIONS)
        if (kept.isEmpty()) {
            dir.deleteRecursively()
            return
        }
        val used = HashSet<String>()
        val re = Regex("assets/([A-Za-z0-9_.]+)")
        for (v in kept) {
            if (v.file.exists()) re.findAll(v.file.readText()).forEach { used.add(it.groupValues[1]) }
        }
        (File(dir, "assets").listFiles() ?: emptyArray()).forEach {
            if (it.name !in used) it.delete()
        }
    }
}
