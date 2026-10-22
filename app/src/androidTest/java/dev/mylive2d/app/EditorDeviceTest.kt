package dev.mylive2d.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mylive2d.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device/emulator tests; these are not JVM test results. */
@RunWith(AndroidJUnit4::class)
class EditorDeviceTest {
    @Test fun pngImportTwoEyesRigSaveReopen() = runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Context>(); val repo=ProjectRepository(context)
        val image=File(context.cacheDir,"eye-test.png")
        val bitmap=Bitmap.createBitmap(128,64,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.CYAN)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        val left=repo.importPng(Uri.fromFile(image)); val right=repo.importPng(Uri.fromFile(image))
        var p=Project(name="Device roundtrip",assets=listOf(left.asset,right.asset),parts=listOf(
            Part(name="Left",assetId=left.asset.id,mesh=Mesh.grid(128f,64f)),
            Part(name="Right",assetId=right.asset.id,mesh=Mesh.grid(128f,64f),transform=Transform(Vec2(180f,0f)))
        ),parameters=listOf(Parameter(name="Eyes")))
        val param=p.parameters.single().id; p.parts.forEach { p=ParameterEngine.blink(p,param,it.id) }
        repo.save(p); val reopened=ProjectRepository(context).open(p.id)
        assertEquals(p,reopened)
        repo.delete(p.id); image.delete()
    }
    @Test fun backupRecoveryAfterCorruptLatestSave() = runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Context>(); val repo=ProjectRepository(context)
        val original=Project(name="Backup original")
        repo.save(original); repo.save(original.copy(name="Latest"))
        val file=File(context.filesDir,"projects/${original.id}.mylive2d")
        file.writeText("corrupt ZIP")
        assertEquals(original,repo.open(original.id))
        // Recovered primary is repaired; another save must not replace the good backup with corrupt data.
        repo.save(original.copy(name="Recovered edit"))
        file.writeText("corrupt again")
        assertEquals(original,repo.open(original.id))
        repo.delete(original.id)
    }
    @Test fun malformedPngFailsWithoutWritingProject() {
        val context=ApplicationProvider.getApplicationContext<Context>(); val repo=ProjectRepository(context)
        val bad=File(context.cacheDir,"bad.png").apply { writeText("not a png") }
        try { repo.importPng(Uri.fromFile(bad)); fail("Expected rejection") } catch(_: IllegalArgumentException) { } finally { bad.delete() }
    }
}
