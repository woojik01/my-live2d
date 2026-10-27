package dev.mylive2d.core

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** A correction relative to the ordinary independent-parameter result, not a second absolute pose. */
@Serializable data class CombinationKey(
    val x: Float, val y: Float,
    val points: List<Vec2>, // vertex or warp-control displacements in target-local coordinates
    val position: Vec2 = Vec2(), val rotation: Float = 0f, val scale: Float = 1f
)
@Serializable data class CombinationBinding(
    val id: String = newId(), val targetId: String,
    val xParameterId: String, val yParameterId: String,
    val xValues: List<Float>, val yValues: List<Float>, val keys: List<CombinationKey>
)

object CombinationEngine {
    private const val EPS = .00001f
    fun axis(p: Parameter): List<Float> = listOf(p.min, p.default, p.max).distinct().sorted()
    fun create(project: Project, target: String, xId: String, yId: String): Project {
        require(xId != yId) { "Choose two different parameters" }
        val x=project.parameters.first { it.id==xId }; val y=project.parameters.first { it.id==yId }
        val part=project.parts.firstOrNull { it.id==target }; val node=project.deformers.firstOrNull { it.id==target }
        require(part!=null || node!=null) { "Missing target" }
        require(project.combinations.none { it.targetId==target && setOf(it.xParameterId,it.yParameterId)==setOf(xId,yId) }) { "Combination already exists" }
        val xs=axis(x); val ys=axis(y); val count=part?.mesh?.vertices?.size ?: node!!.controls.size
        val binding=CombinationBinding(targetId=target,xParameterId=xId,yParameterId=yId,xValues=xs,yValues=ys,
            keys=ys.flatMap { yy -> xs.map { xx -> CombinationKey(xx,yy,List(count) { Vec2() }) } })
        return project.copy(combinations=project.combinations+binding).also { Validation.check(it) }
    }
    private fun bounds(axis: List<Float>, value: Float): Triple<Float,Float,Float> {
        val clamped=value.coerceIn(axis.first(),axis.last())
        val hi=axis.firstOrNull { it >= clamped } ?: axis.last()
        val lo=axis.lastOrNull { it <= clamped } ?: axis.first()
        return Triple(lo,hi,if(lo==hi) 0f else (clamped-lo)/(hi-lo))
    }
    fun sample(binding: CombinationBinding, x: Float, y: Float): CombinationKey {
        val (x0,x1,tx)=bounds(binding.xValues,x); val (y0,y1,ty)=bounds(binding.yValues,y)
        fun key(a: Float,b: Float)=binding.keys.first { it.x==a && it.y==b }
        val a=key(x0,y0); val b=key(x1,y0); val c=key(x0,y1); val d=key(x1,y1)
        fun blend(a: Float,b: Float,c: Float,d: Float): Float = (a*(1-tx)+b*tx)*(1-ty)+(c*(1-tx)+d*tx)*ty
        fun vec(a: Vec2,b: Vec2,c: Vec2,d: Vec2)=Vec2(blend(a.x,b.x,c.x,d.x),blend(a.y,b.y,c.y,d.y))
        return CombinationKey(x,y,a.points.indices.map { i -> vec(a.points[i],b.points[i],c.points[i],d.points[i]) },
            vec(a.position,b.position,c.position,d.position),blend(a.rotation,b.rotation,c.rotation,d.rotation),blend(a.scale,b.scale,c.scale,d.scale))
    }
    fun sample(project: Project, binding: CombinationBinding): CombinationKey {
        val x=project.parameters.first { it.id==binding.xParameterId }.value
        val y=project.parameters.first { it.id==binding.yParameterId }.value
        return sample(binding,x,y)
    }
    fun isKey(project: Project, binding: CombinationBinding): Boolean {
        val x=project.parameters.first { it.id==binding.xParameterId }.value
        val y=project.parameters.first { it.id==binding.yParameterId }.value
        return binding.xValues.any { abs(it-x)<EPS } && binding.yValues.any { abs(it-y)<EPS }
    }
    private fun update(project: Project, binding: CombinationBinding, key: CombinationKey): Project {
        require(isKey(project,binding)) { "Select an exact combination key" }
        val x=binding.xValues.first { abs(it-key.x)<EPS }; val y=binding.yValues.first { abs(it-key.y)<EPS }
        val updated=binding.copy(keys=binding.keys.map { if(it.x==x && it.y==y) key.copy(x=x,y=y) else it })
        return project.copy(combinations=project.combinations.map { if(it.id==binding.id) updated else it })
    }
    fun partDraft(project: Project, binding: CombinationBinding, part: Part): List<Vec2> {
        val correction=sample(project,binding)
        return part.mesh.vertices.indices.map { part.mesh.vertices[it]+correction.points[it] }
    }
    fun recordPart(project: Project, binding: CombinationBinding, part: Part, vertices: List<Vec2>): Project {
        require(binding.targetId==part.id && vertices.size==part.mesh.vertices.size && vertices.all { it.finite() })
        val old=sample(project,binding)
        return update(project,binding,old.copy(points=vertices.indices.map { vertices[it]-part.mesh.vertices[it] }))
    }
    fun deformerDraft(project: Project, binding: CombinationBinding, node: Deformer): DeformerPose {
        val k=sample(project,binding); val base=node.basePose()
        return DeformerPose(Transform(base.transform.position+k.position,base.transform.rotation+k.rotation,base.transform.scale*k.scale),
            base.controls.indices.map { base.controls[it]+k.points[it] })
    }
    fun recordDeformer(project: Project, binding: CombinationBinding, node: Deformer, pose: DeformerPose): Project {
        require(binding.targetId==node.id && pose.controls.size==node.controls.size)
        val base=node.basePose(); val old=sample(project,binding)
        return update(project,binding,old.copy(points=pose.controls.indices.map { pose.controls[it]-base.controls[it] },
            position=pose.transform.position-base.transform.position,rotation=pose.transform.rotation-base.transform.rotation,scale=pose.transform.scale/base.transform.scale))
    }
    fun validate(p: Project) {
        require(p.combinations.size<=128 && p.combinations.map { it.id }.distinct().size==p.combinations.size) { "Combination limit or duplicate ID" }
        val pairs=mutableSetOf<Triple<String,String,String>>()
        p.combinations.forEach { b ->
            require(b.id.matches(Regex("[A-Za-z0-9-]{1,80}")))
            val x=p.parameters.firstOrNull { it.id==b.xParameterId } ?: error("Missing combination parameter")
            val y=p.parameters.firstOrNull { it.id==b.yParameterId } ?: error("Missing combination parameter")
            require(x.id!=y.id && pairs.add(Triple(b.targetId,minOf(x.id,y.id),maxOf(x.id,y.id)))) { "Duplicate combination" }
            val part=p.parts.firstOrNull { it.id==b.targetId }; val node=p.deformers.firstOrNull { it.id==b.targetId }
            require(part!=null || node!=null) { "Missing combination target" }
            require(b.xValues==axis(x) && b.yValues==axis(y)) { "Invalid combination axes" }
            require(b.keys.size==b.xValues.size*b.yValues.size)
            require(b.keys.map { it.x to it.y }.toSet().size==b.keys.size)
            val count=part?.mesh?.vertices?.size ?: node!!.controls.size
            b.keys.forEach { k ->
                require(k.x in b.xValues && k.y in b.yValues && k.points.size==count && k.points.all { it.finite() })
                require(k.position.finite() && k.rotation.isFinite() && abs(k.rotation)<=1_000_000 && k.scale.isFinite() && k.scale in .01f..100f)
                if(part!=null) require(k.position==Vec2() && k.rotation==0f && k.scale==1f)
            }
        }
    }
}
