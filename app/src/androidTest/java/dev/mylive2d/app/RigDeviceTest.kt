package dev.mylive2d.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mylive2d.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class RigDeviceTest {
    @Test fun rigKeysNativeTouchRenderingAndFreshLaunch() = runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Context>(); val repo=ProjectRepository(context)
        val oldActive=repo.active; val image=File(context.cacheDir,"rig-eye.png")
        val bitmap=Bitmap.createBitmap(128,64,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        var scenario=ActivityScenario.launch(MainActivity::class.java); var projectId: String?=null
        val instrument=InstrumentationRegistry.getInstrumentation()
        fun waitReady() {
            var ready=false; var count=0
            while(!ready && count++<80) { scenario.onActivity { ready=!ViewModelProvider(it)[EditorViewModel::class.java].busy }; if(!ready) Thread.sleep(100) }
            assertTrue("Editor ready",ready)
        }
        try {
            waitReady(); scenario.onActivity { ViewModelProvider(it)[EditorViewModel::class.java].newProject("Phase 2 rig QA") }; waitReady()
            scenario.onActivity { ViewModelProvider(it)[EditorViewModel::class.java].importPng(listOf(Uri.fromFile(image))) }; waitReady()
            var groupId=""; var partId=""
            scenario.onActivity { activity ->
                val vm=ViewModelProvider(activity)[EditorViewModel::class.java]; projectId=vm.project.id; partId=vm.selectedId!!
                vm.addDeformer(DeformerKind.WARP); val warpId=vm.selectedDeformerId!!
                vm.addDeformer(DeformerKind.ROTATION); val rotateId=vm.selectedDeformerId!!
                vm.addDeformer(DeformerKind.GROUP); groupId=vm.selectedDeformerId!!
                vm.updateDeformerPose { it.copy(transform=Transform(Vec2(-200f,0f))) }
                vm.addParameter("Angle",0f,0f,1f); val angle=vm.parameterId!!
                vm.selectDeformer(rotateId); vm.parameterValue(angle,1f); vm.startKey()
                vm.updateDeformerPose { it.copy(transform=it.transform.copy(rotation=35f)) }; vm.commitKey()
                vm.addParameter("Warp",0f,0f,1f); val warpParameter=vm.parameterId!!
                vm.selectDeformer(warpId); vm.parameterValue(warpParameter,1f); vm.startKey()
                vm.updateDeformerPose { it.copy(controls=it.controls.map { p -> Vec2(p.x+8f,p.y*.7f) }) }; vm.commitKey()
                vm.addParameter("Lean",0f,0f,1f); val lean=vm.parameterId!!
                vm.selectDeformer(rotateId); vm.parameterValue(lean,1f); vm.startKey()
                assertEquals(0f,vm.deformerDraft!!.transform.rotation,.001f)
                vm.updateDeformerPose { it.copy(transform=it.transform.copy(rotation=10f)) }; vm.commitKey()
                assertEquals(45f,Rigging.pose(vm.project,vm.selectedDeformer!!).transform.rotation,.001f)
                vm.select(partId); vm.addParameter("Eye",0f,1f,1f); vm.blink()
                val part=vm.selected!!; val eval=RigEvaluator(vm.project); val points=eval.partWorld(part)
                val t=part.mesh.triangles.take(3); val inside=(points[t[0]]+points[t[1]]+points[t[2]])*(1f/3)
                vm.addMeshVertex(inside); assertEquals(26,vm.selected!!.mesh.vertices.size)
                assertTrue(vm.project.parameters.last().bindings.single().keys.all { it.vertices.size==26 })
                vm.removeMeshVertex(25); assertEquals(25,vm.selected!!.mesh.vertices.size)
                vm.undo(); assertEquals(26,vm.selected!!.mesh.vertices.size); vm.redo(); assertEquals(25,vm.selected!!.mesh.vertices.size)
                vm.startKey()
                val surface=findSurface(activity.window.decorView) ?: error("No GL surface")
                val vertex=6; val before=RigEvaluator(vm.renderProject).partWorld(vm.selected!!)[vertex]
                val screen=vm.camera.screen(before,surface.width.toFloat(),surface.height.toFloat())
                val time=SystemClock.uptimeMillis()
                fun touch(action: Int,x: Float,y: Float,offset: Long) {
                    val event=MotionEvent.obtain(time,time+offset,action,x,y,0)
                    surface.dispatchTouchEvent(event); event.recycle()
                }
                touch(MotionEvent.ACTION_DOWN,screen.x,screen.y,0)
                touch(MotionEvent.ACTION_MOVE,screen.x+24,screen.y+13,30)
                touch(MotionEvent.ACTION_UP,screen.x+24,screen.y+13,60)
                val after=RigEvaluator(vm.renderProject).partWorld(vm.selected!!)[vertex]
                assertEquals(before.x+24,after.x,.15f); assertEquals(before.y+13,after.y,.15f)
                vm.commitKey(); vm.selectDeformer(groupId)
            }
            Thread.sleep(600); instrument.waitForIdleSync()
            val before=cyanCentroid(instrument.uiAutomation.takeScreenshot() ?: error("No screenshot"))
            scenario.onActivity { activity ->
                val vm=ViewModelProvider(activity)[EditorViewModel::class.java]
                vm.updateDeformerPose { it.copy(transform=it.transform.copy(position=it.transform.position+Vec2(40f,0f))) }
            }
            Thread.sleep(600); instrument.waitForIdleSync()
            val after=cyanCentroid(instrument.uiAutomation.takeScreenshot() ?: error("No screenshot"))
            assertEquals("OpenGL follows group transform",40f,after-before,4f)
            var expected: Project?=null
            scenario.onActivity { val vm=ViewModelProvider(it)[EditorViewModel::class.java]; expected=vm.project; vm.save() }
            var saved=false; var tries=0
            while(!saved && tries++<80) { scenario.onActivity { saved=!ViewModelProvider(it)[EditorViewModel::class.java].dirty }; if(!saved) Thread.sleep(100) }
            assertTrue(saved); assertEquals(expected,ProjectRepository(context).open(projectId!!))
            scenario.close(); Thread.sleep(800); scenario=ActivityScenario.launch(MainActivity::class.java); waitReady()
            scenario.onActivity { activity ->
                val vm=ViewModelProvider(activity)[EditorViewModel::class.java]
                assertEquals(expected,vm.project)
                assertEquals(RigEvaluator(expected!!).partWorld(expected!!.parts.single()),RigEvaluator(vm.project).partWorld(vm.project.parts.single()))
            }
        } finally { scenario.close(); Thread.sleep(800); repo.active=oldActive; projectId?.let { repo.delete(it) }; image.delete() }
    }
    @Test fun legacyOpenMakesV1BackupBeforeSavingV2() = runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Context>(); val repo=ProjectRepository(context)
        val old=Project(version=1,name="Legacy")
        val file=File(context.filesDir,"projects/${old.id}.mylive2d"); file.parentFile!!.mkdirs()
        ZipOutputStream(file.outputStream()).use { z -> z.putNextEntry(ZipEntry("project.json")); z.write(Json.encodeToString(old).toByteArray()); z.closeEntry() }
        val original=file.readBytes(); val restored=repo.open(old.id)
        assertEquals(2,restored.version)
        assertArrayEquals(original,File(file.parentFile,"${old.id}.v1copy").readBytes())
        repo.save(restored); assertEquals(2,file.inputStream().use { ProjectArchive.read(it).sourceVersion })
        repo.delete(old.id)
    }
    private fun findSurface(view: View): ModelSurface? {
        if(view is ModelSurface) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) findSurface(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun cyanCentroid(bitmap: Bitmap): Float {
        val pixels=IntArray(bitmap.width*bitmap.height); val width=bitmap.width
        bitmap.getPixels(pixels,0,width,0,0,width,bitmap.height); bitmap.recycle()
        var x=0.0; var count=0
        pixels.forEachIndexed { i,p -> if(android.graphics.Color.red(p)<25 && android.graphics.Color.green(p)>225 && android.graphics.Color.blue(p)>225) { x+=i%width; count++ } }
        assertTrue("Rendered texture is visible",count>500); return (x/count).toFloat()
    }
}
