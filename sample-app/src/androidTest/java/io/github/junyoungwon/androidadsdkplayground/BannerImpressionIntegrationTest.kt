package io.github.junyoungwon.androidadsdkplayground

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.junyoungwon.adsdk.BannerAdError
import io.github.junyoungwon.adsdk.BannerAdImpressionListener
import io.github.junyoungwon.adsdk.BannerAdInfo
import io.github.junyoungwon.adsdk.BannerAdListener
import io.github.junyoungwon.adsdk.BannerAdRequest
import io.github.junyoungwon.adsdk.BannerAdShowError
import io.github.junyoungwon.adsdk.BannerAdShowListener
import io.github.junyoungwon.adsdk.BannerAdView
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 실제 BannerAdView에서 클리핑과 창 상태가 노출 알림에 반영되는지 확인한다.
 * 테스트마다 새 View와 로컬 HTTP 서버를 사용하며, 이미지 면적과 스크롤은 viewport로 제어한다.
 * 콜백의 유무와 광고 ID를 검증하며, 최종 화면 픽셀이나 다른 View의 임의 가림을 검증하지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class BannerImpressionIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var banner: BannerAdView
    private lateinit var viewport: FrameLayout
    private var dialog: Dialog? = null
    private val events = LinkedBlockingQueue<BannerAdInfo>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            // Activity가 destroy하는 배너를 그대로 사용하되, 부모의 크기와 스크롤을 직접 제어한다.
            val original = activity.findViewById<FrameLayout>(R.id.banner_container)
            banner = original.getChildAt(0) as BannerAdView
            original.removeView(banner)
            val root = FrameLayout(activity)
            viewport = FrameLayout(activity).apply { clipChildren = true }
            root.addView(viewport, FrameLayout.LayoutParams(dp(320), dp(100)))
            viewport.addView(banner, FrameLayout.LayoutParams(dp(320), dp(100)))
            activity.setContentView(root)
            listen()
        }
        awaitUi("광고 View의 창에 포커스가 있어야 합니다") { banner.hasWindowFocus() && banner.width > 0 }
    }

    @After
    fun tearDown() {
        scenario.onActivity { dialog?.dismiss() }
        scenario.close()
        server.shutdown()
    }

    @Test
    fun 로드만_완료하면_알리지_않고_광고_표시후_노출은_한번만_알린다() {
        load("A")
        assertNoEvent()
        var showReturned = false
        scenario.onActivity {
            banner.setImpressionListener(object : BannerAdImpressionListener {
                override fun onImpression(adInfo: BannerAdInfo) {
                    assertTrue(showReturned)
                    assertSame(Looper.getMainLooper(), Looper.myLooper())
                    events.add(adInfo)
                }
            })
            show()
            showReturned = true
        }
        assertEquals(BannerAdInfo("A", "demo-banner"), nextEvent())
        scenario.onActivity {
            repeat(10) { show() }
            viewport.removeView(banner)
            viewport.addView(banner, FrameLayout.LayoutParams(dp(320), dp(100)))
        }
        assertNoEvent()
    }

    @Test
    fun 이미지_면적_49퍼센트는_제외하고_50퍼센트부터_판정한다() {
        scenario.onActivity { viewport.layoutParams = viewport.layoutParams.apply { height = dp(49) } }
        load("half")
        scenario.onActivity { show() }
        assertNoEvent()
        scenario.onActivity { viewport.layoutParams = viewport.layoutParams.apply { height = dp(50) } }
        assertEquals("half", nextEvent().adId)
    }

    @Test
    fun FIT_CENTER의_빈_여백은_광고_이미지_면적에_포함하지_않는다() {
        scenario.onActivity {
            banner.layoutParams = banner.layoutParams.apply { width = dp(320); height = dp(200) }
            viewport.layoutParams = viewport.layoutParams.apply { width = dp(240); height = dp(120) }
        }
        // 컨테이너 기준은 45%지만 실제 이미지(320x100)는 52.5%가 보인다.
        load("letterbox")
        scenario.onActivity { show() }
        assertEquals("letterbox", nextEvent().adId)
    }

    @Test
    fun 부모가_스크롤되면_이동한_이미지의_가시_면적으로_판정한다() {
        scenario.onActivity { viewport.scrollTo(0, dp(51)) }
        load("scroll")
        scenario.onActivity { show() }
        assertNoEvent()
        scenario.onActivity { viewport.scrollTo(0, dp(50)) }
        assertEquals("scroll", nextEvent().adId)
    }

    @Test
    fun 미부착_상태의_show는_성공해도_노출은_부착후부터_판정한다() {
        load("attach")
        scenario.onActivity { viewport.removeView(banner); show() }
        assertNoEvent()
        scenario.onActivity { viewport.addView(banner, FrameLayout.LayoutParams(dp(320), dp(100))) }
        assertNull(events.poll(250, TimeUnit.MILLISECONDS))
        assertEquals("attach", nextEvent().adId)
    }

    @Test
    fun 상위_View가_완전히_투명하면_판정하지_않는다() {
        scenario.onActivity { viewport.alpha = 0f }
        load("alpha")
        scenario.onActivity { show() }
        assertNoEvent()
        scenario.onActivity { viewport.alpha = 1f }
        assertEquals("alpha", nextEvent().adId)
    }

    @Test
    fun 상위_View의_회전변환을_제거한_뒤에야_노출을_알린다() {
        scenario.onActivity { viewport.rotation = 5f }
        load("rotation")
        scenario.onActivity { show() }
        // 이미지가 일부 보여도 회전변환은 현재 지원 범위에서 제외한다.
        assertNoEvent()

        scenario.onActivity { viewport.rotation = 0f }
        assertEquals("rotation", nextEvent().adId)
    }

    @Test
    fun 상위_View의_배율변환을_제거한_뒤에야_노출을_알린다() {
        scenario.onActivity { viewport.scaleX = 0.8f; viewport.scaleY = 0.8f }
        load("scale")
        scenario.onActivity { show() }
        assertNoEvent()

        scenario.onActivity { viewport.scaleX = 1f; viewport.scaleY = 1f }
        assertEquals("scale", nextEvent().adId)
    }

    @Test
    fun 노출_판정전에_View를_분리하면_재부착한_시점부터_1초를_다시_잰다() {
        load("reattach")
        scenario.onActivity { show() }
        SystemClock.sleep(400)
        scenario.onActivity { viewport.removeView(banner) }
        // 관찰자와 예약 작업을 해제하므로 분리 중에는 알림이 없어야 한다.
        assertNoEvent()

        scenario.onActivity { viewport.addView(banner, FrameLayout.LayoutParams(dp(320), dp(100))) }
        assertNull(events.poll(250, TimeUnit.MILLISECONDS))
        assertEquals("reattach", nextEvent().adId)
    }

    @Test
    fun 숨겼다가_다시_보이면_연속시간을_처음부터_측정한다() {
        load("hidden")
        scenario.onActivity { show() }
        SystemClock.sleep(400)
        scenario.onActivity { banner.visibility = View.INVISIBLE }
        assertNoEvent()
        scenario.onActivity { banner.visibility = View.VISIBLE }
        assertNull(events.poll(250, TimeUnit.MILLISECONDS))
        assertEquals("hidden", nextEvent().adId)
    }

    @Test
    fun 다른_창이_포커스를_가져가면_판정을_멈추고_복귀후_다시_측정한다() {
        load("focus")
        scenario.onActivity { activity ->
            dialog = Dialog(activity).apply {
                setContentView(TextView(activity).apply { text = "포커스 전환 실험" })
                show()
            }
        }
        awaitUi("Dialog가 창 포커스를 가져가야 합니다") { !banner.hasWindowFocus() }
        scenario.onActivity { show() }
        assertNoEvent()
        scenario.onActivity { dialog!!.dismiss() }
        assertEquals("focus", nextEvent().adId)
    }

    @Test
    fun A_광고_표시중_B_로드가_실패해도_A의_관찰을_유지한다() {
        load("A")
        server.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(2, TimeUnit.SECONDS))
        val failed = CountDownLatch(1)
        scenario.onActivity {
            show()
            banner.load(request(), object : BannerAdListener {
                override fun onLoaded() = fail("B는 NO_FILL이어야 합니다")
                override fun onFailed(error: BannerAdError) {
                    assertEquals(BannerAdError.Code.NO_FILL, error.code)
                    failed.countDown()
                }
            })
        }
        assertEquals("A", nextEvent().adId)
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertNoEvent()
        load("C")
        assertNoEvent() // 로드 완료만으로 C를 관찰하지 않는다.
        scenario.onActivity { show() }
        assertEquals("C", nextEvent().adId)
    }

    @Test
    fun 미등록_중의_노출은_리스너_등록과_교체후에도_소급하지_않는다() {
        scenario.onActivity { banner.setImpressionListener(null) }
        load("late")
        scenario.onActivity { show() }
        SystemClock.sleep(1_400)
        scenario.onActivity { listen() }
        assertNoEvent()
        load("new")
        scenario.onActivity { show() }
        assertEquals("new", nextEvent().adId)
        scenario.onActivity { listen(); show() }
        assertNoEvent()
    }

    @Test
    fun 해제하면_대기중인_노출과_등록된_리스너의_추가_알림을_막는다() {
        load("destroy")
        scenario.onActivity { show(); banner.destroy(); listen() }
        assertNoEvent()
        scenario.onActivity { assertNull((banner.getChildAt(0) as ImageView).drawable) }
    }

    @Test
    fun 노출_콜백에서_show와_destroy를_호출해도_중복되지_않는다() {
        load("reentry")
        scenario.onActivity {
            banner.setImpressionListener(object : BannerAdImpressionListener {
                override fun onImpression(adInfo: BannerAdInfo) {
                    events.add(adInfo)
                    show()
                    banner.destroy()
                }
            })
            show()
        }
        assertEquals("reentry", nextEvent().adId)
        assertNoEvent()
    }

    private fun listen() {
        banner.setImpressionListener(object : BannerAdImpressionListener {
            override fun onImpression(adInfo: BannerAdInfo) {
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                events.add(adInfo)
            }
        })
    }

    private fun load(adId: String) {
        server.enqueue(MockResponse().setBody("""{"adId":"$adId","imageUrl":"${url("/banner.png")}"}"""))
        val bitmap = Bitmap.createBitmap(64, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
        val done = CountDownLatch(1)
        var loadError: BannerAdError? = null
        scenario.onActivity {
            banner.load(request(), object : BannerAdListener {
                override fun onLoaded() { done.countDown() }
                override fun onFailed(error: BannerAdError) { loadError = error; done.countDown() }
            })
        }
        assertTrue("광고 로드 결과를 받아야 합니다", done.await(10, TimeUnit.SECONDS))
        assertNull(loadError)
    }

    private fun show() {
        banner.show(object : BannerAdShowListener {
            override fun onShowSuccess(adInfo: BannerAdInfo) = Unit
            override fun onShowFailed(error: BannerAdShowError) = fail(error.toString())
        })
    }

    private fun nextEvent(): BannerAdInfo = events.poll(4, TimeUnit.SECONDS)
        ?: throw AssertionError("4초 안에 노출 조건 충족 알림을 받아야 합니다")

    private fun assertNoEvent() { assertNull("노출 알림이 없어야 합니다", events.poll(1_250, TimeUnit.MILLISECONDS)) }

    private fun awaitUi(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 4_000
        do {
            var ready = false
            scenario.onActivity { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        fail(message)
    }

    private fun dp(value: Int): Int = (value * banner.resources.displayMetrics.density).toInt()
    private fun url(path: String) = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(server.port).encodedPath(path).build()
    private fun request() = BannerAdRequest("demo-banner", url("/ads").toString())
}
