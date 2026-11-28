# Android Ad SDK Playground

광고 SDK의 요청·광고 표시·이벤트·생명주기 문제를 이해하고, 구현과 검증을 반복하는 Kotlin Android 학습 프로젝트입니다. 실제 광고망을 연동하거나 운영하는 서비스는 아닙니다.

## 현재 상태

- `:ad-sdk`: 이미지 배너 로드와 광고 표시, 노출 조건 판정, 자원 해제를 제공합니다.
- `:sample-app`: SDK를 의존하는 매체 앱 역할. 로드·광고 표시·해제를 별도 조작하고 모의 응답을 선택할 수 있습니다.
- `mock-server`: Python 표준 라이브러리로 동작하는 로컬 서버입니다. 광고 JSON과 합성 PNG를 제공합니다.
- 노출 판정은 이미지 면적 50% 이상이 연속 1초 보이고 창에 포커스가 있을 때 알리는 프로젝트 정책입니다. 클릭과 서버 트래킹, 프리로드는 미구현입니다. 현재 계약과 미정 범위는 [요구사항](docs/requirements.md)을 따릅니다.

```text
ad-sdk/       Android Library → AAR
sample-app/   Android 앱 → APK, implementation(project(":ad-sdk"))
mock-server/  광고 JSON·이미지·실패 응답을 제공하는 로컬 서버
docs/         문제·요구사항·학습·판단 근거
```

SDK는 광고 준비와 View 반영, 노출 조건 관찰과 자원 해제를, 샘플 앱은 요청·광고 표시 시점과 사용 기간을 맡습니다. 모의 서버는 현재 광고 공급만 담당하며 이벤트 수신은 후속 구현 대상입니다.

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
4. 실행 구성을 `sample-app`으로 선택해 Run 합니다. 서버를 연결하지 않아도 앱은 실행되며, 광고 요청은 아래 모의 서버 절차로 확인합니다.

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

## 모의 서버와 배너 확인

Python 3.9 이상에서 별도 터미널을 열어 서버를 실행하고, 연결된 Android 기기의 루프백 포트를 연결합니다. 에뮬레이터·USB 기기에서 같은 방법을 사용할 수 있습니다.

```sh
python3 mock-server/server.py
adb reverse tcp:8080 tcp:8080
```

기본 주소는 `http://127.0.0.1:8080/ads`입니다. 서버는 개발 컴퓨터의 `127.0.0.1:8080`에서만 수신합니다. 포트가 사용 중이면 `--port 8081`처럼 변경하고 reverse·앱 주소도 같은 포트로 맞춥니다. 기기를 다시 연결하거나 서버 포트를 바꾸면 reverse 설정을 확인합니다.

1. 앱에서 **정상 이미지 배너 → 로드**를 누릅니다.
2. 로드 완료 안내가 표시돼도 배너 영역은 비어 있습니다.
3. **광고 표시**를 누르면 합성 이미지와 광고 라벨이 나타납니다. 이 동작을 노출 집계로 취급하지 않습니다.
4. **해제**는 배너를 완전히 종료합니다. **새 배너**로 새 객체를 만든 뒤 다시 로드할 수 있습니다.

회전 실험은 **정상 이미지 배너 → 광고 응답 지연 3~4초 → 로드 → 완료 전에 회전** 순서로 진행합니다. 슬라이더는 다음 요청의 광고 데이터 응답 지연을 제어하며 회전 후에도 값이 유지됩니다. `slow` 시나리오는 별도로 8초를 기다려 시간 초과를 재현합니다. SDK 내부에는 실험용 지연을 넣지 않았습니다.

화면에 표시되는 ID가 회전 후 바뀌는지 확인합니다. 새 화면은 자동 재요청하지 않으며 미로드 상태입니다. 다시 로드·광고 표시해 사용할 수 있습니다. Android Studio Logcat에서 `BannerSample`로 필터링하거나 CLI로 확인할 수 있습니다.

```sh
adb logcat -s BannerSample
```

이전 ID의 '로드 시작 → 종료 · 구성 변경=true', 새 ID의 '생성'을 확인하고, 이전 화면 종료 뒤 이전 ID로 성공·실패 콜백이 기록되지 않는지 관찰합니다. 회전 전에 이미 로드가 끝난 실행과 구분합니다. 서버는 취소된 요청의 처리를 계속할 수 있으며, 서버 로그와 앱 콜백은 서로 다른 관찰입니다.

실행 중이던 서버는 수정 사항을 자동 반영하지 않으므로 재시작합니다. 기존 8080 서버를 유지하려면 별도로 `python3 mock-server/server.py --port 8081`, `adb reverse tcp:8081 tcp:8081`을 실행하고 앱 주소를 `http://127.0.0.1:8081/ads`로 바꿉니다.

`NETWORK` 오류에 `Failed to connect`가 포함되면 서버 실행과 기기의 포트 연결을 각각 확인합니다. 앱의 주소가 어느 포트를 가리키는지도 확인해야 합니다. `adb reverse --list`가 비어 있으면 연결을 다시 설정합니다. 예를 들어 앱 주소가 `http://127.0.0.1:8084/ads`이고 개발 컴퓨터의 서버는 8080에서 실행 중이라면 `adb reverse tcp:8084 tcp:8080`으로 연결할 수 있습니다. 첫 포트는 기기, 두 번째 포트는 개발 컴퓨터의 포트입니다. 기기를 다시 연결한 뒤에는 reverse 상태를 재확인합니다.

슬라이더를 3초로 설정해도 즉시 완료된다면 앱 주소의 서버가 최신 프로세스인지 먼저 확인합니다. 소스 파일만 수정하고 서버를 재시작하지 않으면 이전 서버는 `delayMs`를 무시할 수 있습니다. 다음 요청은 광고 데이터 응답까지 약 3초 이상 걸려야 합니다.

```sh
curl -sS -o /dev/null -w 'response time: %{time_total}s\n' 'http://127.0.0.1:8080/ads?placementId=demo-banner&delayMs=3000'
```

2026-11-23에는 구버전 8080 서버가 약 0.003초에 응답하는 현상을 재현했습니다. 해당 서버를 최신 코드로 재시작한 뒤 서버 응답 약 3.008초, 샘플의 로드 시작부터 성공 콜백까지 약 3.301초를 확인했습니다. 이미지 준비가 추가되므로 전체 로드는 지정한 서버 응답 지연보다 길 수 있습니다.

| 모의 응답 | 기대 결과 |
| --- | --- |
| 정상 이미지 배너 | 준비 완료 후 명시적 광고 표시 가능 |
| 광고 없음 | `NO_FILL` |
| 서버 오류 | `HTTP_ERROR` |
| 잘못된 응답 | `INVALID_RESPONSE` |
| 이미지 준비 실패 | `IMAGE_LOAD`, 성공 콜백 없음 |
| 응답 지연 · 시간 초과 | `TIMEOUT` |

서버를 종료한 뒤 로드하면 통신 장애를 관찰할 수 있습니다. 지연 응답 중 다시 로드하거나 해제해 이전 결과가 반영되지 않는지도 확인할 수 있습니다. 서버 종료는 터미널의 Ctrl+C, reverse 해제는 `adb reverse --remove tcp:8080`입니다.

Debug 샘플은 `127.0.0.1`의 HTTP만 예외로 허용합니다. SDK와 Release 앱에 전역 HTTP 허용을 추가하지 않았습니다. 다른 서버를 사용하려면 HTTPS와 호스트 앱의 네트워크 정책을 확인합니다.

## SDK 연동

호스트 앱에서 `implementation(project(":ad-sdk"))`를 추가합니다. `BannerAdView`를 레이아웃에 배치하고, 아래처럼 메인 스레드에서 호출합니다. 샘플은 높이 100dp의 영역을 사용합니다.

```kotlin
// 이 View의 모든 광고에 사용합니다. 광고 교체마다 다시 등록하지 않습니다.
banner.setImpressionListener(object : BannerAdImpressionListener {
    override fun onImpression(adInfo: BannerAdInfo) {
        impressionStatus.text = "노출 조건을 충족한 광고: ${adInfo.adId}"
        // 서버 집계나 과금 완료를 뜻하지 않습니다.
    }
})
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
showButton.setOnClickListener {
    banner.show(object : BannerAdShowListener {
        override fun onShowSuccess(adInfo: BannerAdInfo) {
            status.text = "반영한 광고: ${adInfo.adId} / ${adInfo.placementId}"
        }

        override fun onShowFailed(error: BannerAdShowError) {
            status.text = "${error.code}: ${error.message}"
            // 실패해도 이전 광고가 표시 중일 수 있으므로 영역을 숨기지 않습니다.
        }
    })
}
```

- `load()`를 다시 호출하면 이전 작업을 취소하고, 로드는 완료됐지만 아직 View에 반영하지 않은 광고를 버립니다. 이미 표시한 광고는 유지하고 가장 최근에 로드 완료된 광고를 `show(listener)`할 때 교체합니다. 새 로드가 실패해도 기존 광고 표시는 유지합니다.
- `show(listener)`는 마지막 load에서 로드 완료된 광고의 이미지를 반영한 뒤 `onShowSuccess(adInfo)`를 호출합니다. 실패하면 `onShowFailed(error)`를 호출합니다. 콜백은 show 호출 안에서 메인 스레드로 한 번 전달되며 실제 가시성이나 노출을 보장하지 않습니다. 반복 show는 매번 결과를 전달합니다.
- 사용을 마칠 때 `destroy()`를 호출합니다. 샘플의 `Activity.onDestroy()`처럼 호스트가 수명을 관리합니다. 반복 해제는 안전하며, 해제 후 `load()`는 무시하고 `show(listener)`는 DESTROYED를 전달합니다.
- API 호출·콜백은 메인 스레드입니다. 단순 detach·백그라운드 전환으로 자동 해제·재로드하지 않습니다. 회전으로 Activity가 재생성되면 새 배너를 수동 로드합니다.
- 각 HTTP 호출의 제한 시간은 5초이며 전체 로드 5초 제한이 아닙니다. SDK의 자동 `load()` 재호출·광고 캐시는 없습니다. OkHttp의 기본 연결 복구는 허용하므로, 끊긴 연결 등에서 GET이 재시도될 수 있습니다.
- 응답 형식·오류 분류는 [현재 계약](docs/requirements.md#첫-배너에-적용한-계약), 전체 연동 예시는 [MainActivity](sample-app/src/main/java/io/github/junyoungwon/androidadsdkplayground/MainActivity.kt)를 참고합니다.

광고 표시 리스너(`BannerAdShowListener`)는 이번 show 호출의 광고 표시 결과만 전달받습니다. SDK는 show가 반환되기 전에 콜백을 호출하고, 리스너를 멤버 변수에 저장해 이후 이벤트에 재사용하지 않습니다. 다음 show에도 리스너를 전달해야 하며 같은 객체를 다시 전달해도 됩니다. 노출 조건 충족을 계속 구독하려면 별도의 BannerAdImpressionListener를 View에 등록합니다.

기존 `show(): Boolean`은 `show(BannerAdShowListener)`로 변경했습니다. 광고 표시 오류는 `NOT_LOADED`, `LOADING`, `LOAD_FAILED`, `DESTROYED`로 구분합니다. `LOAD_FAILED`라면 `error.loadError`에서 원래 로드 실패 원인도 확인할 수 있습니다. 광고 표시 상태 조회 API는 아직 제공하지 않습니다.

재로드 유지 동작은 A를 로드하고 광고 표시한 뒤, 응답 지연을 3초로 설정해 B를 로드하며 확인합니다. B 로딩 중과 준비 완료 후에도 A가 유지되고, 광고 표시 버튼을 누르면 B의 이미지가 View에 반영되고 광고 ID가 바뀝니다. 모의 서버는 같은 이미지를 반환하므로 픽셀 차이로 A/B를 구분할 수는 없습니다. 실패 시나리오로 재로드했을 때도 기존 이미지가 남는지 확인합니다. 새 화면으로 회전하면 기존처럼 배너를 해제하므로 유지되지 않습니다.

## 노출 판정 확인

노출 리스너는 View에 등록된 상태로 유지되며, 광고가 바뀌어도 onImpression(adInfo) 콜백을 호출해 조건을 충족한 광고 정보를 전달합니다. null로 등록을 해제할 수 있고 destroy에서도 참조를 정리합니다. 리스너 없이 조건을 충족한 과거 이벤트는 나중에 등록해도 다시 전달하지 않습니다.

샘플에는 이 View의 onImpression 콜백 수신 횟수와 마지막 광고 ID, 배너 숨기기 버튼을 추가했습니다.

1. 로드만 완료한 뒤 onImpression 콜백 수신 횟수가 0회인지 확인합니다.
2. 광고 표시를 누르고 이미지가 화면에 절반 이상 보이도록 스크롤합니다. 창에 포커스가 있는 상태로 1초 유지하면 onImpression 콜백 수신 횟수가 1회가 됩니다.
3. 새 load 없이 같은 로드 광고의 광고 표시 버튼을 반복해서 눌러도 onImpression 콜백 수신 횟수는 늘지 않습니다.
4. 새 광고를 로드하고 광고 표시 직후 숨겼다가 다시 보이게 하면, 복귀 후 연속 1초를 다시 측정합니다. 숨기는 버튼을 누르기 전에 이미 1초가 지났다면 같은 adId의 onImpression 콜백은 다시 호출하지 않습니다.
5. A 광고 표시 중 B를 로드하거나 실패시켜도 A의 판정은 유지됩니다. B가 로드 완료돼도 show 전에는 B의 정보로 onImpression 콜백을 호출하지 않습니다.

adId는 서버가 광고 응답마다 발급하는 고유 식별값입니다. 같은 소재를 새로 제공해도 새 adId를 사용합니다. SDK는 별도 loadId를 만들지 않고 한 View의 수명 동안 adId별로 노출 콜백을 한 번만 호출합니다. 같은 이미지를 새 adId로 로드해 광고 표시하면 연속 1초를 새로 측정합니다. 같은 adId를 다시 로드하거나 반복 show해도 진행 중인 시간과 판정 이력을 유지합니다. 다른 View와 서버의 집계까지 보장하지 않습니다.

다른 View가 광고 위를 덮는 경우나 이미지의 투명 픽셀을 정확히 측정하지 않습니다. 회전과 배율 변환이 있으면 판정에서 제외합니다. 자세한 수명과 한계는 [노출 계약](docs/requirements.md#배너-노출-판정--적용)을 참고합니다.

## 검증

```sh
./gradlew :sample-app:lintDebug :ad-sdk:lintDebug
./gradlew :sample-app:testDebugUnitTest :ad-sdk:testDebugUnitTest
./gradlew :sample-app:connectedDebugAndroidTest
```

기기 테스트는 API 26 이상인 연결 기기·에뮬레이터가 필요합니다. 테스트 안에서 서버를 실행하므로 Python 모의 서버·ADB reverse는 필수 조건이 아닙니다. Gradle은 테스트 종료 후 테스트용 앱을 정리할 수 있으므로 시연하려면 샘플 앱을 다시 설치·실행합니다.

2026-11-27 자동 검증 결과와 이전 수동 관찰:

- Debug APK·AAR 빌드 성공, 두 모듈 Lint 오류 0개. SDK 검사에 새 버전 안내 경고 12개, 샘플 검사에는 이슈가 없습니다.
- SDK 단위 테스트 29개 통과: 노출 시간 경계와 중단, 광고 교체, adId별 중복 억제와 같은 adId의 시간 유지에 더해 로드 준비·광고 표시 분리, 이미지 실패, 재요청·해제 후 늦은 결과, 입력·응답 검증, HTTP/NoFill/통신/시간 초과, HTTP 취소 연결, 광고 표시 불가 사유와 실패 콜백 안에서의 재로드.
- API 34 ARM64 에뮬레이터 통합 테스트 27개 통과: 이미지 가시 면적과 여백, 스크롤과 미부착, 상위 alpha와 회전 및 배율 변환, 판정 전 분리와 재부착, 숨김과 창 포커스, 리스너 등록 시점과 재진입, 같은 이미지와 새 adId의 재로드, 같은 adId 재사용 시 중복 콜백 억제에 더해 실제 이미지 디코딩, 기존 광고 표시 유지와 새 이미지 교체, 콜백의 광고 정보와 호출 스레드, NoFill과 이미지 실패 시 기존 광고 유지, 아직 광고 표시하지 않은 로드 완료된 광고 폐기, 해제와 재진입, HTTP/1.0 연결 복구, Activity 재생성과 지연값 복원.
- 2026-11-26 샘플에서 광고 표시 후 onImpression 콜백 수신 1회, 같은 광고의 show 반복과 숨김 후 복귀에도 1회 유지를 확인했습니다.
- 2026-11-23에는 Python 모의 서버·ADB reverse를 연결한 샘플에서 로드 완료 시 빈 영역, 광고 표시 버튼 후 합성 이미지·광고 라벨을 화면 캡처로 확인했습니다.
- 실제 광고망과 공인 노출 측정, 화면 전환·회전·백그라운드 수명 전체, API 26·36 기기 실행, R8 활성화·외부 앱 AAR 연동은 미검증입니다.
- 환경에서 수정한 파일이 Gradle 검사에 반영되지 않을 때는 `--no-watch-fs`를 붙여 재확인할 수 있습니다. 이번 최종 기기 테스트·Lint는 이 옵션으로 실행했습니다.
- 테스트 이름과 시나리오 설명을 한글로 정리했습니다. 단위 테스트는 세션·HTTP 소스, 기기 테스트는 Android 연동을 검증합니다. ActivityScenario 재생성 테스트는 실제 가로·세로 방향 전환, 메모리 회수·누수 검증 전체를 대신하지 않습니다.
- 2026-11-23에는 로컬 모의 서버의 `delayMs=500` 요청을 약 507ms로 관찰하고, 음수·범위 초과·문자열 값의 HTTP 400 처리를 확인했습니다.

## 다음 작업

노출 판정의 광고 ID와 onImpression 콜백 호출 시점을 직접 검토한 뒤 서버 전송과 중복 수신 처리를 다음 기능으로 검토합니다. 클릭과 연결 URL도 별도 계약이 필요합니다. `load()`·`show()` 분리만으로 화면과 독립적인 프리로드·만료·소비 정책을 완성했다고 보지 않습니다.

- [프로젝트 맥락과 현재 상태](docs/project-context.md)
- [문제와 요구사항](docs/requirements.md)
- [의사결정·실험 기록](docs/decisions.md)
- [광고 도메인 학습](docs/ad-domain.md)
- [학습·실험 계획](docs/learning-plan.md)
- [작업 지침](AGENTS.md)
