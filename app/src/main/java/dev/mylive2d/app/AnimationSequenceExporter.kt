package dev.mylive2d.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import dev.mylive2d.core.AnimationEngine
import dev.mylive2d.core.Project
import dev.mylive2d.core.ParameterEngine
import dev.mylive2d.core.RigEvaluator
import dev.mylive2d.core.Validation
import dev.mylive2d.core.Vec2
import org.json.JSONObject
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Renders one transparent PNG per clip frame without retaining rendered frames in memory. */
object AnimationSequenceExporter {
    val OUTPUT_SIZES = listOf(256, 512, 1024)
    const val MAX_FRAMES = 300
    const val MAX_RENDERED_PIXELS = 128_000_000L

    fun canExport(frameCount: Int, size: Int): Boolean =
        size in OUTPUT_SIZES && frameCount in 1..MAX_FRAMES &&
            frameCount.toLong() * size * size <= MAX_RENDERED_PIXELS

    fun requiredAssetIds(project: Project): Set<String> {
        val evaluator = RigEvaluator(project)
        return project.parts.filter { evaluator.visible(it) && it.opacity > 0f }.map { it.assetId }.toSet()
    }

    fun write(
        project: Project,
        clipId: String,
        textures: Map<String, Bitmap>,
        size: Int,
        output: OutputStream,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        Validation.check(project)
        val clip = project.animations.firstOrNull { it.id == clipId } ?: error("Animation clip not found")
        require(clip.tracks.any { it.keyframes.isNotEmpty() }) { "Record at least one animation key before exporting" }
        require(canExport(clip.durationFrames, size)) {
            "Export limit exceeded: choose a smaller frame size or shorten the animation"
        }
        val requiredAssets = requiredAssetIds(project)
        require(requiredAssets.isNotEmpty()) { "The animation has no visible parts to export" }
        requiredAssets.forEach { id ->
            require(textures[id]?.isRecycled == false) { "Missing or invalid texture for asset $id" }
        }

        val bounds = animatedBounds(project, clipId, clip.durationFrames)
        ZipOutputStream(output.buffered()).use { zip ->
            val manifest = JSONObject()
                .put("format", "MyLive2D PNG sequence")
                .put("version", 1)
                .put("clip", clip.name)
                .put("fps", clip.fps)
                .put("frames", clip.durationFrames)
                .put("width", size)
                .put("height", size)
                .put("alpha", true)
                .toString(2)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for (frame in 0 until clip.durationFrames) {
                val bitmap = renderFrame(project, clipId, frame, textures, size, bounds)
                try {
                    val name = String.format(Locale.ROOT, "frames/frame_%04d.png", frame)
                    zip.putNextEntry(ZipEntry(name))
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)) { "Could not encode frame $frame" }
                    zip.closeEntry()
                } finally {
                    bitmap.recycle()
                }
                onProgress(frame + 1, clip.durationFrames)
            }
        }
    }

    private data class Bounds(var minX: Float = Float.POSITIVE_INFINITY, var minY: Float = Float.POSITIVE_INFINITY,
                              var maxX: Float = Float.NEGATIVE_INFINITY, var maxY: Float = Float.NEGATIVE_INFINITY) {
        fun include(point: Vec2) {
            minX = min(minX, point.x); minY = min(minY, point.y)
            maxX = max(maxX, point.x); maxY = max(maxY, point.y)
        }

        fun screen(point: Vec2, size: Int): Vec2 {
            val width = max(maxX - minX, 1f)
            val height = max(maxY - minY, 1f)
            val padding = max(max(width, height) * .02f, 1f)
            val scale = min((size - 4f) / (width + padding * 2), (size - 4f) / (height + padding * 2))
            return Vec2((point.x - (minX + maxX) / 2) * scale + size / 2f,
                (point.y - (minY + maxY) / 2) * scale + size / 2f)
        }
    }

    private fun animatedBounds(project: Project, clipId: String, frameCount: Int): Bounds {
        val bounds = Bounds()
        for (frame in 0 until frameCount) {
            val sample = AnimationEngine.evaluate(project, clipId, frame.toFloat())
            val evaluator = RigEvaluator(sample)
            sample.parts.forEach { part ->
                if (part.opacity > 0f && evaluator.visible(part)) evaluator.partWorld(part).forEach(bounds::include)
            }
        }
        require(bounds.minX.isFinite() && bounds.minY.isFinite() && bounds.maxX.isFinite() && bounds.maxY.isFinite()) {
            "The animation has no visible geometry"
        }
        return bounds
    }

    private fun renderFrame(project: Project, clipId: String, frame: Int, textures: Map<String, Bitmap>,
                            size: Int, bounds: Bounds): Bitmap {
        val sample = AnimationEngine.evaluate(project, clipId, frame.toFloat())
        val evaluator = RigEvaluator(sample)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        val matrix = Matrix()
        val path = Path()
        val source = FloatArray(6)
        val destination = FloatArray(6)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        try {
            sample.parts.forEach { part ->
                if (part.opacity <= 0f || !evaluator.visible(part)) return@forEach
                val texture = textures[part.assetId] ?: error("Missing texture for part ${part.name}")
                val world = evaluator.partWorld(part, ParameterEngine.vertices(sample, part))
                paint.alpha = (part.opacity * 255).roundToInt().coerceIn(0, 255)
                for (i in part.mesh.triangles.indices step 3) {
                    val i0 = part.mesh.triangles[i]; val i1 = part.mesh.triangles[i + 1]; val i2 = part.mesh.triangles[i + 2]
                    val uv0 = part.mesh.uv[i0]; val uv1 = part.mesh.uv[i1]; val uv2 = part.mesh.uv[i2]
                    source[0] = uv0.x * texture.width; source[1] = uv0.y * texture.height
                    source[2] = uv1.x * texture.width; source[3] = uv1.y * texture.height
                    source[4] = uv2.x * texture.width; source[5] = uv2.y * texture.height
                    val p0 = bounds.screen(world[i0], size); val p1 = bounds.screen(world[i1], size); val p2 = bounds.screen(world[i2], size)
                    destination[0] = p0.x; destination[1] = p0.y
                    destination[2] = p1.x; destination[3] = p1.y
                    destination[4] = p2.x; destination[5] = p2.y
                    if (!matrix.setPolyToPoly(source, 0, destination, 0, 3)) continue
                    path.reset(); path.moveTo(p0.x, p0.y); path.lineTo(p1.x, p1.y); path.lineTo(p2.x, p2.y); path.close()
                    val checkpoint = canvas.save()
                    canvas.clipPath(path)
                    canvas.drawBitmap(texture, matrix, paint)
                    canvas.restoreToCount(checkpoint)
                }
            }
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
        return bitmap
    }
}
