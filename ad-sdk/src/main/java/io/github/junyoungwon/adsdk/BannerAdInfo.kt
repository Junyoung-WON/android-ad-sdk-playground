package io.github.junyoungwon.adsdk

/**
 * View에 반영한 광고의 서버 식별 정보. 실제 노출이나 과금 결과를 뜻하지 않는다.
 * adId는 서버 응답값을 그대로 전달하며, 서로 다른 로드에서 같은 값이 반환될 수 있다.
 * SDK 내부의 로드 식별값과는 구분한다.
 */
public data class BannerAdInfo(
    val adId: String,
    val placementId: String
)
