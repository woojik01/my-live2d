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
    var rename by remember(node.id) { mutableStateOf(false) }; var remove by remember(node.id) { mutableStateOf(false) }
    val pose=vm.deformerDraft ?: node.basePose()
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
    if(node.kind==DeformerKind.WARP) Text(vm.tr("격자 모드에서 제어점을 드래그하세요. 키폼 편집 중에는 선택 파라미터의 변형만 기록합니다.","Drag control points in grid mode. Keyform edits record only the selected parameter's contribution."),style=MaterialTheme.typography.labelSmall)
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
