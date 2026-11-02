package com.replydev.reveille.update

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress

/** The real `HttpURLConnection` client against the JDK's built-in server: no network, no extra library. */
class UrlConnectionHttpTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val base = "http://127.0.0.1:${server.address.port}"

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun sendsHeadersAndReturnsLowercaseResponseHeaders() {
        var seenAgent: String? = null
        server.createContext("/ok") { ex ->
            seenAgent = ex.requestHeaders.getFirst("User-Agent")
            ex.responseHeaders.add("ETag", "W/\"1\"")
            val body = "hello".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        val response = UrlConnectionHttp().get("$base/ok", mapOf("User-Agent" to "Reveille/test"))
        assertEquals(200, response.code)
        assertEquals("hello", response.body)
        assertEquals("W/\"1\"", response.headers["etag"])
        assertEquals("Reveille/test", seenAgent)
    }

    @Test
    fun readsErrorBodiesAndEmpty304s() {
        server.createContext("/limited") { ex ->
            ex.responseHeaders.add("X-RateLimit-Remaining", "0")
            val body = "{}".toByteArray()
            ex.sendResponseHeaders(403, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/same") { ex -> ex.sendResponseHeaders(304, -1); ex.close() }
        val limited = UrlConnectionHttp().get("$base/limited", emptyMap())
        assertEquals(403, limited.code)
        assertEquals("{}", limited.body)
        assertEquals("0", limited.headers["x-ratelimit-remaining"])
        assertEquals(304, UrlConnectionHttp().get("$base/same", emptyMap()).code)
    }
}
