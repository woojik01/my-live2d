# Phase 1 검증 기록

검증일: 2026-10-22. 기능 소스 커밋: `e73bf40b6f04b684a5df3d70da644f672bd62677`.

## 빌드 / JVM

[GitHub Actions 실행](https://github.com/woojik01/my-live2d/actions/runs/39743282869)에서 다음 명령이 성공했다.

```
./gradlew --no-daemon :core:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
```

- JVM 테스트 10개, 실패 0, 무시 0 (보고서 기준 0.432초).
- 디버그 APK 9,773,854바이트, 테스트 APK 1,116,325바이트 생성.
- 다운로드한 APK를 Android SDK `apksigner verify --verbose`로 검증: APK v2 서명 유효.
- lint 오류 0, 경고 17. 경고는 최신 target SDK/의존성 버전 권고, 창 너비 API 권고, data extraction rules, Int 상태 전용 타입, XML용 View 생성자, SharedPreferences KTX 권고다. 경고를 숨기는 baseline/전체 suppress는 사용하지 않았다.
- 현재 의존성은 재현성을 위해 고정한 버전이며 최신 버전이라는 뜻은 아니다. 버전 업그레이드와 API 36 이상 검증은 별도 변경에서 수행한다.

[APK + JVM/lint 보고서 아티팩트](https://github.com/woojik01/my-live2d/actions/runs/39743282869/artifacts/12409394281). ZIP 안의 `app/build/outputs/apk/debug/app-debug.apk`가 설치 대상이다. 아티팩트 보관 기한은 2026-11-05다.

## JVM에서 실행해 통과한 항목

| 항목 | 확인 내용 |
|---|---|
| 두 눈 개폐 | 두 눈 바인딩 생성, 닫힘/열림/중간값 선형 보간, 저장 후 동일 결과 |
| 메쉬 | 격자 정점/UV/삼각형 개수·참조, 정점 이동 저장 왕복 |
| 사용자 키폼 | 직접 기록한 정점 보간, 범위 양 끝 고정 |
| Undo / Redo | 되돌리기·다시 실행·새 편집 시 redo 분기 제거 |
| 좌표와 크기 변경 | 카메라/파츠 회전·배율 역변환, 세로/가로 크기, 핀치 초점 보존 |
| 레이어 | 순서·표시·잠금·투명도 저장 왕복 |
| 잘못된 프로젝트 | 미지원 버전·잘못된 인덱스·NaN·잘못된 키폼·이미지 크기 거부 |
| 손상 / 누락 | 손상 JSON/ZIP, 누락 PNG, 경로 탈출, 메타데이터 용량 초과 거부 |

## Android 계측

Android 15 (API 35), x86_64 에뮬레이터의 첫 실행에서 화면 회전/Activity 재생성 테스트와 PNG→편집→저장→OpenGL 개폐 픽셀 비교 테스트가 통과했다. 저장소 테스트 클래스는 한 테스트의 반환형이 Boolean으로 추론되어 JUnit 초기화가 실패했다. 테스트를 명시적 Unit 반환형으로 수정하고 전체 계측 테스트를 재실행한다. 전체 통과로 집계하지 않는다.

소스에 포함된 테스트는 실제 PNG 디코딩/두 파츠 저장/Repository 재생성 후 다시 열기, 손상된 최신 저장본의 백업 복구, 잘못된 PNG 거부, 화면 회전과 Activity 재생성, ViewModel을 통한 새 프로젝트·다중 PNG·메쉬 편집·레이어 순서·Undo/Redo·키폼 생성·저장 및 OpenGL 결과 픽셀 변화를 검증한다. 계측 결과가 나오기 전에는 통과로 집계하지 않는다.

## 수행하지 않은 검증 / 제한

- 실물 스마트폰/태블릿 수동 테스트, 실제 파일 선택기에서의 다중 선택과 저장소 공급자별 내보내기.
- 터치 정밀도와 장시간 편집 체감, 실물 기기별 GPU/메모리/FPS 측정, 시스템 저메모리 프로세스 종료·기기 전원 차단.
- Activity 재생성과 파일 복원은 OS가 앱 프로세스를 강제 종료하는 모든 상황을 재현한 것이 아니다.
- 60FPS를 달성했다고 보고하지 않는다. 미기록 키폼 초안과 Undo 이력은 프로세스 종료 후 복구 대상이 아니다.

로컬 작업 환경에서는 JDK 17과 SDK 35를 준비했지만 Gradle Java가 실행 환경의 프록시에 연결하지 못해 의존성 해석이 실패했다. 빌드·JVM·Android 계측 검증은 위 GitHub Actions 결과를 사용하며 로컬 실행으로 표시하지 않는다.
