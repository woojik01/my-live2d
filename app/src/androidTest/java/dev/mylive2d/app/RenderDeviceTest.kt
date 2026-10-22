package dev.mylive2d.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mylive2d.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RenderDeviceTest {
    @Test fun glTextureAndBlinkChangeVisiblePixels() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>(); val repo=ProjectRepository(context)
        val oldActive=repo.active
        val image=File(context.cacheDir,"render-eye.png")
        val bitmap=Bitmap.createBitmap(128,64,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.CYAN)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        var projectId: String?=null
        try {
            fun waitReady() {
                var ready=false; var attempts=0
                while(!ready && attempts++<60) {
                    scenario.onActivity { ready=!ViewModelProvider(it)[EditorViewModel::class.java].busy }
                    if(!ready) Thread.sleep(100)
                }
                assertTrue("Editor operation completed",ready)
            }
            waitReady()
            scenario.onActivity { ViewModelProvider(it)[EditorViewModel::class.java].newProject("Render QA") }
            waitReady()
            scenario.onActivity { ViewModelProvider(it)[EditorViewModel::class.java].importPng(listOf(Uri.fromFile(image),Uri.fromFile(image))) }
            waitReady()
            var parameter=""; var expected: Project?=null
            scenario.onActivity { activity ->
                val vm=ViewModelProvider(activity)[EditorViewModel::class.java]
                assertEquals(2,vm.project.parts.size)
                vm.project.parts.forEachIndexed { index,part ->
                    vm.select(part.id)
                    vm.updatePart { it.copy(transform=Transform(Vec2(if(index==0) -100f else 100f,0f))) }
                }
                val original=vm.project.parts.first()
                vm.select(original.id); vm.beginGesture(); vm.moveVertex(6,Vec2(0f,10f)); vm.endGesture()
                assertNotEquals(original.mesh.vertices,vm.selected!!.mesh.vertices)
                vm.undo(); assertEquals(original.mesh,vm.selected!!.mesh)
                vm.redo(); assertNotEquals(original.mesh,vm.selected!!.mesh); vm.undo()
                val order=vm.project.parts.map { it.id }
                vm.select(order.last()); vm.reorder(-1); assertNotEquals(order,vm.project.parts.map { it.id })
                vm.undo(); assertEquals(order,vm.project.parts.map { it.id })
                vm.addParameter("Eyes",0f,1f,1f); parameter=vm.parameterId!!
                vm.project.parts.forEach { part -> vm.select(part.id); vm.blink() }
                assertEquals(2,vm.project.parameters.single().bindings.size)
                expected=vm.project; projectId=vm.project.id; vm.save()
            }
            var saved=false; var attempts=0
            while(!saved && attempts++<60) { scenario.onActivity { saved=!ViewModelProvider(it)[EditorViewModel::class.java].dirty }; if(!saved) Thread.sleep(100) }
            assertTrue("Explicit save completed",saved)
            assertEquals(expected,ProjectRepository(context).open(projectId!!))
            Thread.sleep(700); instrumentation.waitForIdleSync()
            val open=instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
            val openCount=cyanPixels(open)
            writeScreenshot(context,"render-open.png",open); open.recycle()
            scenario.onActivity { ViewModelProvider(it)[EditorViewModel::class.java].parameterValue(parameter,0f) }
            Thread.sleep(700); instrumentation.waitForIdleSync()
            val closed=instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
            val closedCount=cyanPixels(closed)
            writeScreenshot(context,"render-closed.png",closed); closed.recycle()
            assertTrue("GL texture should be visible: $openCount",openCount>500)
            assertTrue("Closed eye should shrink: open=$openCount closed=$closedCount",closedCount<openCount*.3)
        } finally {
            scenario.close()
            // ON_STOP saves asynchronously; allow its tiny archive write to complete before test cleanup.
            Thread.sleep(1000)
            repo.active=oldActive
            projectId?.let { repo.delete(it) }; image.delete()
        }
    }
    private fun cyanPixels(bitmap: Bitmap): Int {
        val pixels=IntArray(bitmap.width*bitmap.height); bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        return pixels.count { android.graphics.Color.red(it)<25 && android.graphics.Color.green(it)>225 && android.graphics.Color.blue(it)>225 }
    }
    private fun writeScreenshot(context: Context,name: String,bitmap: Bitmap) {
        val dir=File(context.getExternalFilesDir(null),"qa").apply { mkdirs() }
        File(dir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
