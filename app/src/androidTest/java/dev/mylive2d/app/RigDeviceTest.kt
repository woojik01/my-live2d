package dev.mylive2d.app

import android.content.Context
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Build
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
            var groupId=""; var partId=""; var translationWorld=20f
            scenario.onActivity { activity ->
                val vm=ViewModelProvider(activity)[EditorViewModel::class.java]; projectId=vm.project.id; partId=vm.selectedId!!
                vm.addDeformer(DeformerKind.WARP); val warpId=vm.selectedDeformerId!!
                vm.addDeformer(DeformerKind.ROTATION); val rotateId=vm.selectedDeformerId!!
                vm.addDeformer(DeformerKind.GROUP); groupId=vm.selectedDeformerId!!
                val surface=findSurface(activity.window.decorView) ?: error("No GL surface")
                fun drag(world: Vec2,delta: Vec2) {
                    val screen=vm.camera.screen(world,surface.width.toFloat(),surface.height.toFloat()); val time=SystemClock.uptimeMillis()
                    listOf(MotionEvent.ACTION_DOWN to Vec2(),MotionEvent.ACTION_MOVE to delta,MotionEvent.ACTION_UP to delta).forEachIndexed { i,(action,offset) ->
                        val event=MotionEvent.obtain(time,time+i*30L,action,screen.x+offset.x,screen.y+offset.y,0)
                        surface.dispatchTouchEvent(event); event.recycle()
                    }
                }
                val center=RigEvaluator(vm.project).pivotWorld(vm.selectedDeformer!!)
                drag(center,Vec2(12f,8f))
                assertEquals(12f,vm.selectedDeformer!!.transform.position.x,.1f)
                assertEquals(8f,vm.selectedDeformer!!.transform.position.y,.1f)
                assertEquals(0f,vm.selectedDeformer!!.transform.rotation,.001f)
                vm.updateDeformerPose { it.copy(transform=Transform(Vec2(-200f,0f))) }
                vm.addParameter("Angle",0f,0f,1f); val angle=vm.parameterId!!
                vm.selectDeformer(rotateId); vm.parameterValue(angle,1f); vm.startKey()
                vm.updateDeformerPose { it.copy(transform=it.transform.copy(rotation=35f)) }; vm.commitKey()
                vm.addParameter("Warp",0f,0f,1f); val warpParameter=vm.parameterId!!
                vm.selectDeformer(warpId); vm.parameterValue(warpParameter,1f); vm.startKey()
                vm.updateDeformerPose { it.copy(controls=it.controls.map { p -> Vec2(p.x+8f,p.y*.7f) }) }
                val controlBefore=RigEvaluator(vm.renderProject).controlWorld(vm.selectedDeformer!!).first()
                drag(controlBefore,Vec2(10f,5f))
                val controlAfter=RigEvaluator(vm.renderProject).controlWorld(vm.selectedDeformer!!).first()
                assertEquals(controlBefore.x+10,controlAfter.x,.15f); assertEquals(controlBefore.y+5,controlAfter.y,.15f)
                vm.commitKey()
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
                val worldPoints=RigEvaluator(vm.project).partWorld(vm.project.parts.single())
                val density=activity.resources.displayMetrics.density
                val dp=activity.resources.configuration.screenWidthDp
                val overlay=if(dp>=850) 0f else (if(dp<500) 196 else 250)*density
                val available=surface.width-overlay
                assertTrue("Visible canvas width",available>80f)
                val minX=worldPoints.minOf { it.x }; val maxX=worldPoints.maxOf { it.x }
                val minY=worldPoints.minOf { it.y }; val maxY=worldPoints.maxOf { it.y }
                val zoom=minOf(1f,(available-60)/(maxX-minX),(surface.height-80)/(maxY-minY)).coerceAtLeast(.03f)
                val targetX=available/2-10
                vm.camera=Camera(Vec2((minX+maxX)/2-(targetX-surface.width/2)/zoom,(minY+maxY)/2),zoom)
                translationWorld=20/zoom

            }
            Thread.sleep(600); instrument.waitForIdleSync()
            val beforeBitmap=instrument.uiAutomation.takeScreenshot() ?: error("No screenshot")
            writeQa(context,"rig-before.png",beforeBitmap)
            val before=cyanCentroid(beforeBitmap)
            scenario.onActivity { activity ->
                val vm=ViewModelProvider(activity)[EditorViewModel::class.java]
                vm.updateDeformerPose { it.copy(transform=it.transform.copy(position=it.transform.position+Vec2(translationWorld,0f))) }
            }
            Thread.sleep(600); instrument.waitForIdleSync()
            val afterBitmap=instrument.uiAutomation.takeScreenshot() ?: error("No screenshot")
            writeQa(context,"rig-after.png",afterBitmap)
            val after=cyanCentroid(afterBitmap)
            assertEquals("OpenGL follows group transform without viewport clipping",20f,after-before,3f)
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
        assertEquals(3,restored.version)
        assertArrayEquals(original,File(file.parentFile,"${old.id}.v1copy").readBytes())
        repo.save(restored); assertEquals(3,file.inputStream().use { ProjectArchive.read(it).sourceVersion })
        repo.delete(old.id)
    }
    private fun findSurface(view: View): ModelSurface? {
        if(view is ModelSurface) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) findSurface(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun writeQa(context: Context,name: String,bitmap: Bitmap) {
        if(Build.VERSION.SDK_INT<29) return
        val values=ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,name); put(MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/MyLive2D-QA"); put(MediaStore.Images.Media.IS_PENDING,1)
        }
        val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: return
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING,0); context.contentResolver.update(uri,values,null,null)
    }
    private fun cyanCentroid(bitmap: Bitmap): Float {
        val pixels=IntArray(bitmap.width*bitmap.height); val width=bitmap.width
        bitmap.getPixels(pixels,0,width,0,0,width,bitmap.height); bitmap.recycle()
        var x=0.0; var count=0
        pixels.forEachIndexed { i,p -> if(android.graphics.Color.red(p)<25 && android.graphics.Color.green(p)>225 && android.graphics.Color.blue(p)>225) { x+=i%width; count++ } }
        assertTrue("Rendered texture is visible",count>500); return (x/count).toFloat()
    }
}
