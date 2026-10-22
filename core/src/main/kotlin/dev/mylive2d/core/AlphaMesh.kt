package dev.mylive2d.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Conservative cell mesh: every pixel above the threshold stays covered, including separate islands. */
object AlphaMesh {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Result(val mesh: Mesh, val bounds: Bounds, val columns: Int, val rows: Int, val activeCells: Int) {
        val totalCells get() = columns * rows
    }

    fun generate(width: Int, height: Int, alpha: ByteArray, detail: Int = 16,
                 threshold: Int = 1, padding: Int = 2): Result {
        require(width in 1..2048 && height in 1..2048) { "Image dimensions must be within 1..2048" }
        require(alpha.size == width * height) { "Alpha data does not match the image dimensions" }
        require(detail in 1..24 && threshold in 1..255 && padding in 0..16) { "Invalid automatic mesh options" }
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        for (y in 0 until height) for (x in 0 until width) {
            if ((alpha[y * width + x].toInt() and 255) >= threshold) {
                minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x); maxY = max(maxY, y)
            }
        }
        require(maxX >= 0) { "No pixels meet the alpha threshold" }
        val bounds = Bounds(max(0, minX - padding), max(0, minY - padding),
            min(width, maxX + 1 + padding), min(height, maxY + 1 + padding))
        val cropWidth = bounds.right - bounds.left; val cropHeight = bounds.bottom - bounds.top
        val longest = max(cropWidth, cropHeight)
        val columns = ceil(cropWidth.toDouble() * detail / longest).toInt().coerceIn(1, min(detail, cropWidth))
        val rows = ceil(cropHeight.toDouble() * detail / longest).toInt().coerceIn(1, min(detail, cropHeight))
        // Integer cell edges keep each source pixel entirely within exactly one cell before padding.
        val xs = IntArray(columns + 1) { bounds.left + it * cropWidth / columns }
        val ys = IntArray(rows + 1) { bounds.top + it * cropHeight / rows }
        val xCell = IntArray(cropWidth); val yCell = IntArray(cropHeight)
        for (x in 0 until columns) for (pixel in xs[x] until xs[x + 1]) xCell[pixel - bounds.left] = x
        for (y in 0 until rows) for (pixel in ys[y] until ys[y + 1]) yCell[pixel - bounds.top] = y

        // A tiny 2D difference array marks padded pixel rectangles in O(pixels + cells) time.
        val stride = columns + 1
        val coverage = IntArray(stride * (rows + 1))
        for (y in minY..maxY) for (x in minX..maxX) {
            if ((alpha[y * width + x].toInt() and 255) < threshold) continue
            val x0 = xCell[max(bounds.left, x - padding) - bounds.left]
            val x1 = xCell[min(bounds.right - 1, x + padding) - bounds.left] + 1
            val y0 = yCell[max(bounds.top, y - padding) - bounds.top]
            val y1 = yCell[min(bounds.bottom - 1, y + padding) - bounds.top] + 1
            coverage[y0 * stride + x0]++; coverage[y0 * stride + x1]--
            coverage[y1 * stride + x0]--; coverage[y1 * stride + x1]++
        }
        val active = BooleanArray(columns * rows)
        for (y in 0 until rows) for (x in 0 until columns) {
            val index = y * stride + x
            coverage[index] += (if (x > 0) coverage[index - 1] else 0) +
                (if (y > 0) coverage[index - stride] else 0) -
                (if (x > 0 && y > 0) coverage[index - stride - 1] else 0)
            active[y * columns + x] = coverage[index] > 0
        }
        val vertices = mutableListOf<Vec2>(); val uv = mutableListOf<Vec2>(); val triangles = mutableListOf<Int>()
        val indices = IntArray((columns + 1) * (rows + 1)) { -1 }
        fun vertex(x: Int, y: Int): Int {
            val key = y * (columns + 1) + x
            if (indices[key] < 0) {
                indices[key] = vertices.size
                vertices.add(Vec2(xs[x] - width / 2f, ys[y] - height / 2f))
                uv.add(Vec2(xs[x].toFloat() / width, ys[y].toFloat() / height))
            }
            return indices[key]
        }
        for (y in 0 until rows) for (x in 0 until columns) {
            if (!active[y * columns + x]) continue
            val a = vertex(x, y); val b = vertex(x + 1, y); val c = vertex(x + 1, y + 1); val d = vertex(x, y + 1)
            triangles.addAll(listOf(a, b, d, b, c, d))
        }
        return Result(Mesh(vertices, uv, triangles), bounds, columns, rows, active.count { it })
    }
}
