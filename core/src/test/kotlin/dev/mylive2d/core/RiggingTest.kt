package dev.mylive2d.core

import kotlin.test.*
import kotlin.math.abs
import java.io.*
import java.util.zip.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RiggingTest {
    private fun fixture(): Project {
        val asset=Asset(width=100,height=80)
        return Project(name="Rig",assets=listOf(asset),parts=listOf(Part(name="Eye",assetId=asset.id,mesh=Mesh.grid(100f,80f,2,2))))
    }
    private fun close(a: Vec2,b: Vec2,tolerance: Float=.005f) { assertEquals(a.x,b.x,tolerance); assertEquals(a.y,b.y,tolerance) }
    private fun warp(parent: String?=null): Deformer {
        val d=Deformer(name="Warp",kind=DeformerKind.WARP,parentId=parent,origin=Vec2(-50f,-40f),width=100f,height=80f)
        return d.copy(controls=d.restControls())
    }
    @Test fun rotationPivotAndHierarchyOrder() {
        val p=fixture(); val group=Deformer(name="Group",kind=DeformerKind.GROUP,transform=Transform(Vec2(10f,20f)))
        val rotation=Deformer(name="Rotation",parentId=group.id,pivot=Vec2(5f,0f),transform=Transform(rotation=90f))
        val part=p.parts.single().copy(parentId=rotation.id,transform=Transform(Vec2(100f,0f)))
        val rig=p.copy(parts=listOf(part),deformers=listOf(group,rotation)); Validation.check(rig)
        // Local (-50,-40) -> part (50,-40) -> rotate about (5,0): (45,45) -> group (55,65).
        close(Vec2(55f,65f),RigEvaluator(rig).partWorld(part).first())
    }
    @Test fun identityWarpAndOutsideDisplacement() {
        val d=warp(); val outside=Vec2(150f,100f)
        close(outside,RigEvaluator.warp(d,d.controls,outside))
        val shifted=d.controls.map { it+Vec2(12f,-6f) }
        close(outside+Vec2(12f,-6f),RigEvaluator.warp(d,shifted,outside))
        close(Vec2(12f,-6f),RigEvaluator.warp(d,shifted,Vec2()))
    }
    @Test fun bilinearWarpInterpolatesAtCellCenter() {
        var d=warp(); d=d.copy(controls=d.controls.mapIndexed { i,p -> if(i==0) p+Vec2(20f,8f) else p })
        // First cell center has 1/4 weight on its top-left control.
        close(Vec2(-20f,-18f),RigEvaluator.warp(d,d.controls,Vec2(-25f,-20f)))
    }
    @Test fun inverseTouchCoordinatesThroughRotationAndWarp() {
        val p=fixture(); val root=Deformer(name="Root",transform=Transform(Vec2(40f,-20f),23f,1.3f))
        var d=warp(root.id); d=d.copy(transform=Transform(rotation=-17f),controls=d.controls.map { it+Vec2(it.y*.1f,it.x*.05f) })
        val part=p.parts.single().copy(parentId=d.id,transform=Transform(Vec2(3f,7f),13f,.8f))
        val eval=RigEvaluator(p.copy(parts=listOf(part),deformers=listOf(root,d)))
        for(point in listOf(Vec2(),Vec2(-20f,5f),Vec2(35f,25f),Vec2(90f,70f))) {
            val world=eval.partWorld(part,listOf(point)).single()
            close(point,eval.partLocal(part,world),.03f)
        }
    }
    @Test fun isolatedDeformerKeysComposeWithoutDoubleCounting() {
        val p=fixture(); val node=Deformer(name="Rotate")
        val a=Parameter(name="A",min=0f,default=0f,max=1f,value=1f); val b=a.copy(id=newId(),name="B")
        var rig=p.copy(deformers=listOf(node),parameters=listOf(a,b))
        rig=Rigging.record(rig,a.id,node.id,node.basePose().copy(transform=Transform(rotation=30f)))
        assertEquals(Transform(),Rigging.isolatedPose(rig,node,b.id).transform)
        rig=Rigging.record(rig,b.id,node.id,node.basePose().copy(transform=Transform(rotation=10f)))
        assertEquals(40f,Rigging.pose(rig,node).transform.rotation,.001f)
        rig=rig.copy(parameters=rig.parameters.map { it.copy(value=.5f) })
        assertEquals(20f,Rigging.pose(rig,node).transform.rotation,.001f)
        assertEquals(Rigging.pose(rig,node),Rigging.pose(rig.copy(parameters=rig.parameters.reversed()),node))
    }
    @Test fun independentMeshParametersDoNotBakeOtherContributions() {
        val p=fixture(); val part=p.parts.single()
        val a=Parameter(name="X",min=0f,default=0f,max=1f,value=1f); val b=a.copy(id=newId(),name="Y")
        var rig=p.copy(parameters=listOf(a,b))
        rig=ParameterEngine.record(rig,a.id,part.id,part.mesh.vertices.map { it+Vec2(10f,0f) })
        assertEquals(part.mesh.vertices,ParameterEngine.isolatedVertices(rig,part,b.id))
        rig=ParameterEngine.record(rig,b.id,part.id,part.mesh.vertices.map { it+Vec2(0f,6f) })
        close(part.mesh.vertices.first()+Vec2(10f,6f),ParameterEngine.vertices(rig,part).first())
    }
    @Test fun warpAndRotationKeysCoexistAndRoundTrip() {
        val p=fixture(); val root=Deformer(name="Rotate"); val d=warp(root.id); val part=p.parts.single().copy(parentId=d.id)
        val a=Parameter(name="Angle",min=-30f,default=0f,max=30f,value=30f)
        val b=Parameter(name="Warp",min=0f,default=0f,max=1f,value=1f)
        var rig=p.copy(parts=listOf(part),deformers=listOf(root,d),parameters=listOf(a,b))
        rig=Rigging.record(rig,a.id,root.id,root.basePose().copy(transform=Transform(rotation=30f)))
        rig=Rigging.record(rig,b.id,d.id,d.basePose().copy(controls=d.controls.map { it+Vec2(5f,it.x*.1f) }))
        val png=byteArrayOf(137.toByte(),80,78,71,13,10,26,10,0)
        val bytes=ByteArrayOutputStream(); ProjectArchive.write(rig,rig.assets.associate { it.id to png },bytes)
        val restored=ProjectArchive.read(ByteArrayInputStream(bytes.toByteArray())).project
        assertEquals(rig,restored); assertEquals(RigEvaluator(rig).partWorld(part),RigEvaluator(restored).partWorld(restored.parts.single()))
    }
    @Test fun cyclesMissingParentsAndIdCollisionsRejected() {
        val p=fixture(); val a=Deformer(name="A"); val b=Deformer(name="B",parentId=a.id)
        val rig=p.copy(deformers=listOf(a,b))
        assertFails { Rigging.reparent(rig,a.id,b.id) }
        assertFails { Rigging.reparent(rig,a.id,a.id) }
        assertFails { Rigging.reparent(rig,p.parts.single().id,"missing") }
        assertFails { Validation.check(rig.copy(deformers=rig.deformers+Deformer(id=p.parts.single().id,name="collision"))) }
    }
    @Test fun inheritedVisibilityAndLock() {
        val p=fixture(); val d=Deformer(name="Parent",visible=false,locked=true); val part=p.parts.single().copy(parentId=d.id)
        val eval=RigEvaluator(p.copy(parts=listOf(part),deformers=listOf(d)))
        assertFalse(eval.visible(part)); assertTrue(eval.locked(part))
    }
    @Test fun deletingSubtreeCleansBindingsAndUndoRestoresIt() {
        val p=fixture(); val root=Deformer(name="Group",kind=DeformerKind.GROUP); val d=warp(root.id)
        val part=p.parts.single().copy(parentId=d.id); val parameter=Parameter(name="Rig")
        var rig=p.copy(parts=listOf(part),deformers=listOf(root,d),parameters=listOf(parameter))
        rig=Rigging.record(rig,parameter.id,d.id,d.basePose())
        val history=History(rig); val removed=Rigging.removeSubtree(rig,root.id); Validation.check(removed)
        assertTrue(removed.parts.isEmpty() && removed.deformers.isEmpty() && removed.assets.isEmpty())
        assertTrue(removed.parameters.single().deformerBindings.isEmpty())
        history.commit(removed); assertEquals(rig,history.undo())
    }
    @Test fun insertionPropagatesUvAndEveryMeshKey() {
        var p=fixture(); val part=p.parts.single(); val a=Parameter(name="A"); val b=Parameter(name="B")
        p=p.copy(parameters=listOf(a,b)); p=ParameterEngine.blink(p,a.id,part.id); p=ParameterEngine.blink(p,b.id,part.id)
        val hit=MeshTopology.Hit(0,listOf(.2f,.3f,.5f)); val before=p
        p=MeshTopology.insert(p,part.id,hit); val mesh=p.parts.single().mesh
        assertEquals(10,mesh.vertices.size); assertEquals(10,mesh.uv.size); assertEquals(30,mesh.triangles.size)
        p.parameters.forEach { assertTrue(it.bindings.single().keys.all { k -> k.vertices.size==10 }) }
        val triangle=part.mesh.triangles.take(3)
        val expected=triangle.indices.fold(Vec2()) { sum,i -> sum+part.mesh.uv[triangle[i]]*hit.weights[i] }
        close(expected,mesh.uv.last())
        val history=History(before); history.commit(p); assertEquals(before,history.undo()); assertEquals(p,history.redo())
    }
    private fun area(mesh: Mesh)=mesh.triangles.chunked(3).sumOf { t -> val a=mesh.vertices[t[0]]; val b=mesh.vertices[t[1]]; val c=mesh.vertices[t[2]]; abs((b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)).toDouble()/2 }
    @Test fun interiorRemovalRetriangulatesAndPreservesKeys() {
        var p=fixture(); val part=p.parts.single(); val parameter=Parameter(name="Blink")
        p=p.copy(parameters=listOf(parameter)); p=ParameterEngine.blink(p,parameter.id,part.id)
        val removed=MeshTopology.remove(p,part.id,4); val mesh=removed.parts.single().mesh
        assertEquals(8,mesh.vertices.size); assertEquals(area(part.mesh),area(mesh),.01)
        assertTrue(removed.parameters.single().bindings.single().keys.all { it.vertices.size==8 })
        assertFails { MeshTopology.remove(p,part.id,0) }
    }
    @Test fun singularWarpInverseRejectsInsteadOfReturningNan() {
        val p=fixture(); var d=warp(); d=d.copy(controls=d.controls.map { Vec2(it.x,0f) })
        val part=p.parts.single().copy(parentId=d.id); val eval=RigEvaluator(p.copy(parts=listOf(part),deformers=listOf(d)))
        assertFails { eval.partLocal(part,Vec2(0f,10f)) }
    }
    @Test fun legacyArchiveMigratesAndPreservesIdentity() {
        val old=fixture().copy(version=1)
        val png=byteArrayOf(137.toByte(),80,78,71,13,10,26,10,0)
        val bytes=ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("project.json")); zip.write(Json.encodeToString(old).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/${old.assets.single().id}.png")); zip.write(png); zip.closeEntry()
        }
        val bundle=ProjectArchive.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(1,bundle.sourceVersion); assertEquals(old.copy(version=4),bundle.project)
        assertFails { ProjectMigration.upgrade(old.copy(deformers=listOf(Deformer(name="illegal")))) }
    }
    @Test fun invalidGridAndDeformerKeysRejected() {
        val p=fixture(); val d=warp()
        assertFails { Validation.check(p.copy(deformers=listOf(d.copy(controls=emptyList())))) }
        val parameter=Parameter(name="Bad",deformerBindings=listOf(DeformerBinding(d.id,listOf(DeformerKeyform(1f,DeformerPose(controls=listOf(Vec2())))))))
        assertFails { Validation.check(p.copy(deformers=listOf(d),parameters=listOf(parameter))) }
        val dense=(0..1).map { n -> val node=d.copy(id=newId(),name="Dense$n",columns=8,rows=8); node.copy(controls=node.restControls()) }
        val many=(0 until 32).map { n -> Parameter(name="P$n",min=0f,default=0f,max=31f,
            deformerBindings=dense.map { node -> DeformerBinding(node.id,(0..31).map { value -> DeformerKeyform(value.toFloat(),node.basePose()) }) }) }
        assertFails { Validation.check(p.copy(deformers=dense,parameters=many)) }
    }
}
