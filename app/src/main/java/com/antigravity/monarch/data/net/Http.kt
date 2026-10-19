package com.antigravity.monarch.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class HttpResponse(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
}

/** Minimal HTTP seam so API clients can be unit-tested with a fake. */
interface Http {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
    suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): HttpResponse
}

fun formEncode(params: Map<String, String>): String =
    params.entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

@Singleton
class UrlConnectionHttp @Inject constructor() : Http {
    override suspend fun get(url: String, headers: Map<String, String>) = request("GET", url, headers, null)

    override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>) =
        request("POST", url, headers + ("Content-Type" to "application/x-www-form-urlencoded"), formEncode(form))

    private suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
        withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 30_000
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) doOutput = true
            }
            try {
                if (body != null) connection.outputStream.bufferedWriter().use { it.write(body) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                HttpResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally {
                connection.disconnect()
            }
        }
}
