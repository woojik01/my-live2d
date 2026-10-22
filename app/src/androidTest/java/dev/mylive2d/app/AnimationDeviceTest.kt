package dev.mylive2d.app

import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mylive2d.core.AnimationEngine
import dev.mylive2d.core.Project
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class AnimationDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun timelineRecordsInterpolatesPlaysAndPersistsKeys() = runBlocking<Unit> {
        val vm=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        val repo=vm.repository; val oldActive=repo.active; var projectId: String?=null
        try {
            compose.waitUntil(10_000) { !vm.busy }
            compose.activityRule.scenario.onActivity { vm.newProject("Animation QA") }
            compose.waitUntil(10_000) { !vm.busy }
            compose.activityRule.scenario.onActivity {
                vm.addParameter("Eye",0f,1f,1f)
                projectId=vm.project.id
            }
            compose.onNodeWithTag("animationTimelineToggle").performClick()
            compose.onNodeWithTag("addAnimation").performClick()
            compose.onNodeWithTag("animationPlayhead").assertIsDisplayed()
            compose.activityRule.scenario.onActivity { vm.addAnimationTrack(vm.parameterId!!) }
            compose.onNodeWithTag("recordAnimationKey").performClick()
            compose.activityRule.scenario.onActivity {
                vm.scrubAnimation(15); vm.toggleAnimationPreview(); vm.parameterValue(vm.parameterId!!,0f)
            }
            compose.onNodeWithTag("recordAnimationKey").performClick()
            compose.activityRule.scenario.onActivity {
                val clip=vm.selectedAnimation!!; val track=clip.tracks.single()
                assertEquals(listOf(0,15),track.keyframes.map { it.frame })
                assertEquals(listOf(1f,0f),track.keyframes.map { it.value })
                assertEquals(.5f,AnimationEngine.evaluate(vm.project,clip.id,7.5f).parameters.single().value,.001f)
                vm.moveSelectedAnimationKey(16)
                vm.selectAnimationKey(track.keyframes.first().id)
                vm.setSelectedKeyInterpolation(dev.mylive2d.core.Interpolation.HOLD)
                vm.toggleAnimationPreview()
                vm.seekAnimation(15)
                assertEquals(1f,vm.renderProject.parameters.single().value)
                vm.seekAnimation(16)
                assertEquals(0f,vm.renderProject.parameters.single().value)
                vm.seekAnimation(0)
            }
            compose.activityRule.scenario.onActivity { vm.togglePlayback() }
            compose.waitUntil(3_000) { vm.animationFrame >= 3 }
            compose.activityRule.scenario.onActivity {
                val animated=vm.renderProject.parameters.single().value
                assertTrue("Playback frame evaluated",animated in .7f..1f)
                assertEquals(0f,vm.project.parameters.single().value)
            }
            compose.activityRule.scenario.onActivity { vm.togglePlayback() }
            compose.activityRule.scenario.onActivity {
                assertFalse(vm.playing)
                assertEquals(16,vm.selectedAnimation!!.tracks.single().keyframes.last().frame)
                vm.save()
            }
            compose.waitUntil(10_000) { !vm.dirty }
            assertEquals(vm.project,repo.open(projectId!!))
            compose.onNodeWithTag("projectMenu").performClick()
            compose.onNodeWithTag("exportAnimationSequence").assertIsDisplayed().performClick()
            compose.onNodeWithTag("animationExportDialog").assertIsDisplayed()
            compose.onNodeWithTag("exportSize-512").assertIsDisplayed()
            compose.onNodeWithTag("startAnimationExport").assertIsDisplayed()
        } finally {
            compose.activityRule.scenario.onActivity { vm.stopPlayback() }
            compose.activityRule.scenario.close()
            Thread.sleep(900)
            projectId?.let { repo.delete(it) }
            repo.active=oldActive
        }
    }

    @Test fun versionTwoLocalProjectIsPreservedDuringMigration() = runBlocking<Unit> {
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo=ProjectRepository(context); val old=Project(version=2,name="Legacy v2")
        val file=File(context.filesDir,"projects/${old.id}.mylive2d").apply { parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("project.json")); zip.write(Json.encodeToString(old).toByteArray()); zip.closeEntry()
        }
        val original=file.readBytes()
        try {
            assertEquals(3,repo.open(old.id).version)
            assertArrayEquals(original,File(file.parentFile,"${old.id}.v2copy").readBytes())
        } finally { repo.delete(old.id) }
    }
}
