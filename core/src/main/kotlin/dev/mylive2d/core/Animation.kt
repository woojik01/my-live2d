package dev.mylive2d.core

import kotlinx.serialization.Serializable

@Serializable enum class Interpolation { LINEAR, HOLD }
@Serializable data class AnimationKeyframe(
    val id: String = newId(), val frame: Int, val value: Float,
    val interpolation: Interpolation = Interpolation.LINEAR
)
@Serializable data class ParameterTrack(
    val id: String = newId(), val parameterId: String, val keyframes: List<AnimationKeyframe> = emptyList()
)
@Serializable data class AnimationClip(
    val id: String = newId(), val name: String, val durationFrames: Int = 300,
    val fps: Int = 30, val loop: Boolean = true, val tracks: List<ParameterTrack> = emptyList()
)

/** Frame-based parameter animation independent from the editor's interactive parameter values. */
object AnimationEngine {
    const val MAX_CLIPS = 16
    const val MAX_TRACKS_PER_CLIP = 32
    const val MAX_KEYS_PER_TRACK = 1024
    const val MAX_TOTAL_KEYS = 8192
    const val MAX_DURATION_FRAMES = 36_000

    fun valueAt(keys: List<AnimationKeyframe>, frame: Float): Float? {
        if (keys.isEmpty() || !frame.isFinite()) return null
        val sorted = keys.sortedBy { it.frame }
        if (frame <= sorted.first().frame) return sorted.first().value
        if (frame >= sorted.last().frame) return sorted.last().value
        val nextIndex = sorted.indexOfFirst { it.frame >= frame }
        val a = sorted[nextIndex - 1]
        val b = sorted[nextIndex]
        if (a.interpolation == Interpolation.HOLD) return a.value
        val t = (frame - a.frame) / (b.frame - a.frame)
        return a.value + (b.value - a.value) * t
    }

    fun evaluate(project: Project, clipId: String, frame: Float): Project {
        val clip = project.animations.first { it.id == clipId }
        val bounded = frame.coerceIn(0f, (clip.durationFrames - 1).toFloat())
        val values = clip.tracks.associate { it.parameterId to valueAt(it.keyframes, bounded) }
        return project.copy(parameters = project.parameters.map { parameter ->
            val value = values[parameter.id] ?: return@map parameter
            parameter.copy(value = value.coerceIn(parameter.min, parameter.max))
        })
    }

    fun putKey(clip: AnimationClip, parameterId: String, frame: Int, value: Float): AnimationClip {
        require(frame in 0 until clip.durationFrames && value.isFinite())
        val existing = clip.tracks.firstOrNull { it.parameterId == parameterId }
        val track = existing ?: ParameterTrack(parameterId = parameterId)
        require(track.keyframes.size < MAX_KEYS_PER_TRACK || track.keyframes.any { it.frame == frame }) { "Track key limit reached" }
        val keys = (track.keyframes.filterNot { it.frame == frame } + AnimationKeyframe(frame = frame, value = value)).sortedBy { it.frame }
        val nextTrack = track.copy(keyframes = keys)
        return clip.copy(tracks = if (existing == null) clip.tracks + nextTrack else clip.tracks.map { if (it.id == track.id) nextTrack else it })
    }

    fun moveKey(clip: AnimationClip, trackId: String, keyId: String, frame: Int): AnimationClip {
        require(frame in 0 until clip.durationFrames)
        val track = clip.tracks.first { it.id == trackId }
        track.keyframes.first { it.id == keyId }
        require(track.keyframes.none { it.id != keyId && it.frame == frame }) { "A keyframe already exists at that frame" }
        return clip.copy(tracks = clip.tracks.map { current ->
            if (current.id != trackId) current else current.copy(keyframes = current.keyframes.map { if (it.id == keyId) it.copy(frame = frame) else it }.sortedBy { it.frame })
        })
    }

    fun removeKey(clip: AnimationClip, trackId: String, keyId: String): AnimationClip = clip.copy(tracks = clip.tracks.mapNotNull { track ->
        if (track.id != trackId) track else track.copy(keyframes = track.keyframes.filterNot { it.id == keyId }).takeIf { it.keyframes.isNotEmpty() }
    })

    fun validate(project: Project) {
        require(project.animations.size <= MAX_CLIPS) { "Maximum $MAX_CLIPS animation clips" }
        require(project.animations.map { it.id }.distinct().size == project.animations.size)
        val parameters = project.parameters.associateBy { it.id }
        var totalKeys = 0
        project.animations.forEach { clip ->
            require(clip.id.matches(Regex("[A-Za-z0-9-]{1,80}")) && clip.name.isNotBlank() && clip.name.length <= 120)
            require(clip.durationFrames in 1..MAX_DURATION_FRAMES && clip.fps in 1..120)
            require(clip.tracks.size <= MAX_TRACKS_PER_CLIP)
            require(clip.tracks.map { it.id }.distinct().size == clip.tracks.size)
            require(clip.tracks.map { it.parameterId }.distinct().size == clip.tracks.size) { "A parameter may appear once in each clip" }
            clip.tracks.forEach { track ->
                require(track.id.matches(Regex("[A-Za-z0-9-]{1,80}")))
                val parameter = parameters[track.parameterId] ?: error("Animation track references a missing parameter")
                require(track.keyframes.size <= MAX_KEYS_PER_TRACK)
                require(track.keyframes.map { it.frame }.distinct().size == track.keyframes.size)
                require(track.keyframes.map { it.id }.distinct().size == track.keyframes.size)
                track.keyframes.forEach { key ->
                    require(key.id.matches(Regex("[A-Za-z0-9-]{1,80}")))
                    require(key.frame in 0 until clip.durationFrames)
                    require(key.value.isFinite() && key.value in parameter.min..parameter.max)
                }
                totalKeys += track.keyframes.size
                require(totalKeys <= MAX_TOTAL_KEYS) { "Animation keyframe budget exceeded" }
            }
        }
    }
}
