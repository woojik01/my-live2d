package dev.mylive2d.core

import kotlin.test.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AnimationTest {
    private fun fixture(): Pair<Project, Parameter> {
        val parameter = Parameter(name = "Eye", min = 0f, default = 1f, max = 1f)
        return Project(name = "Animation", parameters = listOf(parameter)) to parameter
    }

    @Test fun linearKeysInterpolateAndHoldKeysStep() {
        val keys = listOf(AnimationKeyframe(frame = 10, value = 1f), AnimationKeyframe(frame = 20, value = 0f))
        assertEquals(1f, AnimationEngine.valueAt(keys, 4f))
        assertEquals(.5f, AnimationEngine.valueAt(keys, 15f)!!, .0001f)
        assertEquals(0f, AnimationEngine.valueAt(keys, 26f))
        val hold = listOf(keys.first().copy(interpolation = Interpolation.HOLD), keys.last())
        assertEquals(1f, AnimationEngine.valueAt(hold, 19.9f))
        assertEquals(0f, AnimationEngine.valueAt(hold, 20f))
    }

    @Test fun recordingMovingAndDeletingKeysPreservesIdentity() {
        val (project, parameter) = fixture()
        var clip = AnimationClip(name = "Blink", durationFrames = 60)
        clip = AnimationEngine.putKey(clip, parameter.id, 0, 1f)
        clip = AnimationEngine.putKey(clip, parameter.id, 30, 0f)
        clip = AnimationEngine.putKey(clip, parameter.id, 30, .25f)
        val track = clip.tracks.single(); val key = track.keyframes.last()
        assertEquals(2, track.keyframes.size)
        assertEquals(.25f, key.value)
        assertFails { AnimationEngine.moveKey(clip, track.id, key.id, 0) }
        clip = AnimationEngine.moveKey(clip, track.id, key.id, 40)
        assertEquals(40, clip.tracks.single().keyframes.last().frame)
        clip = AnimationEngine.removeKey(clip, track.id, key.id)
        assertEquals(listOf(0), clip.tracks.single().keyframes.map { it.frame })
        clip = AnimationEngine.removeKey(clip, track.id, clip.tracks.single().keyframes.single().id)
        assertTrue(clip.tracks.isEmpty())
        Validation.check(project.copy(animations = listOf(clip)))
    }

    @Test fun evaluationIsIsolatedAndDoesNotMutateSavedParameterValues() {
        val (project, eye) = fixture()
        var clip = AnimationClip(name = "Blink", durationFrames = 31)
        clip = AnimationEngine.putKey(clip, eye.id, 0, 1f)
        clip = AnimationEngine.putKey(clip, eye.id, 30, 0f)
        val saved = project.copy(animations = listOf(clip))
        val middle = AnimationEngine.evaluate(saved, clip.id, 15f)
        assertEquals(.5f, middle.parameters.single().value, .0001f)
        assertEquals(1f, saved.parameters.single().value)
        assertEquals(0f, AnimationEngine.evaluate(saved, clip.id, 200f).parameters.single().value)
    }

    @Test fun multipleClipsCanAnimateTheSameParameterIndependently() {
        val (project, parameter) = fixture()
        val open = AnimationEngine.putKey(AnimationClip(name = "Open", durationFrames = 10), parameter.id, 0, 1f)
        val shut = AnimationEngine.putKey(AnimationClip(name = "Shut", durationFrames = 10), parameter.id, 0, 0f)
        val saved = project.copy(animations = listOf(open, shut))
        Validation.check(saved)
        assertEquals(1f, AnimationEngine.evaluate(saved, open.id, 0f).parameters.single().value)
        assertEquals(0f, AnimationEngine.evaluate(saved, shut.id, 0f).parameters.single().value)
    }

    @Test fun versionTwoArchivesUpgradeToCurrentAndRoundTrip() {
        val (base, parameter) = fixture()
        val clip = AnimationEngine.putKey(AnimationClip(name = "Breath", durationFrames = 90, fps = 30), parameter.id, 0, 0f)
        val legacy = base.copy(version = 2)
        val legacyJson=Json.encodeToString(legacy).replace(",\"animations\":[]", "")
        val archive=ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip -> zip.putNextEntry(ZipEntry("project.json")); zip.write(legacyJson.toByteArray()); zip.closeEntry() }
        val legacyBundle = ProjectArchive.read(ByteArrayInputStream(archive.toByteArray()))
        assertEquals(2, legacyBundle.sourceVersion)
        assertEquals(4, legacyBundle.project.version)
        val migrated = legacyBundle.project
        val animated = migrated.copy(animations = listOf(clip))
        val bytes = ByteArrayOutputStream()
        ProjectArchive.write(animated, emptyMap(), bytes)
        val bundle = ProjectArchive.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(4, bundle.sourceVersion)
        assertEquals(animated, bundle.project)
        assertEquals(0f, AnimationEngine.evaluate(bundle.project, clip.id, 0f).parameters.single().value)
    }

    @Test fun versionTwoCannotSmuggleVersionThreeAnimationData() {
        val (project, parameter) = fixture()
        val clip = AnimationEngine.putKey(AnimationClip(name = "Bad", durationFrames = 10), parameter.id, 0, .5f)
        assertFails { ProjectMigration.upgrade(project.copy(version = 2, animations = listOf(clip))) }
        assertFails { ProjectMigration.upgrade(project.copy(version = 5)) }
    }

    @Test fun malformedTracksAndKeyframesAreRejected() {
        val (project, parameter) = fixture()
        val valid = AnimationEngine.putKey(AnimationClip(name = "Clip", durationFrames = 10), parameter.id, 0, .5f)
        assertFails { Validation.check(project.copy(animations = listOf(valid.copy(durationFrames = 0)))) }
        assertFails { Validation.check(project.copy(animations = listOf(valid.copy(fps = 121)))) }
        assertFails { Validation.check(project.copy(animations = listOf(valid.copy(tracks = listOf(valid.tracks.single().copy(parameterId = "missing")))))) }
        assertFails { Validation.check(project.copy(animations = listOf(valid.copy(tracks = listOf(valid.tracks.single().copy(keyframes = listOf(AnimationKeyframe(frame = 12, value = .5f)))))))) }
        assertFails { Validation.check(project.copy(animations = listOf(valid.copy(tracks = listOf(valid.tracks.single().copy(keyframes = listOf(AnimationKeyframe(frame = 0, value = 2f)))))))) }
        assertFails { AnimationEngine.putKey(valid, parameter.id, 10, .5f) }
    }

    @Test fun playbackFrameDomainIsBoundedToClipLength() {
        val (project, parameter) = fixture()
        val clip = AnimationEngine.putKey(AnimationClip(name = "Short", durationFrames = 12), parameter.id, 11, 0f)
        val saved = project.copy(animations = listOf(clip))
        assertEquals(0f, AnimationEngine.evaluate(saved, clip.id, 11f).parameters.single().value)
        assertEquals(0f, AnimationEngine.evaluate(saved, clip.id, 100f).parameters.single().value)
    }
}
