package dev.mylive2d.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

class CombinationTest {
    private fun fixture(): Project {
        val asset=Asset(width=100,height=100)
        return Project(assets=listOf(asset),parts=listOf(Part(name="Eye",assetId=asset.id,mesh=Mesh.grid(100f,100f,2,2))),
            parameters=listOf(Parameter(name="Face",min=-1f,default=0f,max=1f,value=1f),Parameter(name="Eye",min=0f,default=1f,max=1f,value=0f)))
    }
    private fun values(p: Project,x: Float,y: Float)=p.copy(parameters=p.parameters.mapIndexed { i, param -> param.copy(value=if(i==0)x else y) })
    @Test fun correctionInterpolatesWithoutBakingIndependentKeys() {
        var p=fixture(); val part=p.parts.single(); val x=p.parameters[0]; val y=p.parameters[1]
        p=ParameterEngine.record(p,x.id,part.id,part.mesh.vertices.map { it+Vec2(10f,0f) })
        p=ParameterEngine.record(p,y.id,part.id,part.mesh.vertices.map { it+Vec2(0f,6f) })
        val independent=ParameterEngine.vertices(p,part)
        p=CombinationEngine.create(p,part.id,x.id,y.id); val combo=p.combinations.single()
        assertEquals(independent,ParameterEngine.vertices(p,part))
        val draft=CombinationEngine.partDraft(p,combo,part).map { it+Vec2(8f,4f) }
        p=CombinationEngine.recordPart(p,combo,part,draft); Validation.check(p)
        assertEquals(independent.first()+Vec2(8f,4f),ParameterEngine.vertices(p,part).first())
        val halfway=values(p,.5f,.5f)
        val correction=CombinationEngine.sample(halfway,combo.copy(keys=p.combinations.single().keys))
        assertEquals(2f,correction.points.first().x,.0001f)
        assertEquals(1f,correction.points.first().y,.0001f)
        val noCorrection=halfway.copy(combinations=emptyList())
        assertEquals(ParameterEngine.vertices(noCorrection,part).first()+Vec2(2f,1f),ParameterEngine.vertices(halfway,part).first())
        assertFails { CombinationEngine.recordPart(halfway,p.combinations.single(),part,draft) }
    }
    @Test fun deformerCorrectionAndWarpGridResample() {
        var p=fixture(); var d=Deformer(name="Warp",kind=DeformerKind.WARP,origin=Vec2(-50f,-50f),width=100f,height=100f)
        d=d.copy(controls=d.restControls()); p=p.copy(deformers=listOf(d))
        p=Rigging.record(p,p.parameters[0].id,d.id,d.basePose().copy(transform=Transform(rotation=10f)))
        p=CombinationEngine.create(p,d.id,p.parameters[0].id,p.parameters[1].id)
        val c=p.combinations.single(); val draft=CombinationEngine.deformerDraft(p,c,d)
        p=CombinationEngine.recordDeformer(p,c,d,draft.copy(transform=Transform(position=Vec2(4f,2f),rotation=5f,scale=1.2f),controls=draft.controls.map { it+Vec2(3f,0f) }))
        assertEquals(15f,Rigging.pose(p,d).transform.rotation,.0001f)
        assertEquals(Vec2(4f,2f),Rigging.pose(p,d).transform.position)
        val before=Rigging.pose(p,d); val resized=Rigging.resizeWarp(p,d.id,4,4); val newNode=resized.deformers.single()
        Validation.check(resized)
        val after=Rigging.pose(resized,newNode)
        assertEquals(25,after.controls.size); assertEquals(before.transform,after.transform)
        for(point in listOf(Vec2(-45f,-20f),Vec2(),Vec2(30f,15f))) {
            val a=RigEvaluator.warp(d,before.controls,point); val b=RigEvaluator.warp(newNode,after.controls,point)
            assertEquals(a.x,b.x,.001f); assertEquals(a.y,b.y,.001f)
        }
    }
    @Test fun topologyAndDeletionPropagateCorrections() {
        var p=fixture(); val part=p.parts.single(); p=CombinationEngine.create(p,part.id,p.parameters[0].id,p.parameters[1].id)
        val original=p
        p=MeshTopology.insert(p,part.id,MeshTopology.Hit(0,listOf(.2f,.3f,.5f)))
        assertTrue(p.combinations.single().keys.all { it.points.size==part.mesh.vertices.size+1 })
        p=MeshTopology.remove(p,part.id,p.parts.single().mesh.vertices.lastIndex)
        assertTrue(p.combinations.single().keys.all { it.points.size==part.mesh.vertices.size })
        assertTrue(MeshTopology.replace(p,part.id,Mesh.grid(100f,100f)).combinations.isEmpty())
        val history=History(original); history.commit(p); assertEquals(original,history.undo()); assertEquals(p,history.redo())
    }
    @Test fun migrationArchiveAndValidation() {
        var p=fixture(); p=CombinationEngine.create(p,p.parts.single().id,p.parameters[0].id,p.parameters[1].id)
        val image=byteArrayOf(137.toByte(),80,78,71,13,10,26,10,0)
        val out=ByteArrayOutputStream(); ProjectArchive.write(p,mapOf(p.assets.single().id to image),out)
        assertEquals(p,ProjectArchive.read(ByteArrayInputStream(out.toByteArray())).project)
        assertEquals(4,ProjectMigration.upgrade(fixture().copy(version=3)).version)
        assertFails { ProjectMigration.upgrade(p.copy(version=3)) }
        assertFails { Validation.check(p.copy(parameters=p.parameters.drop(1))) }
        assertFails { Validation.check(p.copy(combinations=p.combinations+p.combinations.single().copy(id=newId(),xParameterId=p.parameters[1].id,yParameterId=p.parameters[0].id))) }
        assertFails { Validation.check(p.copy(combinations=listOf(p.combinations.single().copy(keys=p.combinations.single().keys.drop(1))))) }
        assertFails { Validation.check(p.copy(combinations=listOf(p.combinations.single().copy(keys=p.combinations.single().keys.map { it.copy(points=listOf(Vec2())) })))) }
    }
}
