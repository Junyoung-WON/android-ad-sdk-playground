package io.github.junyoungwon.adsdk

/** Results are delivered on the main thread, once for each non-cancelled load. */
public interface BannerAdListener {
    /** Image preparation is complete. Call show() separately; this is not an impression. */
    public fun onLoaded()

    public fun onFailed(error: BannerAdError)
}
