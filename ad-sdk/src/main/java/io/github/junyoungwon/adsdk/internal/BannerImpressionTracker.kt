package io.github.junyoungwon.adsdk.internal

import io.github.junyoungwon.adsdk.BannerAdInfo

/**
 * 한 BannerAdView에서 현재 광고가 노출 조건을 1초동안 연속으로 충족했는지 판단하고,
 * adId별로 노출 조건을 최초 충족했을 때만 광고 정보를 반환하도록 판정 이력을 보관한다.
 * adId는 서버가 광고 응답마다 발급하는 고유 식별값이며, 같은 소재라도 새로 제공한 광고는 다른 값을 갖는다.
 *
 * BannerAdView가 광고를 이미지에 반영하면 [display]로 광고 정보를 전달한다.
 * 이후 View가 가시 면적과 창 상태를 확인해 [observe]에 조건 충족 여부를 전달한다.
 * 이 클래스가 새 노출에 해당하는 광고 정보를 반환하면, View가 등록된 노출 리스너를 호출한다.
 * 이미지 반영과 가시성 검사, 다음 검사 예약과 리스너 호출은 BannerAdView의 역할이다.
 *
 * 이 클래스가 보관하는 상태는 현재 광고 정보와 연속 시간의 시작 시점,
 * 이미 노출 조건 충족으로 판정한 adId다. SDK에서 별도의 광고 식별값을 발급하지 않는다.
 * 최초 1초 충족 여부를 판단하며, 그 이후 광고가 총 몇 초 보였는지는 집계하지 않는다.
 * 한 View에 한 인스턴스를 사용하고, 모든 메서드는 메인 스레드에서 호출한다.
 *
 * @param nowMillis 경과 시간을 밀리초로 읽는 함수. 실행 중에는 uptimeMillis처럼 시간이
 * 뒤로 가지 않는 시계를, 테스트에서는 직접 시간을 바꿀 수 있는 가짜 시계를 전달한다.
 */
internal class BannerImpressionTracker(private val nowMillis: () -> Long) {
    // 현재 View에 반영된 광고. 새로 로드 중이거나 show 전인 광고와 구분한다.
    private var displayed: BannerAdInfo? = null
    // 현재 광고가 조건을 연속으로 충족하기 시작한 시각. 측정 중이 아니면 null이다.
    private var eligibleSince: Long? = null
    // 한 View에서 같은 adId의 정보를 노출 조건 충족 결과로 두 번 반환하지 않는다.
    private val qualifiedAdIds = mutableSetOf<String>()
    private var destroyed = false

    /**
     * View에 반영된 광고를 판정 대상으로 등록한다. 실제 이미지 반영은 호출한 View가 담당한다.
     *
     * 다른 adId로 바뀌면 연속 시간을 새로 측정한다.
     * 같은 adId를 다시 등록하면 객체나 placementId가 달라도 진행 중인 시간을 유지한다.
     * 이미 노출로 판정한 adId 이력은 광고 교체 후에도 유지한다.
     * 시간 측정은 이후 [observe]에 true가 전달될 때 시작하며, 해제 후 호출은 무시한다.
     *
     * @param adInfo View에 반영한 광고 정보. 서버가 발급한 adId로 광고 교체와 중복 여부를 판단한다.
     */
    fun display(adInfo: BannerAdInfo) {
        if (destroyed) return
        if (displayed?.adId != adInfo.adId) eligibleSince = null
        displayed = adInfo
    }

    /**
     * View가 지금 확인한 조건 충족 여부를 반영하고, 이번 호출에서 최초 노출 조건 충족에 해당하는 광고 정보를 반환할지 판단한다.
     *
     * true가 처음 전달되면 현재 시각부터 시간을 잰다. 이후에도 조건이 유지되고
     * 시작 시점부터 1초 이상 지났으면 현재 광고 정보를 한 번 반환한다.
     * false가 전달되면 지금까지의 연속 시간을 버리고, 다음 true에서 처음부터 다시 잰다.
     *
     * 광고 정보를 반환하기 전에 노출 이력을 기록하므로, 호출자가 리스너 안에서
     * 같은 adId의 광고를 다시 등록해도 중복 반환하지 않는다. 리스너가 없어도 이 이력은 유지된다.
     *
     * @param eligible View가 확인한 가시 면적과 창 상태 등의 조건을 지금 충족하는지 여부.
     * 1초 경과 여부는 이 메서드가 판단하므로 입력에 포함하지 않는다.
     * @return 처음으로 1초 조건을 충족한 광고 정보. 아직 1초가 안 됐거나 조건 미충족,
     * 광고 미등록, 이미 판정한 광고, 해제된 상태라면 null. null은 이번 호출에서 새 판정 결과가 없다는 뜻이며 실패 결과가 아니다.
     */
    fun observe(eligible: Boolean): BannerAdInfo? {
        if (destroyed) return null

        val ad = displayed ?: return null
        if (!eligible || ad.adId in qualifiedAdIds) {
            eligibleSince = null
            return null
        }

        val now = nowMillis()
        val since = eligibleSince ?: now.also { eligibleSince = it }
        if (now - since < REQUIRED_MILLIS) return null
        // View가 콜백을 호출하기 전에 판정 이력을 기록해 같은 adId의 정보를 다시 반환하지 않는다.
        qualifiedAdIds.add(ad.adId)
        eligibleSince = null
        return ad
    }

    /**
     * 진행 중인 연속 시간 측정에서 1초를 채우기까지 남은 밀리초를 조회한다.
     *
     * 이미 1초가 지났지만 아직 [observe]로 판정하지 않았다면 0, 측정 중이 아니면 null이다.
     * 이 조회는 노출을 판정하거나 시간을 초기화하지 않는다.
     * View는 반환된 시간 뒤에 검사를 예약하고, 그때 조건을 다시 확인해 [observe]를 호출한다.
     */
    fun remainingMillis(): Long? = eligibleSince?.let {
        (REQUIRED_MILLIS - (nowMillis() - it)).coerceAtLeast(0L)
    }

    /**
     * View가 화면에서 분리되는 경우처럼 관찰을 중단할 때 진행 중인 연속 시간을 버린다.
     * 현재 광고와 이미 노출로 판정한 adId 이력은 유지한다.
     * 다시 [observe]에 true를 전달하면 그 시점부터 1초를 새로 잰다.
     * View에 예약된 검사 작업의 취소는 호출한 View가 담당한다.
     */
    fun stopObserving() {
        eligibleSince = null
    }

    /**
     * 이 인스턴스의 사용을 끝내고 광고 정보, 연속 시간, adId 이력을 정리한다.
     * 이후 [display]는 무시하고 [observe]는 항상 null을 반환하므로 다시 사용할 수 없다.
     * 반복 호출은 안전하다. View의 관찰자와 예약 작업, 리스너 참조는 View에서 별도로 정리한다.
     */
    fun destroy() {
        destroyed = true
        displayed = null
        eligibleSince = null
        qualifiedAdIds.clear()
    }

    private companion object {
        const val REQUIRED_MILLIS = 1_000L
    }
}
