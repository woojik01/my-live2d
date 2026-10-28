# My Live2D Web

브라우저의 Canvas 2D로 작동하는 오프라인 리깅 편집기입니다. 정적 파일을 GitHub Pages 프로젝트 경로에 배포합니다. 브라우저에서 최초 접속한 후 정적 파일이 서비스 워커 캐시에 준비되면 오프라인으로 실행할 수 있습니다. 프로젝트는 브라우저의 IndexedDB에 자동 저장되며 JSON으로 내보내 백업할 수 있습니다. 캐시나 브라우저 데이터를 삭제하면 저장된 작업이 사라질 수 있습니다. 새 Pages 배포는 서비스 워커 빌드 ID로 감지되며, 앱은 실행·복귀·온라인 전환 시와 열린 상태의 5분 간격으로 업데이트를 확인합니다. 편집 중인 키폼/드래그를 마치고 현재 프로젝트를 저장한 뒤 자동으로 새 버전을 엽니다.

## 사용 방법

1. PNG 파츠 또는 PSD의 이미지 레이어를 불러오거나 예제를 엽니다. 레이어별 가져오기는 8비트 RGB, 문서와 레이어 최대 2048×2048, 이미지 최대 32개 및 전체 16MP 제한이 있습니다. 클리핑, 특수 혼합 모드와 그룹 불투명도는 지원하지 않습니다. 레이어 마스크는 투명도에 적용됩니다. 가져온 레이어는 현재 프로젝트에 파츠로 추가됩니다. 최대 이미지 32개, 이미지당 최대 변 2048px 및 총 16MP입니다.
2. 파츠를 선택하고 위치를 조정합니다. `+ 회전` 또는 `+ 워프`는 선택한 항목을 감싸는 디포머를 만듭니다.
3. 기본 파라미터를 추가하거나 새 파라미터를 만듭니다. 값을 고르고 `현재 값 키폼 편집`을 누른 뒤 정점, 격자 또는 디포머 속성을 조절해 기록합니다. 다른 파라미터의 현재 변형은 중복 저장하지 않습니다.
4. 두 파라미터의 결합 상태를 다르게 만들려면 `2차원 조합 보정`에서 두 번째 파라미터를 선택하고 격자 교차점을 편집합니다. 초기 보정은 0이며 교차점 사이 값은 쌍선형 보간합니다.
5. 데스크톱은 휠로 확대, 드래그로 조작합니다. 모바일은 파츠·캔버스·리깅 탭을 전환하고 두 손가락으로 확대 및 이동합니다.
6. `내보내기`는 이미지와 리깅이 포함된 `.mylive2d-web.json`을 내려받습니다. 이전 Android `.mylive2d` ZIP 및 Cubism `.cmo3`/`.moc3`은 호환되지 않습니다.

## 편집 도구

- 캔버스의 + / − 버튼과 전체 보기로 화면을 조절합니다. PC에서는 V(선택), M(메쉬), F(전체 보기), + / −(확대·축소)를 사용할 수 있습니다.
- 파츠를 선택해 속성에서 **복제**하면 이미지, 메쉬, 키폼과 조합 보정이 복사됩니다. 복제본은 약간 옆에 배치됩니다.
- **파라미터 모두 초기화**는 값을 기본값으로 되돌립니다. 선택한 파라미터를 삭제하면 연결된 키폼과 조합 보정도 삭제되며 실행 취소로 복구할 수 있습니다.
- Ctrl+Z / Ctrl+Shift+Z 또는 Ctrl+Y로 실행 취소와 다시 실행을 할 수 있습니다.

## GitHub Pages

워크플로 `.github/workflows/pages.yml`은 `main` 푸시 시 웹 테스트와 TWA 빌드 후 `web/`을 Pages로 배포합니다. 저장소 Settings → Pages → Build and deployment → Source에서 **GitHub Actions**가 설정되어 있어야 합니다. 기본 예상 경로는 `https://woojik01.github.io/my-live2d/`이며 실제 배포 전에는 동작을 보장하지 않습니다. HTML의 상대 경로와 서비스 워커의 범위는 이 프로젝트 하위 경로를 지원합니다.

## TWA

`twa` 모듈은 Android Browser Helper를 사용해 위 Pages 주소를 실행합니다. `./gradlew :twa:assembleDebug`로 시험용 APK를 빌드할 수 있으며 Actions의 `my-live2d-twa-debug`에서도 받을 수 있습니다. 이 APK는 기존 Android 네이티브 앱과 별개의 패키지 `dev.mylive2d.web`입니다.

주소창 없는 TWA에는 앱과 웹사이트의 양방향 Digital Asset Links 검증이 필요합니다. `twa/src/main/res/values/strings.xml`에는 웹 출처가 설정되어 있습니다. 실제 서명 인증서의 SHA-256을 `twa/assetlinks.template.json`에 넣어 **`https://woojik01.github.io/.well-known/assetlinks.json`**에서 HTTP 200과 JSON 타입으로 제공해야 합니다. 프로젝트 Pages의 `/my-live2d/.well-known/`만으로는 검증되지 않습니다. 보통 소유한 `woojik01.github.io` 사용자 사이트 저장소의 `.well-known/assetlinks.json`과 `.nojekyll`을 사용합니다. 해당 루트 저장소 또는 도메인을 만들 권한이 없으면 설정을 완료할 수 없습니다. 검증 전에는 브라우저 상단 주소창이 표시될 수 있습니다.

릴리스 APK는 직접 소유하고 안전하게 보관한 서명 키로 빌드하십시오. 테스트 APK의 SHA-256은 Android SDK의 `apksigner verify --print-certs <APK>`로 확인할 수 있습니다. Actions의 디버그 키는 실행마다 달라질 수 있으므로 지속적인 배포용으로 사용하지 마세요. 앱 서명 키나 비밀번호를 저장소에 올리지 마세요. Google Play App Signing 사용 시에는 실제 배포 APK를 서명하는 인증서의 지문을 사용해야 합니다.

공식 안내: [Android TWA](https://developer.android.com/develop/ui/views/layout/webapps/trusted-web-activities), [Digital Asset Links 설정](https://developer.android.com/training/app-links/configure-assetlinks), [TWA 통합](https://developer.chrome.com/docs/android/trusted-web-activity/integration-guide).

## PSD 합성 이미지 가져오기

레이어별 가져오기를 지원하지 않는 파일은 저장된 합성 이미지를 한 개의 파츠로 여는 선택지가 표시됩니다. PSD/PSB의 RGB 또는 회색조 8·16·32비트 합성 이미지를 읽으며, 문서가 2048픽셀보다 크면 이미지 비율을 유지해 축소합니다. 원본 레이어와 텍스트, 효과는 별도로 편집할 수 없습니다. 압축 방식과 기기 메모리에 따른 제한이 있으며 파일은 최대 256MB, 합성 이미지는 최대 16메가픽셀입니다. 저장된 합성 이미지가 없거나 최신이 아니면 실패하거나 현재 레이어 상태와 다르게 보일 수 있습니다.

## 현재 한계

PSD의 텍스트·벡터·조정 레이어와 고급 효과는 지원되지 않습니다. 임의 메쉬 토폴로지 편집, 애니메이션 타임라인, 마스크, 물리, 얼굴 추적, 외부 앱용 런타임, Cubism 공식 형식은 아직 구현되지 않았습니다. 워프 분할은 2×2이며 부모 변경 UI는 추가 전입니다. 레이어 이미지는 투명한 부분도 선택 영역에 포함될 수 있습니다. 터치 기기와 실제 TWA의 성능 및 파일 선택 동작은 별도 검증이 필요합니다.
