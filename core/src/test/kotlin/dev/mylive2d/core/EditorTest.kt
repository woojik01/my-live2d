package dev.mylive2d.core

import kotlin.test.*
import java.io.*
import java.util.zip.*

class EditorTest {
    private val png = byteArrayOf(137.toByte(),80,78,71,13,10,26,10,0)
    private fun fixture(): Project {
        val a=Asset(width=128,height=64); val b=Asset(width=128,height=64)
        return Project(name="눈 테스트",assets=listOf(a,b),parts=listOf(
            Part(name="왼쪽 눈",assetId=a.id,mesh=Mesh.grid(128f,64f),transform=Transform(Vec2(-100f,0f))),
            Part(name="오른쪽 눈",assetId=b.id,mesh=Mesh.grid(128f,64f),transform=Transform(Vec2(100f,0f)))
        ),parameters=listOf(Parameter(name="눈 개폐")))
    }
    private fun archive(p: Project): ByteArray = ByteArrayOutputStream().also { out -> ProjectArchive.write(p,p.assets.associate { it.id to png },out) }.toByteArray()
    @Test fun twoEyesBlinkInterpolateAndSurviveReopen() {
        var p=fixture(); val param=p.parameters.single()
        p.parts.forEach { p=ParameterEngine.blink(p,param.id,it.id) }
        val restored=ProjectArchive.read(ByteArrayInputStream(archive(p))).project
        assertEquals(p,restored)
        for(part in restored.parts) {
            fun pose(value: Float)=ParameterEngine.vertices(restored.copy(parameters=restored.parameters.map { it.copy(value=value) }),part)
            val closed=pose(0f); val middle=pose(.5f); val open=pose(1f)
            assertEquals(part.mesh.vertices,open)
            assertTrue(closed.maxOf { it.y }-closed.minOf { it.y } < 3f)
            open.indices.forEach { i -> assertEquals((closed[i].y+open[i].y)/2,middle[i].y,.00001f) }
        }
    }
    @Test fun meshUvTrianglesAndVertexEdit() {
        val m=Mesh.grid(100f,80f,3,2)
        assertEquals(12,m.vertices.size); assertEquals(36,m.triangles.size)
        assertEquals(Vec2(0f,0f),m.uv.first()); assertEquals(Vec2(1f,1f),m.uv.last())
        val p=fixture(); val part=p.parts.first(); val moved=part.mesh.vertices.mapIndexed { i,v -> if(i==4) v+Vec2(2f,8f) else v }
        val next=p.copy(parts=p.parts.map { if(it.id==part.id) it.copy(mesh=it.mesh.copy(vertices=moved)) else it })
        assertEquals(next,ProjectArchive.read(ByteArrayInputStream(archive(next))).project)
    }
    @Test fun customKeyAndEndpointClamping() {
        val p=fixture(); val part=p.parts.first(); val param=p.parameters.single()
        val atMin=p.copy(parameters=listOf(param.copy(value=0f)))
        val points=part.mesh.vertices.map { it+Vec2(10f,-4f) }
        val next=ParameterEngine.record(atMin,param.id,part.id,points)
        val keys=next.parameters.single().bindings.single().keys
        assertEquals(points,ParameterEngine.interpolate(keys,-10f))
        assertEquals(part.mesh.vertices,ParameterEngine.interpolate(keys,10f))
        val half=ParameterEngine.interpolate(keys,.5f)
        assertEquals(part.mesh.vertices[0].x+5,half[0].x,.00001f)
    }
    @Test fun historyUndoRedoAndBranching() {
        val p=fixture(); val history=History(p,2); val b=p.copy(name="B"); val c=b.copy(parts=b.parts.reversed())
        history.commit(b); history.commit(c); assertEquals(b,history.undo()); assertEquals(p,history.undo())
        assertEquals(b,history.redo()); history.commit(b.copy(name="D")); assertFalse(history.canRedo)
        assertEquals(b,history.undo())
    }
    @Test fun coordinateRoundTripAcrossResizeRotationZoom() {
        val camera=Camera(Vec2(120f,-70f),2.4f); val transform=Transform(Vec2(90f,40f),37f,1.7f); val p=Vec2(12f,-15f)
        for((w,h) in listOf(1080f to 1920f,1920f to 1080f,1400f to 900f)) {
            val screen=camera.screen(transform.apply(p),w,h); val restored=transform.inverse(camera.world(screen,w,h))
            assertEquals(p.x,restored.x,.0001f); assertEquals(p.y,restored.y,.0001f)
            val focus=Vec2(w*.3f,h*.8f); val next=camera.gesture(focus,Vec2(),2f,w,h)
            assertEquals(camera.world(focus,w,h).x,next.world(focus,w,h).x,.0001f)
            assertEquals(camera.world(focus,w,h).y,next.world(focus,w,h).y,.0001f)
        }
    }
    @Test fun reorderAndVisibilityArePreserved() {
        val p=fixture(); val next=p.copy(parts=p.parts.reversed().map { it.copy(visible=false,locked=true,opacity=.4f) })
        assertEquals(next,ProjectArchive.read(ByteArrayInputStream(archive(next))).project)
    }
    @Test fun unsupportedVersionAndInvalidIndicesRejected() {
        val p=fixture(); assertFails { archive(p.copy(version=999)) }
        assertFails { archive(p.copy(parts=listOf(p.parts.first().copy(mesh=p.parts.first().mesh.copy(triangles=listOf(0,1,99999)))))) }
        assertFails { archive(p.copy(parameters=listOf(p.parameters.single().copy(value=Float.NaN)))) }
    }
    private fun zip(entries: List<Pair<String,ByteArray>>) = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { z -> entries.forEach { (name,bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } } }.toByteArray()
    @Test fun malformedAndMissingAssetsRejected() {
        assertFails { ProjectArchive.read(ByteArrayInputStream(byteArrayOf(1,2,3))) }
        assertFails { ProjectArchive.read(ByteArrayInputStream(zip(listOf("project.json" to "{broken".toByteArray())))) }
        val p=fixture(); val bytes=archive(p)
        val stripped=ByteArrayOutputStream()
        ZipOutputStream(stripped).use { dst -> ZipInputStream(ByteArrayInputStream(bytes)).use { src ->
            while(true) { val e=src.nextEntry ?: break; if(e.name=="project.json") { dst.putNextEntry(ZipEntry(e.name)); src.copyTo(dst); dst.closeEntry() } }
        } }
        assertFails { ProjectArchive.read(ByteArrayInputStream(stripped.toByteArray())) }
    }
    @Test fun pathTraversalAndOversizedMetadataRejected() {
        assertFails { ProjectArchive.read(ByteArrayInputStream(zip(listOf("../escape.png" to png)))) }
        assertFails { ProjectArchive.read(ByteArrayInputStream(zip(listOf("project.json" to ByteArray(8*1024*1024+1))))) }
    }
    @Test fun excessiveDimensionsAndInvalidKeysRejected() {
        val p=fixture(); assertFails { Validation.check(p.copy(assets=p.assets.map { it.copy(width=999999) })) }
        val part=p.parts.first(); val param=p.parameters.single()
        val binding=Binding(part.id,listOf(Keyform(.5f,listOf(Vec2()))))
        assertFails { Validation.check(p.copy(parameters=listOf(param.copy(bindings=listOf(binding))))) }
    }
}
