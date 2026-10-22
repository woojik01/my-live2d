# Open-source notices

My Live 2D — original editor implementation, Apache License 2.0 (see LICENSE). No Cubism SDK, Cubism source or proprietary model format implementation is included. Third-party names belong to their respective owners; the app is an independent tool.

Direct runtime/build dependencies:

| Component | Pinned version | License / source |
|---|---|---|
| AndroidX Compose | BOM 2025.04.01 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt |
| AndroidX Activity | 1.10.1 | Apache-2.0, AndroidX source above |
| AndroidX Lifecycle | 2.9.0 | Apache-2.0, AndroidX source above |
| Kotlin stdlib / compiler plugins | 2.1.20 | Apache-2.0, https://github.com/JetBrains/kotlin/blob/v2.1.20/license/LICENSE.txt |
| kotlinx.serialization | 1.8.1 | Apache-2.0, https://github.com/Kotlin/kotlinx.serialization/blob/v1.8.1/LICENSE.txt |
| kotlinx.coroutines | 1.10.2 | Apache-2.0, https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/LICENSE.txt |
| Gradle wrapper | 8.11.1 | Apache-2.0, https://github.com/gradle/gradle/blob/v8.11.1/LICENSE |
| Android Gradle Plugin | 8.9.2 | Apache-2.0, https://android.googlesource.com/platform/tools/base/ |
| JUnit 4 (tests) | transitive through kotlin-test | EPL-1.0, https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt |
| AndroidX Test (tests) | runner 1.6.2 / rules 1.6.1 / ext-junit 1.2.1 / espresso-core 3.6.1 | Apache-2.0, https://github.com/android/android-test |

The APK packaging retains dependency META-INF license resources except AL2.0/LGPL2.1 boilerplate duplicates. LICENSE and this notice are also bundled as Android assets. The Android SDK is a build prerequisite distributed under its own SDK terms, not an open-source library embedded by this project. Future dependencies require separate review. CI Actions are build automation, not app runtime libraries.

Technical references checked during development (2026-10-22):

- https://developer.android.com/build/releases/agp-8-9-0-release-notes
- https://developer.android.com/develop/ui/compose/bom
- https://developer.android.com/develop/ui/views/graphics/opengl/environment
- https://developer.android.com/guide/topics/providers/document-provider
