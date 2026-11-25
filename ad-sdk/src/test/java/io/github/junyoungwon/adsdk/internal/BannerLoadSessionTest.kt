package io.github.junyoungwon.adsdk.internal

import io.github.junyoungwon.adsdk.BannerAdError
import io.github.junyoungwon.adsdk.BannerAdListener
import io.github.junyoungwon.adsdk.BannerAdShowError
import io.github.junyoungwon.adsdk.BannerAdRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BannerLoadSessionTest {
    private val request = BannerAdRequest("demo-banner", "https://example.test/ads")

    @Test
    // 준비 완료·콜백·show의 순서를 검증한다. 실제 Android 렌더링 대신 문자열과 전달 함수를 사용한다.
    fun 로드가_완료되어도_show_전에는_광고를_전달하지_않는다() = runTest {
        val image = CompletableDeferred<String>()
        val session = BannerLoadSession(this) { image.await() }
        val listener = RecordingListener()
        var rendered: String? = null

        session.load(request, listener)
        runCurrent()
        assertFalse(session.bind { rendered = it })
        assertEquals(0, listener.loads)
        image.complete("decoded-image")
        runCurrent()

        assertEquals(1, listener.loads)
        assertNull(rendered)
        assertTrue(session.bind { rendered = it })
        assertEquals("decoded-image", rendered)
        assertTrue(session.bind { rendered = it })
        assertEquals(1, listener.loads)
    }

    @Test
    // 준비 함수가 IMAGE_LOAD로 실패하면 onLoaded가 없고, 실패 한 번과 show 불가를 확인한다.
    fun 이미지_준비가_실패하면_성공을_알리지_않고_show는_실패한다() = runTest {
        val error = BannerAdError(BannerAdError.Code.IMAGE_LOAD, "decode failed")
        val session = BannerLoadSession<String>(this) { throw BannerLoadException(error) }
        val listener = RecordingListener()
        session.load(request, listener)
        runCurrent()

        assertEquals(0, listener.loads)
        assertEquals(listOf(error), listener.failures)
        assertFalse(session.bind { fail("실패한 로드는 로드 완료된 광고를 전달하면 안 됩니다") })
    }

    @Test
    // 새 요청의 수행뿐 아니라 이전 요청의 콜백 억제와 최신 이미지 유지까지 확인한다.
    fun 이전_작업이_취소를_무시해도_최신_결과만_전달한다() = runTest {
        val first = CompletableDeferred<String>()
        val session = BannerLoadSession(this) { req ->
            if (req.placementId == "first") withContext(NonCancellable) { first.await() }
            else "new-image"
        }
        val oldListener = RecordingListener()
        val newListener = RecordingListener()
        session.load(request.copy(placementId = "first"), oldListener)
        runCurrent()
        session.load(request, newListener)
        runCurrent()
        first.complete("old-image")
        runCurrent()

        assertEquals(0, oldListener.loads)
        assertTrue(oldListener.failures.isEmpty())
        assertEquals(1, newListener.loads)
        assertTrue(session.bind { assertEquals("new-image", it) })
    }

    @Test
    // 진행 작업의 늦은 성공을 무시하고, 반복 destroy·해제 후 load·show의 계약을 확인한다.
    fun 해제_후_늦은_성공과_추가_로드를_무시한다() = runTest {
        val image = CompletableDeferred<String>()
        var preparations = 0
        val session = BannerLoadSession(this) {
            preparations++
            withContext(NonCancellable) { image.await() }
        }
        val listener = RecordingListener()
        session.load(request, listener)
        runCurrent()
        session.destroy()
        session.destroy()
        session.load(request, listener)
        image.complete("late-image")
        runCurrent()

        assertEquals(1, preparations)
        assertEquals(0, listener.loads)
        assertTrue(listener.failures.isEmpty())
        assertFalse(session.bind { fail("해제한 세션은 로드 완료된 광고를 전달하면 안 됩니다") })
    }

    @Test
    // 실패 자체를 없애는 것이 아니라, 해제 후 도착한 실패를 호스트 콜백에 전달하지 않는다.
    fun 해제_후_늦은_실패를_전달하지_않는다() = runTest {
        val release = CompletableDeferred<Unit>()
        val session = BannerLoadSession<String>(this) {
            withContext(NonCancellable) {
                release.await()
                throw BannerLoadException(BannerAdError(BannerAdError.Code.NETWORK, "late"))
            }
        }
        val listener = RecordingListener()
        session.load(request, listener)
        runCurrent()
        session.destroy()
        release.complete(Unit)
        runCurrent()
        assertTrue(listener.failures.isEmpty())
        assertEquals(0, listener.loads)
    }

    @Test
    // 새 load는 이전에 로드 완료된 광고를 버린다. View가 표시 중인 이미지의 유지와는 별개다.
    fun 재로드가_실패해도_이전에_로드_완료된_광고를_재사용하지_않는다() = runTest {
        val session = BannerLoadSession(this) { req ->
            if (req.placementId == "bad") {
                throw BannerLoadException(BannerAdError(BannerAdError.Code.NO_FILL, "empty"))
            }
            "image"
        }
        val listener = RecordingListener()
        session.load(request, listener)
        runCurrent()
        assertTrue(session.bind {})
        session.load(request.copy(placementId = "bad"), listener)
        assertFalse(session.bind {})
        runCurrent()
        assertFalse(session.bind {})
        assertEquals(1, listener.loads)
        assertEquals(BannerAdError.Code.NO_FILL, listener.failures.single().code)
    }

    @Test
    // onLoaded 안에서 show를 호출한 뒤 destroy해도 세션 상태가 일관적인지 확인한다.
    fun 성공_콜백_안에서_show와_destroy를_호출할_수_있다() = runTest {
        val session = BannerLoadSession(this) { "image" }
        session.load(request, object : BannerAdListener {
            override fun onLoaded() {
                assertTrue(session.bind { assertEquals("image", it) })
                session.destroy()
            }
            override fun onFailed(error: BannerAdError) = fail(error.toString())
        })
        runCurrent()
        assertFalse(session.bind {})
    }

    @Test
    fun 표시_실패는_미로드와_로드중과_로드실패와_해제를_구분한다() = runTest {
        val release = CompletableDeferred<Unit>()
        val loadError = BannerAdError(BannerAdError.Code.NO_FILL, "empty")
        val session = BannerLoadSession<String>(this) {
            release.await()
            throw BannerLoadException(loadError)
        }
        val failures = mutableListOf<BannerAdShowError>()
        fun show() = session.bindPrepared({ fail("준비되지 않은 결과를 반영하면 안 됩니다") }, failures::add)

        show()
        session.load(request, RecordingListener())
        show()
        runCurrent()
        release.complete(Unit)
        runCurrent()
        show()
        session.destroy()
        show()

        assertEquals(listOf(
            BannerAdShowError.Code.NOT_LOADED,
            BannerAdShowError.Code.LOADING,
            BannerAdShowError.Code.LOAD_FAILED,
            BannerAdShowError.Code.DESTROYED
        ), failures.map { it.code })
        assertSame(loadError, failures[2].loadError)
        assertNull(failures.last().loadError)
    }

    @Test
    fun 실패한_광고_표시의_콜백에서_다시_로드하면_가장_최근에_로드_완료된_광고를_반영한다() = runTest {
        val session = BannerLoadSession(this) { "new-image" }
        session.bindPrepared({ fail("미로드 상태입니다") }) { error ->
            assertEquals(BannerAdShowError.Code.NOT_LOADED, error.code)
            session.load(request, RecordingListener())
        }
        runCurrent()
        assertTrue(session.bind { assertEquals("new-image", it) })
    }

    private fun <T : Any> BannerLoadSession<T>.bind(render: (T) -> Unit): Boolean {
        var bound = false
        bindPrepared({ result -> bound = true; render(result) }, {})
        return bound
    }

    private class RecordingListener : BannerAdListener {
        var loads = 0
        val failures = mutableListOf<BannerAdError>()
        override fun onLoaded() { loads++ }
        override fun onFailed(error: BannerAdError) { failures += error }
    }
}
