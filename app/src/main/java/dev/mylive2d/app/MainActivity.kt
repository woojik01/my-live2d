package dev.mylive2d.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mylive2d.core.*
import kotlinx.coroutines.launch
import kotlin.math.*

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: EditorViewModel=viewModel()
            MaterialTheme(colorScheme=if(vm.dark) darkColorScheme(primary=Color(0xFFB8C4FF),secondary=Color(0xFF8FDFD1)) else lightColorScheme(primary=Color(0xFF4255A8))) {
                EditorScreen(vm)
            }
        }
    }
}

@Composable private fun EditorScreen(vm: EditorViewModel) {
    val snack=remember { SnackbarHostState() }
    val screenDp=LocalConfiguration.current.screenWidthDp
    val wideScreen=screenDp>=850
    val density=LocalDensity.current.density
    var left by remember { mutableStateOf(false) }; var right by remember { mutableStateOf(true) }
    var projects by remember { mutableStateOf(false) }; var help by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }; var create by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val context=LocalContext.current
    val surface=remember { ModelSurface(context,vm) }
    val png=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if(it.isNotEmpty()) vm.importPng(it) }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importProject) }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::export) }
    LaunchedEffect(vm) { vm.messages.collect { snack.showSnackbar(it) } }
    DisposableEffect(lifecycle,surface) {
        val observer=LifecycleEventObserver { _,event -> when(event) {
            Lifecycle.Event.ON_RESUME -> { surface.onResume(); surface.update() }
            Lifecycle.Event.ON_PAUSE -> surface.onPause()
            Lifecycle.Event.ON_STOP -> vm.save()
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); surface.onPause() }
    }
    Scaffold(snackbarHost={ SnackbarHost(snack) },modifier=Modifier.safeDrawingPadding()) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={ projects=true },enabled=!vm.busy) { Text("☰") }
                Text(vm.project.name+if(vm.dirty) " *" else "",modifier=Modifier.widthIn(max=200.dp).clickable { rename=true }.testTag("projectName"),maxLines=1,overflow=TextOverflow.Ellipsis)
                TextButton(onClick=vm::save,enabled=!vm.busy,modifier=Modifier.testTag("save")) { Text(vm.tr("저장","Save")) }
                TextButton(onClick=vm::undo,enabled=vm.canUndo && !vm.busy) { Text(vm.tr("실행 취소","Undo")) }
                TextButton(onClick=vm::redo,enabled=vm.canRedo && !vm.busy) { Text(vm.tr("다시 실행","Redo")) }
                TextButton(onClick={ help=true }) { Text("?") }
            }
            HorizontalDivider()
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide=maxWidth>=850.dp
                val overlayWidth=if(maxWidth<500.dp) 196.dp else 250.dp
                Row(Modifier.fillMaxSize()) {
                    if(left && wide) Surface(Modifier.width(220.dp).fillMaxHeight(),tonalElevation=2.dp) { Layers(vm) }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        AndroidView(factory={ surface },update={ it.update() },modifier=Modifier.fillMaxSize().testTag("modelCanvas"))
                        if(vm.project.parts.isEmpty()) Text(vm.tr("PNG를 가져와 시작하세요\n두 손가락: 확대·이동","Import PNGs to begin\nTwo fingers: zoom & pan"),Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface.copy(alpha=.9f)).padding(20.dp))
                        Text("${(vm.camera.zoom*100).roundToInt()}%",Modifier.align(Alignment.BottomStart).padding(8.dp).background(MaterialTheme.colorScheme.surface.copy(alpha=.85f)).padding(4.dp))
                        if(!wide && left) Surface(Modifier.width(240.dp).fillMaxHeight().align(Alignment.CenterStart),tonalElevation=6.dp,shadowElevation=8.dp) { Layers(vm) }
                        if(!wide && right) Surface(Modifier.width(overlayWidth).fillMaxHeight().align(Alignment.CenterEnd),tonalElevation=6.dp,shadowElevation=8.dp) { Properties(vm) }
                    }
                    if(right && wide) Surface(Modifier.width(280.dp).fillMaxHeight(),tonalElevation=2.dp) { Properties(vm) }
                }
                if(vm.busy) Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface.copy(alpha=.75f)) {
                    Box(contentAlignment=Alignment.Center) { CircularProgressIndicator() }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={ left=!left; if(left && !wideScreen) right=false }) { Text(vm.tr("파츠","Parts")) }
                TextButton(onClick={ right=!right; if(right && !wideScreen) left=false }) { Text(vm.tr("속성","Properties")) }
                TextButton(onClick={ png.launch(arrayOf("image/png")) },enabled=!vm.busy) { Text("+ PNG") }
                FilterChip(selected=vm.meshMode,onClick={ vm.meshMode=!vm.meshMode },label={ Text(if(vm.selectedDeformer?.kind==DeformerKind.WARP) vm.tr("격자","Grid edit") else vm.tr("메쉬","Mesh")) })
                Spacer(Modifier.width(8.dp))
                TextButton(onClick={ fit(vm,surface.width.toFloat(),surface.height.toFloat(),if(left && !wideScreen) 240*density else 0f,if(right && !wideScreen) (if(screenDp<500) 196 else 250)*density else 0f) }) { Text(vm.tr("전체 보기","Fit")) }
                TextButton(onClick={ vm.grid=!vm.grid }) { Text(vm.tr("그리드","Grid")+if(vm.grid) " ✓" else "") }
                TextButton(onClick={ vm.precise=!vm.precise }) { Text(vm.tr("정밀","Precision")+if(vm.precise) " ✓" else "") }
                TextButton(onClick={ vm.snap=!vm.snap }) { Text(vm.tr("스냅","Snap")+if(vm.snap) " ✓" else "") }
                TextButton(onClick={ vm.background=(vm.background+1)%3 }) { Text(vm.tr("배경","Background")) }
            }
            if(vm.keyEditing) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer),verticalAlignment=Alignment.CenterVertically) {
                Text(vm.tr("키폼 편집 중","Editing keyform"),Modifier.weight(1f).padding(8.dp))
                TextButton(onClick=vm::commitKey) { Text(vm.tr("키폼 기록","Record keyform")) }
                TextButton(onClick=vm::cancelKey) { Text(vm.tr("취소","Cancel")) }
            }
        }
    }
    if(projects) AlertDialog(onDismissRequest={ projects=false },title={ Text("My Live 2D") },text={
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Button(onClick={ projects=false; create=true },enabled=!vm.busy) { Text(vm.tr("새 프로젝트","New project")) }
            OutlinedButton(onClick={ projects=false; open.launch(arrayOf("application/zip","application/octet-stream","*/*")) },enabled=!vm.busy) { Text(vm.tr(".mylive2d 가져오기","Import .mylive2d")) }
            OutlinedButton(onClick={ projects=false; export.launch(vm.project.name.replace(Regex("[^\\p{L}\\p{N} _-]"),"_")+".mylive2d") },enabled=!vm.busy) { Text(vm.tr("프로젝트 내보내기","Export project")) }
            Row { TextButton(onClick=vm::language) { Text(if(vm.english) "한국어" else "English") }; TextButton(onClick=vm::theme) { Text(vm.tr("테마 전환","Toggle theme")) } }
            Text(vm.tr("최근 프로젝트","Recent projects"),style=MaterialTheme.typography.titleSmall)
            vm.recent.forEach { item -> Row(verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={ projects=false; vm.open(item.id) },modifier=Modifier.weight(1f),enabled=!vm.busy) { Text(item.name,maxLines=1,overflow=TextOverflow.Ellipsis) }
                TextButton(onClick={ confirmDelete=item.id },enabled=item.id!=vm.project.id && !vm.busy) { Text(vm.tr("삭제","Delete")) }
            } }
        }
    },confirmButton={ TextButton(onClick={ projects=false }) { Text(vm.tr("닫기","Close")) } })
    confirmDelete?.let { id -> AlertDialog(onDismissRequest={ confirmDelete=null },title={ Text(vm.tr("프로젝트 삭제?","Delete project?")) },text={ Text(vm.tr("이 기기의 프로젝트와 백업을 삭제합니다.","Deletes this local project and its backup.")) },confirmButton={ TextButton(onClick={ confirmDelete=null; vm.deleteRecent(id) }) { Text(vm.tr("삭제","Delete")) } },dismissButton={ TextButton(onClick={ confirmDelete=null }) { Text(vm.tr("취소","Cancel")) } }) }
    if(rename || create) NameDialog(vm,if(create) vm.tr("새 프로젝트","New project") else vm.tr("이름 변경","Rename"),if(create) "" else vm.project.name,onDismiss={ rename=false; create=false }) { name ->
        if(create) vm.newProject(name) else vm.rename(name); rename=false; create=false
    }
    if(help) AlertDialog(onDismissRequest={ help=false },title={ Text(vm.tr("빠른 시작","Quick start")) },text={ Text(vm.tr(
        "1. + PNG로 눈 이미지 두 개를 불러옵니다.\n2. 파츠를 선택해 드래그로 배치하고 속성에서 크기·회전을 조절합니다.\n3. 파라미터를 추가하고 선택한 눈에 ‘눈 개폐 키폼’을 만듭니다. 각 눈에 반복하세요.\n4. 슬라이더 최솟값은 닫힘, 최댓값은 열림입니다.\n5. 직접 수정하려면 값을 정한 뒤 ‘키폼 편집’ → 정점 드래그 → ‘키폼 기록’을 누릅니다.\n6. 저장 후 ☰에서 .mylive2d로 내보낼 수 있습니다.\n\n두 손가락: 확대·이동 / 빈 곳 드래그: 카메라 이동\n메쉬 재생성은 해당 파츠의 기존 키폼을 지웁니다.\n자동 저장은 약 0.8초 후 적용됩니다. 미기록 키폼 초안은 저장되지 않습니다.\n\nPhase 2: 파츠 패널에서 + 그룹/회전/워프로 선택 요소를 감쌉니다. 다른 요소는 속성의 부모 연결로 넣으세요. 워프 격자 제어점 또는 회전 끝점을 움직이고 키폼을 기록합니다. 정점 +/− 모드는 삼각형 내부/내부 정점을 탭합니다.",
        "1. Import two eye PNGs.\n2. Select and drag parts; adjust scale and rotation in Properties.\n3. Add a parameter, then create Eye blink keyforms for each eye.\n4. Minimum closes the eye; maximum opens it.\n5. For custom shapes: set a value → Edit keyform → drag vertices → Record keyform.\n6. Save, then export .mylive2d from ☰.\n\nTwo fingers: zoom/pan. Empty area: pan.\nRebuilding a mesh removes its keyforms.\nAutosave runs after about 0.8 seconds. Unrecorded keyform drafts are not saved.\n\nPhase 2: Parts panel → + Group/Rotate/Warp wraps the selection. Attach other elements via Parent in Properties. Drag warp controls or the rotation endpoint and record keyforms. Vertex +/− taps triangle interiors/interior vertices."
    )) },confirmButton={ TextButton(onClick={ help=false }) { Text(vm.tr("확인","OK")) } })
}

private fun fit(vm: EditorViewModel,width: Float,height: Float,left: Float=0f,right: Float=0f) {
    val eval=RigEvaluator(vm.renderProject)
    val points=vm.renderProject.parts.filter { eval.visible(it) }.flatMap { eval.partWorld(it) }
    if(points.isEmpty() || width<=0 || height<=0) { vm.camera=Camera(); return }
    val minX=points.minOf { it.x }; val maxX=points.maxOf { it.x }; val minY=points.minOf { it.y }; val maxY=points.maxOf { it.y }
    val zoom=min((width-left-right).coerceAtLeast(1f)/(maxX-minX+100),height/(maxY-minY+100)).coerceIn(.03f,20f)
    vm.camera=Camera(Vec2((minX+maxX)/2-(left-right)/(2*zoom),(minY+maxY)/2),zoom)
}

@Composable private fun Layers(vm: EditorViewModel) { HierarchyPanel(vm) }

@Composable private fun Properties(vm: EditorViewModel) {
    var name by remember { mutableStateOf(false) }; var parameter by remember { mutableStateOf(false) }; var mesh by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        val p=vm.selected
        if(vm.selectedDeformer!=null) DeformerProperties(vm)
        if(p!=null) {
            Text(p.name,style=MaterialTheme.typography.titleMedium)
            ParentPicker(vm)
            Row { TextButton(onClick={ name=true },enabled=!vm.selectedLocked) { Text(vm.tr("이름","Rename")) }; TextButton(onClick=vm::duplicate) { Text(vm.tr("복제","Copy")) } }
            FloatEditor(vm.tr("X 위치","X"),p.transform.position.x,enabled=!vm.selectedLocked) { v -> vm.updatePart { it.copy(transform=it.transform.copy(position=it.transform.position.copy(x=v))) } }
            FloatEditor(vm.tr("Y 위치","Y"),p.transform.position.y,enabled=!vm.selectedLocked) { v -> vm.updatePart { it.copy(transform=it.transform.copy(position=it.transform.position.copy(y=v))) } }
            FloatEditor(vm.tr("회전 (도)","Rotation (°)"),p.transform.rotation,enabled=!vm.selectedLocked) { v -> vm.updatePart { it.copy(transform=it.transform.copy(rotation=v.coerceIn(-3600f,3600f))) } }
            FloatEditor(vm.tr("배율","Scale"),p.transform.scale,enabled=!vm.selectedLocked) { v -> if(v in .01f..100f) vm.updatePart { it.copy(transform=it.transform.copy(scale=v)) } }
            Text(vm.tr("투명도","Opacity")+" ${(p.opacity*100).roundToInt()}%",style=MaterialTheme.typography.labelMedium)
            Slider(value=p.opacity,onValueChange=vm::previewOpacity,onValueChangeFinished=vm::endGesture,enabled=!vm.selectedLocked)
            OutlinedButton(onClick={ mesh=true },enabled=!vm.selectedLocked) { Text(vm.tr("메쉬 생성 / 재생성","Build / rebuild mesh")) }
            Text("${p.mesh.vertices.size} "+vm.tr("정점","vertices"),style=MaterialTheme.typography.labelSmall)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(vm.tr("이동","Move"),vm.tr("정점 +","Vertex +"),vm.tr("정점 −","Vertex −")).forEachIndexed { i,label ->
                    TextButton(onClick={ vm.meshMode=true; vm.meshTool=i },enabled=!vm.selectedLocked && (i==0 || !vm.keyEditing),modifier=Modifier.testTag("meshTool-$i")) { Text((if(vm.meshMode && vm.meshTool==i) "✓ " else "")+label) }
                }
            }
            if(vm.meshMode) Text(vm.tr("+는 삼각형 안쪽 탭 · −는 내부 정점 탭 (외곽 보호)","+ tap inside a triangle · − tap an interior vertex (boundary protected)"),style=MaterialTheme.typography.labelSmall)
            if(vm.selectedVertex>=0) Text(vm.tr("선택 정점: ","Selected vertex: ")+vm.selectedVertex,style=MaterialTheme.typography.labelSmall)
            TextButton(onClick=vm::deletePart,enabled=!vm.selectedLocked) { Text(vm.tr("파츠 삭제","Delete part"),color=MaterialTheme.colorScheme.error) }
        } else if(vm.selectedDeformer==null) Text(vm.tr("파츠 또는 디포머를 선택하세요","Select a part or deformer"))
        HorizontalDivider(Modifier.padding(vertical=8.dp))
        Text(vm.tr("파라미터","Parameters"),style=MaterialTheme.typography.titleMedium)
        TextButton(onClick={ parameter=true },enabled=vm.project.parameters.size<32) { Text(vm.tr("+ 파라미터 추가","+ Add parameter")) }
        vm.project.parameters.forEach { param ->
            Column(Modifier.fillMaxWidth().background(if(param.id==vm.parameterId) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).padding(5.dp).clickable { vm.selectParameter(param.id) }) {
                Text(param.name,style=MaterialTheme.typography.labelLarge)
                Text("${format(param.value)}  [${format(param.min)}, ${format(param.max)}]",style=MaterialTheme.typography.labelSmall)
                Slider(value=param.value,onValueChange={ vm.previewParameter(param.id,it) },onValueChangeFinished=vm::endGesture,valueRange=param.min..param.max,enabled=!vm.keyEditing,modifier=Modifier.testTag("parameter-${param.id}"))
                if(param.id==vm.parameterId) {
                    Row {
                        TextButton(onClick={ vm.parameterValue(param.id,param.min) },enabled=!vm.keyEditing) { Text(vm.tr("최소","Min")) }
                        TextButton(onClick={ vm.parameterValue(param.id,param.default) },enabled=!vm.keyEditing) { Text(vm.tr("기본","Default")) }
                        TextButton(onClick={ vm.parameterValue(param.id,param.max) },enabled=!vm.keyEditing) { Text(vm.tr("최대","Max")) }
                    }
                    val values=vm.keyValues(param).sorted()
                    if(values.isNotEmpty()) Text(vm.tr("키폼: ","Keyforms: ")+values.joinToString { format(it) },style=MaterialTheme.typography.labelSmall)
                    values.forEach { value -> TextButton(onClick={ vm.parameterValue(param.id,value) },enabled=!vm.keyEditing) { Text("◆ ${format(value)}") } }

                }
            }
        }
        if(vm.parameter!=null && (p!=null || vm.selectedDeformer!=null)) {
            if(p!=null) Button(onClick=vm::blink,enabled=!vm.selectedLocked && !vm.keyEditing) { Text(vm.tr("눈 개폐 키폼","Eye blink keyforms")) }
            Text(vm.tr("선택 항목에 적용 · 다른 파라미터 변형은 유지","Applies to selection · other parameters stay live"),style=MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick=vm::startKey,enabled=!vm.selectedLocked && !vm.keyEditing,modifier=Modifier.testTag("editKeyform")) { Text(vm.tr("현재 값 키폼 편집","Edit keyform at value")) }
            TextButton(onClick=vm::deleteKey,enabled=!vm.selectedLocked && !vm.keyEditing) { Text(vm.tr("현재 값 키폼 삭제","Delete keyform at value")) }
        }
        if(vm.parameter!=null) TextButton(onClick=vm::deleteParameter,enabled=!vm.keyEditing) { Text(vm.tr("파라미터 삭제","Delete parameter")) }
    }
    if(name) NameDialog(vm,vm.tr("파츠 이름","Part name"),vm.selected?.name.orEmpty(),{ name=false }) { n -> vm.updatePart { it.copy(name=n.take(120)) }; name=false }
    if(parameter) ParameterDialog(vm,{ parameter=false }) { n,min,def,max -> vm.addParameter(n,min,def,max); parameter=false }
    if(mesh) MeshDialog(vm,{ mesh=false }) { cols,rows -> vm.generateMesh(cols,rows); mesh=false }
}
private fun format(v: Float)=String.format(java.util.Locale.ROOT,"%.2f",v)

@Composable internal fun FloatEditor(label: String,value: Float,enabled: Boolean=true,onApply: (Float)->Unit) {
    var text by remember(value) { mutableStateOf(format(value)) }
    Row(verticalAlignment=Alignment.CenterVertically) {
        OutlinedTextField(value=text,onValueChange={ text=it },label={ Text(label) },singleLine=true,enabled=enabled,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii),modifier=Modifier.weight(1f))
        TextButton(onClick={ text.toFloatOrNull()?.takeIf { it.isFinite() && abs(it)<=1_000_000 }?.let(onApply) },enabled=enabled && text.toFloatOrNull()?.isFinite()==true) { Text("✓") }
    }
}
@Composable internal fun NameDialog(vm: EditorViewModel,title: String,initial: String,onDismiss: ()->Unit,onConfirm: (String)->Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest=onDismiss,title={ Text(title) },text={ OutlinedTextField(value=text,onValueChange={ text=it.take(120) },singleLine=true,label={ Text(vm.tr("이름","Name")) }) },confirmButton={ TextButton(onClick={ onConfirm(text.trim()) },enabled=text.isNotBlank()) { Text(vm.tr("확인","OK")) } },dismissButton={ TextButton(onClick=onDismiss) { Text(vm.tr("취소","Cancel")) } })
}
@Composable private fun ParameterDialog(vm: EditorViewModel,onDismiss: ()->Unit,onConfirm: (String,Float,Float,Float)->Unit) {
    var name by remember { mutableStateOf(vm.tr("눈 개폐","Eye open")) }; var min by remember { mutableStateOf("0") }; var def by remember { mutableStateOf("1") }; var max by remember { mutableStateOf("1") }
    val a=min.toFloatOrNull(); val b=def.toFloatOrNull(); val c=max.toFloatOrNull()
    val valid=name.isNotBlank() && a!=null && b!=null && c!=null && a.isFinite() && b.isFinite() && c.isFinite() && a<c && b in a..c && listOf(a,b,c).all { abs(it)<=1_000_000 }
    AlertDialog(onDismissRequest=onDismiss,title={ Text(vm.tr("파라미터 추가","Add parameter")) },text={ Column(Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(value=name,onValueChange={ name=it.take(120) },label={ Text(vm.tr("이름","Name")) })
        OutlinedTextField(value=min,onValueChange={ min=it },label={ Text(vm.tr("최솟값","Minimum")) },keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii))
        OutlinedTextField(value=def,onValueChange={ def=it },label={ Text(vm.tr("기본값","Default")) },keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii))
        OutlinedTextField(value=max,onValueChange={ max=it },label={ Text(vm.tr("최댓값","Maximum")) },keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii))
        if(!valid) Text(vm.tr("최소 < 최대, 기본값은 범위 안에 입력하세요","Min < max; default must be within range"),color=MaterialTheme.colorScheme.error)
    } },confirmButton={ TextButton(onClick={ onConfirm(name,a!!,b!!,c!!) },enabled=valid) { Text(vm.tr("추가","Add")) } },dismissButton={ TextButton(onClick=onDismiss) { Text(vm.tr("취소","Cancel")) } })
}
@Composable private fun MeshDialog(vm: EditorViewModel,onDismiss: ()->Unit,onConfirm: (Int,Int)->Unit) {
    var cols by remember { mutableStateOf("4") }; var rows by remember { mutableStateOf("4") }
    AlertDialog(onDismissRequest=onDismiss,title={ Text(vm.tr("규칙 격자 메쉬","Grid mesh")) },text={ Column {
        Text(vm.tr("기존 메쉬와 이 파츠의 키폼이 초기화됩니다. Undo로 복구할 수 있습니다.","Replaces this part's mesh and keyforms. Undo restores them."))
        OutlinedTextField(value=cols,onValueChange={ cols=it },label={ Text(vm.tr("열 (1~24)","Columns (1–24)")) },keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        OutlinedTextField(value=rows,onValueChange={ rows=it },label={ Text(vm.tr("행 (1~24)","Rows (1–24)")) },keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
    } },confirmButton={ TextButton(onClick={ onConfirm(cols.toInt(),rows.toInt()) },enabled=cols.toIntOrNull() in 1..24 && rows.toIntOrNull() in 1..24) { Text(vm.tr("생성","Build")) } },dismissButton={ TextButton(onClick=onDismiss) { Text(vm.tr("취소","Cancel")) } })
}
