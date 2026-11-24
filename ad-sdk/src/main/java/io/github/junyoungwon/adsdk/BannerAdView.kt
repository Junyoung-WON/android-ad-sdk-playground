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
    private val session = BannerLoadSession<Drawable>(scope) { request ->
        images.prepare(source.fetch(request))
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

    /** Clears any previous banner and replaces an in-flight load. No rendering until [show].
     * 배너에 실려있는 광고를 제거하고 로드, show를 하기 전까지 렌더링하지 않음 */
    @MainThread
    public fun load(request: BannerAdRequest, listener: BannerAdListener) {
        requireMainThread()
        if (destroyed) return
        clearImage()
        session.load(request, listener)
    }

    /**
     * Binds the prepared image, returning false if unavailable/destroyed. Repeated calls are safe.
     * True means the image was bound, not that the view is visible or an impression occurred.
     */
    @MainThread
    public fun show(): Boolean {
        requireMainThread()
        return session.show { drawable ->
            image.setImageDrawable(drawable)
            badge.visibility = View.VISIBLE
        }
    }

    /** Terminal and idempotent. Later load() calls do nothing; show() returns false. */
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
}
