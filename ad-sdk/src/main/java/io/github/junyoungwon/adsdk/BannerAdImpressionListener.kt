package io.github.junyoungwon.adsdk

/**
 * [BannerAdView.setImpressionListener]에 등록한 View의 광고가 노출 조건을 처음 충족했을 때 호출하는 콜백을 정의한다.
 * 콜백은 메인 스레드에서 호출하며, 한 번 로드 완료된 광고에 한 번만 전달한다.
 * 서버 adId가 같아도 새로 로드해 View에 반영한 광고라면 onImpression 콜백을 별도로 호출한다.
 *
 * 광고 교체 후에도 등록을 유지한다. View에 null을 등록하거나 destroy하면 SDK의 참조를 정리한다.
 * 리스너 없이 조건을 충족한 과거 이벤트는 나중에 등록해도 소급하지 않는다.
 * onImpression 호출은 프로젝트의 가시 면적과 연속 시간 조건을 충족했다는 뜻이며 서버 집계나 과금 완료가 아니다.
 */
public interface BannerAdImpressionListener {
    /** @param adInfo 노출 조건을 충족한 광고의 adId와 placementId. 총 가시 시간은 포함하지 않는다. */
    public fun onImpression(adInfo: BannerAdInfo)
}
