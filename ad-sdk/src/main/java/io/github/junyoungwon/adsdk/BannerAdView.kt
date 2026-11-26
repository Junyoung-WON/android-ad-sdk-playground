package io.github.junyoungwon.adsdk

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.MainThread
import coil.ImageLoader
import io.github.junyoungwon.adsdk.internal.BannerHttpSource
import io.github.junyoungwon.adsdk.internal.BannerImageLoader
import io.github.junyoungwon.adsdk.internal.BannerImpressionTracker
import io.github.junyoungwon.adsdk.internal.BannerLoadSession
import io.github.junyoungwon.adsdk.internal.visibleImageFraction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient

/**
 * 광고 로드와 광고 표시 시점을 분리해 사용하는 이미지 배너 View다.
 *
 * [load]는 광고 데이터와 이미지 준비를 완료하고, [show]는 로드 완료된 광고를 View에 반영한다.
 * 로드 완료와 광고 표시 성공은 실제 화면 가시성이나 노출 조건 충족을 보장하지 않는다.
 * 재로드 중에도 기존 광고를 유지하고, 새로 로드 완료된 광고를 show할 때 교체한다.
 *
 * [setImpressionListener]로 노출 알림을 구독한다. 창에 포커스가 있고 이미지 면적의 50% 이상이
 * 연속 1초 보이면 이 View의 동일 adId에 한 번 알린다. 면적은 사각형의 클리핑을 기준으로 하며,
 * 다른 View의 임의 가림이나 투명 픽셀까지 측정하는 공인 측정 구현은 아니다.
 * 총 가시 시간 집계와 서버 전송, 과금은 제공하지 않는다.
 *
 * 로드 상태와 시간 판정은 내부 객체에 위임하며, 이 View가 이미지 반영과 화면 상태 관찰,
 * 검사 예약과 콜백 순서를 연결한다. 모든 공개 메서드와 콜백은 메인 스레드에서 사용한다.
 *
 * 호스트는 사용 종료 시 [destroy]를 호출한다. 예를 들어 Activity.onDestroy에서 해제한다.
 * 단순 detach나 백그라운드 전환은 최종 해제와 다르며 자동 재로드하지 않는다.
 * 관찰이 중단되면 연속 시간은 초기화하지만 광고와 기존 노출 이력은 유지한다.
 */
public class BannerAdView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        // 각 HTTP 호출의 제한 시간 안에서, 끊긴 풀 연결 등에 대한 OkHttp의 기본 복구를 허용한다.
        .retryOnConnectionFailure(true)
        .build()
    private val imageLoader = ImageLoader.Builder(context.applicationContext)
        .okHttpClient(client)
        .memoryCache(null)
        .diskCache(null)
        .build()
    // 광고 서버와 통신하고 응답을 검증한다.
    private val source = BannerHttpSource(client)
    // 이미지 다운로드와 디코딩을 담당한다.
    private val images = BannerImageLoader(context, imageLoader)
    // 최신 요청의 상태와 로드 완료된 광고를 보관한다. 현재 View의 광고와 수명이 다르다.
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
    // 세션의 최신 로드 광고(B)와 별개로, 현재 View에 반영된 광고(A)를 관찰한다.
    private val impressions = BannerImpressionTracker(SystemClock::uptimeMillis)
    private var displayedAd: PreparedBanner? = null
    private var impressionListener: BannerAdImpressionListener? = null
    private var trackingReady = false
    // 호스트의 show 결과 콜백이 반환되기 전에는 노출 콜백을 중첩 호출하지 않는다.
    private var showCallbackDepth = 0
    private var observedTree: ViewTreeObserver? = null
    private val impressionCheck = Runnable { checkImpression() }
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        checkImpression()
        true
    }
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { checkImpression() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { checkImpression() }

    init {
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        trackingReady = true
    }

    /**
     * 이 View에서 발생하는 노출 알림을 받을 리스너를 등록하거나 교체한다.
     * 광고 교체 후에도 유지하므로 load/show 전에 한 번 등록해 사용할 수 있다.
     *
     * 리스너가 없어도 노출 판정 이력은 유지한다. 나중에 등록한 리스너에 과거 이벤트를
     * 소급하지 않으며, 등록 교체로 같은 광고의 알림을 다시 만들지 않는다.
     * [destroy] 이후 등록은 무시한다.
     *
     * @param listener 메인 스레드에서 호출할 노출 리스너. null이면 SDK가 보관한 참조를 해제한다.
     */
    @MainThread
    public fun setImpressionListener(listener: BannerAdImpressionListener?) {
        requireMainThread()
        if (destroyed) return
        impressionListener = listener
    }

    /**
     * 광고 데이터 요청과 이미지 다운로드, 디코딩을 시작한다. 모두 완료하면 onLoaded를 알린다.
     * 로드 완료된 광고의 실제 View 반영은 [show]에서 수행한다.
     *
     * 새 호출은 이전 작업을 취소하고 이전에 로드 완료된 광고를 무효화한다.
     * 이미 View에 반영된 광고와 그 노출 관찰은 유지하며, 새 로드가 실패해도 제거하지 않는다.
     * 교체나 해제로 취소된 요청의 늦은 결과 콜백은 전달하지 않는다. [destroy] 이후 호출은 무시한다.
     *
     * @param request 광고 지면과 서버 주소를 담은 요청.
     * @param listener 이번 요청의 로드 성공 또는 실패를 메인 스레드에서 전달받을 리스너.
     */
    @MainThread
    public fun load(request: BannerAdRequest, listener: BannerAdListener) {
        requireMainThread()
        if (destroyed) return
        session.load(request, listener)
    }

    /**
     * 가장 최근에 로드 완료된 광고를 반영하고, 호출 안에서 결과 콜백 하나를 메인 스레드로 전달한다.
     * 새 광고가 아직 로드 중이거나 로드에 실패했다면 광고 표시 실패를 알리고 기존 광고를 유지한다.
     * 새 load 없이 반복 호출하면 같은 광고를 반영하고 매번 성공을 알린다.
     *
     * 성공은 이미지와 광고 라벨을 View에 설정했다는 의미다. 미부착 상태에서도 성공할 수 있으며,
     * 노출 조건 충족 여부는 별도로 관찰한다. 같은 광고의 반복 show는 노출 시간을 초기화하거나
     * 이미 알린 노출을 중복 발생시키지 않는다.
     *
     * @param listener 이번 호출의 광고 표시 결과를 받을 리스너. SDK는 이후 이벤트를 위해 보관하지 않는다.
     */
    @MainThread
    public fun show(listener: BannerAdShowListener) {
        requireMainThread()
        showCallbackDepth++
        try {
            session.bindPrepared(
                render = { prepared ->
                    bindBanner(prepared)
                    listener.onShowSuccess(prepared.info)
                },
                onUnavailable = listener::onShowFailed
            )
        } finally {
            showCallbackDepth--
            if (showCallbackDepth == 0 && !destroyed) post(impressionCheck)
        }
    }

    /**
     * 이 View의 사용을 최종 종료한다. 반복 호출은 안전하며 해제한 View는 재사용할 수 없다.
     * 로드 작업과 노출 관찰, 예약 검사를 중단하고 광고와 노출 리스너 참조, 소유한 로더 자원을 정리한다.
     * 이후 load와 리스너 등록은 무시하며 show는 DESTROYED를 전달한다.
     * 참조 정리는 Bitmap 등의 메모리가 즉시 회수된다는 보장은 아니다.
     */
    @MainThread
    public fun destroy() {
        requireMainThread()
        if (destroyed) return
        destroyed = true
        stopImpressionObservation()
        impressions.destroy()
        impressionListener = null
        displayedAd = null
        session.destroy()
        scope.cancel()
        clearImage()
        imageLoader.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /** 현재 광고 정보와 이미지, Tracker의 판정 대상을 함께 바꾼다. 외부 콜백은 호출하지 않는다. */
    private fun bindBanner(prepared: PreparedBanner) {
        displayedAd = prepared
        impressions.display(prepared.info)
        image.setImageDrawable(prepared.drawable)
        badge.visibility = View.VISIBLE
    }

    private fun clearImage() {
        image.setImageDrawable(null)
        badge.visibility = View.GONE
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "BannerAdView requires the main thread" }
    }

    /** 화면에 부착되면 관찰자를 등록하고 현재 광고의 조건을 확인한다. 로드를 시작하지는 않는다. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!trackingReady || destroyed) return
        observedTree = viewTreeObserver.also {
            it.addOnPreDrawListener(preDrawListener)
            it.addOnScrollChangedListener(scrollListener)
            it.addOnGlobalLayoutListener(layoutListener)
        }
        checkImpression()
    }

    /** 관찰을 중단하되 광고와 노출 이력은 유지한다. 재부착되면 연속 시간을 새로 측정한다. */
    override fun onDetachedFromWindow() {
        if (trackingReady) {
            stopImpressionObservation()
        }
        super.onDetachedFromWindow()
    }

    /** 포커스 상실과 복귀를 시간 판정에 반영한다. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        checkImpression()
    }

    /** 창이 숨겨지거나 다시 보이면 조건을 재확인한다. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        checkImpression()
    }

    /** 이 View나 상위 View의 가시성 변경을 반영한다. */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        checkImpression()
    }

    /**
     * 현재 화면 상태를 Tracker에 전달하고, 남은 시간 뒤의 검사와 새 노출 콜백을 연결한다.
     * show 결과 콜백 중에는 노출 콜백을 중첩하지 않는다. 콜백 안에서 광고 교체나 해제가
     * 일어날 수 있으므로 노출 리스너 호출 뒤에는 상태를 덮어쓰지 않는다.
     */
    private fun checkImpression() {
        // View 생성자에서 visibility 콜백이 올 수도 있으므로 필드 초기화 전에는 접근하지 않는다.
        if (!trackingReady || destroyed) return
        removeCallbacks(impressionCheck)
        val eligible = displayedAd != null && image.visibleImageFraction() >= 0.5f
        if (showCallbackDepth > 0) {
            if (!eligible) impressions.stopObserving()
            return
        }
        val event = impressions.observe(eligible)
        impressions.remainingMillis()?.let { postDelayed(impressionCheck, it) }
        // 이후 상태를 덮어쓰지 않아 콜백 안에서 show/load/destroy할 수 있다.
        if (event != null) impressionListener?.onImpression(event)
    }

    /** 화면 관찰과 예약 작업을 함께 중단한다. 광고 정보와 이미 알린 노출 이력은 보존한다. */
    private fun stopImpressionObservation() {
        removeImpressionObservers()
        removeCallbacks(impressionCheck)
        impressions.stopObserving()
    }

    /** 등록할 때 보관한 ViewTreeObserver에서 동일한 관찰자를 해제한다. */
    private fun removeImpressionObservers() {
        observedTree?.takeIf { it.isAlive }?.let {
            it.removeOnPreDrawListener(preDrawListener)
            it.removeOnScrollChangedListener(scrollListener)
            it.removeOnGlobalLayoutListener(layoutListener)
        }
        observedTree = null
    }

    private data class PreparedBanner(val info: BannerAdInfo, val drawable: Drawable)
}
