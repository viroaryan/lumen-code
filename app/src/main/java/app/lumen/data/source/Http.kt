// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.data.source

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

object Http {
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // A hard ceiling per request (not per read), so a stalled server can never hang a search or the player.
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): String = retrying {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code, url)
            response.body.string()
        }
    }

    /**
     * Tries once more after a network hiccup (dropped connection, timeout, server 5xx), which covers most
     * transient failures on mobile networks. Client errors (4xx) are final and aren't retried.
     */
    inline fun <T> retrying(block: () -> T): T {
        try {
            return block()
        } catch (e: IOException) {
            val transient = e !is HttpStatusException || e.code >= 500
            if (!transient || Thread.currentThread().isInterrupted) throw e
            if (e is InterruptedIOException && e.message == "Canceled") throw e
            Thread.sleep(600)
            return block()
        }
    }
}

class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code for ${url.substringBefore('?')}")

/** OkHttp-backed transport for NewPipeExtractor. */
object NewPipeDownloader : Downloader() {
    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response = Http.retrying {
        val body = request.dataToSend()?.toRequestBody()
        val builder = Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", Http.USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        Http.client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body.string(),
                response.request.url.toString(),
            )
        }
    }
}
