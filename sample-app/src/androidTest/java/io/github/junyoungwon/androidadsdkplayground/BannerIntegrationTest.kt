package io.github.junyoungwon.androidadsdkplayground

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.junyoungwon.adsdk.BannerAdError
import io.github.junyoungwon.adsdk.BannerAdListener
import io.github.junyoungwon.adsdk.BannerAdRequest
import io.github.junyoungwon.adsdk.BannerAdView
import io.github.junyoungwon.adsdk.BannerAdInfo
import io.github.junyoungwon.adsdk.BannerAdShowError
import io.github.junyoungwon.adsdk.BannerAdShowListener
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.HttpUrl
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 로컬 HTTP·실제 Android 이미지 디코딩·샘플 호스트 안의 SDK View를 연결해 검증한다. */
@RunWith(AndroidJUnit4::class)
class BannerIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        server.shutdown()
    }

    @Test
    fun 준비된_이미지는_show로_반영하고_콜백은_메인에서_전달한다() {
        enqueueBanner()
        val listener = ResultListener()
        scenario.onActivity { activity ->
            val banner = activity.banner()
            banner.assertShowFailure()
            banner.load(request(), listener)
        }
        listener.await()
        assertEquals(listener.error.get().toString(), 1, listener.loads.get())
        assertNull(listener.error.get())
        assertSame(Looper.getMainLooper(), listener.callbackLooper.get())
        scenario.onActivity { activity ->
            val banner = activity.banner()
            val image = banner.getChildAt(0) as ImageView
            assertNull(image.drawable)
            banner.assertShowSuccess()
            assertNotNull(image.drawable)
            banner.assertShowSuccess()
        }
        assertEquals(1, listener.loads.get())
        assertEquals("demo-banner", server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("placementId"))
        assertEquals("/banner.png", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun HTTP_1_0_응답_뒤_연결이_끊겨도_이미지를_준비한다() {
        enqueueBanner(closeMetadataConnection = true)
        val listener = ResultListener()
        scenario.onActivity { it.banner().load(request(), listener) }
        listener.await()
        assertEquals(listener.error.get().toString(), 1, listener.loads.get())
        scenario.onActivity { it.banner().assertShowSuccess() }
    }

    @Test
    fun 이미지_디코딩이_실패하면_성공_알림과_이미지_반영이_없다() {
        server.enqueue(MockResponse().setBody(metadata()))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("not an image"))
        val listener = ResultListener()
        scenario.onActivity { it.banner().load(request(), listener) }
        listener.await()

        assertEquals(0, listener.loads.get())
        assertEquals(BannerAdError.Code.IMAGE_LOAD, listener.error.get()!!.code)
        scenario.onActivity {
            it.banner().assertShowFailure()
            assertNull((it.banner().getChildAt(0) as ImageView).drawable)
        }
    }

    @Test
    fun 화면_재생성은_진행_중인_로드를_해제하고_새_배너를_만든다() {
        val firstRequestEntered = CountDownLatch(1)
        val releaseFirstResponse = CountDownLatch(1)
        val metadataCalls = AtomicInteger()
        val imageBytes = pngBytes()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl!!.encodedPath == "/banner.png") {
                    return MockResponse().setHeader("Content-Type", "image/png")
                        .setBody(Buffer().write(imageBytes))
                }
                if (metadataCalls.incrementAndGet() == 1) {
                    firstRequestEntered.countDown()
                    if (!releaseFirstResponse.await(10, TimeUnit.SECONDS)) {
                        return MockResponse().setResponseCode(503)
                    }
                }
                return MockResponse().setBody(metadata())
            }
        }
        val oldActivity = AtomicReference<MainActivity>()
        val oldBanner = AtomicReference<BannerAdView>()
        val oldListener = ResultListener()
        val newListener = ResultListener()
        try {
            // 실제 시간 지연 대신 서버 응답을 막아 재생성 시점까지 로딩 상태를 유지한다.
            scenario.onActivity {
                oldActivity.set(it)
                oldBanner.set(it.banner())
                it.findViewById<SeekBar>(R.id.response_delay).progress = 3
                it.banner().load(request(), oldListener)
            }
            assertTrue("첫 요청이 서버에 도착하지 않았습니다", firstRequestEntered.await(3, TimeUnit.SECONDS))
            // Activity 재생성을 검증한다. 실제 기기의 가로·세로 회전 조작과는 구분한다.
            scenario.recreate()
            assertTrue(oldActivity.get().isDestroyed)
            releaseFirstResponse.countDown()
            scenario.onActivity {
                assertNotSame(oldBanner.get(), it.banner())
                assertEquals(3, it.findViewById<SeekBar>(R.id.response_delay).progress)
                assertEquals(it.getString(R.string.sample_status), it.findViewById<TextView>(R.id.sample_status).text.toString())
                it.banner().assertShowFailure()
                // 이전 View의 load는 무시되고, 새 View만 정상적으로 요청할 수 있어야 한다.
                oldBanner.get().load(request(), oldListener)
                it.banner().load(request(), newListener)
            }
            newListener.await()
            assertEquals(newListener.error.get().toString(), 1, newListener.loads.get())
            scenario.onActivity { it.banner().assertShowSuccess() }
            assertFalse("종료된 화면의 리스너에 결과가 전달됐습니다", oldListener.receivedWithin(500))
            assertEquals(2, metadataCalls.get())
        } finally {
            releaseFirstResponse.countDown()
        }
    }

    @Test
    fun 재로드_중에는_기존_이미지를_유지하고_준비된_새_이미지는_show에서_교체한다() {
        enqueueBanner(adId = "first-ad")
        val first = ResultListener()
        scenario.onActivity { it.banner().load(request(), first) }
        first.await()
        val displayed = AtomicReference<Drawable>()
        scenario.onActivity {
            assertEquals("first-ad", it.banner().assertShowSuccess().adId)
            displayed.set((it.banner().getChildAt(0) as ImageView).drawable)
        }

        enqueueBanner(color = Color.RED, adId = "second-ad")
        val second = ResultListener()
        scenario.onActivity {
            it.banner().load(request(), second)
            // 메인 스레드를 반환하기 전이므로 새 로드 결과는 아직 전달될 수 없다.
            it.banner().assertShowFailure(BannerAdShowError.Code.LOADING)
            assertSame(displayed.get(), (it.banner().getChildAt(0) as ImageView).drawable)
        }
        second.await()
        assertEquals(second.error.get().toString(), 1, second.loads.get())
        scenario.onActivity {
            val image = it.banner().getChildAt(0) as ImageView
            assertSame(displayed.get(), image.drawable)
            val info = it.banner().assertShowSuccess()
            assertEquals(BannerAdInfo("second-ad", "demo-banner"), info)
            assertEquals(info, it.banner().assertShowSuccess())
            assertNotSame(displayed.get(), image.drawable)
            assertEquals(Color.RED, (image.drawable as BitmapDrawable).bitmap.getPixel(0, 0))
        }
    }

    @Test
    fun 재로드가_NO_FILL이면_표시는_실패해도_기존_이미지를_유지하고_해제하면_지운다() {
        enqueueBanner()
        val first = ResultListener()
        scenario.onActivity { it.banner().load(request(), first) }
        first.await()
        val displayed = AtomicReference<Drawable>()
        scenario.onActivity {
            it.banner().assertShowSuccess()
            displayed.set((it.banner().getChildAt(0) as ImageView).drawable)
        }

        server.enqueue(MockResponse().setResponseCode(204))
        val second = ResultListener()
        scenario.onActivity {
            it.banner().load(request(), second)
            assertSame(displayed.get(), (it.banner().getChildAt(0) as ImageView).drawable)
            it.banner().assertShowFailure(BannerAdShowError.Code.LOADING)
        }
        second.await()
        assertEquals(0, second.loads.get())
        assertEquals(BannerAdError.Code.NO_FILL, second.error.get()!!.code)
        scenario.onActivity {
            val failure = it.banner().assertShowFailure(BannerAdShowError.Code.LOAD_FAILED)
            assertEquals(BannerAdError.Code.NO_FILL, failure.loadError!!.code)
            assertSame(displayed.get(), (it.banner().getChildAt(0) as ImageView).drawable)
            it.banner().destroy()
            it.banner().destroy()
            it.banner().assertShowFailure(BannerAdShowError.Code.DESTROYED)
            assertNull((it.banner().getChildAt(0) as ImageView).drawable)
        }
    }

    @Test
    fun 새_이미지_디코딩이_실패해도_이미_표시한_광고는_유지한다() {
        enqueueBanner()
        val first = ResultListener()
        scenario.onActivity { it.banner().load(request(), first) }
        first.await()
        val displayed = AtomicReference<Drawable>()
        scenario.onActivity {
            it.banner().assertShowSuccess()
            displayed.set((it.banner().getChildAt(0) as ImageView).drawable)
        }
        server.enqueue(MockResponse().setBody(metadata("bad-image")))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("invalid image"))
        val second = ResultListener()
        scenario.onActivity { it.banner().load(request(), second) }
        second.await()
        scenario.onActivity {
            val failure = it.banner().assertShowFailure(BannerAdShowError.Code.LOAD_FAILED)
            assertEquals(BannerAdError.Code.IMAGE_LOAD, failure.loadError!!.code)
            assertSame(displayed.get(), (it.banner().getChildAt(0) as ImageView).drawable)
        }
    }

    @Test
    fun 로드_완료됐지만_광고_표시하지_않은_광고는_다음_로드가_시작되면_버린다() {
        enqueueBanner(adId = "unshown")
        val first = ResultListener()
        scenario.onActivity { it.banner().load(request(), first) }
        first.await()
        server.enqueue(MockResponse().setResponseCode(204))
        val second = ResultListener()
        scenario.onActivity { it.banner().load(request(), second) }
        second.await()
        scenario.onActivity {
            it.banner().assertShowFailure(BannerAdShowError.Code.LOAD_FAILED)
            assertNull((it.banner().getChildAt(0) as ImageView).drawable)
        }
    }

    @Test
    fun 광고_표시중인_광고와_새로_로드_완료된_광고가_함께_있어도_해제하면_둘다_사용할_수_없다() {
        enqueueBanner(adId = "displayed")
        val first = ResultListener()
        scenario.onActivity { it.banner().load(request(), first) }
        first.await()
        val displayed = AtomicReference<Drawable>()
        scenario.onActivity {
            it.banner().assertShowSuccess()
            displayed.set((it.banner().getChildAt(0) as ImageView).drawable)
        }
        enqueueBanner(color = Color.RED, adId = "prepared")
        val second = ResultListener()
        scenario.onActivity { it.banner().load(request(), second) }
        second.await()
        scenario.onActivity {
            val banner = it.banner()
            assertSame(displayed.get(), (banner.getChildAt(0) as ImageView).drawable)
            banner.destroy()
            assertNull((banner.getChildAt(0) as ImageView).drawable)
            banner.assertShowFailure(BannerAdShowError.Code.DESTROYED)
        }
    }

    @Test
    fun 광고_표시_성공_콜백에서_해제해도_결과는_한번만_전달한다() {
        enqueueBanner(adId = "callback-ad")
        val loaded = ResultListener()
        scenario.onActivity { it.banner().load(request(), loaded) }
        loaded.await()
        var successes = 0
        scenario.onActivity {
            val banner = it.banner()
            banner.show(object : BannerAdShowListener {
                override fun onShowSuccess(adInfo: BannerAdInfo) {
                    successes++
                    assertEquals("callback-ad", adInfo.adId)
                    assertNotNull((banner.getChildAt(0) as ImageView).drawable)
                    banner.destroy()
                }
                override fun onShowFailed(error: BannerAdShowError) = fail(error.toString())
            })
            banner.assertShowFailure(BannerAdShowError.Code.DESTROYED)
            assertNull((banner.getChildAt(0) as ImageView).drawable)
        }
        assertEquals(1, successes)
    }

    private fun BannerAdView.assertShowSuccess(): BannerAdInfo {
        val result = ShowResultListener()
        show(result)
        assertEquals(1, result.successes.size)
        assertTrue(result.failures.isEmpty())
        assertSame(Looper.getMainLooper(), result.callbackLooper)
        return result.successes.single()
    }

    private fun BannerAdView.assertShowFailure(code: BannerAdShowError.Code? = null): BannerAdShowError {
        val result = ShowResultListener()
        show(result)
        assertEquals(1, result.failures.size)
        assertTrue(result.successes.isEmpty())
        assertSame(Looper.getMainLooper(), result.callbackLooper)
        return result.failures.single().also { if (code != null) assertEquals(code, it.code) }
    }

    private class ShowResultListener : BannerAdShowListener {
        val successes = mutableListOf<BannerAdInfo>()
        val failures = mutableListOf<BannerAdShowError>()
        var callbackLooper: Looper? = null
        override fun onShowSuccess(adInfo: BannerAdInfo) {
            callbackLooper = Looper.myLooper()
            successes += adInfo
        }
        override fun onShowFailed(error: BannerAdShowError) {
            callbackLooper = Looper.myLooper()
            failures += error
        }
    }

    private fun MainActivity.banner(): BannerAdView =
        findViewById<FrameLayout>(R.id.banner_container).getChildAt(0) as BannerAdView

    // Debug 호스트가 HTTP를 허용한 루프백 주소와 맞춘다. localhost는 허용하지 않는다.
    // MockWebServer.url()은 호스트명 조회를 할 수 있으므로 메인 스레드에서도 안전하게 직접 조립한다.
    private fun localUrl(path: String) = HttpUrl.Builder()
        .scheme("http").host("127.0.0.1").port(server.port).encodedPath(path).build()
    private fun request() = BannerAdRequest("demo-banner", localUrl("/ads").toString())
    private fun metadata(adId: String = "integration-test") = """{"adId":"$adId","imageUrl":"${localUrl("/banner.png")}"}"""

    private fun enqueueBanner(closeMetadataConnection: Boolean = false, color: Int = Color.BLUE, adId: String = "integration-test") {
        val bytes = pngBytes(color)
        val metadataResponse = MockResponse().setBody(metadata(adId))
        if (closeMetadataConnection) {
            metadataResponse.setStatus("HTTP/1.0 200 OK")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        }
        server.enqueue(metadataResponse)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
    }

    private fun pngBytes(color: Int = Color.BLUE): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        bitmap.recycle()
        return bytes
    }

    private class ResultListener : BannerAdListener {
        val loads = AtomicInteger()
        val error = AtomicReference<BannerAdError?>()
        val callbackLooper = AtomicReference<Looper?>()
        private val done = CountDownLatch(1)

        override fun onLoaded() {
            callbackLooper.set(Looper.myLooper())
            loads.incrementAndGet()
            done.countDown()
        }

        override fun onFailed(error: BannerAdError) {
            callbackLooper.set(Looper.myLooper())
            this.error.set(error)
            done.countDown()
        }

        fun await() {
            assertTrue("15초 안에 로드 결과를 받지 못했습니다", done.await(15, TimeUnit.SECONDS))
        }

        fun receivedWithin(milliseconds: Long): Boolean = done.await(milliseconds, TimeUnit.MILLISECONDS)
    }
}
