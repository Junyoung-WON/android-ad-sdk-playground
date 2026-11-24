# Android Ad SDK Playground

광고 SDK의 요청·표시·이벤트·생명주기 문제를 이해하고, 구현과 검증을 반복하는 Kotlin Android 학습 프로젝트입니다. 실제 광고망을 연동하거나 운영하는 서비스는 아닙니다.

## 현재 상태

- `:ad-sdk`: 이미지 배너 요청·이미지 준비·결과 알림, 명시적 표시·해제를 제공합니다.
- `:sample-app`: SDK를 의존하는 시작 화면입니다. 배너 조작 UI와 연동 검증은 다음 커밋에서 추가합니다.
- 노출·클릭·트래킹·프리로드 정책은 미구현입니다. 현재 계약과 미정 범위는 [요구사항](docs/requirements.md)을 따릅니다.

```text
ad-sdk/       Android Library → AAR
sample-app/   Android 앱 → APK, implementation(project(":ad-sdk"))
docs/         문제·요구사항·학습·판단 근거
```

SDK는 광고 준비·View 반영·자원 해제를, 샘플 앱은 요청·표시 시점과 사용 기간을 맡습니다. 모의 서버·샘플 배너 연동·이벤트 수신은 후속 작업입니다.

## 개발 환경

| 항목 | 초기 구성 |
| --- | --- |
| Gradle 실행 JDK | 17 |
| Gradle Wrapper | 8.13 |
| Android Gradle Plugin | 8.11.1 |
| Kotlin | 2.2.21 |
| compileSdk / sample targetSdk | 36 / 36 |
| minSdk | 26, 기존 템플릿 기준 유지 |
| Java / Kotlin 바이트코드 | 11 |
| 샘플 UI | Android Views/XML, AppCompat 1.7.1, Material 1.13.0 |
| AndroidX Core KTX | 1.17.0 |
| SDK HTTP / 이미지 | OkHttp 4.12.0 / Coil 2.7.0 |
| 비동기 / JSON | Coroutines 1.10.2 / kotlinx.serialization JSON 1.9.0 |

버전은 [버전 카탈로그](gradle/libs.versions.toml)에 고정합니다. 기존 템플릿의 Core 1.19.1은 API 37·AGP 9.1 이상을 요구해 빌드되지 않았습니다. 설치된 API 36·JDK 17에 맞춘 조합을 선택했으며, 근거와 한계는 [D-004](docs/decisions.md#d-004--초기-프로젝트-세팅)에 기록했습니다. 최소 지원 API는 향후 SDK 연동 대상에 따라 재검토할 수 있습니다.

## 처음 실행하기

1. Android Studio에서 이 루트를 열고 Gradle JDK를 **JDK 17**로 설정합니다.
2. SDK Manager에서 **Android SDK Platform 36**, **Build-Tools 35.0.0**, **Platform-Tools**를 설치합니다. 실행할 기기·에뮬레이터는 API 26 이상이어야 합니다.
3. Gradle Sync를 실행합니다. 최초 빌드에는 Google Maven·Maven Central·Gradle 배포 서버에 접근할 수 있어야 합니다.
4. 실행 구성을 `sample-app`으로 선택해 Run 합니다. 현재 앱은 시작 화면을 표시하며 배너 연동은 후속 작업입니다.

로컬 SDK 경로는 Android Studio가 생성하는 `local.properties`로 지정합니다. CLI에서 필요한 경우 다음처럼 로컬 경로를 직접 설정할 수 있습니다. 이 파일은 Git에서 제외됩니다.

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

macOS/Linux 빌드:

```sh
./gradlew :sample-app:assembleDebug :ad-sdk:assembleDebug
```

- APK: `sample-app/build/outputs/apk/debug/sample-app-debug.apk`
- AAR: `ad-sdk/build/outputs/aar/ad-sdk-debug.aar`

기기 한 대를 연결한 CLI 설치·실행:

```sh
./gradlew :sample-app:installDebug
adb shell am start -n io.github.junyoungwon.androidadsdkplayground/.MainActivity
```

여러 기기가 연결돼 있으면 Android Studio에서 실행 대상을 선택하거나 ADB의 `-s <serial>` 옵션을 사용합니다. Windows에서는 `./gradlew` 대신 `gradlew.bat`를 사용합니다. Android CLI는 개발 편의 도구이며 빌드 필수 조건은 아닙니다.

## SDK 연동

호스트 앱에서 `implementation(project(":ad-sdk"))`를 추가합니다. `BannerAdView`를 레이아웃에 배치하고, 아래처럼 메인 스레드에서 호출합니다. 호스트가 배너 영역 크기를 지정합니다.

```kotlin
banner.load(
    BannerAdRequest(
        placementId = "demo-banner",
        serverUrl = "http://127.0.0.1:8080/ads"
    ),
    object : BannerAdListener {
        override fun onLoaded() {
            showButton.isEnabled = true // 이미지 준비 완료. 아직 View에 반영하지 않음.
        }

        override fun onFailed(error: BannerAdError) {
            showButton.isEnabled = false
            status.text = "${error.code}: ${error.message}"
        }
    }
)
showButton.setOnClickListener { banner.show() }
```

- `load()`를 다시 호출하면 기존 이미지·준비 결과를 지우고 이전 작업을 취소합니다. 이전 작업의 성공·실패 콜백은 전달하지 않습니다.
- `show()`는 준비된 이미지를 View에 반영하면 `true`, 준비되지 않았거나 해제됐다면 `false`입니다. `true`는 실제 가시성·노출을 보장하지 않습니다.
- 사용을 마칠 때 `destroy()`를 호출합니다. 호스트의 `Activity.onDestroy()` 등 사용 종료 시점에 해제합니다. 반복 해제는 안전하며, 해제 후 `load()`는 무시하고 `show()`는 `false`입니다.
- API 호출·콜백은 메인 스레드입니다. 단순 detach·백그라운드 전환으로 자동 해제·재로드하지 않습니다. 회전으로 Activity가 재생성되면 새 배너를 수동 로드합니다.
- 각 HTTP 호출의 제한 시간은 5초이며 전체 로드 5초 제한이 아닙니다. SDK의 자동 `load()` 재호출·광고 캐시는 없습니다. OkHttp의 기본 연결 복구는 허용하므로, 끊긴 연결 등에서 GET이 재시도될 수 있습니다.
- 응답 형식·오류 분류는 [현재 계약](docs/requirements.md#첫-배너에-적용한-계약)을 참고합니다.

## SDK 검증

```sh
./gradlew :ad-sdk:testDebugUnitTest :ad-sdk:assembleDebug :ad-sdk:lintDebug --no-watch-fs --console=plain
```

SDK 단위 테스트 16개는 준비·표시 분리, 실패 분류, 재요청·해제 후 늦은 결과 차단, HTTP 취소 연결을 검증합니다. Android 디코딩·호스트 생명주기의 기기 통합 테스트는 샘플 연동 커밋에서 추가합니다. 실제 광고망·노출·클릭·트래킹·외부 앱 AAR 소비는 미검증입니다.

- [현재 계약](docs/requirements.md#첫-배너에-적용한-계약)
- [프로젝트 상태](docs/project-context.md)
