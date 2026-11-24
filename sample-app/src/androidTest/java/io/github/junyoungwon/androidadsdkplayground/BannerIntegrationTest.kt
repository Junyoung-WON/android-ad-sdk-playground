package io.github.junyoungwon.androidadsdkplayground

import android.graphics.Bitmap
import android.graphics.Color
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
            assertFalse(banner.show())
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
            assertTrue(banner.show())
            assertNotNull(image.drawable)
            assertTrue(banner.show())
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
        scenario.onActivity { assertTrue(it.banner().show()) }
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
            assertFalse(it.banner().show())
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
                assertFalse(it.banner().show())
                // 이전 View의 load는 무시되고, 새 View만 정상적으로 요청할 수 있어야 한다.
                oldBanner.get().load(request(), oldListener)
                it.banner().load(request(), newListener)
            }
            newListener.await()
            assertEquals(newListener.error.get().toString(), 1, newListener.loads.get())
            scenario.onActivity { assertTrue(it.banner().show()) }
            assertFalse("종료된 화면의 리스너에 결과가 전달됐습니다", oldListener.receivedWithin(500))
            assertEquals(2, metadataCalls.get())
        } finally {
            releaseFirstResponse.countDown()
        }
    }

    @Test
    fun 재로드하면_표시된_이미지를_지우고_NO_FILL에서_재사용하지_않는다() {
        enqueueBanner()
        val first = ResultListener()
        scenario.onActivity { it.banner().load(request(), first) }
        first.await()
        scenario.onActivity { assertTrue(it.banner().show()) }

        server.enqueue(MockResponse().setResponseCode(204))
        val second = ResultListener()
        scenario.onActivity {
            it.banner().load(request(), second)
            assertNull((it.banner().getChildAt(0) as ImageView).drawable)
            assertFalse(it.banner().show())
        }
        second.await()
        assertEquals(0, second.loads.get())
        assertEquals(BannerAdError.Code.NO_FILL, second.error.get()!!.code)
        scenario.onActivity {
            assertFalse(it.banner().show())
            it.banner().destroy()
            it.banner().destroy()
            assertFalse(it.banner().show())
        }
    }

    private fun MainActivity.banner(): BannerAdView =
        findViewById<FrameLayout>(R.id.banner_container).getChildAt(0) as BannerAdView

    // Debug 호스트가 HTTP를 허용한 루프백 주소와 맞춘다. localhost는 허용하지 않는다.
    // MockWebServer.url()은 호스트명 조회를 할 수 있으므로 메인 스레드에서도 안전하게 직접 조립한다.
    private fun localUrl(path: String) = HttpUrl.Builder()
        .scheme("http").host("127.0.0.1").port(server.port).encodedPath(path).build()
    private fun request() = BannerAdRequest("demo-banner", localUrl("/ads").toString())
    private fun metadata() = """{"adId":"integration-test","imageUrl":"${localUrl("/banner.png")}"}"""

    private fun enqueueBanner(closeMetadataConnection: Boolean = false) {
        val bytes = pngBytes()
        val metadataResponse = MockResponse().setBody(metadata())
        if (closeMetadataConnection) {
            metadataResponse.setStatus("HTTP/1.0 200 OK")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        }
        server.enqueue(metadataResponse)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
    }

    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
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
