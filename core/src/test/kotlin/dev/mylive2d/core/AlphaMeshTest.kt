package dev.mylive2d.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.*

class AlphaMeshTest {
    private fun covered(mesh: Mesh, width: Int, height: Int, x: Int, y: Int) =
        MeshTopology.hit(mesh,mesh.vertices,Vec2(x+.5f-width/2f,y+.5f-height/2f)) != null

    @Test fun trimsTransparentMarginsWithoutMovingTheTexture() {
        val alpha=ByteArray(80*60)
        for(y in 18..35) for(x in 21..50) alpha[y*80+x]=255.toByte()
        val result=AlphaMesh.generate(80,60,alpha,detail=12,padding=2)
        assertEquals(AlphaMesh.Bounds(19,16,53,38),result.bounds)
        assertEquals(12,result.columns); assertEquals(8,result.rows)
        assertEquals(result.totalCells,result.activeCells)
        result.mesh.vertices.zip(result.mesh.uv).forEach { (vertex,uv) ->
            assertEquals((uv.x-.5f)*80,vertex.x,.00001f)
            assertEquals((uv.y-.5f)*60,vertex.y,.00001f)
        }
        for(y in 18..35) for(x in 21..50) assertTrue(covered(result.mesh,80,60,x,y))
        assertFalse(covered(result.mesh,80,60,0,0))
    }

    @Test fun retainsSeparateIslandsAndLeavesLargeHolesEmpty() {
        val alpha=ByteArray(24*24)
        for(y in 2..10) for(x in 2..10) if(x !in 5..7 || y !in 5..7) alpha[y*24+x]=255.toByte()
        for(y in 18..20) for(x in 18..20) alpha[y*24+x]=255.toByte()
        val result=AlphaMesh.generate(24,24,alpha,24,padding=0)
        assertEquals(81-9+9,result.activeCells)
        assertTrue(result.activeCells<result.totalCells)
        assertTrue(covered(result.mesh,24,24,3,3))
        assertTrue(covered(result.mesh,24,24,19,19))
        assertFalse(covered(result.mesh,24,24,6,6))
        assertFalse(covered(result.mesh,24,24,15,15))
    }

    @Test fun alphaIsUnsignedAndThresholdAndPaddingAreApplied() {
        val alpha=ByteArray(9*9)
        alpha[4*9+4]=255.toByte(); alpha[0]=127; alpha[8*9+8]=128.toByte()
        val strict=AlphaMesh.generate(9,9,alpha,24,threshold=200,padding=0)
        assertEquals(AlphaMesh.Bounds(4,4,5,5),strict.bounds)
        assertEquals(1,strict.activeCells)
        val padded=AlphaMesh.generate(9,9,alpha,24,threshold=200,padding=2)
        assertEquals(AlphaMesh.Bounds(2,2,7,7),padded.bounds)
        assertEquals(25,padded.activeCells)
        assertTrue(covered(padded.mesh,9,9,2,2))
        assertFalse(covered(padded.mesh,9,9,1,1))
        assertEquals(AlphaMesh.Bounds(4,4,9,9),AlphaMesh.generate(9,9,alpha,24,128,0).bounds)
        assertEquals(AlphaMesh.Bounds(0,0,9,9),AlphaMesh.generate(9,9,alpha,24,127,0).bounds)
    }

    @Test fun narrowImagesAndUnevenCellsHaveNoMissingPixelsOrDegenerateTriangles() {
        for((width,height) in listOf(1 to 1,1 to 43,57 to 1,31 to 17,2048 to 2)) {
            val result=AlphaMesh.generate(width,height,ByteArray(width*height) { 255.toByte() },24,padding=0)
            for(y in 0 until height) for(x in 0 until width) assertTrue(covered(result.mesh,width,height,x,y))
            assertMeshValid(result.mesh,width,height)
        }
    }

    @Test fun sparseMasksAreDeterministicCoveredAndBounded() {
        val random=Random(806)
        repeat(12) { iteration ->
            val width=31+iteration; val height=25+iteration
            val alpha=ByteArray(width*height) { if(random.nextInt(8)==0) random.nextInt(1,256).toByte() else 0 }
            val detail=4+iteration; val threshold=64; val padding=iteration%3
            val result=AlphaMesh.generate(width,height,alpha,detail,threshold,padding)
            assertEquals(result,AlphaMesh.generate(width,height,alpha,detail,threshold,padding))
            alpha.forEachIndexed { i,a -> if((a.toInt() and 255)>=threshold) assertTrue(covered(result.mesh,width,height,i%width,i/width)) }
            assertMeshValid(result.mesh,width,height)
        }
        val maximum=AlphaMesh.generate(2048,2048,ByteArray(2048*2048) { 255.toByte() },24)
        assertEquals(625,maximum.mesh.vertices.size); assertEquals(3456,maximum.mesh.triangles.size)
    }

    private fun assertMeshValid(mesh: Mesh,width: Int,height: Int) {
        val asset=Asset(width=width,height=height)
        Validation.check(Project(assets=listOf(asset),parts=listOf(Part(name="Auto",assetId=asset.id,mesh=mesh))))
        assertEquals(mesh.vertices.indices.toSet(),mesh.triangles.toSet())
        val edges=mutableMapOf<Pair<Int,Int>,Int>()
        mesh.triangles.chunked(3).forEach { t ->
            val a=mesh.vertices[t[0]]; val b=mesh.vertices[t[1]]; val c=mesh.vertices[t[2]]
            assertTrue((b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)>0)
            for(i in 0..2) {
                val edge=minOf(t[i],t[(i+1)%3]) to maxOf(t[i],t[(i+1)%3])
                edges[edge]=(edges[edge] ?: 0)+1
            }
        }
        assertTrue(edges.values.all { it in 1..2 })
    }

    @Test fun rebuildingResetsOnlyTargetKeysAndUndoArchiveRestoreAllData() {
        val asset=Asset(width=24,height=24)
        val left=Part(name="Left",assetId=asset.id,mesh=Mesh.grid(24f,24f),transform=Transform(Vec2(70f,15f),27f,1.5f))
        val right=left.copy(id=newId(),name="Right")
        var original=Project(assets=listOf(asset),parts=listOf(left,right),parameters=listOf(Parameter(name="Eye")))
        original.parts.forEach { original=ParameterEngine.blink(original,original.parameters.single().id,it.id) }
        val node=Deformer(name="Parent",kind=DeformerKind.ROTATION)
        original=original.copy(deformers=listOf(node),parts=original.parts.map { it.copy(parentId=node.id) })
        val mesh=AlphaMesh.generate(24,24,ByteArray(576) { if(it%24 in 3..20 && it/24 in 5..18) 255.toByte() else 0 }).mesh
        val next=MeshTopology.replace(original,left.id,mesh)
        assertEquals(original.parts.first().copy(mesh=mesh),next.parts.first())
        assertEquals(original.parts.last(),next.parts.last())
        assertEquals(original.parameters.single().bindings.filter { it.partId==right.id },next.parameters.single().bindings)
        assertEquals(original.deformers,next.deformers)
        val history=History(original); history.commit(next)
        assertEquals(original,history.undo()); assertEquals(next,history.redo())
        val png=byteArrayOf(137.toByte(),80,78,71,13,10,26,10,0)
        val bytes=ByteArrayOutputStream().also { ProjectArchive.write(next,mapOf(asset.id to png),it) }.toByteArray()
        assertEquals(next,ProjectArchive.read(ByteArrayInputStream(bytes)).project)
        assertFails { MeshTopology.replace(original,"missing",mesh) }
        assertFails { MeshTopology.replace(original,left.id,mesh.copy(triangles=listOf(0,1,9999))) }
    }

    @Test fun emptyMasksAndInvalidOptionsAreRejected() {
        assertFails { AlphaMesh.generate(3,3,ByteArray(9)) }
        assertFails { AlphaMesh.generate(3,3,ByteArray(9) { 100 },threshold=101) }
        assertFails { AlphaMesh.generate(0,3,ByteArray(0)) }
        assertFails { AlphaMesh.generate(2049,1,ByteArray(2049)) }
        assertFails { AlphaMesh.generate(3,3,ByteArray(8)) }
        for(detail in listOf(0,25)) assertFails { AlphaMesh.generate(1,1,byteArrayOf(-1),detail) }
        for(threshold in listOf(0,256)) assertFails { AlphaMesh.generate(1,1,byteArrayOf(-1),threshold=threshold) }
        for(padding in listOf(-1,17)) assertFails { AlphaMesh.generate(1,1,byteArrayOf(-1),padding=padding) }
    }
}
