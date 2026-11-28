package io.github.junyoungwon.adsdk.internal

import io.github.junyoungwon.adsdk.BannerAdError
import io.github.junyoungwon.adsdk.BannerAdListener
import io.github.junyoungwon.adsdk.BannerAdRequest
import io.github.junyoungwon.adsdk.BannerAdShowError
import io.github.junyoungwon.adsdk.BannerAdShowError.Code
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class BannerLoadException(val error: BannerAdError) : Exception(error.message)

/** Main-thread confined. The owner supplies the scope and releases rendered resources. */
internal class BannerLoadSession<T : Any>(
    private val scope: CoroutineScope,
    private val prepare: suspend (BannerAdRequest) -> T
) {
    private var generation = 0L
    private var job: Job? = null
    private var prepared: T? = null
    private var destroyed = false
    private var unavailable = BannerAdShowError(Code.NOT_LOADED, "광고를 먼저 로드해주세요.")

    fun load(request: BannerAdRequest, listener: BannerAdListener) {
        if (destroyed) return

        val current = ++generation
        job?.cancel()
        prepared = null
        unavailable = BannerAdShowError(Code.LOADING, "최신 광고를 준비하고 있습니다.")

        // Assign before starting: a synchronous callback may re-enter load() or destroy().
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                prepare(request)
            } catch (failure: BannerLoadException) {
                ensureActive()
                if (!destroyed && current == generation) {
                    unavailable = BannerAdShowError(Code.LOAD_FAILED, "최신 광고 로드가 실패했습니다.", failure.error)
                    listener.onFailed(failure.error)
                }
                return@launch
            }
            ensureActive()
            if (destroyed || current != generation) return@launch
            prepared = result
            listener.onLoaded()
        }
        job = next
        next.start()
    }

    // 표시 중인 이미지의 수명은 View가 관리한다. 이 함수는 가장 최근에 로드 완료된 광고만 반영한다.
    fun bindPrepared(render: (T) -> Unit, onUnavailable: (BannerAdShowError) -> Unit) {
        val banner = prepared
        if (banner == null) {
            onUnavailable(unavailable)
            return
        }
        render(banner)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        generation++
        job?.cancel()
        job = null
        prepared = null
        unavailable = BannerAdShowError(Code.DESTROYED, "해제한 배너는 표시할 수 없습니다.")
    }
}
