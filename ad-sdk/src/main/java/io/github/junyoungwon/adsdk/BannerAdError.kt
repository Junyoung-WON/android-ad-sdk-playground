package io.github.junyoungwon.adsdk

/** A load failure. An image failure never becomes a successful load. */
public data class BannerAdError(val code: Code, val message: String) {
    public enum class Code {
        INVALID_REQUEST,
        NO_FILL,
        NETWORK,
        TIMEOUT,
        HTTP_ERROR,
        INVALID_RESPONSE,
        IMAGE_LOAD
    }
}
