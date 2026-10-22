# 전체 구조 — Phase 3

core는 Android와 독립적인 Kotlin/JVM 모듈이다. Model의 불변 문서, ParameterEngine의 메쉬 보간, Rigging/RigEvaluator의 계층·디포머 평가, AnimationEngine의 시간 샘플링/클립 검증, MeshTopology의 토폴로지 갱신, History의 스냅샷 Undo, Validation/RigValidation의 참조/범위/계층 검사, ProjectArchive/ProjectMigration의 자체 포맷을 포함한다. [리깅 상세](RIGGING.md)를 참조한다.

app의 EditorViewModel이 프로젝트·선택·제스처·키폼 초안·히스토리·애니메이션 재생 시계를 관리한다. MainActivity/RigPanels/AnimationTimeline은 Compose 화면이다. ModelSurface는 터치를 월드 좌표로 전달하고 같은 RigEvaluator 결과로 GL ES 2.0 삼각형·격자·손잡이를 그린다. GL 스레드에는 불변 Scene을 넘긴다. renderProject는 초안 또는 선택 클립의 프레임을 임시 평가해 실제 편집 파라미터를 바꾸지 않고 그린다.

텍스처는 asset ID별 GPU 캐시, 비트맵은 업로드 직후 해제한다. premultiplied alpha에 ONE/ONE_MINUS_SRC_ALPHA를 쓰고 context 재생성 시 셰이더/텍스처를 재생성한다. 사용하지 않는 GPU 텍스처를 지운다. RENDERMODE_WHEN_DIRTY로 상태 변경 시 렌더를 요청하며 재생 프레임 상태도 UI를 통해 갱신한다. 현재 CPU 평가/버퍼 생성은 파츠·정점 수에 비례하며 실제 기기 FPS 보장은 없고 후속 계측 대상이다.

ProjectRepository는 Android SAF와 내부 파일을 연결한다. 외부 PNG는 내부로 복사한다. 저장은 IO 코루틴과 Mutex, 임시 ZIP→fsync→백업→atomic move(미지원 시 replace) 순서다. 손상된 최신 파일은 정상 백업으로 복구하고 복구된 주 파일을 수리한다. v1은 최초 열기 때 별도 원본을 보존한다. 외부 저장소 공급자까지 원자적 교체를 보장하지 않는다.

편집은 불변 문서 스냅샷 최대40개를 공유하고 이미지 바이트는 이력에 복사하지 않는다. 파츠/정점/디포머 드래그와 파라미터/투명도 슬라이더는 제스처 단위로 기록한다. 토폴로지 변경은 UV/모든 키폼/인덱스를 하나의 문서 변경으로 반영한다. 초안은 기록 전 Undo/저장에 넣지 않는다. 카메라와 선택은 모델 데이터 밖이다.

PNG 디코딩 크기·실제 MIME, 포맷/버전·ID·UV·인덱스·키폼·부모·순환·개수·크기·해제 용량을 검증한다. ZIP entry를 임의 경로로 추출하지 않는다. 원본이 아닌 새 asset ID로 가져와 기존 파일 덮어쓰기를 막는다. 앱은 INTERNET 권한을 선언하지 않는다.
