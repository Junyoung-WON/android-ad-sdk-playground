package io.github.junyoungwon.adsdk.internal

import io.github.junyoungwon.adsdk.BannerAdError
import io.github.junyoungwon.adsdk.BannerAdListener
import io.github.junyoungwon.adsdk.BannerAdRequest
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

    fun load(request: BannerAdRequest, listener: BannerAdListener) {
        if (destroyed) return
        val current = ++generation
        job?.cancel()
        prepared = null
        // Assign before starting: a synchronous callback may re-enter load() or destroy().
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                prepare(request)
            } catch (failure: BannerLoadException) {
                ensureActive()
                if (!destroyed && current == generation) listener.onFailed(failure.error)
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

    fun show(render: (T) -> Unit): Boolean {
        if (destroyed) return false
        val banner = prepared ?: return false
        render(banner)
        return true
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        generation++
        job?.cancel()
        job = null
        prepared = null
    }
}
