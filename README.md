# Android Ad SDK Playground

광고 SDK의 요청·표시·이벤트·생명주기 문제를 이해하고, 구현과 검증을 반복하는 Kotlin Android 학습 프로젝트입니다. 실제 광고망을 연동하거나 운영하는 서비스는 아닙니다.

## 현재 상태

- `:ad-sdk`: Android Library 골격. Debug AAR 생성 가능. 광고 코드·공개 API는 아직 없습니다.
- `:sample-app`: SDK 모듈을 의존하는 매체 앱 역할. 실행 가능한 시작 화면을 제공합니다.
- 광고 요청·이미지 표시·트래킹·모의 서버는 미구현입니다. API와 세부 정책은 [요구사항](docs/requirements.md)을 검토한 뒤 정합니다.

```text
ad-sdk/       Android Library → AAR
sample-app/   Android 앱 → APK, implementation(project(":ad-sdk"))
docs/         문제·요구사항·학습·판단 근거
```

SDK는 광고 기능을, 샘플 앱은 연동·화면 구성을 맡습니다. 모의 서버는 이후 광고 응답·이벤트 수신을 제어하는 최소 역할로 검토합니다.

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

버전은 [버전 카탈로그](gradle/libs.versions.toml)에 고정합니다. 기존 템플릿의 Core 1.19.1은 API 37·AGP 9.1 이상을 요구해 빌드되지 않았습니다. 설치된 API 36·JDK 17에 맞춘 조합을 선택했으며, 근거와 한계는 [D-004](docs/decisions.md#d-004--초기-프로젝트-세팅)에 기록했습니다. 최소 지원 API는 향후 SDK 연동 대상에 따라 재검토할 수 있습니다.

## 처음 실행하기

1. Android Studio에서 이 루트를 열고 Gradle JDK를 **JDK 17**로 설정합니다.
2. SDK Manager에서 **Android SDK Platform 36**, **Build-Tools 35.0.0**, **Platform-Tools**를 설치합니다. 실행할 기기·에뮬레이터는 API 26 이상이어야 합니다.
3. Gradle Sync를 실행합니다. 최초 빌드에는 Google Maven·Maven Central·Gradle 배포 서버에 접근할 수 있어야 합니다.
4. 실행 구성을 `sample-app`으로 선택해 Run 합니다. 첫 화면에 “배너 광고 실험실”, “아직 광고를 요청하지 않았습니다.”가 표시됩니다.

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

## 검증

```sh
./gradlew :sample-app:lintDebug :ad-sdk:lintDebug
./gradlew :sample-app:testDebugUnitTest :ad-sdk:testDebugUnitTest
```

2026-11-21 세팅 검증:

- Debug APK·AAR 생성 성공, 두 모듈 Lint 오류 0개. 새 버전 안내 경고 7개가 남습니다.
- API 34 ARM64 에뮬레이터에서 APK 설치·Activity 실행·한글 첫 화면을 확인했습니다.
- JUnit 4와 AndroidX Test/Espresso 의존성·러너만 구성했습니다. 템플릿의 산술·패키지명 테스트는 제거했고, 현재 테스트 코드는 없어 단위 테스트 작업은 `NO-SOURCE`입니다. 테스트 통과나 광고 동작 검증으로 해석하지 않습니다.
- 광고 정책·생명주기 동작, 최저 API 26·target API 36에서의 실행, R8 활성화 빌드·외부 앱의 AAR 연동은 아직 검증하지 않았습니다.

## 다음 작업

첫 배너의 `load`·재요청·해제 계약과 관찰 가능한 완료 기준을 정한 뒤 정상 흐름을 구현합니다. 네트워크·이미지·비동기 라이브러리는 이 계약을 검토할 때 선택합니다.

- [프로젝트 맥락과 현재 상태](docs/project-context.md)
- [문제와 요구사항](docs/requirements.md)
- [의사결정·실험 기록](docs/decisions.md)
- [광고 도메인 학습](docs/ad-domain.md)
- [학습·실험 계획](docs/learning-plan.md)
- [작업 지침](AGENTS.md)
