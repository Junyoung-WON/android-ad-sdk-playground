# 광고 도메인 학습과 참고 자료

최종 내용 갱신일: 2026-11-27

운영 지침은 [AGENTS.md](../AGENTS.md)를 따른다. 관련 상태는 [프로젝트 맥락](project-context.md), 판단 근거는 [의사결정·실험 기록](decisions.md)을 참고한다.

## 지금까지 학습한 광고 도메인

### 1. 참여 주체와 용어

| 개념 | 의미 |
| --- | --- |
| 광고주 | 상품·서비스를 알리고 구매·가입 등 성과를 얻기 위해 광고를 집행하는 주체 |
| 매체·퍼블리셔 | 앱·웹 등에 광고 공간을 제공하는 주체 |
| 광고 SDK | 매체 앱에 포함되어 광고 요청·광고 표시·이벤트 처리를 제공하는 코드 |
| 광고 서버·플랫폼 | 광고 선택·공급, 이벤트 수집, 운영과 분석 등을 담당하는 시스템 |
| 광고 지면·광고 단위 | 앱에서 광고를 제공할 자리 또는 광고 요청의 설정·관리 단위. 플랫폼별 정확한 용어 범위는 다를 수 있음 |
| 인벤토리 | 매체가 제공하는 광고 공간과 노출 기회 |
| 소재·Creative | 이미지·문구·동영상 등 실제 광고 내용 |
| 캠페인 | 목표·예산·기간 등의 조건으로 운영하는 광고 활동의 단위 |
| DSP | 광고주·대행사 측에서 광고 기회를 평가하고 구매·입찰하는 플랫폼 |
| SSP | 매체 측에서 광고 인벤토리를 여러 구매 경로에 연결하고 판매·수익화를 돕는 플랫폼 |
| Ad Exchange | 광고 구매·판매와 경매를 연결하는 거래소 |

한 기업·플랫폼이 여러 역할을 제공할 수 있다. 와이즈버즈의 내부 시스템 구성이나 이 채용 팀의 담당 제품은 공고만으로 단정하지 않는다.

### 2. 광고가 제공되는 전체 흐름

1. 광고주가 목표·예산·기간·대상·소재·연결 페이지·입찰 전략 등을 설정한다.
2. 매체가 광고 지면을 준비하고 SDK를 연동한다.
3. 사용자가 앱을 이용하면 앱이 SDK를 통해 광고를 요청한다.
4. 광고 시스템은 지면·형식·크기와 이용 가능한 신호, 캠페인 조건 등을 바탕으로 후보를 판단한다.
5. RTB를 사용하는 경로에서는 구매 측에 입찰 요청을 보내고 유효한 응답을 평가한다. 직접 계약 등 다른 공급 방식도 존재한다.
6. 선택된 광고를 공급하기 위한 데이터·소재 정보가 앱 측에 전달된다.
7. SDK가 이미지 등 필요한 자원을 준비하고 광고를 렌더링한다.
8. 시스템의 집계 기준에 따라 노출·클릭을 측정하고 관련 이벤트를 전달한다.
9. 광고주 웹·앱 등에서 구매·가입 같은 전환을 별도로 측정하고 광고 접점과 연결한다.
10. 수집한 데이터를 성과 분석, 광고 운영·입찰 최적화, 수익 산정·정산 등에 활용한다.

이는 학습용 일반화다. SDK 응답 형식, 이벤트 발생 주체, 소재 다운로드 경로와 집계·과금 기준은 플랫폼마다 다르다.

### 3. RTB와 미디에이션

- RTB는 개별 광고 제공 기회를 실시간으로 평가·입찰하는 방식이다.
- 매체 앱의 광고 요청(Ad request)과 거래소가 구매 측에 보내는 입찰 요청(Bid request)은 별개다.
- 구매 측은 요청의 조건을 보고 입찰 참여 여부·가격·소재를 결정한다. 유효성·소재 조건·응답 기한 등도 충족해야 한다.
- 광고 선택·입찰의 핵심 판단은 주로 서버 측에서 이루어진다. SDK는 앱 측 요청·소재 로드 준비·광고 표시·이벤트 처리에 관여한다.
- 낙찰은 앱에서의 렌더링이나 실제 노출을 보장하지 않는다.
- 미디에이션은 여러 광고 공급원을 통합해 광고 공급을 관리하는 기능이다.
- 비딩은 공급원이 실시간 경쟁하는 방식이고, 워터폴은 설정값·과거 실적 등으로 정한 순서에 따라 공급원을 호출하는 방식이다. 두 방식을 결합할 수도 있다.
- 모든 광고가 RTB를 거치지는 않으며, 모든 SDK가 같은 미디에이션 구조를 갖지도 않는다.

### 4. 요청·로드·렌더링·노출·클릭의 구분

| 단계 | 의미와 주의점 |
| --- | --- |
| 요청 | 광고를 공급해 달라고 요청함. 광고 반환이나 표시를 보장하지 않음 |
| 응답 수신 | 광고 데이터나 광고 없음 등의 결과를 받음. 이미지 준비 완료와 다름 |
| 로드 완료 | SDK가 정의한 표시 준비가 완료됨. 구체적인 범위는 SDK마다 다름 |
| 렌더링 | 준비된 광고 내용을 화면에 그림 |
| 노출·Impression | 해당 시스템의 노출 집계 기준을 충족함. 로드 완료와 동일하다고 일반화하지 않음 |
| 조회 가능한 노출·Viewable impression | 가시 면적·시간 등의 조회 가능성 측정 기준을 충족한 노출. 일반 노출과 구분 |
| 클릭 | 사용자가 광고를 누르는 행동. 연결 페이지 이동 성공·전환과 구분 |

일반적인 디스플레이 광고의 조회 가능성 기준으로 설명한 ‘면적 50% 이상이 1초 이상 보임’을 모든 일반 노출의 정의로 적용하지 않는다. 상세 기준·예외와 적용 환경은 해당 측정 문서를 따른다.

사용자가 답변해 이해를 확인한 사례:

- 광고 데이터 응답은 받았지만 이미지 다운로드가 실패한 경우: 응답 수신은 성공했으나, 이미지 준비까지 요구하는 우리 프로젝트의 로드는 실패한 것이다.
- 프리로드를 완료했지만 해당 화면에 진입하지 않은 경우: 로드 완료는 알리되 광고 표시·노출이 발생했다고 기록하지 않는다.

노출 판정 구현 준비에서 확인한 사항(2026-11-26): [Google Active View](https://support.google.com/admanager/answer/3154105?hl=en)는 디스플레이 조회 가능성에 면적 50%와 1초를 설명하면서 일반 노출과 조회 가능성 측정을 구분한다. 이 값을 프로젝트 정책으로 선택해도 공인 측정 구현이 되는 것은 아니다. [Android View 문서](https://developer.android.com/reference/android/view/View)는 getGlobalVisibleRect를 root View에서 잘리지 않은 영역의 사각형으로 설명한다. 이 기하 정보만으로 다른 View나 창의 임의 가림을 모두 알 수 있다고 해석하지 않는다. [Activity 생명주기 문서](https://developer.android.com/guide/components/activities/activity-lifecycle)에서는 Paused Activity도 다중 창에서 보일 수 있음을 설명하므로, 창 포커스와 가시성을 같은 의미로 쓰지 않는다.

### 노출 관련 이벤트와 시간 지표

이벤트는 특정 시점의 발생 사실이고, 가시 면적과 경과 시간은 측정값이며, 노출 수와 평균 시간은 집계 지표다. 예를 들어 조건을 처음 충족한 시점에 이벤트를 한 번 알리는 것과 이후 보인 시간까지 계속 측정하는 것은 별도 책임이다. SDK가 내부에서 관찰하는 모든 신호를 호스트 앱의 공개 콜백으로 제공하는 것은 아니다.

[IAB Tech Lab OM SDK](https://iabtechlab.com/standards/open-measurement-sdk/)는 화면상 광고와 가시 비율, 시간을 측정하기 위한 신호와 제삼자 측정 접근을 설명한다. [Android 연동 문서](https://interactiveadvertisingbureau.github.io/Open-Measurement-SDKAndroid/)는 impression 신호와 동영상 시작, 재생 구간 도달, 일시 정지와 재개 등을 구분한다. 일반 노출과 조회 가능한 노출, 클릭, 동영상 재생 이벤트를 하나의 성공 콜백으로 해석하지 않는다. 지원 여부는 형식과 제품 계약을 따른다.

노출 시간을 설계할 때는 어떤 면적과 창 상태를 만족한 시간을 셀지, 중단 전후를 합산할지, 가장 긴 연속 구간을 구할지, 광고 교체와 화면 종료 중 언제 보고할지를 정해야 한다. 광고 로드 지연과 영상 재생 시간, 사람이 실제 주의를 기울인 시간과도 구분한다. 이는 가능한 설계 항목이며 이번 프로젝트에 새로 적용한 요구사항은 아니다.

[Google Active View 보고서](https://support.google.com/admanager/answer/3154105?hl=en)는 평균 조회 가능 시간을 별도 지표로 설명한다. 다만 [지원 범위 문서](https://support.google.com/admanager/answer/7079212?hl=en)는 해당 평균 시간 지표를 모바일 앱 인벤토리에는 제공하지 않는다고 명시한다. 시간 측정 자체의 가능성과 특정 SDK 콜백 또는 보고서의 제공 여부를 구분한다.

현재 프로젝트는 show로 반영한 광고가 프로젝트 조건을 처음 충족하면 등록된 리스너의 onImpression 콜백을 호출한다. 한 번 로드 완료된 광고에 한 번 호출한다. 서버 adId와 이미지가 같아도 새로 로드해 반영한 광고는 별도로 판정한다. 내부에서 연속 1초를 판정하지만 총 가시 시간, 최대 연속 시간, 노출 종료 이벤트는 제공하지 않는다. 콜백 정보에는 adId와 placementId만 있으며 시간값은 없다. 판정 이후의 시간 통계와 서버 수신, 집계는 별도 범위다. 이 기능은 단순화한 조회 가능 조건 판정에 가깝고 공인 측정 구현을 뜻하지 않는다.

### 5. 트래킹과 전환

- 트래킹은 광고 관련 이벤트를 감지하고 측정 시스템에 전달하는 과정이다.
- SDK → 매체 앱 콜백은 앱이 상태에 대응하도록 알리는 통로다. SDK → 서버 트래킹과 구분한다.
- 앱에서 이벤트 감지, 전송 시도, 서버 수신·저장, 보고서 집계는 서로 다른 단계다.
- 지면 ID, 소재 ID, 개별 요청·광고 제공 건의 식별자는 목적이 다르다. 같은 소재도 여러 제공 건에서 사용될 수 있다.
- 클라이언트에서 중복 이벤트를 억제했다고 서버 전체에서 정확히 한 번 집계됨을 보장하는 것은 아니다.
- 전환은 광고주가 중요하다고 정의한 구매·가입·설치 등의 행동이다.
- 매체의 광고 SDK가 다른 앱·웹에서 발생한 구매를 자동으로 모두 알 수 있는 것은 아니다. 광고주 측 등의 별도 측정 수단이 필요하다.
- 어트리뷰션은 측정한 성과를 광고 접점에 연결하고 기여를 배분하는 과정이다. 모든 전환이 클릭 직후에 발생한다고 가정하지 않는다.

### 소재 식별과 제공 건, SDK 로드의 구분

같은 소재를 새로 선택해 제공하는 것과 같은 제공 건의 이벤트를 중복 처리하는 것은 다르다. [IAB Tech Lab VAST 4.3](https://iabtechlab.com/wp-content/uploads/2022/09/VAST_4.3.pdf)의 3.4.3 AdServingId는 제공한 광고의 수명과 시스템 간 이벤트를 연결하는 식별자를 설명하며, 3.7.1 UniversalAdId는 소재 식별을 별도로 다룬다. 이는 동영상 VAST의 구체적인 규격이며 이번 이미지 배너의 JSON 필드에 그대로 적용하는 표준은 아니다. 식별자가 무엇을 구분하는지 계약으로 정해야 한다는 참고 사례다.

현재 프로젝트의 서버 adId는 응답값으로 전달한다. 로컬 중복 억제에는 이미지 준비까지 끝난 SDK 로드마다 발급하는 loadId를 사용한다. 같은 adId의 응답도 새로운 로드라면 다시 가시 조건과 연속 시간을 확인한다. 한 로드의 반복 show에서는 loadId를 유지한다. SDK가 같은 소재의 재선정을 무효 노출로 판정하지 않는다.

이 로컬 식별값은 서버 제공 건의 유효성을 증명하거나 전역 집계를 보장하지 않는다. [AdMob의 무효 트래픽 설명](https://support.google.com/admob/answer/3342054?hl=en)도 인위적으로 노출을 늘리는 행위를 별도 문제로 다룬다. SDK의 중복 콜백 억제와 서버의 유효성 확인, 반복 요청 남용 대응, 최종 집계와 과금을 구분한다. 서버가 응답했다는 사실만으로 모든 반복 로드의 과금이 인정된다고 해석하지 않는다.

### 반복 표시와 노출 중복, 무효 트래픽

2026-11-25 확인한 [AdMob 배너 가이드](https://developers.google.com/admob/android/next-gen/banner)는 로드 완료와 별개로 onAdImpression 이벤트를 제공한다. [AdMob 무효 트래픽 설명](https://support.google.com/admob/answer/3342054?hl=en)은 광고 비용이나 매체 수익을 인위적으로 부풀리는 클릭과 노출을 무효 트래픽에 포함한다. [공식 FAQ](https://support.google.com/admob/answer/1348754?hl=en)는 전문가와 자동 시스템이 이상 활동을 탐지하고 검토한다고 설명한다. 구체적인 중복 제거 키나 부정 탐지 알고리즘이 공개됐다고 해석하지 않는다.

프로젝트 설계 후보는 세 경계를 분리한다. 표시 명령 결과, 노출 조건을 충족한 논리적 이벤트, 서버가 유효하게 받아 집계한 건수는 각각 다르다. 동일 제공 건의 반복 이벤트를 제거해도 새 제공 건을 계속 만들어 내는 악용까지 해결하지는 못한다. 서버 발급 제공 건의 유효성 확인과 중복 제거도 실제 화면 노출의 증명은 아니므로 별도 검증과 이상 활동 분석이 필요하다. 한 소재당 무조건 한 번이라는 규칙으로 일반화하지 않으며, 제공 건의 집계 단위는 제품 계약으로 정한다.

### 6. 비용·수익 지표

| 지표 | 의미 |
| --- | --- |
| CPM | 노출 1,000회당 비용 |
| CPC | 클릭 1회당 비용 |
| eCPM | 수익을 노출 1,000회 기준으로 환산한 지표 |
| 클릭률·CTR | 클릭 수 / 노출 수 × 100% |
| Match rate | AdMob의 해당 보고서 기준으로 광고 반환 요청 수 / 광고 요청 수 × 100% |
| Show rate | AdMob의 해당 보고서 기준으로 노출 수 / 광고 반환 요청 수 × 100% |

`eCPM = 수익 / 노출 수 × 1,000`

예: 노출 10,000회에서 수익 20,000원을 얻었다면 eCPM은 2,000원이다. eCPM으로 비교한다고 모두 CPM 방식으로 과금되는 것은 아니다. 요청·입찰·낙찰·콜백 발생만으로 수익·정산 완료를 단정하지 않는다.

### 7. 배너 로드와 갱신 결과를 구분하는 플랫폼 사례

2026-11-24 확인한 [Google Mobile Ads Next-Gen 배너 가이드](https://developers.google.com/admob/android/next-gen/banner)는 최초 로드 결과를 받는 AdLoadCallback과 자동 갱신 결과를 받는 BannerAdRefreshCallback을 구분한다. 로드 결과와 광고 노출 이벤트도 별도로 제공한다. 이는 호스트가 최초 로드와 갱신, 노출을 각각 다룰 수 있는 제품 사례다. 이 사실만으로 갱신 실패 시 이전 이미지 보존 정책이나 우리 프로젝트의 show 반환 계약을 단정하지 않는다.

AdMob의 모든 광고 형식이 같은 show API를 제공한다고 일반화하지 않는다. 2026-11-25 확인한 [배너 가이드](https://developers.google.com/admob/android/next-gen/banner)는 레이아웃에 추가한 AdView로 loadAd를 호출하고, [전면 광고 가이드](https://developers.google.com/admob/android/next-gen/interstitial)는 이용 가능한 광고 객체를 얻어 show를 호출하는 흐름을 설명한다. 우리 이미지 배너의 별도 show(listener)와 View 반영 성공 콜백은 프로젝트에서 선택한 계약이다.

## 학습에 참고한 일차 자료

다음 링크는 대화에서 실제 참고한 자료다. 특정 SDK의 최신 API·정책을 구현에 사용할 때는 문서 버전과 변경 사항을 다시 확인한다.

- Google 배너 광고: https://developers.google.com/admob/android/next-gen/banner
- Google RTB 기본 흐름: https://developers.google.com/authorized-buyers/rtb/get-started/start
- RTB 응답과 렌더링 트래킹: https://developers.google.com/authorized-buyers/rtb/get-started/response-guide
- RTB 입찰 필터링: https://developers.google.com/authorized-buyers/rtb/response-guide/bid-filtering
- AdMob 미디에이션: https://support.google.com/admob/answer/13420272?hl=en
- AdMob 비딩 흐름: https://support.google.com/admob/answer/9234488?hl=en
- AdMob 광고 활동 보고서: https://support.google.com/admob/answer/10979428?hl=en
- 조회 가능성·Active View: https://support.google.com/admanager/answer/3154105?hl=en
- 전환 액션: https://support.google.com/google-ads/answer/6032150?hl=en-EN
- 전환 측정 위치와 데이터 소스: https://support.google.com/google-ads/answer/15464305?hl=en
- CPC: https://support.google.com/google-ads/answer/116495?hl=en
- CPM: https://support.google.com/google-ads/answer/6310?hl=en-au
- eCPM: https://support.google.com/admob/answer/15337570
- Google의 프리로드 기능 예: https://developers.google.com/admob/android/next-gen/interstitial
- 수동 사전 로드·형식별 만료 정책 예: https://developers.google.com/admob/android/interstitial
- Android 라이브러리 최적화·R8: https://developer.android.com/topic/performance/app-optimization/library-optimization
- IAB DSP 정의: https://www.iabuk.com/jargon-buster/demand-side-platform-dsp
- IAB SSP·DSP 설명: https://www.iabuk.com/guide-to-data-curation
- IAB 프로그래매틱 광고 입문: https://www.iabuk.com/standards-guidelines/back-basics-guide-programmatic
- Google 앱 오픈 광고의 진입·복귀·콜드 스타트 예: https://developers.google.com/admob/android/app-open
- Android 시스템 SplashScreen: https://developer.android.com/develop/ui/views/launch/splash-screen
- Media3 플레이어 상태·이벤트: https://developer.android.com/media/media3/exoplayer/listening-to-player-events
- Media3 시작·스레드·플레이어 해제: https://developer.android.com/media/media3/exoplayer/hello-world
- Google 네이티브 동영상 이벤트 예: https://developers.google.com/admob/android/native/video-ads
- IAB VAST: https://iabtechlab.com/standards/vast/
- IAB MRAID 소개: https://iabtechlab.com/mraid-3-0-ready-to-play-rich-flawless-ads-in-app-on-your-phone/
- IAB Open Measurement SDK: https://iabtechlab.com/standards/open-measurement-sdk/

## 유지관리 원칙

공식 문서에서 확인한 사실, 특정 제품의 정책, 프로젝트의 가정을 구분한다. 새 학습이나 정정이 있으면 해당 개념과 출처를 갱신하고, 프로젝트 정책 변화는 요구사항·의사결정 기록에도 반영한다.
