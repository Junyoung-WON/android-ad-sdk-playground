package io.github.junyoungwon.adsdk.internal

import android.content.Context
import android.graphics.drawable.Drawable
import coil.ImageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import io.github.junyoungwon.adsdk.BannerAdError

internal class BannerImageLoader(
    context: Context,
    private val imageLoader: ImageLoader
) {
    private val appContext = context.applicationContext

    suspend fun prepare(creative: BannerCreative): Drawable {
        val request = ImageRequest.Builder(appContext)
            .data(creative.imageUrl.toString())
            .size(640, 200)
            .scale(Scale.FIT)
            .allowHardware(false)
            .build()

        return when (val result = imageLoader.execute(request)) {
            is SuccessResult -> result.drawable
            is ErrorResult -> throw BannerLoadException(
                BannerAdError(
                    BannerAdError.Code.IMAGE_LOAD,
                    "Image preparation failed: ${result.throwable.message ?: result.throwable.javaClass.simpleName}"
                )
            )
        }
    }
}
