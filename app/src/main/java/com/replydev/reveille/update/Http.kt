package com.replydev.reveille.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Response headers are keyed in lowercase. */
data class HttpResponse(val code: Int, val headers: Map<String, String>, val body: String)

/** A blocking GET, so the GitHub source can be tested without a server. */
fun interface Http {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

class UrlConnectionHttp(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : Http {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val responseHeaders = connection.headerFields
                .filterKeys { it != null }
                .mapNotNull { (name, values) -> values.lastOrNull()?.let { name.lowercase() to it } }
                .toMap()
            return HttpResponse(code, responseHeaders, body)
        } finally {
            connection.disconnect()
        }
    }
}
