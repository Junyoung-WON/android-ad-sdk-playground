package io.github.junyoungwon.adsdk.internal

import io.github.junyoungwon.adsdk.BannerAdError.Code
import io.github.junyoungwon.adsdk.BannerAdRequest
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BannerHttpSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var source: BannerHttpSource
    private lateinit var request: BannerAdRequest

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
        source = BannerHttpSource(client)
        request = BannerAdRequest("demo-banner", server.url("/ads?scenario=normal").toString())
    }

    @After
    fun tearDown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    @Test
    // 한글·특수문자를 포함한 지면 ID가 보존되고, 기존 scenario=normal 쿼리도 유지된다.
    fun 특수문자가_포함된_지면_ID와_기존_쿼리_값을_보존한다() = runBlocking {
        server.enqueue(MockResponse().setBody(validBody()))
        val placement = "지면 & ? = /"
        val creative = source.fetch(request.copy(placementId = placement))
        val received = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", received.method)
        assertEquals("/ads", received.requestUrl!!.encodedPath)
        assertEquals("normal", received.requestUrl!!.queryParameter("scenario"))
        assertEquals(placement, received.requestUrl!!.queryParameter("placementId"))
        assertEquals("ad-1", creative.adId)
        assertEquals(server.url("/banner.png"), creative.imageUrl)
    }

    @Test
    // 서버의 광고 없음(204)과 서버 오류(503)를 서로 다른 실패 코드로 전달한다.
    fun 응답_204는_NO_FILL로_503은_HTTP_ERROR로_분류한다() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        expectFailure(Code.NO_FILL) { source.fetch(request) }
        server.enqueue(MockResponse().setResponseCode(503))
        expectFailure(Code.HTTP_ERROR) { source.fetch(request) }
    }

    @Test
    // HTTP 요청·응답 이후 JSON 구문·최상위 타입·필드 누락·필드 타입 오류를 INVALID_RESPONSE로 처리한다.
    fun 잘못된_JSON과_누락되거나_타입이_틀린_필드를_거부한다() = runBlocking {
        for (body in listOf("not-json", "[]", "{}", "{\"adId\":12,\"imageUrl\":\"https://example.test/image.png\"}")) {
            server.enqueue(MockResponse().setBody(body))
            expectFailure(Code.INVALID_RESPONSE) { source.fetch(request) }
        }
    }

    @Test
    // 요청을 차단하는 검증이 아니라, 받은 광고 응답의 ID와 이미지 URL을 검증한다.
    fun 빈_광고_ID와_허용하지_않는_이미지_URL을_거부한다() = runBlocking {
        for (body in listOf(
            "{\"adId\":\" \",\"imageUrl\":\"https://example.test/image.png\"}",
            "{\"adId\":\"a\",\"imageUrl\":\"file:///tmp/image.png\"}"
        )) {
            server.enqueue(MockResponse().setBody(body))
            expectFailure(Code.INVALID_RESPONSE) { source.fetch(request) }
        }
    }

    @Test
    // 64 KiB를 넘는 응답은 JSON 파싱 전에 INVALID_RESPONSE로 실패한다.
    fun 응답이_64KiB를_넘으면_파싱_전에_거부한다() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(64 * 1024 + 1)))
        expectFailure(Code.INVALID_RESPONSE) { source.fetch(request) }
    }

    @Test
    // 올바르지 않은 입력에 대해서는 요청을 보내지 않는다.
    fun 잘못된_입력은_HTTP_요청을_보내지_않는다() = runBlocking {
        expectFailure(Code.INVALID_REQUEST) { source.fetch(request.copy(placementId = " ")) }
        expectFailure(Code.INVALID_REQUEST) { source.fetch(request.copy(serverUrl = "file:///ads")) }
        assertEquals(0, server.requestCount)
    }

    @Test
    // 응답하지 않는 서버에 대한 호출 제한 시간을 넘으면 TIMEOUT 예외로 분류한다.
    fun 응답이_없어_호출_제한_시간을_넘으면_TIMEOUT으로_실패한다() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val fastClient = client.newBuilder().callTimeout(150, TimeUnit.MILLISECONDS).build()
        expectFailure(Code.TIMEOUT) { BannerHttpSource(fastClient).fetch(request) }
    }

    @Test
    // 연결 끊김은 네트워크 요청 실패로 처리된다.
    fun 연결이_끊기면_NETWORK로_실패한다() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        expectFailure(Code.NETWORK) { source.fetch(request) }
    }

    @Test
    // 코루틴 취소는 HTTP 요청 취소로 이어진다.
    fun 코루틴을_취소하면_HTTP_호출도_취소된다() = runBlocking {
        val failed = CountDownLatch(1)
        val observedClient = client.newBuilder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
        }).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val loading = async { BannerHttpSource(observedClient).fetch(request) }
        val received = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
        assertNotNull(received)
        loading.cancelAndJoin()
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertTrue(loading.isCancelled)
    }

    private fun validBody() = """{"adId":"ad-1","imageUrl":"${server.url("/banner.png")}"}"""

    private suspend fun expectFailure(expected: Code, action: suspend () -> Any) {
        try {
            action()
            fail("$expected 실패를 예상했지만 정상 반환했습니다")
        } catch (failure: BannerLoadException) {
            assertEquals(expected, failure.error.code)
        }
    }
}
