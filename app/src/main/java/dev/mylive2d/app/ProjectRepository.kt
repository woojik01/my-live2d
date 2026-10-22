package dev.mylive2d.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import dev.mylive2d.core.*
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProjectRepository(private val context: Context) {
    val assetDir = File(context.filesDir,"assets").apply { mkdirs() }
    private val projects = File(context.filesDir,"projects").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("projects",Context.MODE_PRIVATE)
    private val mutex = Mutex()
    data class Recent(val id: String, val name: String, val modified: Long)
    fun recent(): List<Recent> = projects.listFiles().orEmpty().filter { it.extension == "mylive2d" || it.extension == "bak" }
        .map { it.name.substringBefore('.') }.distinct().map { id ->
            Recent(id,prefs.getString("name-$id",id) ?: id,File(projects,"$id.mylive2d").lastModified())
        }.sortedByDescending { it.modified }
    var active: String?
        get() = prefs.getString("active",null)
        set(value) { prefs.edit().putString("active",value).apply() }
    fun assetFile(id: String) = File(assetDir,"$id.png")
    private fun images(p: Project) = p.assets.associate { it.id to assetFile(it.id).readBytes() }
    suspend fun save(p: Project) = mutex.withLock {
        val target = File(projects,"${p.id}.mylive2d"); val temp = File(projects,"${p.id}.tmp"); val backup = File(projects,"${p.id}.bak")
        try {
            FileOutputStream(temp).use { stream ->
                // Archive closes its stream; sync via a separately opened descriptor after close.
                ProjectArchive.write(p,images(p),stream)
            }
            FileOutputStream(temp,true).use { it.fd.sync() }
            if(target.exists()) Files.copy(target.toPath(),backup.toPath(),StandardCopyOption.REPLACE_EXISTING)
            try { Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING) }
            prefs.edit().putString("name-${p.id}",p.name).apply()
        } finally { temp.delete() }
    }
    suspend fun open(id: String): Project = mutex.withLock {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val file = File(projects,"$id.mylive2d"); val backup = File(projects,"$id.bak")
        val bundle = try { file.inputStream().use { ProjectArchive.read(it) } }
        catch (e: Exception) { if(backup.exists()) backup.inputStream().use { ProjectArchive.read(it) } else throw e }
        install(bundle)
    }
    suspend fun delete(id: String) = mutex.withLock {
        File(projects,"$id.mylive2d").delete(); File(projects,"$id.bak").delete()
        prefs.edit().remove("name-$id").apply()
    }
    suspend fun importProject(uri: Uri): Project = mutex.withLock {
        val bundle = context.contentResolver.openInputStream(uri)?.use { ProjectArchive.read(it) } ?: error("Cannot read file")
        // Imported identifiers are remapped, so existing projects/assets cannot be overwritten.
        val remap = bundle.project.assets.associate { it.id to newId() }
        val p = bundle.project.copy(id = newId(),assets = bundle.project.assets.map { it.copy(id = remap.getValue(it.id)) },
            parts = bundle.project.parts.map { it.copy(assetId = remap.getValue(it.assetId)) })
        install(ProjectArchive.Bundle(p,bundle.images.mapKeys { remap.getValue(it.key) }))
    }
    private fun install(bundle: ProjectArchive.Bundle): Project {
        bundle.project.assets.forEach { a ->
            val bytes = bundle.images.getValue(a.id)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth == a.width && bounds.outHeight == a.height && bounds.outMimeType == "image/png") { "Invalid image dimensions" }
            val bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: error("Invalid PNG image")
            bitmap.recycle()
        }
        bundle.images.forEach { (id,bytes) -> assetFile(id).writeBytes(bytes) }
        return bundle.project
    }
    suspend fun export(p: Project, uri: Uri) = mutex.withLock {
        context.contentResolver.openOutputStream(uri,"wt")?.use { ProjectArchive.write(p,images(p),it) } ?: error("Cannot write file")
    }
    data class Imported(val asset: Asset, val name: String)
    fun importPng(uri: Uri): Imported {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream(); val b = ByteArray(8192)
            while(true) { val n = input.read(b); if(n<0) break; require(out.size()+n <= 16*1024*1024) { "PNG must be <=16 MB" }; out.write(b,0,n) }; out.toByteArray()
        } ?: error("Cannot read PNG")
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true; inSampleSize = 1 }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
        require(opts.outMimeType == "image/png" && opts.outWidth > 0 && opts.outHeight > 0) { "PNG required" }
        while(opts.outWidth/opts.inSampleSize > 2048 || opts.outHeight/opts.inSampleSize > 2048) opts.inSampleSize *= 2
        opts.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts) ?: error("Invalid PNG")
        val asset = Asset(width = bitmap.width,height = bitmap.height)
        try { assetFile(asset.id).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } } finally { bitmap.recycle() }
        val name = context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null } ?: "PNG"
        return Imported(asset,name.substringBeforeLast('.').take(120).ifBlank { "PNG" })
    }
}
