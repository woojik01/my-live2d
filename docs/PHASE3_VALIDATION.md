# Phase 3 검증 — v0.3.0

검증일은 2026-10-22입니다. 기능 및 테스트 소스 커밋은 `3dd83a6701e1137194b6b51a5b6318fd8b97d86a`이고, [GitHub Actions 실행 #26](https://github.com/woojik01/my-live2d/actions/runs/39769019566)은 성공으로 끝났습니다. 이 문서와 README는 검증 후 추가한 기록입니다.

## CI 결과

| 검증 | 실제 결과 |
| --- | --- |
| `:core:test` | 33개 통과, 실패 0, 무시 0 |
| `:app:assembleDebug` | 성공, APK 9,904,926바이트 |
| `:app:lintDebug` | 오류 0개, 경고 17개 |
| `:app:assembleDebugAndroidTest` | 성공 |
| `apksigner verify --verbose` | 성공, APK Signature Scheme v2 확인 |
| `:app:connectedDebugAndroidTest` | API 35 / Android 15 / x86_64 / google_apis 에뮬레이터에서 9개 통과, 실패 0, 건너뜀 0 |

- [APK 및 JVM·Lint 보고서 아티팩트](https://github.com/woojik01/my-live2d/actions/runs/39769019566/artifacts/12420489538)
- [Android 계측 보고서 및 GL 화면 캡처 아티팩트](https://github.com/woojik01/my-live2d/actions/runs/39769019566/artifacts/12421518836)
- [Phase 3 PR #3](https://github.com/woojik01/my-live2d/pull/3): Phase 2의 `feature/phase2-rigging` 위에 쌓았습니다. 기본 브랜치에는 병합하지 않았습니다.

APK는 CI 디버그 키로 서명됩니다. 기존 설치본과 서명 키가 다를 수 있으므로 기존 앱을 교체하기 전에 프로젝트를 `.mylive2d`로 내보내 보관하세요. 아티팩트는 14일 보관되며 2026-11-05 만료 예정입니다. GitHub 로그인이 필요할 수 있습니다. 일반 설치 파일은 첫 아티팩트 ZIP 안의 `app/build/outputs/apk/debug/app-debug.apk`입니다.

검증 아티팩트 ZIP의 크기는 10,516,076바이트이고 CRC와 GitHub SHA-256을 확인했습니다. ZIP SHA-256은 `fc02f082168d21bff08e71edfa5a864df4a92b8c09597cc8cd0836d3288f6243`입니다. APK SHA-256은 `5169d464d34866a9581a372dff49e7940ee9aa4c2a499755ab18168b9c0a1d65`입니다. Android 계측 보고서 ZIP은 67,825바이트이고 SHA-256 `984a7a5c3c111f1c03c56f0eccfa33e670e1e93d60c5353c8745383851e7d258`로 GitHub 아티팩트 digest와 일치했습니다.

## 자동화 범위

JVM 테스트는 애니메이션 프레임 선형/유지 보간, 키 추가·이동·삭제, 서로 다른 클립의 독립 샘플, 미리보기와 저장된 파라미터의 분리, 포맷 v2→v3 마이그레이션 및 아카이브 왕복, 잘못된 트랙·키 검증을 포함합니다.

Android 에뮬레이터에서는 타임라인 열기, 클립·트랙 만들기, 키 기록, 보간과 재생 값, 파라미터의 원본값 보존, 저장 후 프로젝트 재열기를 검사했습니다. 기존 PNG 가져오기/레이어/Undo, 리깅, OpenGL 렌더링 계측 테스트도 함께 통과했습니다. 리깅 검증의 `rig-before.png`와 `rig-after.png` 화면은 계측 보고서 아티팩트에 포함됩니다.

## 남은 제한과 미검증

- 실제 스마트폰·태블릿, Android 최소 API 26, 기기별 GPU, 멀티윈도우와 60FPS 성능은 검증하지 않았습니다. CI의 API 35 에뮬레이터 결과를 실기기 성능으로 일반화하지 않습니다.
- 애니메이션 재생 상태와 저장 복원은 자동화에서 ViewModel 경로로 확인했습니다. 화면의 재생 버튼을 실제 손가락으로 조작하는 수동 사용성 검증은 별도입니다.
- 로컬 빌드는 시도했으나 Gradle 8.11.1 배포본 다운로드가 `Network is unreachable`로 차단되어 완료하지 못했습니다. 빌드·테스트 결과는 위 GitHub Actions 실행에서 확인했습니다.
- Lint는 통과했지만 경고 17개가 남았습니다. 애니메이션 출력 파일, Cubism/PSD 호환, 오디오 동기화, cubic easing, 실제 기기 성능 개선은 후속 작업입니다.
