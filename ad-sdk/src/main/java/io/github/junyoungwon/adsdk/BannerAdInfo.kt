package io.github.junyoungwon.adsdk

/** View에 반영한 광고의 식별 정보. 실제 노출이나 과금 결과를 뜻하지 않는다. */
public data class BannerAdInfo(
    val adId: String,
    val placementId: String
)
