package io.github.junyoungwon.adsdk

/**
 * View에 반영한 광고의 서버 식별 정보. 실제 노출이나 과금 결과를 뜻하지 않는다.
 * adId는 서버가 광고 응답마다 발급하는 고유 식별값이다. 소재가 같아도 새로 제공한 광고는 다른 값을 갖는다.
 * SDK는 응답값을 그대로 전달하며, 한 View 안에서 같은 adId의 노출 콜백 중복을 억제한다.
 */
data class BannerAdInfo(
    val adId: String,
    val placementId: String
)
