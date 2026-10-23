package dev.mylive2d.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mylive2d.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AlphaMeshDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun alphaDialogRebuildUndoRigAndSaveReopen() = runBlocking<Unit> {
        val vm=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        val repo=vm.repository; val oldActive=repo.active
        val image=File(compose.activity.cacheDir,"alpha-mesh-test.png")
        val blank=File(compose.activity.cacheDir,"alpha-empty-test.png")
        var projectId: String?=null
        fun ready()=compose.waitUntil(10_000) { !vm.busy }
        try {
            val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
            for(y in 16..47) for(x in 12..44) if(x !in 24..31 || y !in 25..34) bitmap.setPixel(x,y,Color.CYAN)
            for(y in 20..27) for(x in 72..79) bitmap.setPixel(x,y,Color.CYAN)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.eraseColor(Color.TRANSPARENT)
            blank.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            ready()
            compose.activityRule.scenario.onActivity { vm.newProject("Alpha mesh QA") }; ready()
            compose.activityRule.scenario.onActivity { vm.importPng(listOf(Uri.fromFile(image))) }; ready()
            compose.onNodeWithTag("panelProperties").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("autoAlphaMesh").fetchSemanticsNodes().isNotEmpty() }
            var original: Project?=null
            compose.activityRule.scenario.onActivity {
                projectId=vm.project.id
                vm.addParameter("Eye",0f,1f,1f); vm.blink(); original=vm.project
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("autoAlphaMesh").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("autoAlphaMesh").performScrollTo()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("alphaThreshold").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("alphaThreshold").performScrollTo().performTextReplacement("0")
            compose.onNodeWithTag("buildAlphaMesh").assertIsNotEnabled()
            compose.onNodeWithTag("alphaThreshold").performTextReplacement("1")
            compose.onNodeWithTag("buildAlphaMesh").assertIsEnabled().performClick(); ready()
            var rebuilt: Project?=null
            compose.activityRule.scenario.onActivity {
                val part=vm.selected!!
                assertNotEquals(original!!.parts.single().mesh,part.mesh)
                assertTrue(vm.project.parameters.single().bindings.isEmpty())
                assertEquals(repo.generateAlphaMesh(vm.project.assets.single(),16,1,2).mesh,part.mesh)
                rebuilt=vm.project
                vm.undo(); assertEquals(original,vm.project)
                vm.redo(); assertEquals(rebuilt,vm.project)
                vm.blink(); vm.parameterValue(vm.parameterId!!,.5f)
                assertNotEquals(vm.selected!!.mesh.vertices,ParameterEngine.vertices(vm.project,vm.selected!!))
                vm.save()
            }
            compose.waitUntil(10_000) { !vm.dirty }
            assertEquals(vm.project,ProjectRepository(compose.activity).open(projectId!!))
            val saved=vm.project
            compose.activityRule.scenario.onActivity { vm.open(projectId!!) }; ready()
            compose.activityRule.scenario.onActivity { assertEquals(saved,vm.project) }
            // A fully transparent PNG is valid to import, but cannot produce an alpha mesh.
            compose.activityRule.scenario.onActivity { vm.importPng(listOf(Uri.fromFile(blank))) }; ready()
            val beforeFailure=vm.project
            compose.activityRule.scenario.onActivity { vm.generateAlphaMesh(16,1,2) }; ready()
            compose.activityRule.scenario.onActivity {
                assertEquals(beforeFailure,vm.project)
                assertFalse(vm.busy)
                vm.undo(); assertEquals(saved,vm.project)
            }
        } finally {
            compose.activityRule.scenario.close()
            Thread.sleep(900)
            projectId?.let { repo.delete(it) }; repo.active=oldActive
            image.delete(); blank.delete()
        }
    }
}
