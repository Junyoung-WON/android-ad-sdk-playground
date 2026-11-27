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
 * 실제 BannerAdView에서 클리핑과 창 상태에 따라 onImpression 콜백이 호출되는지 확인한다.
 * 테스트마다 새 View와 로컬 HTTP 서버를 사용하며, 이미지 면적과 스크롤은 viewport로 제어한다.
 * 리스너의 onImpression 호출에서 수신한 광고 정보를 큐에 저장해 콜백의 유무와 광고 ID를 검증하며, 최종 화면 픽셀이나 다른 View의 임의 가림을 검증하지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class BannerImpressionIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var banner: BannerAdView
    private lateinit var viewport: FrameLayout
    private var dialog: Dialog? = null
    private val impressionCallbackResults = LinkedBlockingQueue<BannerAdInfo>()

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
    fun 로드만_완료하면_onImpression_호출이_없고_show후_조건을_충족하면_한번_호출된다() {
        load("A")
        assertNoImpressionCallback()
        var showReturned = false
        scenario.onActivity {
            banner.setImpressionListener(object : BannerAdImpressionListener {
                override fun onImpression(adInfo: BannerAdInfo) {
                    assertTrue(showReturned)
                    assertSame(Looper.getMainLooper(), Looper.myLooper())
                    impressionCallbackResults.add(adInfo)
                }
            })
            show()
            showReturned = true
        }
        assertEquals(BannerAdInfo("A", "demo-banner"), awaitImpressionCallback())
        scenario.onActivity {
            repeat(10) { show() }
            viewport.removeView(banner)
            viewport.addView(banner, FrameLayout.LayoutParams(dp(320), dp(100)))
        }
        assertNoImpressionCallback()
    }

    @Test
    fun 같은_adId와_이미지라도_새로_로드해_show하면_onImpression을_다시_호출한다() {
        load("same-ad")
        scenario.onActivity { show() }
        val first = awaitImpressionCallback()

        // 서버가 같은 ID와 같은 PNG를 다시 반환해도 별도로 로드 완료된 광고다.
        load("same-ad")
        assertNoImpressionCallback() // 새 광고를 아직 show하지 않았으므로 onImpression을 추가 호출하지 않는다.
        scenario.onActivity { show() }
        val second = awaitImpressionCallback()
        assertEquals(first, second) // 메타데이터는 같아도 두 번의 로드에 각각 onImpression이 호출돼야 한다.

        scenario.onActivity { repeat(5) { show() } }
        assertNoImpressionCallback() // 새 load 없이 반복 show하면 onImpression을 추가 호출하지 않는다.
    }

    @Test
    fun 이미지_면적_49퍼센트에서는_콜백이_없고_50퍼센트가_1초_유지되면_onImpression을_호출한다() {
        scenario.onActivity { viewport.layoutParams = viewport.layoutParams.apply { height = dp(49) } }
        load("half")
        scenario.onActivity { show() }
        assertNoImpressionCallback()
        scenario.onActivity { viewport.layoutParams = viewport.layoutParams.apply { height = dp(50) } }
        assertEquals("half", awaitImpressionCallback().adId)
    }

    @Test
    fun FIT_CENTER_여백을_제외한_이미지_면적이_기준을_충족하면_onImpression을_호출한다() {
        scenario.onActivity {
            banner.layoutParams = banner.layoutParams.apply { width = dp(320); height = dp(200) }
            viewport.layoutParams = viewport.layoutParams.apply { width = dp(240); height = dp(120) }
        }
        // 컨테이너 기준은 45%지만 실제 이미지(320x100)는 52.5%가 보인다.
        load("letterbox")
        scenario.onActivity { show() }
        assertEquals("letterbox", awaitImpressionCallback().adId)
    }

    @Test
    fun 스크롤로_이미지_가시_면적을_49퍼센트에서_50퍼센트로_늘리면_onImpression을_호출한다() {
        scenario.onActivity { viewport.scrollTo(0, dp(51)) }
        load("scroll")
        scenario.onActivity { show() }
        assertNoImpressionCallback()
        scenario.onActivity { viewport.scrollTo(0, dp(50)) }
        assertEquals("scroll", awaitImpressionCallback().adId)
    }

    @Test
    fun 미부착_상태의_show는_성공해도_onImpression은_부착후_조건을_충족해야_호출된다() {
        load("attach")
        scenario.onActivity { viewport.removeView(banner); show() }
        assertNoImpressionCallback()
        scenario.onActivity { viewport.addView(banner, FrameLayout.LayoutParams(dp(320), dp(100))) }
        assertNull(impressionCallbackResults.poll(250, TimeUnit.MILLISECONDS))
        assertEquals("attach", awaitImpressionCallback().adId)
    }

    @Test
    fun 상위_View가_완전히_투명하면_onImpression을_호출하지_않는다() {
        scenario.onActivity { viewport.alpha = 0f }
        load("alpha")
        scenario.onActivity { show() }
        assertNoImpressionCallback()
        scenario.onActivity { viewport.alpha = 1f }
        assertEquals("alpha", awaitImpressionCallback().adId)
    }

    @Test
    fun 상위_View의_회전변환을_제거한_뒤에야_onImpression을_호출한다() {
        scenario.onActivity { viewport.rotation = 5f }
        load("rotation")
        scenario.onActivity { show() }
        // 이미지가 일부 보여도 회전변환은 현재 지원 범위에서 제외한다.
        assertNoImpressionCallback()

        scenario.onActivity { viewport.rotation = 0f }
        assertEquals("rotation", awaitImpressionCallback().adId)
    }

    @Test
    fun 상위_View의_배율변환을_제거한_뒤에야_onImpression을_호출한다() {
        scenario.onActivity { viewport.scaleX = 0.8f; viewport.scaleY = 0.8f }
        load("scale")
        scenario.onActivity { show() }
        assertNoImpressionCallback()

        scenario.onActivity { viewport.scaleX = 1f; viewport.scaleY = 1f }
        assertEquals("scale", awaitImpressionCallback().adId)
    }

    @Test
    fun View를_분리하면_재부착후_1초를_다시_채워야_onImpression을_호출한다() {
        load("reattach")
        scenario.onActivity { show() }
        SystemClock.sleep(400)
        scenario.onActivity { viewport.removeView(banner) }
        // 관찰자와 예약 작업을 해제하므로 분리 중에는 onImpression 호출이 없어야 한다.
        assertNoImpressionCallback()

        scenario.onActivity { viewport.addView(banner, FrameLayout.LayoutParams(dp(320), dp(100))) }
        assertNull(impressionCallbackResults.poll(250, TimeUnit.MILLISECONDS))
        assertEquals("reattach", awaitImpressionCallback().adId)
    }

    @Test
    fun 숨김후_복귀한_광고는_연속_1초를_다시_채워야_onImpression을_호출한다() {
        load("hidden")
        scenario.onActivity { show() }
        SystemClock.sleep(400)
        scenario.onActivity { banner.visibility = View.INVISIBLE }
        assertNoImpressionCallback()
        scenario.onActivity { banner.visibility = View.VISIBLE }
        assertNull(impressionCallbackResults.poll(250, TimeUnit.MILLISECONDS))
        assertEquals("hidden", awaitImpressionCallback().adId)
    }

    @Test
    fun 창_포커스를_잃으면_콜백이_없고_복귀후_조건을_충족하면_onImpression을_호출한다() {
        load("focus")
        scenario.onActivity { activity ->
            dialog = Dialog(activity).apply {
                setContentView(TextView(activity).apply { text = "포커스 전환 실험" })
                show()
            }
        }
        awaitUi("Dialog가 창 포커스를 가져가야 합니다") { !banner.hasWindowFocus() }
        scenario.onActivity { show() }
        assertNoImpressionCallback()
        scenario.onActivity { dialog!!.dismiss() }
        assertEquals("focus", awaitImpressionCallback().adId)
    }

    @Test
    fun A_광고_표시중_B_로드가_실패해도_onImpression은_A의_정보를_전달한다() {
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
        assertEquals("A", awaitImpressionCallback().adId)
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertNoImpressionCallback()
        load("C")
        assertNoImpressionCallback() // 로드 완료만으로 C를 관찰하지 않는다.
        scenario.onActivity { show() }
        assertEquals("C", awaitImpressionCallback().adId)
    }

    @Test
    fun 리스너없이_조건을_충족한_광고는_나중에_등록해도_onImpression을_호출하지_않는다() {
        scenario.onActivity { banner.setImpressionListener(null) }
        load("late")
        scenario.onActivity { show() }
        SystemClock.sleep(1_400)
        scenario.onActivity { listen() }
        assertNoImpressionCallback()
        load("new")
        scenario.onActivity { show() }
        assertEquals("new", awaitImpressionCallback().adId)
        scenario.onActivity { listen(); show() }
        assertNoImpressionCallback()
    }

    @Test
    fun destroy_후에는_onImpression_콜백을_호출하지_않는다() {
        load("destroy")
        scenario.onActivity { show(); banner.destroy(); listen() }
        assertNoImpressionCallback()
        scenario.onActivity { assertNull((banner.getChildAt(0) as ImageView).drawable) }
    }

    @Test
    fun onImpression_안에서_show와_destroy를_호출해도_콜백을_중복_호출하지_않는다() {
        load("reentry")
        scenario.onActivity {
            banner.setImpressionListener(object : BannerAdImpressionListener {
                override fun onImpression(adInfo: BannerAdInfo) {
                    impressionCallbackResults.add(adInfo)
                    show()
                    banner.destroy()
                }
            })
            show()
        }
        assertEquals("reentry", awaitImpressionCallback().adId)
        assertNoImpressionCallback()
    }

    private fun listen() {
        banner.setImpressionListener(object : BannerAdImpressionListener {
            override fun onImpression(adInfo: BannerAdInfo) {
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                impressionCallbackResults.add(adInfo)
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

    /** onImpression이 호출될 때 큐에 저장한 광고 정보를 기다린다. */
    private fun awaitImpressionCallback(): BannerAdInfo = impressionCallbackResults.poll(4, TimeUnit.SECONDS)
        ?: throw AssertionError("4초 안에 onImpression 콜백에서 광고 정보를 수신해야 합니다")

    private fun assertNoImpressionCallback() { assertNull("onImpression 콜백 호출이 없어야 합니다", impressionCallbackResults.poll(1_250, TimeUnit.MILLISECONDS)) }

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
