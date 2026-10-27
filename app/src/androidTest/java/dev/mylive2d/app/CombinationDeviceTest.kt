package dev.mylive2d.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mylive2d.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CombinationDeviceTest {
    @Test fun editPreviewUndoAndReopenCorrection() = runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val repository=ProjectRepository(context); val oldActive=repository.active
        val image=File(context.cacheDir,"combo-test.png")
        Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.CYAN)
            image.outputStream().use { compress(Bitmap.CompressFormat.PNG,100,it) }
            recycle()
        }
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        var projectId: String?=null
        fun waitReady() {
            var ready=false; var n=0
            while(!ready && n++<80) {
                scenario.onActivity { ready=!ViewModelProvider(it)[EditorViewModel::class.java].busy }
                if(!ready) Thread.sleep(100)
            }
            assertTrue("Editor ready",ready)
        }
        try {
            waitReady(); scenario.onActivity { ViewModelProvider(it)[EditorViewModel::class.java].newProject("Combination QA") }; waitReady()
            scenario.onActivity { ViewModelProvider(it)[EditorViewModel::class.java].importPng(listOf(Uri.fromFile(image))) }; waitReady()
            lateinit var snapshot: Project
            scenario.onActivity { activity ->
                val vm=ViewModelProvider(activity)[EditorViewModel::class.java]; projectId=vm.project.id
                vm.addParameter("Face",-1f,0f,1f); val x=vm.parameterId!!
                vm.addParameter("Eye",0f,1f,1f); val y=vm.parameterId!!
                vm.selectParameter(x); vm.createCombination(y)
                val combo=vm.selectedCombinations.single(); vm.selectCombinationKey(combo.id,1f,0f)
                val before=ParameterEngine.vertices(vm.project,vm.selected!!).first()
                vm.startCombinationKey(combo.id); assertTrue(vm.keyEditing)
                vm.beginGesture(); vm.moveVertex(0,Vec2(10f,4f)); vm.endGesture()
                assertEquals(before.x+10f,ParameterEngine.vertices(vm.renderProject,vm.selected!!).first().x,.001f)
                vm.commitKey(); assertFalse(vm.keyEditing)
                assertEquals(before.x+10f,ParameterEngine.vertices(vm.project,vm.selected!!).first().x,.001f)
                vm.undo(); assertEquals(before.x,ParameterEngine.vertices(vm.project,vm.selected!!).first().x,.001f)
                vm.redo(); assertEquals(before.x+10f,ParameterEngine.vertices(vm.project,vm.selected!!).first().x,.001f)
                snapshot=vm.project
            }
            repository.save(snapshot)
            val reopened=repository.open(snapshot.id)
            assertEquals(snapshot,reopened)
            assertEquals(ParameterEngine.vertices(snapshot,snapshot.parts.single()),ParameterEngine.vertices(reopened,reopened.parts.single()))
        } finally {
            scenario.close()
            projectId?.let { repository.delete(it) }
            repository.active=oldActive
            image.delete()
        }
    }
}
