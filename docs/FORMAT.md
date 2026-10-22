# .mylive2d v3

ZIP은 project.json과 assets/<asset-id>.png를 포함한다. JSON `format=MyLive2D`, `version=3`. 정확한 스키마 소스는 core/Model.kt, Rigging.kt, Animation.kt의 @Serializable 타입이다. ZIP 경로/용량/PNG 참조 검증은 ProjectArchive에서 수행한다.

- Project: id, name, assets, parts, parameters, **deformers**
- Part: id, name, assetId, mesh, transform, visible, locked, opacity, **parentId**
- Mesh: vertices, uv, triangles (UV와 모든 키폼은 같은 정점 수)
- Parameter: id, name, min/default/max/value, bindings, **deformerBindings**
- Binding: partId, keys[{value,vertices}]
- Deformer: id, name, kind(GROUP/ROTATION/WARP), parentId, transform, pivot, origin, width/height, columns/rows, controls, visible/locked
- DeformerBinding: deformerId, keys[{value,pose}]
- DeformerPose: transform(position, rotation, scale), controls
- AnimationClip: id, name, durationFrames, fps, loop, tracks
- ParameterTrack: id, parameterId, keyframes[{id,frame,value,interpolation}]
- Interpolation: LINEAR 또는 HOLD

parentId는 디포머 ID만 허용한다. parts 배열의 전역 뒤→앞 순서를 계층과 독립적으로 유지한다. 카메라/선택/미기록 초안/Undo는 파일에 넣지 않는다. 키폼 정점·제어점은 각 대상의 로컬 좌표이다. 피벗과 계층은 애니메이션되지 않는 공통 구조다.

v1은 v2 리깅 필드가 비어 있는지 확인하고 v2를 거친 뒤 v3으로 변환한다. v2는 애니메이션 필드가 비어 있는지 확인하고 v3으로 변환한다. 내부 저장소에서 처음 연 v1/v2 원본 바이트는 각각 `.v1copy`/`.v2copy`로 보존한다. 잘못된 이전 버전 필드나 미래 버전은 추측하지 않고 거부한다. 새 파일은 항상 v3으로 쓴다. Phase 1/2 앱은 v3을 지원하지 않는다.

최대 33 entries, PNG16 MiB/개, JSON8 MiB, 해제 총64 MiB. 파츠64 / 디포머64 / 부모최대12 / 이미지32 / 메쉬1024정점·6144인덱스 / 파라미터32 / 바인딩별키폼32 상한과 전체 리깅 키폼8192 / 키폼 포함 정점·제어점100000 상한을 검사한다. 애니메이션은 클립16 / 클립당 트랙32 / 트랙별 키1024 / 전체 키8192 / 클립 길이36,000프레임 / 1~120 FPS로 제한한다. 프레임은 클립 길이 안에 있어야 하며 트랙은 존재하는 파라미터를 참조하고 키 값은 그 파라미터 범위 안에 있어야 한다. 가져오기는 새 프로젝트/asset ID로 복사하고 리깅 노드 ID 참조는 그 프로젝트 안에서 보존한다.
