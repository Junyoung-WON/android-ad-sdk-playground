package io.github.junyoungwon.adsdk

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.MainThread
import coil.ImageLoader
import io.github.junyoungwon.adsdk.internal.BannerHttpSource
import io.github.junyoungwon.adsdk.internal.BannerImageLoader
import io.github.junyoungwon.adsdk.internal.BannerLoadSession
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient

/**
 * Image banner with separate load/show calls. All public methods require the main thread.
 * The host must call [destroy] when it finishes using this view, e.g. in Activity.onDestroy.
 * Detachment/backgrounding alone does not destroy the banner or automatically reload it.
 */
public class BannerAdView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        // Preserve OkHttp's recovery of stale pooled sockets within the call timeout.
        .retryOnConnectionFailure(true)
        .build()
    private val imageLoader = ImageLoader.Builder(context.applicationContext)
        .okHttpClient(client)
        .memoryCache(null)
        .diskCache(null)
        .build()
    // 광고를 불러오는 소스(광고 서버)
    private val source = BannerHttpSource(client)
    // 광고 이미지를 로드하는 이미지 로더
    private val images = BannerImageLoader(context, imageLoader)
    // 광고를 로드할 세션, 코루틴 스코프와 광고를 준비하는 람다 함수(prepare)를 파라미터로 받는다.
    private val session = BannerLoadSession<PreparedBanner>(scope) { request ->
        val creative = source.fetch(request)
        PreparedBanner(BannerAdInfo(creative.adId, request.placementId), images.prepare(creative))
    }
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = context.getString(R.string.adsdk_banner_description)
    }
    private val badge = TextView(context).apply {
        text = context.getString(R.string.adsdk_ad_label)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.DKGRAY)
        textSize = 12f
        val padding = (4 * resources.displayMetrics.density).toInt()
        setPadding(padding, 0, padding, 0)
        visibility = View.GONE
    }
    private var destroyed = false

    init {
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
    }

    /**
     * 진행 중인 로드와 로드는 완료됐지만 아직 View에 반영하지 않은 광고를 교체한다.
     * 기존 광고 표시는 유지하고, 가장 최근에 로드 완료된 광고는 [show]에서 반영한다.
     */
    @MainThread
    public fun load(request: BannerAdRequest, listener: BannerAdListener) {
        requireMainThread()
        if (destroyed) return
        session.load(request, listener)
    }

    /**
     * 가장 최근에 로드 완료된 광고를 반영하고, 호출 안에서 결과 콜백 하나를 메인 스레드로 전달한다.
     * 실패해도 기존 광고 표시는 유지한다. 반복 호출은 같은 광고를 반영하고 매번 성공을 알린다.
     * 성공은 View에 이미지가 설정됐다는 의미이며 실제 가시성이나 노출을 보장하지 않는다.
     */
    @MainThread
    public fun show(listener: BannerAdShowListener) {
        requireMainThread()
        session.bindPrepared(
            render = { prepared ->
                image.setImageDrawable(prepared.drawable)
                badge.visibility = View.VISIBLE
                listener.onShowSuccess(prepared.info)
            },
            onUnavailable = listener::onShowFailed
        )
    }

    /** 최종 해제이며 반복 호출은 안전하다. 이후 load는 무시하고 show는 DESTROYED를 알린다. */
    @MainThread
    public fun destroy() {
        requireMainThread()
        if (destroyed) return
        destroyed = true
        session.destroy()
        scope.cancel()
        clearImage()
        imageLoader.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun clearImage() {
        image.setImageDrawable(null)
        badge.visibility = View.GONE
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "BannerAdView requires the main thread" }
    }

    private data class PreparedBanner(val info: BannerAdInfo, val drawable: Drawable)
}
