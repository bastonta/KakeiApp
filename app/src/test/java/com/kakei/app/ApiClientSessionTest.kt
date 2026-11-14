package com.kakei.app

import com.kakei.app.data.remote.ApiException
import com.kakei.app.data.remote.InsecureServerException
import com.kakei.app.data.remote.KakeiApiClient
import com.kakei.app.data.remote.RefreshCookieStorage
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Session handling of [KakeiApiClient] against a local stand-in for the Kakei server. */
class ApiClientSessionTest {

    private class MemoryCookieStorage(var value: String? = null) : RefreshCookieStorage {
        override fun load(): String? = value
        override fun save(serializedCookie: String?) {
            value = serializedCookie
        }
    }

    private lateinit var server: HttpServer
    private val storage = MemoryCookieStorage()

    /** Status the refresh endpoint answers with; 200 also issues a new access token. */
    private var refreshStatus = 200

    /** `path -> Cookie header` of every request the server saw. */
    private val seenCookies = CopyOnWriteArrayList<Pair<String, String?>>()

    private fun HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            seenCookies += path to exchange.requestHeaders.getFirst("Cookie")
            when {
                path.endsWith("/identity/login") -> {
                    exchange.responseHeaders.add(
                        "Set-Cookie",
                        "refresh_token=R1; Path=/api/identity/token; Max-Age=3600; HttpOnly; SameSite=Strict"
                    )
                    exchange.reply(200, """{"userId":"u-1","token":"expired-token","need2fa":false}""")
                }
                path.endsWith("/identity/token/refresh") ->
                    if (refreshStatus == 200) exchange.reply(200, """{"token":"fresh-token"}""")
                    else exchange.reply(refreshStatus, """{"title":"refresh refused","status":$refreshStatus}""")
                path.endsWith("/version") -> {
                    val auth = exchange.requestHeaders.getFirst("Authorization")
                    if (auth == "Bearer fresh-token") exchange.reply(200, """{"version":"1.0.0","debug":false}""")
                    else exchange.reply(401, """{"title":"Unauthorized","status":401}""")
                }
                else -> exchange.reply(404, "{}")
            }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun baseUrl(host: String = "127.0.0.1") = "http://$host:${server.address.port}"

    private fun signedInClient(): KakeiApiClient = runBlocking {
        val client = KakeiApiClient(initialBaseUrl = baseUrl(), refreshCookieStorage = storage)
        client.login(com.kakei.app.data.remote.dto.LoginRequest("a@b.c", "pw")).getOrThrow()
        client
    }

    @Test
    fun refreshOutage_keepsTheSession() = runBlocking {
        val client = signedInClient()
        var expired = false
        client.onSessionExpired = { expired = true }
        refreshStatus = 503

        val result = client.getVersion()

        assertEquals(401, (result.exceptionOrNull() as ApiException).statusCode)
        assertFalse("a server outage must not sign the user out", expired)
        assertTrue(client.hasRefreshToken())
        assertTrue(storage.value != null)

        // Once the server is back the same session carries on.
        refreshStatus = 200
        assertEquals("1.0.0", client.getVersion().getOrThrow().version)
        assertFalse(expired)
    }

    @Test
    fun refusedRefreshToken_endsTheSession() = runBlocking {
        val client = signedInClient()
        var expired = false
        client.onSessionExpired = { expired = true }
        refreshStatus = 400

        assertTrue(client.getVersion().isFailure)

        assertTrue(expired)
        assertFalse(client.hasRefreshToken())
        assertNull(storage.value)
        assertNull(client.accessToken)
    }

    @Test
    fun refreshCookie_travelsOnlyToTokenEndpoints() = runBlocking {
        val client = signedInClient()
        seenCookies.clear()

        client.getVersion().getOrThrow()

        val byPath = seenCookies.groupBy({ it.first.substringAfter("/api") }, { it.second })
        assertTrue(byPath.getValue("/version").all { it == null })
        assertEquals(listOf<String?>("refresh_token=R1"), byPath.getValue("/identity/token/refresh"))
    }

    @Test
    fun refreshCookie_isNotHandedToAnotherHost() = runBlocking {
        val client = signedInClient()
        assertTrue(client.hasRefreshToken())

        // Same machine, different host name: for the cookie it is another server.
        client.updateBaseUrl(baseUrl("localhost"))
        assertFalse(client.hasRefreshToken())

        // A fresh client for the other host does not pick the stored cookie up either.
        val other = KakeiApiClient(initialBaseUrl = baseUrl("localhost"), refreshCookieStorage = storage)
        assertFalse(other.hasRefreshToken())

        // The issuing host still gets it back after a restart.
        val same = KakeiApiClient(initialBaseUrl = baseUrl(), refreshCookieStorage = storage)
        assertTrue(same.hasRefreshToken())
    }

    @Test
    fun legacyStoredCookie_isBoundToTheCurrentServerOnce() {
        val legacy = MemoryCookieStorage("refresh_token=OLD; max-age=3600; path=/api/identity/token; httponly")
        val client = KakeiApiClient(initialBaseUrl = baseUrl(), refreshCookieStorage = legacy)

        assertTrue(client.hasRefreshToken())
        assertTrue(legacy.value!!.startsWith("127.0.0.1\n"))
    }

    @Test
    fun plainHttpToPublicHost_isRefusedBeforeAnythingIsSent() = runBlocking {
        val client = KakeiApiClient(initialBaseUrl = "http://kakei.example.org")

        val error = client.login(com.kakei.app.data.remote.dto.LoginRequest("a@b.c", "pw")).exceptionOrNull()

        assertTrue(error is InsecureServerException)
    }

    @Test
    fun privateHosts_areRecognised() {
        listOf(
            "localhost", "10.0.2.2", "192.168.1.10", "172.16.0.1", "172.31.255.254", "127.0.0.1",
            "169.254.1.1", "100.64.0.5", "nas.local", "nas", "kakei.lan", "::1", "fd12:3456::1", "fe80::1"
        ).forEach { assertTrue(it, KakeiApiClient.isPrivateHost(it)) }
        listOf(
            "kakei.example.org", "8.8.8.8", "172.32.0.1", "192.169.1.1", "100.128.0.1", "2001:db8::1", "1.2.3"
        ).forEach { assertFalse(it, KakeiApiClient.isPrivateHost(it)) }

        assertTrue(KakeiApiClient.isInsecurePublicUrl("http://kakei.example.org"))
        assertFalse(KakeiApiClient.isInsecurePublicUrl("https://kakei.example.org"))
        assertFalse(KakeiApiClient.isInsecurePublicUrl("http://192.168.1.10:5144"))
        assertFalse(KakeiApiClient.isInsecurePublicUrl(""))
    }
}
