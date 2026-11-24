package io.github.junyoungwon.adsdk

/** A single banner request. [serverUrl] is the complete HTTP(S) ad endpoint.
 * 배너의 광고 요청을 담은 객체 */
public data class BannerAdRequest(
    val placementId: String,
    val serverUrl: String
)
