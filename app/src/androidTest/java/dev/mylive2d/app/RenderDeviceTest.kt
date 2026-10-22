package dev.mylive2d.app

import android.content.Context
import android.graphics.Bitmap
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
        val asset=Asset(width=128,height=64)
        val bitmap=Bitmap.createBitmap(128,64,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.CYAN)
        repo.assetFile(asset.id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        var p=Project(name="Render QA",assets=listOf(asset),parts=listOf(
            Part(name="Left eye",assetId=asset.id,mesh=Mesh.grid(128f,64f),transform=Transform(Vec2(-100f,0f))),
            Part(name="Right eye",assetId=asset.id,mesh=Mesh.grid(128f,64f),transform=Transform(Vec2(100f,0f)))
        ),parameters=listOf(Parameter(name="Eyes")))
        val parameter=p.parameters.single().id; p.parts.forEach { p=ParameterEngine.blink(p,parameter,it.id) }
        repo.save(p); repo.active=p.id
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            var ready=false
            repeat(60) {
                scenario.onActivity { ready=!ViewModelProvider(it)[EditorViewModel::class.java].busy }
                if(!ready) Thread.sleep(100)
            }
            assertTrue("Project loaded",ready)
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
            // ON_STOP saves asynchronously; wait for the same repository lock via a short bounded pause.
            Thread.sleep(1000)
            repo.active=oldActive
            repo.delete(p.id)
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
