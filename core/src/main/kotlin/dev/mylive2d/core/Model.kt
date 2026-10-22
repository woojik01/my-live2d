package dev.mylive2d.core

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.*

fun newId(): String = UUID.randomUUID().toString()
@Serializable data class Vec2(val x: Float = 0f, val y: Float = 0f) {
    operator fun plus(b: Vec2) = Vec2(x + b.x, y + b.y)
    operator fun minus(b: Vec2) = Vec2(x - b.x, y - b.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    fun finite() = x.isFinite() && y.isFinite() && abs(x) <= 1_000_000 && abs(y) <= 1_000_000
}
@Serializable data class Transform(val position: Vec2 = Vec2(), val rotation: Float = 0f, val scale: Float = 1f) {
    fun apply(p: Vec2): Vec2 {
        val r = rotation * PI.toFloat() / 180
        return Vec2((p.x*cos(r)-p.y*sin(r))*scale, (p.x*sin(r)+p.y*cos(r))*scale) + position
    }
    fun inverse(p: Vec2): Vec2 {
        val d = p - position; val r = -rotation * PI.toFloat() / 180
        return Vec2(d.x*cos(r)-d.y*sin(r), d.x*sin(r)+d.y*cos(r)) * (1f/scale)
    }
}
@Serializable data class Asset(val id: String = newId(), val width: Int, val height: Int)
@Serializable data class Mesh(val vertices: List<Vec2>, val uv: List<Vec2>, val triangles: List<Int>) {
    companion object {
        fun grid(width: Float, height: Float, columns: Int = 4, rows: Int = 4): Mesh {
            require(columns in 1..24 && rows in 1..24)
            val uv = (0..rows).flatMap { y -> (0..columns).map { x -> Vec2(x.toFloat()/columns, y.toFloat()/rows) } }
            val v = uv.map { Vec2((it.x-.5f)*width, (it.y-.5f)*height) }
            val t = (0 until rows).flatMap { y -> (0 until columns).flatMap { x ->
                val a = y*(columns+1)+x; listOf(a,a+1,a+columns+1,a+1,a+columns+2,a+columns+1)
            } }
            return Mesh(v, uv, t)
        }
    }
}
@Serializable data class Part(
    val id: String = newId(), val name: String, val assetId: String,
    val mesh: Mesh, val transform: Transform = Transform(),
    val visible: Boolean = true, val locked: Boolean = false, val opacity: Float = 1f
)
@Serializable data class Keyform(val value: Float, val vertices: List<Vec2>)
@Serializable data class Binding(val partId: String, val keys: List<Keyform>)
@Serializable data class Parameter(
    val id: String = newId(), val name: String, val min: Float = 0f,
    val default: Float = 1f, val max: Float = 1f, val value: Float = default,
    val bindings: List<Binding> = emptyList()
)
@Serializable data class Project(
    val format: String = "MyLive2D", val version: Int = 1, val id: String = newId(),
    val name: String = "새 프로젝트", val assets: List<Asset> = emptyList(),
    // First part is backmost. Order is identical in archives, evaluation and rendering.
    val parts: List<Part> = emptyList(), val parameters: List<Parameter> = emptyList()
)

object ParameterEngine {
    fun interpolate(keys: List<Keyform>, value: Float): List<Vec2> {
        require(keys.isNotEmpty())
        val sorted = keys.sortedBy { it.value }
        if (value <= sorted.first().value) return sorted.first().vertices
        if (value >= sorted.last().value) return sorted.last().vertices
        val b = sorted.first { it.value >= value }; val a = sorted.last { it.value < value }
        val t = (value-a.value)/(b.value-a.value)
        return a.vertices.zip(b.vertices) { x,y -> x + (y-x)*t }
    }
    fun vertices(project: Project, part: Part): List<Vec2> {
        var result = part.mesh.vertices
        project.parameters.forEach { p ->
            p.bindings.firstOrNull { it.partId == part.id }?.let { binding ->
                val pose = interpolate(binding.keys, p.value.coerceIn(p.min,p.max))
                result = result.indices.map { result[it] + (pose[it]-part.mesh.vertices[it]) }
            }
        }
        return result
    }
    fun record(project: Project, parameterId: String, partId: String, vertices: List<Vec2>): Project {
        val part = project.parts.first { it.id == partId }
        require(vertices.size == part.mesh.vertices.size && vertices.all { it.finite() })
        return project.copy(parameters = project.parameters.map { p ->
            if (p.id != parameterId) p else {
                val old = p.bindings.firstOrNull { it.partId == partId }
                val keys = old?.keys ?: listOf(Keyform(p.default, part.mesh.vertices))
                val updated = (keys.filter { abs(it.value-p.value) > .00001f } + Keyform(p.value,vertices)).sortedBy { it.value }
                p.copy(bindings = p.bindings.filter { it.partId != partId } + Binding(partId,updated))
            }
        })
    }
    fun blink(project: Project, parameterId: String, partId: String): Project {
        val part = project.parts.first { it.id == partId }
        val p = project.parameters.first { it.id == parameterId }
        val cy = part.mesh.vertices.map { it.y }.average().toFloat()
        val closed = part.mesh.vertices.map { Vec2(it.x, cy + (it.y-cy)*.04f) }
        return project.copy(parameters = project.parameters.map {
            if (it.id != p.id) it else it.copy(bindings = it.bindings.filter { b -> b.partId != partId } +
                Binding(partId,listOf(Keyform(p.min,closed),Keyform(p.max,part.mesh.vertices))))
        })
    }
}

// World units are source image pixels, +Y points down. Camera is independent of parts.
data class Camera(val center: Vec2 = Vec2(), val zoom: Float = 1f) {
    fun screen(p: Vec2, width: Float, height: Float) = (p-center)*zoom + Vec2(width/2,height/2)
    fun world(p: Vec2, width: Float, height: Float) = (p-Vec2(width/2,height/2))*(1f/zoom)+center
    fun gesture(focus: Vec2, pan: Vec2, factor: Float, width: Float, height: Float): Camera {
        val anchor = world(focus,width,height); val z = (zoom*factor).coerceIn(.03f,20f)
        return Camera(anchor - (focus+pan-Vec2(width/2,height/2))*(1f/z),z)
    }
}

class History(initial: Project, private val limit: Int = 40) {
    var current = initial; private set
    private val past = ArrayDeque<Project>(); private val future = ArrayDeque<Project>()
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun commit(next: Project) {
        if (current == next) return
        past.addLast(current); while (past.size > limit) past.removeFirst()
        current = next; future.clear()
    }
    fun undo(): Project { if(canUndo) { future.addLast(current); current = past.removeLast() }; return current }
    fun redo(): Project { if(canRedo) { past.addLast(current); current = future.removeLast() }; return current }
}

object Validation {
    fun check(p: Project) {
        require(p.format == "MyLive2D" && p.version == 1) { "Unsupported project format/version" }
        fun id(s: String) = s.matches(Regex("[A-Za-z0-9-]{1,80}"))
        require(id(p.id) && p.name.isNotBlank() && p.name.length <= 120)
        require(p.assets.size <= 32 && p.parts.size <= 64 && p.parameters.size <= 32)
        require(p.assets.map { it.id }.distinct().size == p.assets.size)
        require(p.parts.map { it.id }.distinct().size == p.parts.size)
        require(p.parameters.map { it.id }.distinct().size == p.parameters.size)
        require(p.assets.sumOf { it.width.toLong()*it.height } <= 16_777_216L)
        p.assets.forEach { require(id(it.id) && it.width in 1..2048 && it.height in 1..2048) }
        p.parts.forEach { part ->
            require(id(part.id) && part.name.length in 1..120 && p.assets.any { it.id == part.assetId })
            val m = part.mesh
            require(m.vertices.size in 3..625 && m.uv.size == m.vertices.size)
            require(m.triangles.isNotEmpty() && m.triangles.size <= 3456 && m.triangles.size%3 == 0)
            require(m.vertices.all { it.finite() } && m.uv.all { it.finite() && it.x in 0f..1f && it.y in 0f..1f })
            require(m.triangles.all { it in m.vertices.indices })
            require(part.transform.position.finite() && part.transform.rotation.isFinite())
            require(part.transform.scale.isFinite() && part.transform.scale in .01f..100f)
            require(part.opacity.isFinite() && part.opacity in 0f..1f)
        }
        p.parameters.forEach { param ->
            require(id(param.id) && param.name.length in 1..120)
            require(listOf(param.min,param.max,param.default,param.value).all { it.isFinite() && abs(it)<=1_000_000 })
            require(param.min < param.max && param.default in param.min..param.max && param.value in param.min..param.max)
            require(param.bindings.size <= p.parts.size && param.bindings.map { it.partId }.distinct().size == param.bindings.size)
            param.bindings.forEach { b ->
                val part = p.parts.firstOrNull { it.id == b.partId }; require(part != null)
                require(b.keys.size in 1..32 && b.keys.map { it.value }.distinct().size == b.keys.size)
                b.keys.forEach { k ->
                    require(k.value.isFinite() && k.value in param.min..param.max)
                    require(k.vertices.size == part.mesh.vertices.size && k.vertices.all { it.finite() })
                }
            }
        }
    }
}
