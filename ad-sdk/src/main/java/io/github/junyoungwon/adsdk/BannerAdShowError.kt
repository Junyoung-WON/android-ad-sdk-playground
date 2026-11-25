package io.github.junyoungwon.adsdk

/** 가장 최근에 로드 완료된 광고를 반영하지 못한 이유. 기존 광고가 사라졌다는 뜻은 아니다. */
public data class BannerAdShowError(
    val code: Code,
    val message: String,
    val loadError: BannerAdError? = null
) {
    public enum class Code {
        NOT_LOADED,
        LOADING,
        LOAD_FAILED,
        DESTROYED
    }
}
