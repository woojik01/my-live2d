package dev.mylive2d.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.*
import java.util.zip.*

/** Bounded ZIP reader. Entries are never extracted to paths supplied by the archive. */
object ProjectArchive {
    private const val MAX_TOTAL = 64 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    data class Bundle(val project: Project, val images: Map<String,ByteArray>)
    fun write(project: Project, images: Map<String,ByteArray>, output: OutputStream) {
        Validation.check(project)
        require(project.assets.all { images.containsKey(it.id) })
        require(images.values.sumOf { it.size.toLong() } <= MAX_TOTAL)
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            val metadata = json.encodeToString(project).toByteArray(Charsets.UTF_8)
            require(metadata.size <= 8*1024*1024) { "Project metadata exceeds 8 MB" }
            entry("project.json",metadata)
            project.assets.forEach { asset ->
                val bytes = images.getValue(asset.id); require(isPng(bytes)) { "PNG required" }
                entry("assets/${asset.id}.png",bytes)
            }
        }
    }
    fun read(input: InputStream): Bundle {
        val entries = linkedMapOf<String,ByteArray>(); var total = 0
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                require(!e.isDirectory && (e.name == "project.json" || e.name.matches(Regex("assets/[A-Za-z0-9-]{1,80}\\.png")))) { "Invalid archive entry" }
                require(e.name !in entries && entries.size < 33) { "Duplicate/too many archive entries" }
                val limit = if(e.name == "project.json") 8*1024*1024 else 16*1024*1024
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val n = zip.read(buffer); if(n < 0) break
                    total += n; require(total <= MAX_TOTAL && out.size()+n <= limit) { "Project exceeds safe size limit" }
                    out.write(buffer,0,n)
                }
                entries[e.name] = out.toByteArray(); zip.closeEntry()
            }
        }
        val meta = entries.remove("project.json") ?: error("Missing project.json")
        val project = json.decodeFromString<Project>(meta.toString(Charsets.UTF_8)); Validation.check(project)
        require(entries.size == project.assets.size) { "Unexpected or missing assets" }
        val images = project.assets.associate { a ->
            val bytes = entries["assets/${a.id}.png"] ?: error("Missing image")
            require(isPng(bytes)) { "PNG required" }; a.id to bytes
        }
        return Bundle(project,images)
    }
    private fun isPng(b: ByteArray) = b.size >= 8 && b.take(8) == listOf(137,80,78,71,13,10,26,10).map { it.toByte() }
}
