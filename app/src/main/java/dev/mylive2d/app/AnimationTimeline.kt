package dev.mylive2d.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.mylive2d.core.Interpolation

@Composable internal fun AnimationTimeline(vm: EditorViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal=8.dp, vertical=2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={ expanded=!expanded },modifier=Modifier.testTag("animationTimelineToggle")) {
                Text(vm.tr(if(expanded) "애니메이션 ▾" else "애니메이션 ▸", if(expanded) "Animation ▾" else "Animation ▸"))
            }
            vm.selectedAnimation?.let { clip ->
                Text("${vm.animationFrame} / ${clip.durationFrames-1}f",style=MaterialTheme.typography.labelMedium)
            }
        }
        if(expanded) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment=Alignment.CenterVertically) {
                vm.project.animations.forEach { clip ->
                    FilterChip(selected=clip.id==vm.selectedAnimationId,onClick={ vm.selectAnimation(clip.id) },label={ Text(clip.name) },modifier=Modifier.padding(end=4.dp))
                }
                OutlinedButton(onClick=vm::addBlinkExample,enabled=vm.project.animations.size<16) { Text(vm.tr("눈 깜빡임 예제","Blink example")) }
                OutlinedButton(onClick=vm::addBreathExample,enabled=vm.project.animations.size<16) { Text(vm.tr("호흡 예제","Breathing example")) }
                OutlinedButton(onClick=vm::addAnimation,enabled=vm.project.animations.size<16,modifier=Modifier.testTag("addAnimation")) { Text(vm.tr("+ 새 애니메이션","+ Animation")) }
                vm.selectedAnimation?.let {
                    TextButton(onClick={ renaming=true },enabled=!vm.keyEditing && !vm.playing) { Text(vm.tr("이름","Rename")) }
                    TextButton(onClick={ settings=true },enabled=!vm.keyEditing && !vm.playing) { Text(vm.tr("길이·FPS","Length · FPS")) }
                    TextButton(onClick=vm::deleteAnimation,enabled=!vm.keyEditing && !vm.playing) { Text(vm.tr("삭제","Delete")) }
                }
            }
            val clip=vm.selectedAnimation
            if(clip!=null) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
                    Button(onClick=vm::togglePlayback,enabled=!vm.keyEditing,modifier=Modifier.testTag("animationPlay")) { Text(if(vm.playing) vm.tr("일시정지","Pause") else vm.tr("재생","Play")) }
                    TextButton(onClick={ vm.stopPlayback(); vm.seekAnimation(0) }) { Text("⏮") }
                    FilterChip(selected=clip.loop,onClick={ vm.setAnimationLoop(!clip.loop) },label={ Text(vm.tr("반복","Loop")) })
                    FilterChip(selected=vm.animationPreview,onClick=vm::toggleAnimationPreview,label={ Text(if(vm.animationPreview) vm.tr("미리보기","Preview") else vm.tr("값 편집","Edit values")) })
                    TextButton(onClick=vm::recordAnimationKey,enabled=vm.selectedTrack!=null && !vm.playing,modifier=Modifier.testTag("recordAnimationKey")) { Text(vm.tr("◆ 키 기록","◆ Add key")) }
                    TextButton(onClick=vm::deleteSelectedAnimationKey,enabled=vm.selectedAnimationKeyId!=null && !vm.playing) { Text(vm.tr("키 삭제","Delete key")) }
                }
                Slider(value=vm.animationFrame.toFloat(),onValueChange={ vm.scrubAnimation(it.toInt()) },valueRange=0f..(clip.durationFrames-1).toFloat(),enabled=clip.durationFrames>1,modifier=Modifier.testTag("animationPlayhead"))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
                    Text(vm.tr("트랙","Tracks")+":",style=MaterialTheme.typography.labelMedium)
                    vm.project.parameters.forEach { param ->
                        val track=clip.tracks.firstOrNull { it.parameterId==param.id }
                        FilterChip(selected=vm.selectedTrackParameterId==param.id,onClick={ vm.addAnimationTrack(param.id) },label={ Text((if(track!=null) "✓ " else "+ ")+param.name) },modifier=Modifier.padding(start=4.dp))
                    }
                }
                val track=vm.selectedTrack
                if(track!=null) {
                    val keys=track.keyframes.sortedBy { it.frame }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
                        keys.forEach { key ->
                            FilterChip(selected=key.id==vm.selectedAnimationKeyId,onClick={ vm.selectAnimationKey(key.id); vm.scrubAnimation(key.frame) },label={ Text("◆ ${key.frame}f · ${"%.2f".format(java.util.Locale.ROOT,key.value)}") },modifier=Modifier.padding(end=4.dp))
                        }
                        val current=keys.firstOrNull { it.id==vm.selectedAnimationKeyId }
                        if(current!=null) {
                            TextButton(onClick={ vm.moveSelectedAnimationKey(current.frame-1) },enabled=current.frame>0 && !vm.playing) { Text("◀1f") }
                            TextButton(onClick={ vm.moveSelectedAnimationKey(current.frame+1) },enabled=current.frame<clip.durationFrames-1 && !vm.playing) { Text("1f▶") }
                            FilterChip(selected=current.interpolation==Interpolation.HOLD,onClick={ vm.setSelectedKeyInterpolation(if(current.interpolation==Interpolation.HOLD) Interpolation.LINEAR else Interpolation.HOLD) },label={ Text(if(current.interpolation==Interpolation.HOLD) vm.tr("유지","Hold") else vm.tr("선형","Linear")) })
                        }
                    }
                }
            } else {
                Text(vm.tr("애니메이션을 만들어 파라미터 트랙을 추가하세요.","Create an animation, then add parameter tracks."),style=MaterialTheme.typography.bodySmall)
            }
        }
    }
    if(renaming) vm.selectedAnimation?.let { clip -> NameDialog(vm,vm.tr("애니메이션 이름","Animation name"),clip.name,{ renaming=false }) { vm.renameAnimation(clip.id,it); renaming=false } }
    if(settings) vm.selectedAnimation?.let { clip -> AnimationSettingsDialog(vm,clip.durationFrames,clip.fps,{ settings=false }) { frames,fps -> vm.setAnimationDuration(frames); vm.setAnimationFps(fps); settings=false } }
}

@Composable private fun AnimationSettingsDialog(vm: EditorViewModel,initialFrames: Int,initialFps: Int,onDismiss: ()->Unit,onApply: (Int,Int)->Unit) {
    var frames by remember(initialFrames) { mutableStateOf(initialFrames.toString()) }
    var fps by remember(initialFps) { mutableStateOf(initialFps.toString()) }
    val frameValue=frames.toIntOrNull(); val fpsValue=fps.toIntOrNull()
    val valid=frameValue!=null && frameValue in 1..36_000 && fpsValue!=null && fpsValue in 1..120
    AlertDialog(onDismissRequest=onDismiss,title={ Text(vm.tr("애니메이션 길이와 속도","Duration and frame rate")) },text={ Column {
        OutlinedTextField(value=frames,onValueChange={ frames=it },singleLine=true,label={ Text(vm.tr("길이 (프레임)","Length (frames)")) },keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.testTag("animationDuration"))
        OutlinedTextField(value=fps,onValueChange={ fps=it },singleLine=true,label={ Text(vm.tr("FPS","FPS")) },keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        if(!valid) Text(vm.tr("길이 1~36,000, FPS 1~120","Length 1–36,000; FPS 1–120"),color=MaterialTheme.colorScheme.error)
    } },confirmButton={ TextButton(onClick={ onApply(frameValue!!,fpsValue!!) },enabled=valid) { Text(vm.tr("적용","Apply")) } },dismissButton={ TextButton(onClick=onDismiss) { Text(vm.tr("취소","Cancel")) } })
}
