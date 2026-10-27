package dev.mylive2d.app

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mylive2d.core.Vec2
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class PsdDeviceTest {
    private fun block(write: DataOutputStream.()->Unit): ByteArray = ByteArrayOutputStream().also { b -> DataOutputStream(b).use(write) }.toByteArray()
    private fun DataOutputStream.sized(b: ByteArray) { writeInt(b.size); write(b) }
    private fun psd(): ByteArray {
        val planes=listOf(byteArrayOf(255.toByte(),0,0,0),byteArrayOf(0,255.toByte(),0,0),byteArrayOf(0,0,255.toByte(),0),byteArrayOf(255.toByte(),255.toByte(),255.toByte(),0))
        val data=planes.map { plane -> block { writeShort(0); write(plane) } }
        val extra=block { writeInt(0); writeInt(0); writeByte(3); writeBytes("Eye") }
        val record=block {
            writeInt(2); writeInt(3); writeInt(4); writeInt(5); writeShort(4)
            listOf(0,1,2,-1).forEachIndexed { i,id -> writeShort(id); writeInt(data[i].size) }
            writeBytes("8BIM"); writeBytes("norm"); writeByte(200); writeByte(0); writeByte(0); writeByte(0); sized(extra)
        }
        val info=block { writeShort(1); write(record); data.forEach { write(it) } }
        val layerMask=block { sized(info); writeInt(0) }
        return block { writeBytes("8BPS"); writeShort(1); write(ByteArray(6)); writeShort(4); writeInt(8); writeInt(8); writeShort(8); writeShort(3); writeInt(0); writeInt(0); sized(layerMask) }
    }
    @Test fun importsPositionPixelsAndPersists() = runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Context>(); val repo=ProjectRepository(context); val oldActive=repo.active
        val file=File(context.cacheDir,"layer-test.psd").apply { writeBytes(psd()) }
        val scenario=ActivityScenario.launch(MainActivity::class.java); var projectId: String?=null
        fun waitReady() { var ready=false; var n=0; while(!ready && n++<100) { scenario.onActivity { ready=!ViewModelProvider(it)[EditorViewModel::class.java].busy }; if(!ready) Thread.sleep(100) }; assertTrue(ready) }
        try {
            waitReady(); scenario.onActivity { ViewModelProvider(it)[EditorViewModel::class.java].newProject("PSD QA") }; waitReady()
            scenario.onActivity { ViewModelProvider(it)[EditorViewModel::class.java].importPsd(Uri.fromFile(file)) }; waitReady()
            scenario.onActivity { activity ->
                val vm=ViewModelProvider(activity)[EditorViewModel::class.java]; projectId=vm.project.id
                val part=vm.project.parts.single(); assertEquals("Eye",part.name); assertEquals(Vec2(0f,-1f),part.transform.position)
                assertEquals(200/255f,part.opacity,.001f)
                val bitmap=BitmapFactory.decodeFile(repo.assetFile(part.assetId).absolutePath)
                try { assertEquals(android.graphics.Color.RED,bitmap.getPixel(0,0)); assertEquals(0,bitmap.getPixel(1,1) ushr 24) } finally { bitmap.recycle() }
                vm.save()
            }
            var saved=false; var n=0; while(!saved && n++<100) { scenario.onActivity { saved=!ViewModelProvider(it)[EditorViewModel::class.java].dirty }; if(!saved) Thread.sleep(100) }
            assertTrue(saved); assertEquals("Eye",repo.open(projectId!!).parts.single().name)
        } finally { scenario.close(); Thread.sleep(900); projectId?.let { repo.delete(it) }; repo.active=oldActive; file.delete() }
    }
}
