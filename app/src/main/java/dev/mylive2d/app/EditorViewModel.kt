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
import java.util.Locale

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
    var parameterId by mutableStateOf<String?>(null)
    var keyEditing by mutableStateOf(false); private set
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
    private var saveJob: Job? = null
    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()
    val selected get() = project.parts.firstOrNull { it.id == selectedId }
    val parameter get() = project.parameters.firstOrNull { it.id == parameterId }
    init {
        viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) { repository.active?.let { repository.open(it) } }
                if(restored != null) { setProject(restored); message(tr("자동 저장 복구 완료","Autosave restored")) }
            } catch(e: Exception) { failure(e) }
            finally { busy = false; refreshRecent() }
        }
    }
    fun tr(ko: String, en: String) = if(english) en else ko
    fun language() { english = !english; prefs.edit().putBoolean("english",english).apply() }
    fun theme() { dark = !dark; prefs.edit().putBoolean("dark",dark).apply() }
    fun message(text: String) { events.trySend(text) }
    private fun failure(e: Exception) { message(tr("작업 실패: ","Operation failed: ")+(e.message ?: e.javaClass.simpleName)) }
    private fun refreshRecent() { recent = repository.recent() }
    private fun setProject(p: Project) {
        project = p; history = History(p); selectedId = p.parts.lastOrNull()?.id; parameterId = p.parameters.firstOrNull()?.id
        keyEditing = false; draft = null; gestureStart = null; camera = Camera(); dirty = false; revision++; syncHistory()
        repository.active = p.id
    }
    private fun syncHistory() { canUndo = history.canUndo; canRedo = history.canRedo }
    fun change(p: Project) {
        if(busy || p == project) return
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
        withContext(Dispatchers.IO) { repository.save(next) }; setProject(next)
    }
    fun open(id: String) = operation {
        saveJob?.cancel(); val old = project
        val next = withContext(Dispatchers.IO) { repository.save(old); repository.open(id) }; setProject(next)
    }
    fun deleteRecent(id: String) = operation {
        require(id != project.id) { tr("열려 있는 프로젝트는 삭제할 수 없습니다","Create/open another project before deleting this one") }
        withContext(Dispatchers.IO) { repository.delete(id) }
    }
    fun importProject(uri: Uri) = operation {
        saveJob?.cancel(); val old = project
        val next = withContext(Dispatchers.IO) { repository.save(old); repository.importProject(uri) }
        withContext(Dispatchers.IO) { repository.save(next) }; setProject(next); message(tr("프로젝트 가져옴","Project imported"))
    }
    fun export(uri: Uri) = operation {
        val p = project; withContext(Dispatchers.IO) { repository.export(p,uri) }; message(tr("프로젝트 내보내기 완료","Project exported"))
    }
    fun importPng(uris: List<Uri>) = operation {
        require(project.assets.size+uris.size <= 32 && project.parts.size+uris.size <= 64) { "Maximum 32 PNGs / 64 parts" }
        val imported = withContext(Dispatchers.IO) { uris.map { repository.importPng(it) } }
        val assets = project.assets+imported.map { it.asset }
        require(assets.sumOf { it.width.toLong()*it.height } <= 16_777_216) { "Image memory budget exceeded (16 MP)" }
        val next = project.copy(assets=assets,parts=project.parts+imported.map { i ->
            Part(name=i.name,assetId=i.asset.id,mesh=Mesh.grid(i.asset.width.toFloat(),i.asset.height.toFloat()))
        })
        history.commit(next); project = next; syncHistory(); dirty = true; revision++; selectedId = next.parts.lastOrNull()?.id; cancelKey(); autosave()
        message(tr("PNG 가져옴 · 파츠를 이동해 배치하세요","PNG imported · move parts into place"))
    }
    fun select(id: String?) { cancelKey(); selectedId = id }
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
        change(project.copy(parts=parts,parameters=project.parameters.map { it.copy(bindings=it.bindings.filter { b -> b.partId!=id }) }))
        select(parts.lastOrNull()?.id)
    }
    fun generateMesh(columns: Int, rows: Int) {
        val part = selected ?: return; if(part.locked) return
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
    fun parameterValue(id: String, value: Float) {
        if(keyEditing) return
        change(project.copy(parameters=project.parameters.map { if(it.id==id) it.copy(value=value.coerceIn(it.min,it.max)) else it }))
    }
    fun deleteParameter() { val id=parameterId ?: return; cancelKey(); change(project.copy(parameters=project.parameters.filter { it.id!=id })); parameterId=project.parameters.firstOrNull()?.id }
    fun blink() {
        val part = selected ?: return; val p = parameter ?: return; if(part.locked) return
        cancelKey(); change(ParameterEngine.blink(project,p.id,part.id)); message(tr("최솟값=닫힘 · 최댓값=열림 키폼 생성","Min=closed · max=open keyforms created"))
    }
    fun startKey() {
        val part = selected ?: return; val p=parameter ?: return; if(part.locked) return
        if(project.parameters.any { it.id!=p.id && it.bindings.any { b-> b.partId==part.id } }) {
            message(tr("Phase 1에서는 파츠당 한 파라미터로 키폼을 편집하세요","Phase 1 keyform editing supports one parameter per part")); return
        }
        draft=ParameterEngine.vertices(project,part); keyEditing=true; meshMode=true
    }
    fun commitKey() {
        val part=selected ?: return; val p=parameter ?: return; val pose=draft ?: return
        if((p.bindings.firstOrNull { it.partId==part.id }?.keys?.size ?: 0) >= 32) { message("Maximum 32 keyforms"); return }
        change(ParameterEngine.record(project,p.id,part.id,pose)); cancelKey()
    }
    fun cancelKey() { keyEditing=false; draft=null }
    fun deleteKey() {
        val p=parameter ?: return; val part=selected ?: return
        change(project.copy(parameters=project.parameters.map { if(it.id!=p.id) it else it.copy(bindings=it.bindings.mapNotNull { b ->
            if(b.partId!=part.id) b else b.keys.filter { kotlin.math.abs(it.value-p.value)>.00001f }.let { if(it.isEmpty()) null else b.copy(keys=it) }
        }) })); cancelKey()
    }
    fun beginGesture() { gestureStart=project }
    fun endGesture() {
        val initial = gestureStart ?: return; gestureStart=null
        if(initial!=project) { history.commit(project); syncHistory(); dirty=true; revision++; autosave() }
    }
    fun movePart(delta: Vec2) {
        val part=selected ?: return; if(busy || part.locked || keyEditing) return
        var pos=part.transform.position+delta*(if(precise) .2f else 1f)
        if(snap) pos=Vec2(kotlin.math.round(pos.x/10)*10,kotlin.math.round(pos.y/10)*10)
        project=project.copy(parts=project.parts.map { if(it.id==part.id) it.copy(transform=it.transform.copy(position=pos)) else it })
    }
    fun moveVertex(index: Int, delta: Vec2) {
        val part=selected ?: return; if(busy || part.locked) return
        val source=if(keyEditing) draft ?: return else part.mesh.vertices
        if(index !in source.indices) return
        if(!keyEditing && project.parameters.any { it.bindings.any { b -> b.partId==part.id } }) { message(tr("키폼 편집을 켜세요","Enable keyform editing")); return }
        var pos=source[index]+delta*(if(precise) .2f else 1f)
        if(snap) pos=Vec2(kotlin.math.round(pos.x/10)*10,kotlin.math.round(pos.y/10)*10)
        val points=source.mapIndexed { i,v -> if(i==index) pos else v }
        if(keyEditing) draft=points else project=project.copy(parts=project.parts.map { if(it.id==part.id) it.copy(mesh=it.mesh.copy(vertices=points)) else it })
    }
    fun undo() { cancelKey(); project=history.undo(); syncHistory(); sanitizeSelection(); dirty=true; revision++; autosave() }
    fun redo() { cancelKey(); project=history.redo(); syncHistory(); sanitizeSelection(); dirty=true; revision++; autosave() }
    private fun sanitizeSelection() { if(project.parts.none { it.id==selectedId }) selectedId=project.parts.lastOrNull()?.id; if(project.parameters.none { it.id==parameterId }) parameterId=project.parameters.firstOrNull()?.id }
}
