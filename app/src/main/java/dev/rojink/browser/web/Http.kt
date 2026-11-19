package dev.rojink.browser.web

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Read on OkHttp's dispatcher, with cancellation closing the socket even during body reads. */
internal suspend fun <T> OkHttpClient.exchange(request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            try { continuation.resume(response.use(read)) } catch (e: Exception) { continuation.resumeWithException(e) }
        }
    })
}
