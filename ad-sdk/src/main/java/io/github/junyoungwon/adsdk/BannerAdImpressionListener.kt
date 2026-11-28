package io.github.junyoungwon.adsdk

/**
 * [BannerAdView.setImpressionListener]에 등록한 View의 광고가 노출 조건을 처음 충족했을 때 호출하는 콜백을 정의한다.
 * 콜백은 메인 스레드에서 호출하며, 같은 View에서 adId별로 한 번만 전달한다.
 * 같은 소재라도 새 adId로 제공된 광고는 별도로 판정한다. 같은 adId의 재로드로 콜백을 중복 호출하지 않는다.
 *
 * 광고 교체 후에도 등록을 유지한다. View에 null을 등록하거나 destroy하면 SDK의 참조를 정리한다.
 * 리스너 없이 조건을 충족한 과거 이벤트는 나중에 등록해도 소급하지 않는다.
 * onImpression 호출은 프로젝트의 가시 면적과 연속 시간 조건을 충족했다는 뜻이며 서버 집계나 과금 완료가 아니다.
 */
public interface BannerAdImpressionListener {
    /** @param adInfo 노출 조건을 충족한 광고의 adId와 placementId. 총 가시 시간은 포함하지 않는다. */
    public fun onImpression(adInfo: BannerAdInfo)
}
