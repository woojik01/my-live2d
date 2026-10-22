package dev.mylive2d.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mylive2d.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.*

class EditorViewModel(app: Application): AndroidViewModel(app) {
    val repository = ProjectRepository(app)
    private val prefs = app.getSharedPreferences("settings",0)
    var english by mutableStateOf(prefs.getBoolean("english",false)); private set
    var dark by mutableStateOf(prefs.getBoolean("dark",true)); private set
    var project by mutableStateOf(Project()); private set
    private var history = History(project)
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var selectedId by mutableStateOf<String?>(null)
    var selectedDeformerId by mutableStateOf<String?>(null)
    var selectedVertex by mutableIntStateOf(-1)
    var meshTool by mutableIntStateOf(0) // 0 move, 1 insert, 2 remove
    var deformerDraft by mutableStateOf<DeformerPose?>(null); private set
    var parameterId by mutableStateOf<String?>(null)
    var keyEditing by mutableStateOf(false); private set
    var selectedAnimationId by mutableStateOf<String?>(null); private set
    var selectedTrackParameterId by mutableStateOf<String?>(null); private set
    var selectedAnimationKeyId by mutableStateOf<String?>(null); private set
    var animationFrame by mutableIntStateOf(0); private set
    var animationPreview by mutableStateOf(true); private set
    var playing by mutableStateOf(false); private set
    private var playbackJob: Job? = null
    var draft by mutableStateOf<List<Vec2>?>(null); private set
    var meshMode by mutableStateOf(false)
    var precise by mutableStateOf(false)
    var snap by mutableStateOf(false)
    var grid by mutableStateOf(true)
    var camera by mutableStateOf(Camera())
    var background by mutableStateOf(0) // checker, dark, white
    var busy by mutableStateOf(true); private set
    var recent by mutableStateOf(emptyList<ProjectRepository.Recent>()); private set
    var dirty by mutableStateOf(false); private set
    var revision by mutableIntStateOf(0); private set
    private var gestureStart: Project? = null
    private var rawPartPosition: Vec2? = null
    private var rawVertexPosition: Vec2? = null
    private var rawVertexIndex = -1
    private var saveJob: Job? = null
    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()
    val selected get() = project.parts.firstOrNull { it.id == selectedId }
    val selectedDeformer get() = project.deformers.firstOrNull { it.id==selectedDeformerId }
    val selectedLocked get() = selected?.let { RigEvaluator(project).locked(it) } ?: selectedDeformer?.let { RigEvaluator(project).locked(it) } ?: true
    val renderProject: Project get() {
        val parameter=parameterId
        if(keyEditing && parameter!=null) {
            selected?.let { part -> draft?.let { return ParameterEngine.record(project,parameter,part.id,it) } }
            selectedDeformer?.let { node -> deformerDraft?.let { return Rigging.record(project,parameter,node.id,it) } }
        }
        return selectedAnimation?.takeIf { animationPreview || playing }?.let { AnimationEngine.evaluate(project,it.id,animationFrame.toFloat()) } ?: project
    }
    val hasDeformerKeys get() = selectedDeformerId?.let { id -> project.parameters.any { p -> p.deformerBindings.any { it.deformerId==id } } } ?: false
    fun keyValues(p: Parameter): List<Float> = selectedId?.let { id -> p.bindings.firstOrNull { it.partId==id }?.keys?.map { it.value } }
        ?: selectedDeformerId?.let { id -> p.deformerBindings.firstOrNull { it.deformerId==id }?.keys?.map { it.value } } ?: emptyList()
    val parameter get() = project.parameters.firstOrNull { it.id == parameterId }
    val selectedAnimation get() = project.animations.firstOrNull { it.id == selectedAnimationId }
    val selectedTrack get() = selectedAnimation?.tracks?.firstOrNull { it.parameterId == selectedTrackParameterId }
    init {
        viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) { repository.active?.let { repository.open(it) } }
                if(restored != null) { adoptProject(restored); message(tr("자동 저장 복구 완료","Autosave restored")) }
                else adoptProject(project)
            } catch(e: Exception) { adoptProject(project); failure(e) }
            finally { busy = false; refreshRecent() }
        }
    }
    fun tr(ko: String, en: String) = if(english) en else ko
    fun language() { english = !english; prefs.edit().putBoolean("english",english).apply() }
    fun theme() { dark = !dark; prefs.edit().putBoolean("dark",dark).apply() }
    fun message(text: String) { events.trySend(text) }
    private fun failure(e: Exception) { message(tr("작업 실패: ","Operation failed: ")+(e.message ?: e.javaClass.simpleName)) }
    private fun refreshRecent() { recent = repository.recent() }
    private fun adoptProject(p: Project) {
        project = p; history = History(p); selectedId = p.parts.lastOrNull()?.id; parameterId = p.parameters.firstOrNull()?.id
        stopPlayback(); selectedAnimationId=p.animations.firstOrNull()?.id; selectedTrackParameterId=p.animations.firstOrNull()?.tracks?.firstOrNull()?.parameterId ?: p.parameters.firstOrNull()?.id; selectedAnimationKeyId=null; animationFrame=0; animationPreview=true
        keyEditing = false; draft = null; deformerDraft=null; selectedDeformerId=null; selectedVertex=-1; gestureStart = null; camera = Camera(); dirty = false; revision++; syncHistory()
        repository.active = p.id
    }
    private fun syncHistory() { canUndo = history.canUndo; canRedo = history.canRedo }
    fun change(p: Project) {
        if(busy || p == project) return
        try { Validation.check(p) } catch(e: Exception) { failure(e); return }
        history.commit(p); project = p; syncHistory(); dirty = true; revision++; autosave()
    }
    private fun autosave() {
        saveJob?.cancel(); val p = project
        saveJob = viewModelScope.launch { delay(800); try { withContext(Dispatchers.IO) { repository.save(p) }; refreshRecent() } catch(e: CancellationException) { throw e } catch(e: Exception) { failure(e) } }
    }
    fun save() {
        if(busy) return
        saveJob?.cancel(); val p = project; val rev = revision
        saveJob = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { repository.save(p) }; if(revision == rev) dirty = false; refreshRecent(); message(tr("저장 완료","Saved")) }
            catch(e: CancellationException) { throw e } catch(e: Exception) { failure(e) }
        }
    }
    private fun operation(block: suspend () -> Unit) {
        if(busy) return
        busy = true
        viewModelScope.launch { try { block() } catch(e: Exception) { failure(e) } finally { busy = false; refreshRecent() } }
    }
    fun newProject(name: String) = operation {
        saveJob?.cancel(); val old = project
        withContext(Dispatchers.IO) { repository.save(old) }
        val next = Project(name = name.trim().take(120).ifBlank { tr("새 프로젝트","New project") })
        withContext(Dispatchers.IO) { repository.save(next) }; adoptProject(next)
    }
    fun open(id: String) = operation {
        saveJob?.cancel(); val old = project
        val next = withContext(Dispatchers.IO) { repository.save(old); repository.open(id) }; adoptProject(next)
    }
    fun deleteRecent(id: String) = operation {
        require(id != project.id) { tr("열려 있는 프로젝트는 삭제할 수 없습니다","Create/open another project before deleting this one") }
        withContext(Dispatchers.IO) { repository.delete(id) }
    }
    fun importProject(uri: Uri) = operation {
        saveJob?.cancel(); val old = project
        val next = withContext(Dispatchers.IO) { repository.save(old); repository.importProject(uri) }
        withContext(Dispatchers.IO) { repository.save(next) }; adoptProject(next); message(tr("프로젝트 가져옴","Project imported"))
    }
    fun export(uri: Uri) = operation {
        val p = project; withContext(Dispatchers.IO) { repository.export(p,uri) }; message(tr("프로젝트 내보내기 완료","Project exported"))
    }
    fun exportAnimationSequence(uri: Uri, size: Int) = operation {
        require(!keyEditing) { tr("키폼 편집을 먼저 마치세요","Finish keyform editing before exporting") }
        stopPlayback()
        val p = project
        val clip = selectedAnimation ?: error(tr("애니메이션을 먼저 선택하세요","Select an animation first"))
        withContext(Dispatchers.IO) { repository.exportAnimationSequence(p,clip.id,size,uri) }
        message(tr("투명 PNG 애니메이션 프레임을 ZIP으로 저장했습니다","Transparent PNG animation frames exported as ZIP"))
    }
    fun importPng(uris: List<Uri>) = operation {
        require(project.assets.size+uris.size <= 32 && project.parts.size+uris.size <= 64) { "Maximum 32 PNGs / 64 parts" }
        val imported = withContext(Dispatchers.IO) { uris.map { repository.importPng(it) } }
        val assets = project.assets+imported.map { it.asset }
        require(assets.sumOf { it.width.toLong()*it.height } <= 16_777_216) { "Image memory budget exceeded (16 MP)" }
        val next = project.copy(assets=assets,parts=project.parts+imported.map { i ->
            Part(name=i.name,assetId=i.asset.id,mesh=Mesh.grid(i.asset.width.toFloat(),i.asset.height.toFloat()))
        })
        history.commit(next); project = next; syncHistory(); dirty = true; revision++; select(next.parts.lastOrNull()?.id); autosave()
        message(tr("PNG 가져옴 · 파츠를 이동해 배치하세요","PNG imported · move parts into place"))
    }
    fun select(id: String?) { cancelKey(); selectedId = id; selectedDeformerId=null; selectedVertex=-1 }
    fun selectDeformer(id: String?) { cancelKey(); selectedDeformerId=id; selectedId=null; selectedVertex=-1; meshMode=selectedDeformer?.kind==DeformerKind.WARP }
    fun selectParameter(id: String?) { cancelKey(); parameterId = id }
    fun rename(name: String) { if(name.isNotBlank()) change(project.copy(name=name.trim().take(120))) }
    fun updatePart(edit: (Part)->Part) { selected?.let { old -> change(project.copy(parts=project.parts.map { if(it.id==old.id) edit(it) else it })) } }
    fun reorder(delta: Int) {
        val index = project.parts.indexOfFirst { it.id == selectedId }; if(index < 0) return
        val target = (index+delta).coerceIn(0,project.parts.lastIndex); val list = project.parts.toMutableList()
        val part = list.removeAt(index); list.add(target,part); change(project.copy(parts=list))
    }
    fun duplicate() { selected?.let { p -> if(project.parts.size < 64) {
        val copy = p.copy(id=newId(),name=(p.name+tr(" 복사"," copy")).take(120),transform=p.transform.copy(position=p.transform.position+Vec2(24f,24f)))
        val params = project.parameters.map { param -> param.copy(bindings=param.bindings+param.bindings.filter { it.partId==p.id }.map { it.copy(partId=copy.id) }) }
        change(project.copy(parts=project.parts+copy,parameters=params)); select(copy.id)
    } } }
    fun deletePart() {
        val id = selectedId ?: return
        val parts = project.parts.filter { it.id != id }
        change(project.copy(parts=parts,assets=project.assets.filter { a -> parts.any { it.assetId==a.id } },parameters=project.parameters.map { it.copy(bindings=it.bindings.filter { b -> b.partId!=id }) }))
        select(parts.lastOrNull()?.id)
    }
    fun generateMesh(columns: Int, rows: Int) {
        val part = selected ?: return; if(selectedLocked) return
        val asset = project.assets.first { it.id == part.assetId }
        cancelKey()
        change(project.copy(parts=project.parts.map { if(it.id != part.id) it else it.copy(mesh=Mesh.grid(asset.width.toFloat(),asset.height.toFloat(),columns,rows)) },
            parameters=project.parameters.map { it.copy(bindings=it.bindings.filter { b -> b.partId!=part.id }) }))
    }
    fun addParameter(name: String, min: Float, default: Float, max: Float) {
        require(name.isNotBlank() && min.isFinite() && max.isFinite() && default.isFinite() && min<max && default in min..max)
        if(project.parameters.size >= 32) return
        val param = Parameter(name=name.trim().take(120),min=min,default=default,max=max)
        change(project.copy(parameters=project.parameters+param)); selectParameter(param.id)
    }
    fun addAnimation() = editSafely {
        stopPlayback(); cancelKey(); require(project.animations.size < AnimationEngine.MAX_CLIPS) { "Maximum ${AnimationEngine.MAX_CLIPS} clips" }
        val clip=AnimationClip(name=tr("애니메이션 ${project.animations.size+1}","Animation ${project.animations.size+1}"))
        change(project.copy(animations=project.animations+clip)); selectAnimation(clip.id)
    }
    fun addBlinkExample() = addExampleAnimation(
        tr("눈 깜빡임","Eye blink"),tr("눈 개폐","Eye open"),1f,30,listOf(0 to 1f,8 to 0f,15 to 1f)
    )
    fun addBreathExample() = addExampleAnimation(
        tr("호흡","Breathing"),tr("호흡","Breath"),0f,120,listOf(0 to 0f,30 to .5f,60 to 1f,90 to .5f,119 to 0f)
    )
    private fun addExampleAnimation(title: String,parameterName: String,default: Float,duration: Int,keys: List<Pair<Int,Float>>) = editSafely {
        stopPlayback(); cancelKey(); require(project.animations.size < AnimationEngine.MAX_CLIPS) { "Maximum ${AnimationEngine.MAX_CLIPS} clips" }
        val existing=project.parameters.firstOrNull { it.name==parameterName || it.name==if(english) (if(parameterName=="Eye open") "눈 개폐" else "호흡") else (if(parameterName=="눈 개폐") "Eye open" else "Breath") }
        val parameter=existing ?: run {
            require(project.parameters.size < 32) { "Parameter limit reached" }
            Parameter(name=parameterName,min=0f,default=default,max=1f).also { newParameter ->
                change(project.copy(parameters=project.parameters+newParameter))
            }
        }
        require(parameter.min<=0f && parameter.max>=1f) { tr("예제에는 0~1 범위 파라미터가 필요합니다","Example requires a parameter with a 0–1 range") }
        var clip=AnimationClip(name=title,durationFrames=duration,fps=30,loop=true)
        keys.forEach { (frame,value) -> clip=AnimationEngine.putKey(clip,parameter.id,frame,value) }
        change(project.copy(animations=project.animations+clip)); parameterId=parameter.id; selectAnimation(clip.id)
    }
    fun selectAnimation(id: String?) {
        stopPlayback(); cancelKey(); selectedAnimationId=project.animations.firstOrNull { it.id==id }?.id
        selectedTrackParameterId=selectedAnimation?.tracks?.firstOrNull()?.parameterId ?: project.parameters.firstOrNull()?.id
        selectedAnimationKeyId=null; animationFrame=0; animationPreview=true
    }
    fun toggleAnimationPreview() { if(!playing) animationPreview=!animationPreview }
    fun renameAnimation(id: String,name: String) {
        if(name.isNotBlank()) change(project.copy(animations=project.animations.map { if(it.id==id) it.copy(name=name.trim().take(120)) else it }))
    }
    fun deleteAnimation() {
        val id=selectedAnimationId ?: return; stopPlayback()
        change(project.copy(animations=project.animations.filterNot { it.id==id }))
        selectAnimation(project.animations.firstOrNull()?.id)
    }
    fun addAnimationTrack(parameterId: String) = editSafely {
        val clip=selectedAnimation ?: return@editSafely
        require(project.parameters.any { it.id==parameterId })
        if(clip.tracks.any { it.parameterId==parameterId }) { selectedTrackParameterId=parameterId; return@editSafely }
        require(clip.tracks.size < AnimationEngine.MAX_TRACKS_PER_CLIP) { "Track limit reached" }
        change(project.copy(animations=project.animations.map { if(it.id==clip.id) it.copy(tracks=it.tracks+ParameterTrack(parameterId=parameterId)) else it }))
        selectedTrackParameterId=parameterId; selectedAnimationKeyId=null
    }
    fun selectAnimationTrack(parameterId: String) { selectedTrackParameterId=parameterId; selectedAnimationKeyId=null }
    fun seekAnimation(frame: Int) { selectedAnimation?.let { animationFrame=frame.coerceIn(0,it.durationFrames-1) } }
    fun scrubAnimation(frame: Int) { stopPlayback(); seekAnimation(frame) }
    fun setAnimationDuration(frames: Int) = editSafely {
        val clip=selectedAnimation ?: return@editSafely
        require(frames in 1..AnimationEngine.MAX_DURATION_FRAMES)
        require(clip.tracks.all { track -> track.keyframes.all { it.frame < frames } }) { tr("현재 키폼이 있는 프레임보다 긴 길이를 지정하세요","Duration must include every existing keyframe") }
        stopPlayback(); change(project.copy(animations=project.animations.map { if(it.id==clip.id) it.copy(durationFrames=frames) else it })); seekAnimation(animationFrame)
    }
    fun setAnimationFps(fps: Int) = editSafely {
        val clip=selectedAnimation ?: return@editSafely; require(fps in 1..120)
        stopPlayback(); change(project.copy(animations=project.animations.map { if(it.id==clip.id) it.copy(fps=fps) else it }))
    }
    fun setAnimationLoop(loop: Boolean) {
        val clip=selectedAnimation ?: return; stopPlayback()
        change(project.copy(animations=project.animations.map { if(it.id==clip.id) it.copy(loop=loop) else it }))
    }
    fun recordAnimationKey() = editSafely {
        val clip=selectedAnimation ?: return@editSafely
        val parameterId=selectedTrackParameterId ?: return@editSafely
        val parameter=renderProject.parameters.firstOrNull { it.id==parameterId } ?: return@editSafely
        val next=AnimationEngine.putKey(clip,parameterId,animationFrame,parameter.value)
        change(project.copy(animations=project.animations.map { if(it.id==clip.id) next else it }))
        selectedAnimationKeyId=next.tracks.first { it.parameterId==parameterId }.keyframes.first { it.frame==animationFrame }.id
    }
    fun selectAnimationKey(id: String?) { selectedAnimationKeyId=selectedTrack?.keyframes?.firstOrNull { it.id==id }?.id }
    fun moveSelectedAnimationKey(frame: Int) = editSafely {
        val clip=selectedAnimation ?: return@editSafely; val track=selectedTrack ?: return@editSafely
        val key=track.keyframes.firstOrNull { it.id==selectedAnimationKeyId } ?: return@editSafely
        val next=AnimationEngine.moveKey(clip,track.id,key.id,frame)
        change(project.copy(animations=project.animations.map { if(it.id==clip.id) next else it })); seekAnimation(frame)
    }
    fun deleteSelectedAnimationKey() = editSafely {
        val clip=selectedAnimation ?: return@editSafely; val track=selectedTrack ?: return@editSafely
        val key=track.keyframes.firstOrNull { it.id==selectedAnimationKeyId } ?: return@editSafely
        val next=AnimationEngine.removeKey(clip,track.id,key.id)
        change(project.copy(animations=project.animations.map { if(it.id==clip.id) next else it })); selectedAnimationKeyId=null
    }
    fun setSelectedKeyInterpolation(mode: Interpolation) = editSafely {
        val clip=selectedAnimation ?: return@editSafely; val track=selectedTrack ?: return@editSafely
        val key=track.keyframes.firstOrNull { it.id==selectedAnimationKeyId } ?: return@editSafely
        val next=clip.copy(tracks=clip.tracks.map { t -> if(t.id!=track.id) t else t.copy(keyframes=t.keyframes.map { if(it.id==key.id) it.copy(interpolation=mode) else it }) })
        change(project.copy(animations=project.animations.map { if(it.id==clip.id) next else it }))
    }
    fun togglePlayback() {
        if(playing) { stopPlayback(); return }
        if(keyEditing) { message(tr("키폼 편집을 먼저 마치세요","Finish keyform editing before playback")); return }
        val clip=selectedAnimation ?: return
        if(clip.tracks.none { it.keyframes.isNotEmpty() }) { message(tr("재생할 키프레임을 먼저 기록하세요","Record at least one keyframe before playback")); return }
        cancelKey(); if(animationFrame>=clip.durationFrames-1) animationFrame=0
        val startedAt=System.nanoTime(); val startFrame=animationFrame; playing=true; animationPreview=true
        playbackJob=viewModelScope.launch {
            while(isActive && selectedAnimationId==clip.id) {
                val elapsed=(System.nanoTime()-startedAt)/1_000_000_000.0
                val position=startFrame+elapsed*clip.fps
                if(!clip.loop && position>=clip.durationFrames-1) {
                    animationFrame=clip.durationFrames-1; playing=false; playbackJob=null; break
                }
                val wrapped=if(clip.loop) position%clip.durationFrames else position
                animationFrame=wrapped.toInt().coerceIn(0,clip.durationFrames-1)
                delay(16)
            }
        }
    }
    fun stopPlayback() { playing=false; playbackJob?.cancel(); playbackJob=null }
    override fun onCleared() { stopPlayback(); super.onCleared() }
    fun parameterValue(id: String, value: Float) {
        if(keyEditing) return
        change(project.copy(parameters=project.parameters.map { if(it.id==id) it.copy(value=value.coerceIn(it.min,it.max)) else it }))
    }
    fun deleteParameter() { val id=parameterId ?: return; cancelKey(); change(project.copy(parameters=project.parameters.filter { it.id!=id },animations=project.animations.map { clip -> clip.copy(tracks=clip.tracks.filterNot { it.parameterId==id }) })); parameterId=project.parameters.firstOrNull()?.id; if(selectedTrackParameterId==id) { selectedTrackParameterId=parameterId; selectedAnimationKeyId=null } }
    fun blink() {
        val part = selected ?: return; val p = parameter ?: return; if(selectedLocked) return
        cancelKey(); change(ParameterEngine.blink(project,p.id,part.id)); message(tr("최솟값=닫힘 · 최댓값=열림 키폼 생성","Min=closed · max=open keyforms created"))
    }
    fun startKey() {
        stopPlayback(); val param=parameter ?: return; if(selectedLocked) return
        val part=selected; val node=selectedDeformer
        if(part!=null) { draft=ParameterEngine.isolatedVertices(project,part,param.id); meshMode=true; meshTool=0 }
        else if(node!=null) deformerDraft=Rigging.isolatedPose(project,node,param.id)
        else return
        keyEditing=true
    }
    fun commitKey() {
        val p=parameter ?: return
        val values=keyValues(p)
        if(values.size>=32 && values.none { abs(it-p.value)<.00001f }) { message(tr("키폼은 최대 32개입니다","Maximum 32 keyforms")); return }
        selected?.let { part -> draft?.let { change(ParameterEngine.record(project,p.id,part.id,it)) } }
        selectedDeformer?.let { node -> deformerDraft?.let { change(Rigging.record(project,p.id,node.id,it)) } }
        cancelKey()
    }
    fun cancelKey() { keyEditing=false; draft=null; deformerDraft=null }
    fun deleteKey() {
        val p=parameter ?: return; if(selectedLocked) return
        change(project.copy(parameters=project.parameters.map { if(it.id!=p.id) it else it.copy(
            bindings=it.bindings.mapNotNull { b -> if(b.partId!=selectedId) b else b.keys.filter { k -> abs(k.value-p.value)>.00001f }.let { keys -> if(keys.isEmpty()) null else b.copy(keys=keys) } },
            deformerBindings=it.deformerBindings.mapNotNull { b -> if(b.deformerId!=selectedDeformerId) b else b.keys.filter { k -> abs(k.value-p.value)>.00001f }.let { keys -> if(keys.isEmpty()) null else b.copy(keys=keys) } }
        ) })); cancelKey()
    }
    fun previewParameter(id: String, value: Float) {
        if(busy || keyEditing) return
        if(gestureStart==null) beginGesture()
        project=project.copy(parameters=project.parameters.map { if(it.id==id) it.copy(value=value.coerceIn(it.min,it.max)) else it })
    }
    fun previewOpacity(value: Float) {
        val part=selected ?: return; if(busy || selectedLocked) return
        if(gestureStart==null) beginGesture()
        project=project.copy(parts=project.parts.map { if(it.id==part.id) it.copy(opacity=value) else it })
    }
    fun beginGesture() { gestureStart=project; rawPartPosition=selected?.transform?.position; rawVertexPosition=null; rawVertexIndex=-1 }
    fun endGesture() {
        val initial = gestureStart ?: return; gestureStart=null
        if(initial!=project) {
            try { Validation.check(project) } catch(e: Exception) { project=initial; failure(e); return }
            history.commit(project); syncHistory(); dirty=true; revision++; autosave() }
    }
    fun movePart(delta: Vec2) {
        val part=selected ?: return; if(busy || selectedLocked || keyEditing) return
        val raw=(rawPartPosition ?: part.transform.position)+delta*(if(precise) .2f else 1f)
        rawPartPosition=raw
        var pos=raw
        if(snap) pos=Vec2(kotlin.math.round(pos.x/10)*10,kotlin.math.round(pos.y/10)*10)
        project=project.copy(parts=project.parts.map { if(it.id==part.id) it.copy(transform=it.transform.copy(position=pos)) else it })
    }
    fun moveVertex(index: Int, delta: Vec2) {
        val part=selected ?: return; if(busy || selectedLocked) return
        val source=if(keyEditing) draft ?: return else part.mesh.vertices
        if(index !in source.indices) return
        if(!keyEditing && project.parameters.any { it.bindings.any { b -> b.partId==part.id } }) { message(tr("키폼 편집을 켜세요","Enable keyform editing")); return }
        if(rawVertexIndex!=index) { rawVertexIndex=index; rawVertexPosition=source[index] }
        val raw=(rawVertexPosition ?: source[index])+delta*(if(precise) .2f else 1f)
        rawVertexPosition=raw
        var pos=raw
        if(snap) pos=Vec2(kotlin.math.round(pos.x/10)*10,kotlin.math.round(pos.y/10)*10)
        val points=source.mapIndexed { i,v -> if(i==index) pos else v }
        if(keyEditing) draft=points else project=project.copy(parts=project.parts.map { if(it.id==part.id) it.copy(mesh=it.mesh.copy(vertices=points)) else it })
    }
    private fun editSafely(block: () -> Unit) { try { block() } catch(e: Exception) { failure(e) } }
    fun addDeformer(kind: DeformerKind) = editSafely {
        if(busy || keyEditing || project.deformers.size>=64 || ((selected!=null || selectedDeformer!=null) && selectedLocked)) return@editSafely
        val eval=RigEvaluator(project); val part=selected; val oldNode=selectedDeformer
        val parent=part?.parentId ?: oldNode?.parentId
        val targets=oldNode?.let { Rigging.subtree(project,it.id) }
        val points=when {
            part!=null -> eval.partWorld(part)
            targets!=null -> project.parts.filter { it.parentId in targets }.flatMap { eval.partWorld(it) }
            else -> emptyList()
        }.map { eval.worldToParent(parent,it) }
        val center=if(points.isEmpty()) eval.worldToParent(parent,camera.center) else Vec2((points.minOf { it.x }+points.maxOf { it.x })/2,(points.minOf { it.y }+points.maxOf { it.y })/2)
        val width=if(points.isEmpty()) 300f else max(40f,points.maxOf { it.x }-points.minOf { it.x }+40)
        val height=if(points.isEmpty()) 300f else max(40f,points.maxOf { it.y }-points.minOf { it.y }+40)
        val label=when(kind) { DeformerKind.GROUP -> tr("그룹","Group"); DeformerKind.ROTATION -> tr("회전 디포머","Rotation deformer"); DeformerKind.WARP -> tr("워프 디포머","Warp deformer") }
        var node=Deformer(name=label,kind=kind,parentId=parent,pivot=center,origin=center-Vec2(width/2,height/2),width=width,height=height)
        if(kind==DeformerKind.WARP) node=node.copy(controls=node.restControls())
        var next=project.copy(deformers=project.deformers+node)
        val child=part?.id ?: oldNode?.id
        if(child!=null) next=Rigging.reparent(next,child,node.id)
        change(next); if(project.deformers.any { it.id==node.id }) selectDeformer(node.id)
    }
    fun setParent(parentId: String?) = editSafely {
        if(busy || keyEditing || selectedLocked) return@editSafely
        val id=selectedId ?: selectedDeformerId ?: return@editSafely
        change(Rigging.reparent(project,id,parentId))
    }
    fun updateDeformer(edit: (Deformer)->Deformer) {
        val node=selectedDeformer ?: return
        if(keyEditing) return
        change(project.copy(deformers=project.deformers.map { if(it.id==node.id) edit(it) else it }))
    }
    fun updateDeformerPose(edit: (DeformerPose)->DeformerPose) = editSafely {
        val node=selectedDeformer ?: return@editSafely; if(busy || selectedLocked) return@editSafely
        if(keyEditing) {
            val next=edit(deformerDraft ?: return@editSafely)
            val check=Rigging.record(project,parameterId ?: return@editSafely,node.id,next)
            Validation.check(check); deformerDraft=next
        } else {
            if(hasDeformerKeys) { message(tr("키폼 편집을 켜세요","Enable keyform editing")); return@editSafely }
            val pose=edit(node.basePose()); updateDeformer { it.copy(transform=pose.transform,controls=pose.controls) }
        }
    }
    fun removeDeformer() {
        val node=selectedDeformer ?: return; if(selectedLocked || keyEditing) return
        change(Rigging.removeSubtree(project,node.id)); select(project.parts.lastOrNull()?.id)
    }
    fun addMeshVertex(world: Vec2) = editSafely {
        val part=selected ?: return@editSafely; if(selectedLocked || keyEditing) return@editSafely
        val render=renderProject; val eval=RigEvaluator(render)
        val hit=MeshTopology.hit(part.mesh,eval.partWorld(part),world) ?: return@editSafely
        change(MeshTopology.insert(project,part.id,hit)); selectedVertex=project.parts.first { it.id==part.id }.mesh.vertices.lastIndex
    }
    fun removeMeshVertex(index: Int=selectedVertex) = editSafely {
        val part=selected ?: return@editSafely; if(selectedLocked || keyEditing) return@editSafely
        change(MeshTopology.remove(project,part.id,index)); selectedVertex=-1
    }
    fun moveElementWorld(delta: Vec2) = editSafely {
        if(busy || selectedLocked) return@editSafely
        val eval=RigEvaluator(renderProject)
        selected?.let { part ->
            val anchor=eval.parentToWorld(part.parentId,part.transform.position)
            val local=eval.worldToParent(part.parentId,anchor+delta)-part.transform.position
            movePart(local); return@editSafely
        }
        val node=selectedDeformer ?: return@editSafely
        if(!keyEditing && hasDeformerKeys) return@editSafely
        val pose=eval.pose(node.id)
        val world=eval.pivotWorld(node)
        val local=eval.worldToParent(node.parentId,world+delta*(if(precise) .2f else 1f))-(node.pivot+pose.transform.position)
        val source=deformerDraft ?: node.basePose()
        val next=source.copy(transform=source.transform.copy(position=source.transform.position+local))
        if(keyEditing) deformerDraft=next else project=project.copy(deformers=project.deformers.map { if(it.id==node.id) it.copy(transform=next.transform) else it })
    }
    fun moveVertexWorld(index: Int,delta: Vec2) = editSafely {
        val part=selected ?: return@editSafely
        val eval=RigEvaluator(renderProject); val current=ParameterEngine.vertices(renderProject,part)
        if(index !in current.indices) return@editSafely
        val world=eval.partWorld(part,current)[index]
        val local=eval.partLocal(part,world+delta)-current[index]
        moveVertex(index,local)
    }
    fun moveWarpControl(index: Int,delta: Vec2) = editSafely {
        val node=selectedDeformer ?: return@editSafely; if(selectedLocked || (!keyEditing && hasDeformerKeys)) return@editSafely
        val eval=RigEvaluator(renderProject); val pose=eval.pose(node.id)
        if(index !in pose.controls.indices) return@editSafely
        val local=eval.controlLocal(node,eval.controlWorld(node)[index]+delta*(if(precise) .2f else 1f))-pose.controls[index]
        val source=deformerDraft ?: node.basePose()
        val points=source.controls.mapIndexed { i,p -> if(i==index) p+local else p }
        if(keyEditing) deformerDraft=source.copy(controls=points) else project=project.copy(deformers=project.deformers.map { if(it.id==node.id) it.copy(controls=points) else it })
    }
    fun rotateDeformer(from: Vec2,to: Vec2) = editSafely {
        val node=selectedDeformer ?: return@editSafely; if(selectedLocked || (!keyEditing && hasDeformerKeys)) return@editSafely
        val eval=RigEvaluator(renderProject); val pose=eval.pose(node.id); val center=node.pivot+pose.transform.position
        val a=eval.worldToParent(node.parentId,from)-center; val b=eval.worldToParent(node.parentId,to)-center
        var angle=(atan2(b.y,b.x)-atan2(a.y,a.x))*180/PI.toFloat()
        if(angle>180) angle-=360; if(angle< -180) angle+=360
        val source=deformerDraft ?: node.basePose(); val next=source.copy(transform=source.transform.copy(rotation=source.transform.rotation+angle*(if(precise) .2f else 1f)))
        if(keyEditing) deformerDraft=next else project=project.copy(deformers=project.deformers.map { if(it.id==node.id) it.copy(transform=next.transform) else it })
    }
    fun undo() { cancelKey(); project=history.undo(); syncHistory(); sanitizeSelection(); dirty=true; revision++; autosave() }
    fun redo() { cancelKey(); project=history.redo(); syncHistory(); sanitizeSelection(); dirty=true; revision++; autosave() }
    private fun sanitizeSelection() { if(project.deformers.none { it.id==selectedDeformerId }) selectedDeformerId=null; selectedVertex=-1; if(selectedDeformerId!=null) { selectedId=null; return }; if(project.parts.none { it.id==selectedId }) selectedId=project.parts.lastOrNull()?.id; if(project.parameters.none { it.id==parameterId }) parameterId=project.parameters.firstOrNull()?.id }
}
