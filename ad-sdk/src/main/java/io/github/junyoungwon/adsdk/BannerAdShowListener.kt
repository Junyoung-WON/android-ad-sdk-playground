package io.github.junyoungwon.adsdk

/**
 * 이번 show 호출의 광고 표시 성공 또는 실패를 메인 스레드에서 전달받는다.
 * 콜백은 show가 반환되기 전에 한 번 호출된다. SDK는 이 리스너를 멤버 변수에 저장하거나
 * 이후의 광고 표시·노출 이벤트 구독자로 등록하지 않는다. 다음 show 호출에도 리스너를 전달해야 한다.
 */
public interface BannerAdShowListener {
    /** 마지막 load에서 로드 완료된 광고의 이미지를 View에 반영했다. 실제 가시성이나 노출을 보장하지 않는다. */
    public fun onShowSuccess(adInfo: BannerAdInfo)

    /** 이번 호출에서 가장 최근에 로드 완료된 광고를 반영하지 못했다. 기존 광고 표시는 유지한다. */
    public fun onShowFailed(error: BannerAdShowError)
}
