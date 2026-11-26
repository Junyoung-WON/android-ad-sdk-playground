package io.github.junyoungwon.adsdk.internal

import io.github.junyoungwon.adsdk.BannerAdInfo
import org.junit.Assert.*
import org.junit.Test

/**
 * View가 알려준 노출 조건과 경과 시간으로 노출 알림 여부를 판단하는 테스트다.
 * observe(eligible = true)는 View가 가시 면적과 창 상태 등의 조건을 충족했다고 알려준다는 뜻이다.
 * observe가 광고 정보를 반환하면 새 노출 알림이 있고, null이면 알림이 없다.
 * 실제로 기다리지 않고 nowMillis를 바꿔 시간을 진행한다. 실제 View나 리스너 콜백은 이 테스트에서 다루지 않는다.
 */
class BannerImpressionTrackerTest {
    private val adA = BannerAdInfo("A", "banner")
    private val adB = BannerAdInfo("B", "banner")

    @Test
    fun 생성_직후_광고를_등록하지_않으면_노출_측정을_시작하지_않는다() {
        // 생성 직후이며 display()를 호출하지 않아 등록된 광고가 없다.
        val tracker = BannerImpressionTracker { 0L }

        // 광고 없이 true가 전달되는 입력에도 안전하게 대응하는지 확인한다.
        val event = tracker.observe(eligible = true)
        val remainingMillis = tracker.remainingMillis()

        assertNull(event)
        assertNull(remainingMillis)
    }

    @Test
    fun 노출_조건을_999ms_유지하면_알리지_않고_1000ms_유지하면_알린다() {
        var nowMillis = 0L
        val tracker = BannerImpressionTracker { nowMillis }
        tracker.display(adA)

        // A가 노출 조건을 충족하기 시작했다. 아직 1초를 채우지 않았다.
        assertNull(tracker.observe(eligible = true))
        assertEquals(1_000L, tracker.remainingMillis())

        nowMillis = 999L
        assertNull(tracker.observe(eligible = true))
        assertEquals(1L, tracker.remainingMillis())

        nowMillis = 1_000L
        assertEquals(adA, tracker.observe(eligible = true))
        assertNull(tracker.remainingMillis())
    }

    @Test
    fun 이미_노출을_알린_A가_계속_보여도_A의_노출을_다시_알리지_않는다() {
        var nowMillis = 0L
        val tracker = BannerImpressionTracker { nowMillis }
        tracker.display(adA)
        tracker.observe(eligible = true)

        nowMillis = 1_000L
        assertEquals(adA, tracker.observe(eligible = true))

        // 최초 알림 이후에도 조건을 계속 충족하지만 추가 알림은 없다.
        nowMillis = 2_000L
        assertNull(tracker.observe(eligible = true))
        nowMillis = 5_000L
        assertNull(tracker.observe(eligible = true))
        assertNull(tracker.remainingMillis())
    }

    @Test
    fun 노출_조건이_끊기면_다시_충족한_시점부터_1초후에_알린다() {
        var nowMillis = 0L
        val tracker = BannerImpressionTracker { nowMillis }
        tracker.display(adA)
        tracker.observe(eligible = true)

        // 700ms 만에 조건이 끊겼으므로, 이 시간은 다음 구간에 더하지 않는다.
        nowMillis = 700L
        assertNull(tracker.observe(eligible = false))
        assertNull(tracker.remainingMillis())

        nowMillis = 1_000L
        assertNull(tracker.observe(eligible = true))

        // 이전 700ms와 이번 400ms를 합하면 1초가 넘지만, 연속으로는 400ms다.
        nowMillis = 1_400L
        assertNull(tracker.observe(eligible = true))
        assertEquals(600L, tracker.remainingMillis())

        nowMillis = 2_000L
        assertEquals(adA, tracker.observe(eligible = true))
    }

    @Test
    fun A가_700ms_보인_뒤_같은_A를_다시설정하면_300ms_후_A의_노출을_알린다() {
        var nowMillis = 0L
        val tracker = BannerImpressionTracker { nowMillis }
        tracker.display(adA)
        tracker.observe(eligible = true)

        // 새 객체라도 adId가 같으면 같은 광고다. 이미 지난 700ms를 유지한다.
        nowMillis = 700L
        tracker.display(adA.copy())
        assertNull(tracker.observe(eligible = true))
        assertEquals(300L, tracker.remainingMillis())

        nowMillis = 1_000L
        assertEquals(adA, tracker.observe(eligible = true))
    }

    @Test
    fun A를_700ms_보여준_뒤_B로_바꾸면_B를_1초_보여준_후_B를_알린다() {
        var nowMillis = 0L
        val tracker = BannerImpressionTracker { nowMillis }
        tracker.display(adA)
        tracker.observe(eligible = true)

        nowMillis = 700L
        tracker.display(adB)
        assertNull(tracker.observe(eligible = true))
        assertEquals(1_000L, tracker.remainingMillis())

        // A를 포함하면 1초지만 B는 300ms만 보였다. A나 B의 알림이 나오면 안 된다.
        nowMillis = 1_000L
        assertNull(tracker.observe(eligible = true))

        nowMillis = 1_700L
        assertEquals(adB, tracker.observe(eligible = true))
    }

    @Test
    fun A와_B의_노출을_각각_알린_후_A로_돌아가면_A를_다시_알리지_않는다() {
        var nowMillis = 0L
        val tracker = BannerImpressionTracker { nowMillis }

        tracker.display(adA)
        tracker.observe(eligible = true)
        nowMillis = 1_000L
        assertEquals(adA, tracker.observe(eligible = true))

        tracker.display(adB)
        tracker.observe(eligible = true)
        nowMillis = 2_000L
        assertEquals(adB, tracker.observe(eligible = true))

        // B로 교체했어도 A의 알림 이력은 남아 있다.
        tracker.display(adA.copy())
        assertNull(tracker.observe(eligible = true))
        nowMillis = 7_000L
        assertNull(tracker.observe(eligible = true))
    }

    @Test
    fun 노출_관찰을_중지했다_재개하면_재개한_시점부터_1초후에_알린다() {
        var nowMillis = 0L
        val tracker = BannerImpressionTracker { nowMillis }
        tracker.display(adA)
        tracker.observe(eligible = true)

        // View가 화면에서 분리되는 경우처럼 관찰을 명시적으로 중지한다.
        nowMillis = 700L
        tracker.stopObserving()
        assertNull(tracker.remainingMillis())

        // 중지 전 700ms와 관찰하지 않은 시간은 포함하지 않는다.
        nowMillis = 5_000L
        assertNull(tracker.observe(eligible = true))
        assertEquals(1_000L, tracker.remainingMillis())

        nowMillis = 5_999L
        assertNull(tracker.observe(eligible = true))
        nowMillis = 6_000L
        assertEquals(adA, tracker.observe(eligible = true))
    }

    @Test
    fun destroy후에는_시간이_지나거나_B를_설정해도_노출을_알리지_않는다() {
        var nowMillis = 0L
        val tracker = BannerImpressionTracker { nowMillis }
        tracker.display(adA)
        tracker.observe(eligible = true)
        tracker.destroy()
        tracker.destroy() // 반복 해제도 안전하다.

        // 해제 전에 관찰하던 A의 늦은 알림을 차단한다.
        nowMillis = 5_000L
        assertNull(tracker.observe(eligible = true))
        assertNull(tracker.remainingMillis())

        // 해제된 tracker에 B를 설정해도 관찰을 다시 시작하지 않는다.
        tracker.display(adB)
        assertNull(tracker.observe(eligible = true))
        nowMillis = 6_000L
        assertNull(tracker.observe(eligible = true))
        assertNull(tracker.remainingMillis())
    }
}
