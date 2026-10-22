# Phase 2 검증 — v0.2.0

2026-10-22 GitHub Actions에서 실행했다. 검증한 기능 소스 커밋은 `3286a4dc15bce42013d8d7c3e926ba48ca3ebe88`이다. 이후 결과 기록 커밋은 README와 이 문서만 변경하며 `[skip ci]`를 사용한다. 로컬 환경에서 Android SDK/에뮬레이터를 실행한 결과가 아니다.

## 결과와 배포 파일

| 검증 | 실제 결과 |
| --- | --- |
| `:core:test` | 25개 성공, 실패 0, 무시 0 |
| `:app:assembleDebug` | 성공, APK 9,855,774바이트 |
| `:app:assembleDebugAndroidTest` | 성공 |
| `:app:lintDebug` | 오류 0개, 경고 17개 |
| `apksigner verify --verbose` | 성공, APK Signature Scheme v2 확인 |
| `:app:connectedDebugAndroidTest` | API 35 / Android 15 / x86_64 / google_apis 에뮬레이터에서 7개 성공, 실패 0, 건너뜀 0 |
| 결과물 재다운로드 | 두 ZIP의 CRC와 Actions SHA-256 일치 확인 |

- [CI 실행 및 로그](https://github.com/woojik01/my-live2d/actions/runs/39759563949)
- [APK·JVM·Lint 보고서 ZIP](https://github.com/woojik01/my-live2d/actions/runs/39759563949/artifacts/12417895971)
- [Android 테스트 보고서·OpenGL 비교 화면 ZIP](https://github.com/woojik01/my-live2d/actions/runs/39759563949/artifacts/12417901593)
- [Phase 2 PR #2](https://github.com/woojik01/my-live2d/pull/2): Phase 1의 `feature/phase1-editor`를 대상으로 쌓았다. 기본 브랜치 병합은 하지 않았다.

아티팩트는 14일 보관이며 2026-11-05 만료 예정이다. GitHub 로그인이 필요할 수 있다. 일반 설치 파일은 ZIP의 `app/build/outputs/apk/debug/app-debug.apk`이다. `app-debug-androidTest.apk`는 계측 테스트용이다. CI 실행별 디버그 서명이 달라 기존 앱과 바로 덮어쓰기 되지 않을 수 있으므로 삭제 전에 프로젝트를 내보낸다.

SHA-256:

```text
app-debug.apk
afde8aa70c63d9b5bf07520e6e59aef38fba932aeff0289304ffd3fd9c575fd7
APK·JVM·Lint ZIP
7eb74aaf2e513ab0ba0b816ad2c2f82e56459e235517d4ac2b6d7a691b0d510d
Android 보고서 ZIP
62b0bb22a6f013b8db6669ba0612c722761ce43f67111ee70735ae8dac26f8d1
```

## 자동화 범위

JVM의 기존 `EditorTest` 10개와 신규 `RiggingTest` 15개가 통과했다. 회전축/부모 적용 순서, bilinear 워프와 격자 밖 변위, 회전·워프의 역좌표, 독립 복수 파라미터 합성, 리깅 ZIP 왕복, 순환/누락/충돌 ID 거부, 상속 잠금/숨김, 계층 삭제와 Undo, 정점 삽입 시 UV·모든 키폼 동기화, 내부 정점 삭제 재삼각분할, 특이 워프 거부, v1 ZIP 변환, 잘못된 격자·키폼과 전체 포인트 예산을 검사한다.

Android는 기존 5개와 신규 `RigDeviceTest` 2개가 통과했다.

- 기존 회귀: PNG 두 눈과 눈 개폐 보간, 레이어 순서/Undo/Redo, 파일 왕복 및 잘못된 프로젝트 처리, Activity 재생성과 가로 전환 뒤 캔버스/저장 버튼 표시, OpenGL 텍스처·알파 픽셀 검사.
- 새 리깅 경로: PNG 가져오기 → 그룹/회전/워프 계층 → 회전 파라미터 35도+10도 합성 → 워프 키폼 → 눈 키폼 → 정점 삽입/내부 삭제/Undo/Redo → 저장 → 저장소 재열기 → Activity와 ViewModel을 새로 만들어 모델·최종 정점 좌표 일치 확인.
- 실제 `ModelSurface.dispatchTouchEvent`에 MotionEvent를 보내 그룹 중심 이동, 워프 제어점 이동, 변형 계층 아래 메쉬 정점 이동의 역좌표를 검사한다. 중심을 드래그했을 때 잘못 회전하는 손잡이 선택 회귀도 검사한다.
- 화면을 캡처해 청록 PNG의 중심이 그룹 이동에 따라 화면 X축으로 20픽셀(허용 오차 3픽셀) 이동하는지 확인한다. `rig-before.png`/`rig-after.png`를 보고서에 보존했고 육안으로도 확인했다. 테스트용 단색 PNG이며 사용자 캐릭터의 외형 품질 검증은 아니다.
- 기존 로컬 v1을 열 때 원본 바이트가 `.v1copy`에 보존되고, 다음 저장 파일이 v2인지 확인한다.

첫 CI의 새 픽셀 이동 검사는 작은 에뮬레이터의 뷰포트 밖으로 모델이 잘려 실패했다. 이후 노출된 캔버스에 맞춰 카메라와 예상 화면 이동량을 보정했고 위 커밋에서 전체 7개가 통과했다. 앱의 전체 보기에서도 패널이 가리는 영역을 반영했다.

## 남은 경고와 미검증

Lint HTML에 남은 경고는 고정된 target SDK/의존성, `Configuration.screenWidthDp`, 데이터 추출 규칙, Compose 상태 오토박싱, XML용 View 생성자, KTX 권고 관련이다. Lint를 통과했다는 말은 경고가 없다는 의미가 아니다. 최신 도구로 전환하거나 경고를 숨기는 변경은 이번 리깅 작업에 포함하지 않았다.

자동화의 프로젝트/속성 조작 일부는 ViewModel을 직접 호출한다. 시스템 파일 선택기, 모든 다이얼로그/오류 메시지, 모든 언어·테마 조합을 사람이 끝까지 조작한 검증은 수행하지 않았다. 새 Activity 실행은 검사했지만 OS 강제 종료·전원 차단 복구를 재현하지 않았다.

실물 스마트폰/태블릿, API 26 최소 버전, OEM GPU, 전체 화면 크기·멀티윈도우, 60FPS·대형 리깅·메모리 부족·장시간 사용은 미검증이다. 에뮬레이터의 소프트웨어 GPU 통과 결과를 실제 기기 성능으로 일반화하지 않는다. 접힌 워프, 극단적인 합성 배율에서의 편집감도도 실사용 검토가 필요하다.

워프 UI의 2×2 셀 고정, 외곽 정점 삭제 보호, 다차원 조합 보정 키폼 미지원, 애니메이션/외부 결과물 내보내기 미구현은 [README](../README.md)에 명시했다. 새 외부 라이브러리는 추가하지 않았다.
