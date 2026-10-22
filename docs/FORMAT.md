# .mylive2d v1

ZIP 루트:

```
project.json
assets/<asset-id>.png
```

JSON `format`은 `MyLive2D`, `version`은 `1`이다. 지원하지 않는 버전은 읽기를 거부한다. JSON 구조의 유일한 스키마 소스는 `core/Model.kt`의 @Serializable 타입이다. 향후 버전은 변환기를 명시적으로 추가하며 조용히 이전 형식으로 해석하지 않는다.

- Project: id, name, assets, parts, parameters
- Asset: id, width, height
- Part: id, name, assetId, mesh, transform, visible, locked, opacity
- Mesh: vertices[{x,y}], uv[{x,y}], triangles[Int]
- Transform: position{x,y}, rotation(도), scale(균일 배율)
- Parameter: id, name, min, default, max, value, bindings
- Binding: partId, keys
- Keyform: value, vertices (해당 메쉬와 같은 길이)

parts 배열은 뒤에서 앞으로 그린다. 각 키폼은 절대 로컬 정점 좌표다. 카메라·선택·초안·Undo 이력은 교환 포맷에 포함하지 않는다. `.mylive2d` 하나에 편집 복원에 필요한 이미지가 전부 포함된다. 숨김 파츠의 이미지도 보존한다.

최대 33 ZIP entries, PNG 16 MiB/개, JSON 8 MiB, 해제 총합 64 MiB. ZIP의 경로는 정규화하여 허용하는 것이 아니라 허용 목록에 정확히 일치할 때만 읽는다. 외부 파일은 새로운 project/asset ID로 가져와 로컬 파일 덮어쓰기를 막는다.
