package dev.mylive2d.core

import kotlin.test.*

class ReparentTest {
    private fun close(a: List<Vec2>, b: List<Vec2>) {
        assertEquals(a.size,b.size)
        a.zip(b).forEach { (x,y) -> assertEquals(x.x,y.x,.002f); assertEquals(x.y,y.y,.002f) }
    }
    @Test fun preservesPartAndChildDeformerAcrossParameterAndCombinationPoses() {
        val asset=Asset(width=100,height=80)
        val a=Deformer(name="Old",pivot=Vec2(12f,8f),transform=Transform(Vec2(40f,-20f),32f,1.4f))
        val b=Deformer(name="New",pivot=Vec2(-4f,18f),transform=Transform(Vec2(-10f,30f),-21f,.8f))
        val child=Deformer(name="Child",parentId=a.id,pivot=Vec2(5f,7f),transform=Transform(Vec2(2f,3f),6f,1.1f))
        val part=Part(name="Eye",assetId=asset.id,parentId=child.id,mesh=Mesh.grid(100f,80f,2,2))
        val x=Parameter(name="X",min=0f,default=0f,max=1f,value=1f); val y=Parameter(name="Y",min=0f,default=0f,max=1f,value=1f)
        var p=Project(assets=listOf(asset),parts=listOf(part),deformers=listOf(a,b,child),parameters=listOf(x,y))
        p=Rigging.record(p,x.id,child.id,child.basePose().copy(transform=Transform(Vec2(8f,9f),35f,1.4f)))
        p=CombinationEngine.create(p,child.id,x.id,y.id)
        val combo=p.combinations.single(); val draft=CombinationEngine.deformerDraft(p,combo,child)
        p=CombinationEngine.recordDeformer(p,combo,child,draft.copy(transform=draft.transform.copy(position=draft.transform.position+Vec2(9f,2f),rotation=draft.transform.rotation+5f)))
        val moved=Rigging.reparentPreserving(p,child.id,b.id)
        for(value in listOf(0f,.25f,1f)) {
            val before=p.copy(parameters=p.parameters.map { it.copy(value=value) })
            val after=moved.copy(parameters=moved.parameters.map { it.copy(value=value) })
            close(RigEvaluator(before).partWorld(part),RigEvaluator(after).partWorld(after.parts.single()))
        }
        val direct=p.copy(parts=listOf(part.copy(parentId=a.id)))
        val directMoved=Rigging.reparentPreserving(direct,part.id,b.id)
        close(RigEvaluator(direct).partWorld(direct.parts.single()),RigEvaluator(directMoved).partWorld(directMoved.parts.single()))
    }
    @Test fun warpedAnimatedAndCyclicParentsAreRejected() {
        val asset=Asset(width=20,height=20); val part=Part(name="p",assetId=asset.id,mesh=Mesh.grid(20f,20f))
        val raw=Deformer(name="w",kind=DeformerKind.WARP); val warp=raw.copy(controls=raw.restControls())
        val p=Project(assets=listOf(asset),parts=listOf(part),deformers=listOf(warp))
        assertFails { Rigging.reparentPreserving(p,part.id,warp.id) }
        val node=Deformer(name="rot"); val parameter=Parameter(name="x")
        var rig=p.copy(deformers=listOf(node),parameters=listOf(parameter)); rig=Rigging.record(rig,parameter.id,node.id,node.basePose())
        assertFails { Rigging.reparentPreserving(rig,part.id,node.id) }
        assertFails { Rigging.reparentPreserving(rig,node.id,node.id) }
        assertNull(rig.parts.single().parentId)
    }
}
