package io.github.junyoungwon.adsdk.internal

import android.util.Log
import io.github.junyoungwon.adsdk.BannerAdError
import io.github.junyoungwon.adsdk.BannerAdError.Code
import io.github.junyoungwon.adsdk.BannerAdRequest
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal data class BannerCreative(val adId: String, val imageUrl: HttpUrl)

internal class BannerHttpSource(
    private val client: OkHttpClient,
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    suspend fun fetch(request: BannerAdRequest): BannerCreative {
        if (request.placementId.isBlank()) fail(Code.INVALID_REQUEST, "placementId must not be blank")
        val endpoint = request.serverUrl.toHttpUrlOrNull()
            ?: fail(Code.INVALID_REQUEST, "serverUrl must be an absolute HTTP(S) URL")
        val url = endpoint.newBuilder()
            .setQueryParameter("placementId", request.placementId)
            .build()
        val body = client.newCall(Request.Builder().url(url).build()).readAdBody()
        return withContext(parsingDispatcher) { parseCreative(body) }
    }

    private suspend fun Call.readAdBody(): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            cancel()
            Log.d("BannerHttpSource", "코루틴 취소 요청 호출")
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val code = if (e is InterruptedIOException) Code.TIMEOUT else Code.NETWORK
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        BannerLoadException(BannerAdError(code, "Ad request failed: ${e.message}"))
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (!continuation.isActive) return
                        when {
                            it.code == 204 -> fail(Code.NO_FILL, "No ad available")
                            it.code != 200 -> fail(Code.HTTP_ERROR, "Ad server returned HTTP ${it.code}")
                        }
                        val source = it.body?.source()
                            ?: fail(Code.INVALID_RESPONSE, "Missing response body")
                        source.request(MAX_RESPONSE_BYTES + 1)
                        if (source.buffer.size > MAX_RESPONSE_BYTES) {
                            fail(Code.INVALID_RESPONSE, "Ad response exceeds 64 KiB")
                        }
                        source.readUtf8()
                    }
                    continuation.resume(body)
                } catch (failure: BannerLoadException) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } catch (failure: IOException) {
                    onFailure(call, failure)
                }
            }
        })
    }

    private fun parseCreative(body: String): BannerCreative {
        val json = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: fail(Code.INVALID_RESPONSE, "Expected a JSON object")
        fun requiredString(name: String): String {
            val value = (json[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return value?.takeIf { it.isNotBlank() }
                ?: fail(Code.INVALID_RESPONSE, "Missing or invalid $name")
        }
        val adId = requiredString("adId")
        val imageUrl = requiredString("imageUrl").toHttpUrlOrNull()
            ?: fail(Code.INVALID_RESPONSE, "imageUrl must be an absolute HTTP(S) URL")
        return BannerCreative(adId, imageUrl)
    }

    private fun fail(code: Code, message: String): Nothing =
        throw BannerLoadException(BannerAdError(code, message))

    private companion object {
        const val MAX_RESPONSE_BYTES = 64L * 1024
    }
}
