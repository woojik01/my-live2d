package dev.mylive2d.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.mylive2d.core.*

@Composable internal fun HierarchyPanel(vm: EditorViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
        Text(vm.tr("파츠 · 디포머 계층","Parts · deformer hierarchy"),style=MaterialTheme.typography.titleSmall)
        Text(vm.tr("선택 항목을 감싸서 생성","Create around the selection"),style=MaterialTheme.typography.labelSmall)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            DeformerKind.entries.forEach { kind ->
                TextButton(onClick={ vm.addDeformer(kind) },enabled=!vm.busy && !vm.keyEditing && vm.project.deformers.size<64,modifier=Modifier.testTag("create-${kind.name}")) {
                    Text(when(kind) { DeformerKind.GROUP -> vm.tr("+ 그룹","+ Group"); DeformerKind.ROTATION -> vm.tr("+ 회전","+ Rotate"); DeformerKind.WARP -> vm.tr("+ 워프","+ Warp") })
                }
            }
        }
        HierarchyChildren(vm,null,0)
        TextButton(onClick={ vm.reorder(1) },enabled=vm.selected!=null && !vm.busy && !vm.keyEditing) { Text(vm.tr("파츠 앞으로","Part forward")) }
        TextButton(onClick={ vm.reorder(-1) },enabled=vm.selected!=null && !vm.busy && !vm.keyEditing) { Text(vm.tr("파츠 뒤로","Part backward")) }
    }
}
@Composable private fun HierarchyChildren(vm: EditorViewModel,parent: String?,depth: Int) {
    if(depth>12) return
    vm.project.deformers.filter { it.parentId==parent }.forEach { node ->
        Surface(color=if(node.id==vm.selectedDeformerId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            modifier=Modifier.fillMaxWidth().padding(start=(depth*6).dp,top=3.dp).clickable(enabled=!vm.busy) { vm.selectDeformer(node.id) }.testTag("node-${node.id}")) {
            Column(Modifier.padding(6.dp)) {
                val kind=when(node.kind) { DeformerKind.GROUP -> vm.tr("그룹","Group"); DeformerKind.ROTATION -> vm.tr("회전","Rotate"); DeformerKind.WARP -> vm.tr("워프","Warp") }
                Text("[$kind] ${node.name}",maxLines=2)
                Row {
                    TextButton(onClick={ vm.selectDeformer(node.id); vm.updateDeformer { it.copy(visible=!it.visible) } },enabled=!vm.busy) { Text(if(node.visible) vm.tr("표시","Shown") else vm.tr("숨김","Hidden")) }
                    TextButton(onClick={ vm.selectDeformer(node.id); vm.updateDeformer { it.copy(locked=!it.locked) } },enabled=!vm.busy) { Text(if(node.locked) vm.tr("잠금","Locked") else vm.tr("편집","Edit")) }
                }
            }
        }
        HierarchyChildren(vm,node.id,depth+1)
    }
    vm.project.parts.asReversed().filter { it.parentId==parent }.forEach { part ->
        Surface(color=if(part.id==vm.selectedId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            modifier=Modifier.fillMaxWidth().padding(start=(depth*6).dp,top=3.dp).clickable(enabled=!vm.busy) { vm.select(part.id) }) {
            Column(Modifier.padding(6.dp)) {
                Text(part.name,maxLines=2)
                Row {
                    TextButton(onClick={ vm.select(part.id); vm.updatePart { it.copy(visible=!it.visible) } },enabled=!vm.busy) { Text(if(part.visible) vm.tr("표시","Shown") else vm.tr("숨김","Hidden")) }
                    TextButton(onClick={ vm.select(part.id); vm.updatePart { it.copy(locked=!it.locked) } },enabled=!vm.busy) { Text(if(part.locked) vm.tr("잠금","Locked") else vm.tr("편집","Edit")) }
                }
            }
        }
    }
}

@Composable internal fun ParentPicker(vm: EditorViewModel) {
    var open by remember { mutableStateOf(false) }
    val parent=vm.selected?.parentId ?: vm.selectedDeformer?.parentId
    val current=parent?.let { id -> vm.project.deformers.firstOrNull { it.id==id }?.name } ?: vm.tr("루트","Root")
    OutlinedButton(onClick={ open=true },enabled=!vm.busy && !vm.keyEditing && !vm.selectedLocked,modifier=Modifier.testTag("parentPicker")) { Text(vm.tr("부모: ","Parent: ")+current) }
    if(open) {
        val id=vm.selectedId ?: vm.selectedDeformerId
        val excluded=vm.selectedDeformerId?.let { Rigging.subtree(vm.project,it) } ?: emptySet()
        AlertDialog(onDismissRequest={ open=false },title={ Text(vm.tr("부모 연결","Set parent")) },text={
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(vm.tr("로컬 좌표를 유지합니다. 변형된 부모로 바꾸면 화면상 위치가 달라질 수 있습니다.","Keeps local coordinates. A transformed parent can change the visible position."))
                TextButton(onClick={ vm.setParent(null); open=false }) { Text(vm.tr("루트 (부모 없음)","Root (no parent)")) }
                vm.project.deformers.filter { it.id!=id && it.id !in excluded }.forEach { node ->
                    TextButton(onClick={ vm.setParent(node.id); open=false }) { Text(node.name) }
                }
            }
        },confirmButton={ TextButton(onClick={ open=false }) { Text(vm.tr("닫기","Close")) } })
    }
}

@Composable internal fun DeformerProperties(vm: EditorViewModel) {
    val node=vm.selectedDeformer ?: return
    var rename by remember(node.id) { mutableStateOf(false) }; var remove by remember(node.id) { mutableStateOf(false) }; var grid by remember(node.id) { mutableStateOf(false) }
    val pose=vm.deformerDraft ?: Rigging.pose(vm.project,node)
    val editable=!vm.selectedLocked && (!vm.hasDeformerKeys || vm.keyEditing)
    Text(node.name,style=MaterialTheme.typography.titleMedium)
    TextButton(onClick={ rename=true },enabled=!vm.selectedLocked && !vm.keyEditing) { Text(vm.tr("이름 변경","Rename")) }
    ParentPicker(vm)
    if(vm.hasDeformerKeys && !vm.keyEditing) Text(vm.tr("변형을 바꾸려면 아래에서 키폼 편집을 켜세요.","Enable keyform editing below to change this rigged pose."),style=MaterialTheme.typography.labelSmall)
    FloatEditor(vm.tr("X 이동","X translation"),pose.transform.position.x,editable) { value -> vm.updateDeformerPose { it.copy(transform=it.transform.copy(position=it.transform.position.copy(x=value))) } }
    FloatEditor(vm.tr("Y 이동","Y translation"),pose.transform.position.y,editable) { value -> vm.updateDeformerPose { it.copy(transform=it.transform.copy(position=it.transform.position.copy(y=value))) } }
    FloatEditor(vm.tr("회전 (도)","Rotation (°)"),pose.transform.rotation,editable) { value -> vm.updateDeformerPose { it.copy(transform=it.transform.copy(rotation=value)) } }
    FloatEditor(vm.tr("배율 0.01~100","Scale 0.01–100"),pose.transform.scale,editable) { value -> if(value in .01f..100f) vm.updateDeformerPose { it.copy(transform=it.transform.copy(scale=value)) } }
    FloatEditor(vm.tr("회전축 X","Pivot X"),node.pivot.x,!vm.selectedLocked && !vm.keyEditing) { value -> vm.updateDeformer { it.copy(pivot=it.pivot.copy(x=value)) } }
    FloatEditor(vm.tr("회전축 Y","Pivot Y"),node.pivot.y,!vm.selectedLocked && !vm.keyEditing) { value -> vm.updateDeformer { it.copy(pivot=it.pivot.copy(y=value)) } }
    Text(vm.tr("주황색 끝점 드래그: 회전 · 중심점: 이동","Drag orange endpoint: rotate · pivot: move"),style=MaterialTheme.typography.labelSmall)
    if(node.kind==DeformerKind.WARP) {
        OutlinedButton(onClick={ grid=true },enabled=!vm.selectedLocked && !vm.keyEditing && !vm.playing,modifier=Modifier.testTag("warpGridSettings")) { Text(vm.tr("격자 분할: ","Grid divisions: ")+"${node.columns} × ${node.rows}") }
        Text(vm.tr("격자 모드에서 제어점을 드래그하세요. 키폼 편집 중에는 선택 파라미터의 변형만 기록합니다.","Drag control points in grid mode. Keyform edits record only the selected parameter's contribution."),style=MaterialTheme.typography.labelSmall)
        if(grid) WarpGridDialog(vm,node) { grid=false }
    }
    TextButton(onClick={ remove=true },enabled=!vm.selectedLocked && !vm.keyEditing) { Text(vm.tr("선택 계층 삭제","Delete subtree"),color=MaterialTheme.colorScheme.error) }
    if(rename) NameDialog(vm,vm.tr("디포머 이름","Deformer name"),node.name,{ rename=false }) { name -> vm.updateDeformer { it.copy(name=name) }; rename=false }
    if(remove) {
        val nodes=Rigging.subtree(vm.project,node.id)
        val parts=vm.project.parts.count { it.parentId in nodes }
        AlertDialog(onDismissRequest={ remove=false },title={ Text(vm.tr("하위 요소도 삭제할까요?","Delete this subtree?")) },
            text={ Text(vm.tr("디포머/그룹 ${nodes.size}개와 파츠 ${parts}개를 삭제합니다. 실행 취소로 복구할 수 있습니다.","Deletes ${nodes.size} deformers/groups and $parts parts. Undo restores them.")) },
            confirmButton={ TextButton(onClick={ vm.removeDeformer(); remove=false }) { Text(vm.tr("삭제","Delete")) } },dismissButton={ TextButton(onClick={ remove=false }) { Text(vm.tr("취소","Cancel")) } })
    }
}

/** The 2D keys are corrections to the existing independent parameter shapes. */
@Composable internal fun CombinationPanel(vm: EditorViewModel) {
    var add by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<String?>(null) }
    val first=vm.parameter
    val hasTarget=vm.selected!=null || vm.selectedDeformer!=null
    HorizontalDivider(Modifier.padding(vertical=8.dp))
    Text(vm.tr("2차원 조합 보정","2D combination corrections"),style=MaterialTheme.typography.titleMedium)
    Text(vm.tr("두 파라미터가 함께 바뀔 때 생기는 모양을 추가로 보정합니다. 먼저 파라미터를 선택하세요.",
        "Correct the pose when two parameters change together. Select the first parameter above."),style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick={ add=true },enabled=hasTarget && first!=null && vm.project.parameters.size>=2 && !vm.selectedLocked && !vm.keyEditing && !vm.playing,
        modifier=Modifier.testTag("addCombination")) { Text(vm.tr("+ 선택 파라미터와 조합","+ Combine selected parameter")) }
    vm.selectedCombinations.forEach { combo ->
        val x=vm.project.parameters.first { it.id==combo.xParameterId }
        val y=vm.project.parameters.first { it.id==combo.yParameterId }
        val active=CombinationEngine.isKey(vm.project,combo)
        Surface(tonalElevation=2.dp,modifier=Modifier.fillMaxWidth().padding(vertical=4.dp)) {
            Column(Modifier.padding(8.dp)) {
                Text("${x.name} × ${y.name}",style=MaterialTheme.typography.titleSmall)
                Text(vm.tr("가로: ","Horizontal: ")+x.name+vm.tr(" · 세로: "," · Vertical: ")+y.name,style=MaterialTheme.typography.labelSmall)
                combo.yValues.asReversed().forEach { yValue ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                        combo.xValues.forEach { xValue ->
                            val chosen=kotlin.math.abs(x.value-xValue)<.00001f && kotlin.math.abs(y.value-yValue)<.00001f
                            OutlinedButton(onClick={ vm.selectCombinationKey(combo.id,xValue,yValue) },
                                enabled=!vm.keyEditing && !vm.playing,contentPadding=PaddingValues(2.dp),
                                modifier=Modifier.weight(1f).testTag("combination-${combo.id}-${combo.xValues.indexOf(xValue)}-${combo.yValues.indexOf(yValue)}")) {
                                Text((if(chosen) "● " else "○ ")+String.format(java.util.Locale.ROOT,"%.1f/%.1f",xValue,yValue),style=MaterialTheme.typography.labelSmall,maxLines=1)
                            }
                        }
                    }
                }
                if(!active) Text(vm.tr("키를 편집하려면 교차점을 선택하세요.","Select an intersection to edit a key."),style=MaterialTheme.typography.labelSmall)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick={ vm.startCombinationKey(combo.id) },enabled=active && !vm.keyEditing && !vm.playing && !vm.selectedLocked,modifier=Modifier.testTag("editCombination-${combo.id}")) { Text(vm.tr("선택한 교차점 편집","Edit selected key")) }
                    TextButton(onClick={ remove=combo.id },enabled=!vm.keyEditing && !vm.playing && !vm.selectedLocked) { Text(vm.tr("조합 삭제","Delete combination")) }
                }
            }
        }
    }
    if(add && first!=null) AlertDialog(onDismissRequest={ add=false },title={ Text(vm.tr("두 번째 파라미터 선택","Choose the second parameter")) },text={
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(vm.tr("첫 번째: ","First: ")+first.name)
            vm.project.parameters.filter { it.id!=first.id }.forEach { other ->
                TextButton(onClick={ vm.createCombination(other.id); add=false },modifier=Modifier.fillMaxWidth().testTag("combineWith-${other.id}")) { Text(other.name) }
            }
        }
    },confirmButton={ TextButton(onClick={ add=false }) { Text(vm.tr("닫기","Close")) } })
    remove?.let { id -> AlertDialog(onDismissRequest={ remove=null },title={ Text(vm.tr("조합 보정을 삭제할까요?","Delete this correction?")) },
        text={ Text(vm.tr("이 조합의 모든 보정 키가 삭제됩니다. 실행 취소로 복원할 수 있습니다.","All correction keys in this combination will be removed. Undo can restore them.")) },
        confirmButton={ TextButton(onClick={ vm.removeCombination(id); remove=null }) { Text(vm.tr("삭제","Delete")) } },
        dismissButton={ TextButton(onClick={ remove=null }) { Text(vm.tr("취소","Cancel")) } }) }
}

@Composable internal fun WarpGridDialog(vm: EditorViewModel, node: Deformer, onDismiss: ()->Unit) {
    var cols by remember(node.id) { mutableStateOf(node.columns.toString()) }
    var rows by remember(node.id) { mutableStateOf(node.rows.toString()) }
    val c=cols.toIntOrNull(); val r=rows.toIntOrNull()
    AlertDialog(onDismissRequest=onDismiss,title={ Text(vm.tr("워프 격자 분할","Warp grid divisions")) },text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(vm.tr("기존 모양과 모든 키폼을 새 격자에 다시 샘플링합니다. 분할을 줄이거나 배수가 아닌 값으로 바꾸면 모양이 달라질 수 있습니다. 실행 취소로 복구할 수 있습니다.",
                "Resamples the current shape and every key. Coarser or non-multiple divisions may change the shape. Undo restores it."))
            OutlinedTextField(value=cols,onValueChange={ cols=it },singleLine=true,label={ Text(vm.tr("가로 (1~8)","Columns (1–8)")) })
            OutlinedTextField(value=rows,onValueChange={ rows=it },singleLine=true,label={ Text(vm.tr("세로 (1~8)","Rows (1–8)")) })
        }
    },confirmButton={ TextButton(onClick={ vm.resizeWarp(c!!,r!!); onDismiss() },enabled=c in 1..8 && r in 1..8) { Text(vm.tr("적용","Apply")) } },
        dismissButton={ TextButton(onClick=onDismiss) { Text(vm.tr("취소","Cancel")) } })
}
