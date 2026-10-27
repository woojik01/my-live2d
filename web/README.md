# My Live2D Web

브라우저의 Canvas 2D로 작동하는 오프라인 리깅 편집기입니다. 정적 파일을 GitHub Pages 프로젝트 경로에 배포합니다. 브라우저에서 최초 접속한 후 정적 파일이 서비스 워커 캐시에 준비되면 오프라인으로 실행할 수 있습니다. 프로젝트는 브라우저의 IndexedDB에 자동 저장되며 JSON으로 내보내 백업할 수 있습니다. 캐시나 브라우저 데이터를 삭제하면 저장된 작업이 사라질 수 있습니다.

## 사용 방법

1. PNG 파츠를 하나 이상 불러오거나 예제를 엽니다. 최대 이미지 32개, 이미지당 최대 변 2048px 및 총 16MP입니다.
2. 파츠를 선택하고 위치를 조정합니다. `+ 회전` 또는 `+ 워프`는 선택한 항목을 감싸는 디포머를 만듭니다.
3. 기본 파라미터를 추가하거나 새 파라미터를 만듭니다. 값을 고르고 `현재 값 키폼 편집`을 누른 뒤 정점, 격자 또는 디포머 속성을 조절해 기록합니다. 다른 파라미터의 현재 변형은 중복 저장하지 않습니다.
4. 두 파라미터의 결합 상태를 다르게 만들려면 `2차원 조합 보정`에서 두 번째 파라미터를 선택하고 격자 교차점을 편집합니다. 초기 보정은 0이며 교차점 사이 값은 쌍선형 보간합니다.
5. 데스크톱은 휠로 확대, 드래그로 조작합니다. 모바일은 파츠·캔버스·리깅 탭을 전환하고 두 손가락으로 확대 및 이동합니다.
6. `내보내기`는 이미지와 리깅이 포함된 `.mylive2d-web.json`을 내려받습니다. 이전 Android `.mylive2d` ZIP 및 Cubism `.cmo3`/`.moc3`은 호환되지 않습니다.

## GitHub Pages

워크플로 `.github/workflows/pages.yml`은 `main` 푸시 시 웹 테스트와 TWA 빌드 후 `web/`을 Pages로 배포합니다. 저장소 Settings → Pages → Build and deployment → Source에서 **GitHub Actions**가 설정되어 있어야 합니다. 기본 예상 경로는 `https://woojik01.github.io/my-live2d/`이며 실제 배포 전에는 동작을 보장하지 않습니다. HTML의 상대 경로와 서비스 워커의 범위는 이 프로젝트 하위 경로를 지원합니다.

## TWA

`twa` 모듈은 Android Browser Helper를 사용해 위 Pages 주소를 실행합니다. `./gradlew :twa:assembleDebug`로 시험용 APK를 빌드할 수 있으며 Actions의 `my-live2d-twa-debug`에서도 받을 수 있습니다. 이 APK는 기존 Android 네이티브 앱과 별개의 패키지 `dev.mylive2d.web`입니다.

주소창 없는 TWA에는 앱과 웹사이트의 양방향 Digital Asset Links 검증이 필요합니다. `twa/src/main/res/values/strings.xml`에는 웹 출처가 설정되어 있습니다. 실제 서명 인증서의 SHA-256을 `twa/assetlinks.template.json`에 넣어 **`https://woojik01.github.io/.well-known/assetlinks.json`**에서 HTTP 200과 JSON 타입으로 제공해야 합니다. 프로젝트 Pages의 `/my-live2d/.well-known/`만으로는 검증되지 않습니다. 보통 소유한 `woojik01.github.io` 사용자 사이트 저장소의 `.well-known/assetlinks.json`과 `.nojekyll`을 사용합니다. 해당 루트 저장소 또는 도메인을 만들 권한이 없으면 설정을 완료할 수 없습니다. 검증 전에는 브라우저 상단 주소창이 표시될 수 있습니다.

릴리스 APK는 직접 소유하고 안전하게 보관한 서명 키로 빌드하십시오. 테스트 APK의 SHA-256은 Android SDK의 `apksigner verify --print-certs <APK>`로 확인할 수 있습니다. Actions의 디버그 키는 실행마다 달라질 수 있으므로 지속적인 배포용으로 사용하지 마세요. 앱 서명 키나 비밀번호를 저장소에 올리지 마세요. Google Play App Signing 사용 시에는 실제 배포 APK를 서명하는 인증서의 지문을 사용해야 합니다.

공식 안내: [Android TWA](https://developer.android.com/develop/ui/views/layout/webapps/trusted-web-activities), [Digital Asset Links 설정](https://developer.android.com/training/app-links/configure-assetlinks), [TWA 통합](https://developer.chrome.com/docs/android/trusted-web-activity/integration-guide).

## 현재 한계

PNG만 가져올 수 있습니다. PSD, 임의 메쉬 토폴로지 편집, 애니메이션 타임라인, 마스크, 물리, 얼굴 추적, 외부 앱용 런타임, Cubism 공식 형식은 아직 구현되지 않았습니다. 워프 분할은 2×2이며 부모 변경 UI는 추가 전입니다. 레이어 이미지는 투명한 부분도 선택 영역에 포함될 수 있습니다. 터치 기기와 실제 TWA의 성능 및 파일 선택 동작은 별도 검증이 필요합니다.
