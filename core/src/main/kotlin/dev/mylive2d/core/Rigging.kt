package dev.mylive2d.core

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable enum class DeformerKind { GROUP, ROTATION, WARP }
@Serializable data class Deformer(
    val id: String = newId(), val name: String,
    val kind: DeformerKind = DeformerKind.ROTATION,
    val parentId: String? = null, val transform: Transform = Transform(),
    val pivot: Vec2 = Vec2(), val origin: Vec2 = Vec2(-150f,-150f),
    val width: Float = 300f, val height: Float = 300f,
    val columns: Int = 2, val rows: Int = 2,
    val controls: List<Vec2> = emptyList(),
    val visible: Boolean = true, val locked: Boolean = false
) {
    fun restControls() = (0..rows).flatMap { y -> (0..columns).map { x ->
        origin + Vec2(width*x/columns,height*y/rows)
    } }
    fun basePose() = DeformerPose(transform,if(kind==DeformerKind.WARP) controls else emptyList())
}
@Serializable data class DeformerPose(val transform: Transform = Transform(), val controls: List<Vec2> = emptyList())
@Serializable data class DeformerKeyform(val value: Float, val pose: DeformerPose)
@Serializable data class DeformerBinding(val deformerId: String, val keys: List<DeformerKeyform>)

object Rigging {
    fun interpolate(keys: List<DeformerKeyform>, value: Float): DeformerPose {
        require(keys.isNotEmpty())
        val sorted=keys.sortedBy { it.value }
        if(value<=sorted.first().value) return sorted.first().pose
        if(value>=sorted.last().value) return sorted.last().pose
        val b=sorted.first { it.value>=value }; val a=sorted.last { it.value<value }
        val t=(value-a.value)/(b.value-a.value)
        return DeformerPose(Transform(a.pose.transform.position+(b.pose.transform.position-a.pose.transform.position)*t,
            a.pose.transform.rotation+(b.pose.transform.rotation-a.pose.transform.rotation)*t,
            a.pose.transform.scale+(b.pose.transform.scale-a.pose.transform.scale)*t),
            a.pose.controls.zip(b.pose.controls) { x,y -> x+(y-x)*t })
    }
    fun isolatedPose(project: Project, node: Deformer, parameterId: String): DeformerPose {
        val p=project.parameters.first { it.id==parameterId }
        return p.deformerBindings.firstOrNull { it.deformerId==node.id }?.let { interpolate(it.keys,p.value) } ?: node.basePose()
    }
    fun pose(project: Project, node: Deformer): DeformerPose {
        val base=node.basePose(); var position=base.transform.position; var rotation=base.transform.rotation
        var scale=base.transform.scale; var controls=base.controls
        project.parameters.forEach { p -> p.deformerBindings.firstOrNull { it.deformerId==node.id }?.let { binding ->
            val pose=interpolate(binding.keys,p.value)
            position += pose.transform.position-base.transform.position
            rotation += pose.transform.rotation-base.transform.rotation
            scale *= pose.transform.scale/base.transform.scale
            controls=controls.indices.map { controls[it]+(pose.controls[it]-base.controls[it]) }
        } }
        return DeformerPose(Transform(position,rotation,scale.coerceIn(.01f,100f)),controls)
    }
    fun record(project: Project, parameterId: String, nodeId: String, pose: DeformerPose): Project {
        val node=project.deformers.first { it.id==nodeId }
        return project.copy(parameters=project.parameters.map { p -> if(p.id!=parameterId) p else {
            val old=p.deformerBindings.firstOrNull { it.deformerId==nodeId }
            val keys=old?.keys ?: listOf(DeformerKeyform(p.default,node.basePose()))
            val updated=(keys.filter { abs(it.value-p.value)>.00001f }+DeformerKeyform(p.value,pose)).sortedBy { it.value }
            p.copy(deformerBindings=p.deformerBindings.filter { it.deformerId!=nodeId }+DeformerBinding(nodeId,updated))
        } })
    }
    /** Reparenting preserves local coordinates. Wrapping an element in an identity node preserves its world pose. */
    fun reparent(project: Project, id: String, parentId: String?): Project {
        require(parentId==null || project.deformers.any { it.id==parentId }) { "Missing parent" }
        require(id!=parentId) { "A node cannot parent itself" }
        val result=when {
            project.parts.any { it.id==id } -> project.copy(parts=project.parts.map { if(it.id==id) it.copy(parentId=parentId) else it })
            project.deformers.any { it.id==id } -> project.copy(deformers=project.deformers.map { if(it.id==id) it.copy(parentId=parentId) else it })
            else -> error("Missing node")
        }
        RigValidation.check(result); return result
    }
    fun subtree(project: Project, root: String): Set<String> {
        val result=mutableSetOf(root)
        var changed=true
        while(changed) { changed=false; project.deformers.forEach { if(it.parentId in result && result.add(it.id)) changed=true } }
        return result
    }
    fun removeSubtree(project: Project, root: String): Project {
        val nodes=subtree(project,root); val removedParts=project.parts.filter { it.parentId in nodes }.map { it.id }.toSet()
        val parts=project.parts.filter { it.id !in removedParts }
        return project.copy(deformers=project.deformers.filter { it.id !in nodes },parts=parts,
            assets=project.assets.filter { a -> parts.any { it.assetId==a.id } },
            parameters=project.parameters.map { p -> p.copy(bindings=p.bindings.filter { it.partId !in removedParts },deformerBindings=p.deformerBindings.filter { it.deformerId !in nodes }) })
    }
}

/** One evaluator per frame/gesture: cache poses; share exactly the same geometry for display and touch. */
class RigEvaluator(val project: Project) {
    private val nodes=project.deformers.associateBy { it.id }
    private val poses=nodes.mapValues { Rigging.pose(project,it.value) }
    fun node(id: String)=nodes.getValue(id)
    fun pose(id: String)=poses.getValue(id)
    fun chain(parentId: String?): List<Deformer> {
        val result=mutableListOf<Deformer>(); var id=parentId
        while(id!=null) { require(result.size<12) { "Hierarchy too deep" }; val d=nodes[id] ?: error("Missing parent"); result.add(d); id=d.parentId }
        return result
    }
    fun visible(part: Part)=part.visible && chain(part.parentId).all { it.visible }
    fun locked(part: Part)=part.locked || chain(part.parentId).any { it.locked }
    fun visible(node: Deformer)=node.visible && chain(node.parentId).all { it.visible }
    fun locked(node: Deformer)=node.locked || chain(node.parentId).any { it.locked }
    fun parentToWorld(parentId: String?,point: Vec2): Vec2 {
        var p=point
        chain(parentId).forEach { node -> p=apply(node,poses.getValue(node.id),p) }
        return p
    }
    fun worldToParent(parentId: String?,point: Vec2): Vec2 {
        var p=point
        chain(parentId).asReversed().forEach { node ->
            val pose=poses.getValue(node.id)
            val target=pose.transform.inverse(p-node.pivot)+node.pivot
            p=if(node.kind==DeformerKind.WARP) unwarp(node,pose.controls,target) else target
        }
        require(p.finite()) { "Coordinates exceed safe editing range" }; return p
    }
    fun partWorld(part: Part,vertices: List<Vec2> = ParameterEngine.vertices(project,part)) = vertices.map { parentToWorld(part.parentId,part.transform.apply(it)) }
    fun partLocal(part: Part,world: Vec2)=part.transform.inverse(worldToParent(part.parentId,world))
    fun controlWorld(node: Deformer): List<Vec2> {
        val pose=poses.getValue(node.id)
        return pose.controls.map { parentToWorld(node.parentId,pose.transform.apply(it-node.pivot)+node.pivot) }
    }
    fun controlLocal(node: Deformer,world: Vec2): Vec2 {
        val pose=poses.getValue(node.id)
        return pose.transform.inverse(worldToParent(node.parentId,world)-node.pivot)+node.pivot
    }
    fun pivotWorld(node: Deformer)=parentToWorld(node.parentId,node.pivot+poses.getValue(node.id).transform.position)
    fun axisWorld(node: Deformer)=parentToWorld(node.parentId,poses.getValue(node.id).transform.apply(Vec2(60f,0f))+node.pivot)
    private fun apply(node: Deformer,pose: DeformerPose,p: Vec2): Vec2 {
        val warped=if(node.kind==DeformerKind.WARP) warp(node,pose.controls,p) else p
        return pose.transform.apply(warped-node.pivot)+node.pivot
    }
    companion object {
        fun warp(node: Deformer,controls: List<Vec2>,point: Vec2): Vec2 {
            val u=((point.x-node.origin.x)/node.width).coerceIn(0f,1f)*node.columns
            val v=((point.y-node.origin.y)/node.height).coerceIn(0f,1f)*node.rows
            val x=floor(u).toInt().coerceIn(0,node.columns-1); val y=floor(v).toInt().coerceIn(0,node.rows-1)
            val fx=u-x; val fy=v-y; val i=y*(node.columns+1)+x
            fun blend(points: List<Vec2>)=points[i]*((1-fx)*(1-fy))+points[i+1]*(fx*(1-fy))+points[i+node.columns+1]*((1-fx)*fy)+points[i+node.columns+2]*(fx*fy)
            val rest=node.origin+Vec2(node.width*u/node.columns,node.height*v/node.rows)
            return point+(blend(controls)-rest)
        }
        private fun unwarp(node: Deformer,controls: List<Vec2>,target: Vec2): Vec2 {
            var p=target; val h=max(node.width,node.height)*.0001f
            repeat(32) {
                val value=warp(node,controls,p); val error=value-target
                if(hypot(error.x,error.y)<.002f) return p
                val dx=(warp(node,controls,p+Vec2(h,0f))-value)*(1/h)
                val dy=(warp(node,controls,p+Vec2(0f,h))-value)*(1/h)
                val det=dx.x*dy.y-dy.x*dx.y
                require(abs(det)>.000001f && det.isFinite()) { "Warp is folded or singular; adjust its controls first" }
                var step=Vec2((error.x*dy.y-error.y*dy.x)/det,(dx.x*error.y-dx.y*error.x)/det)
                val length=hypot(step.x,step.y); val maximum=max(node.width,node.height)*2
                if(length>maximum) step=step*(maximum/length)
                p-=step
                require(p.finite()) { "Warp inverse is outside editing bounds" }
            }
            error("Warp inverse did not converge; adjust its controls first")
        }
    }
}

object RigValidation {
    fun check(p: Project) {
        val nodeIds=p.deformers.map { it.id }.toSet()
        require(p.deformers.size<=64 && nodeIds.size==p.deformers.size)
        require((p.parts.map { it.id }+nodeIds).distinct().size==p.parts.size+nodeIds.size) { "Duplicate node IDs" }
        val byId=p.deformers.associateBy { it.id }
        fun parent(id: String?, self: String) {
            val seen=mutableSetOf(self); var next=id
            while(next!=null) { require(seen.add(next) && seen.size<=13) { "Cyclic or excessively deep hierarchy" }; next=(byId[next] ?: error("Missing parent")).parentId }
        }
        p.parts.forEach { parent(it.parentId,it.id) }
        fun transform(t: Transform) { require(t.position.finite() && t.rotation.isFinite() && abs(t.rotation)<=1_000_000 && t.scale.isFinite() && t.scale in .01f..100f) }
        p.deformers.forEach { d ->
            require(d.id.matches(Regex("[A-Za-z0-9-]{1,80}")) && d.name.isNotBlank() && d.name.length<=120)
            parent(d.parentId,d.id); transform(d.transform); require(d.pivot.finite() && d.origin.finite())
            require(d.width.isFinite() && d.height.isFinite() && d.width in 1f..100_000f && d.height in 1f..100_000f)
            require(d.columns in 1..8 && d.rows in 1..8)
            require(if(d.kind==DeformerKind.WARP) d.controls.size==(d.columns+1)*(d.rows+1) else d.controls.isEmpty())
            require(d.controls.all { it.finite() })
        }
        p.parameters.forEach { parameter ->
            require(parameter.deformerBindings.size<=p.deformers.size && parameter.deformerBindings.map { it.deformerId }.distinct().size==parameter.deformerBindings.size)
            parameter.deformerBindings.forEach { b ->
                val d=byId[b.deformerId] ?: error("Missing deformer binding target")
                require(b.keys.size in 1..32 && b.keys.map { it.value }.distinct().size==b.keys.size)
                b.keys.forEach { k ->
                    require(k.value.isFinite() && k.value in parameter.min..parameter.max); transform(k.pose.transform)
                    require(k.pose.controls.size==d.controls.size && k.pose.controls.all { it.finite() })
                }
            }
        }
    }
}

object ProjectMigration {
    fun upgrade(p: Project): Project {
        require(p.format=="MyLive2D" && p.version in 1..2) { "Unsupported project format/version" }
        if(p.version==1) {
            require(p.deformers.isEmpty() && p.parts.all { it.parentId==null } && p.parameters.all { it.deformerBindings.isEmpty() }) { "Version 1 cannot contain version 2 rigging" }
            return p.copy(version=2)
        }
        return p
    }
}
