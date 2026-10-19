package com.antigravity.monarch.data.strava

import com.antigravity.monarch.data.net.Http
import com.antigravity.monarch.data.net.HttpResponse
import com.antigravity.monarch.domain.model.ActivitySource
import com.antigravity.monarch.domain.model.WorkoutType
import com.antigravity.monarch.domain.usecase.FakeTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate

private class InMemoryTokenStore(var tokens: StravaTokens? = null) : StravaTokenStore {
    var state: String? = null
    override suspend fun load() = tokens
    override suspend fun save(tokens: StravaTokens) { this.tokens = tokens }
    override suspend fun clear() { tokens = null; state = null }
    override suspend fun savePendingState(state: String?) { this.state = state }
    override suspend fun pendingState() = state
}

private class ScriptedHttp : Http {
    data class Call(val method: String, val url: String, val form: Map<String, String>, val headers: Map<String, String>)
    val calls = mutableListOf<Call>()
    val responses = ArrayDeque<HttpResponse>()
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        calls += Call("GET", url, emptyMap(), headers)
        return responses.removeFirstOrNull() ?: HttpResponse(500, "")
    }
    override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
        calls += Call("POST", url, form, headers)
        return responses.removeFirstOrNull() ?: HttpResponse(500, "")
    }
}

private val CONFIG = StravaConfig("12345", "s3cret")
private const val NOW_S = 1_800_000_000L
private fun tokenJson(access: String, refresh: String, expiresAt: Long, athlete: String = "") =
    """{"token_type":"Bearer","access_token":"$access","refresh_token":"$refresh","expires_at":$expiresAt,"expires_in":21600$athlete}"""

class StravaClientTest {
    private val time = FakeTime(LocalDate.of(2027, 1, 15), now = NOW_S * 1000)

    private fun client(http: Http, store: StravaTokenStore, config: StravaConfig = CONFIG) = StravaClient(http, store, time, config)

    private fun query(url: String): Map<String, String> = StravaClient.parseQuery(url.substringAfter('?'))

    @Test
    fun `unconfigured build reports not configured and is never connected`() = runTest {
        val c = client(ScriptedHttp(), InMemoryTokenStore(StravaTokens("a", "r", NOW_S + 9999)), StravaConfig("", ""))
        assertEquals(StravaStatus.NotConfigured, c.refreshStatus())
        assertFalse(c.isConnected())
    }

    @Test
    fun `authorization url targets mobile oauth with read scope and a state`() = runTest {
        val store = InMemoryTokenStore()
        val url = client(ScriptedHttp(), store).authorizationUrl()
        assertTrue(url.startsWith(StravaClient.AUTHORIZE_URL))
        val q = query(url)
        assertEquals("12345", q["client_id"])
        assertEquals("monarch://localhost/strava", q["redirect_uri"])
        assertEquals("code", q["response_type"])
        assertEquals("activity:read_all", q["scope"])
        assertEquals(store.state, q["state"])
        assertEquals(32, q["state"]!!.length)
    }

    @Test
    fun `redirect exchanges the code and stores tokens`() = runTest {
        val http = ScriptedHttp().apply { responses += HttpResponse(200, tokenJson("acc", "ref", NOW_S + 21600, ""","athlete":{"id":7,"firstname":"Sung","lastname":"Jinwoo"}""")) }
        val store = InMemoryTokenStore().apply { state = "abc" }
        val c = client(http, store)
        val result = c.handleRedirect("state=abc&code=CODE1&scope=read,activity:read_all")
        assertEquals(StravaAuthResult.Connected("Sung Jinwoo"), result)
        assertEquals(StravaTokens("acc", "ref", NOW_S + 21600, "Sung Jinwoo"), store.tokens)
        val form = http.calls.single().form
        assertEquals(mapOf("client_id" to "12345", "client_secret" to "s3cret", "code" to "CODE1", "grant_type" to "authorization_code"), form)
        assertEquals(StravaStatus.Connected("Sung Jinwoo"), c.status.value)
        assertNull("state is single use", store.state)
    }

    @Test
    fun `redirect rejects bad state, denial and missing scope without calling strava`() = runTest {
        val http = ScriptedHttp()
        val store = InMemoryTokenStore()
        val c = client(http, store)
        store.state = "good"
        assertTrue(c.handleRedirect("state=evil&code=X&scope=activity:read_all") is StravaAuthResult.Failed)
        store.state = "good"
        assertEquals(StravaAuthResult.Failed("Strava access was declined"), c.handleRedirect("state=good&error=access_denied"))
        store.state = "good"
        assertTrue(c.handleRedirect("state=good&code=X&scope=read") is StravaAuthResult.Failed)
        assertTrue(http.calls.isEmpty())
        assertNull(store.tokens)
    }

    @Test
    fun `token is refreshed when about to expire and the new refresh token replaces the old`() = runTest {
        val http = ScriptedHttp().apply { responses += HttpResponse(200, tokenJson("acc2", "ref2", NOW_S + 21600)) }
        val store = InMemoryTokenStore(StravaTokens("acc1", "ref1", NOW_S + 60, "A"))
        assertEquals("acc2", client(http, store).validAccessToken(forceRefresh = false))
        assertEquals("refresh_token", http.calls.single().form["grant_type"])
        assertEquals("ref1", http.calls.single().form["refresh_token"])
        assertEquals(StravaTokens("acc2", "ref2", NOW_S + 21600, "A"), store.tokens)

        // Still valid for hours: no network call.
        val quiet = ScriptedHttp()
        assertEquals("acc2", client(quiet, store).validAccessToken(forceRefresh = false))
        assertTrue(quiet.calls.isEmpty())
    }

    @Test
    fun `revoked refresh token disconnects`() = runTest {
        val http = ScriptedHttp().apply { responses += HttpResponse(400, """{"message":"Bad Request"}""") }
        val store = InMemoryTokenStore(StravaTokens("acc", "ref", NOW_S - 10))
        val c = client(http, store)
        try {
            c.validAccessToken(forceRefresh = false)
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Reconnect"))
        }
        assertNull(store.tokens)
        assertEquals(StravaStatus.Disconnected, c.status.value)
    }

    @Test
    fun `activities are paged, mapped and a 401 triggers one refresh`() = runTest {
        val page1 = (1..100).joinToString(",", "[", "]") {
            """{"id":$it,"name":"Run $it","sport_type":"TrailRun","start_date":"2027-01-14T07:00:00Z","moving_time":1800,"elapsed_time":2000}"""
        }
        val page2 = """[{"id":101,"name":"","sport_type":"WeightTraining","start_date":"2027-01-15T06:00:00Z","moving_time":0,"elapsed_time":2700},
            {"id":102,"name":"Bad date","sport_type":"Ride","start_date":"nope","moving_time":600}]"""
        val http = ScriptedHttp().apply {
            responses += HttpResponse(401, "")
            responses += HttpResponse(200, tokenJson("fresh", "ref2", NOW_S + 21600))
            responses += HttpResponse(200, page1)
            responses += HttpResponse(200, page2)
        }
        val store = InMemoryTokenStore(StravaTokens("stale", "ref1", NOW_S + 10_000))
        val out = client(http, store).workoutsBetween(Instant.ofEpochSecond(NOW_S - 86_400 * 2), Instant.ofEpochSecond(NOW_S))

        assertEquals(101, out.size)
        val first = out.first()
        assertEquals("strava:1", first.externalId)
        assertEquals(WorkoutType.RUNNING, first.type)
        assertEquals(30, first.durationMinutes)
        assertEquals(ActivitySource.STRAVA, first.source)
        assertEquals(Instant.parse("2027-01-14T07:00:00Z").toEpochMilli(), first.startEpochMs)
        val lift = out.last()
        assertEquals(WorkoutType.STRENGTH, lift.type)
        assertEquals(45, lift.durationMinutes) // elapsed time used when moving time is 0
        assertEquals("WeightTraining", lift.title)

        val gets = http.calls.filter { it.method == "GET" }
        assertEquals("Bearer stale", gets[0].headers["Authorization"])
        assertEquals("Bearer fresh", gets[1].headers["Authorization"])
        val q = query(gets[1].url)
        assertEquals((NOW_S - 86_400 * 2).toString(), q["after"])
        assertEquals("1", q["page"])
        assertEquals("2", query(gets[2].url)["page"])
    }

    @Test
    fun `rate limiting is surfaced as an error`() = runTest {
        val http = ScriptedHttp().apply { responses += HttpResponse(429, "") }
        val c = client(http, InMemoryTokenStore(StravaTokens("a", "r", NOW_S + 10_000)))
        try {
            c.workoutsBetween(Instant.ofEpochSecond(0), Instant.ofEpochSecond(NOW_S))
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("rate limit"))
        }
    }

    @Test
    fun `disconnect revokes at strava and forgets tokens even if revoke fails`() = runTest {
        val http = ScriptedHttp().apply { responses += HttpResponse(200, "") }
        val store = InMemoryTokenStore(StravaTokens("acc", "ref", NOW_S + 999))
        val c = client(http, store)
        c.disconnect()
        val revoke = http.calls.single()
        assertEquals(StravaClient.REVOKE_URL, revoke.url)
        assertEquals("ref", revoke.form["token"])
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("12345:s3cret".toByteArray()), revoke.headers["Authorization"])
        assertNull(store.tokens)

        val failing = ScriptedHttp().apply { responses += HttpResponse(500, ""); responses += HttpResponse(200, "") }
        val store2 = InMemoryTokenStore(StravaTokens("acc", "ref", NOW_S + 999))
        client(failing, store2).disconnect()
        assertEquals(StravaClient.DEAUTHORIZE_URL, failing.calls[1].url)
        assertNull(store2.tokens)
    }

    @Test
    fun `query parsing decodes values`() {
        assertEquals(mapOf("a" to "1 2", "b" to "", "scope" to "read,activity:read"), StravaClient.parseQuery("a=1+2&b=&scope=read%2Cactivity%3Aread"))
        assertTrue(StravaClient.parseQuery(null).isEmpty())
        assertEquals("x y", URLDecoder.decode("x+y", "UTF-8"))
    }
}

class StravaMapperTest {
    @Test
    fun `sport types map to stats-bearing workout types`() {
        val cases = mapOf(
            "Run" to WorkoutType.RUNNING, "VirtualRun" to WorkoutType.RUNNING, "TrailRun" to WorkoutType.RUNNING,
            "Ride" to WorkoutType.CYCLING, "MountainBikeRide" to WorkoutType.CYCLING, "EBikeRide" to WorkoutType.CYCLING,
            "Swim" to WorkoutType.SWIMMING, "Walk" to WorkoutType.WALKING, "Hike" to WorkoutType.WALKING,
            "WeightTraining" to WorkoutType.STRENGTH, "Crossfit" to WorkoutType.STRENGTH,
            "HighIntensityIntervalTraining" to WorkoutType.HIIT, "Rowing" to WorkoutType.HIIT,
            "Yoga" to WorkoutType.YOGA, "Pilates" to WorkoutType.YOGA,
            "Soccer" to WorkoutType.SPORTS, "Tennis" to WorkoutType.SPORTS,
            "Workout" to WorkoutType.OTHER, "SomethingNew" to WorkoutType.OTHER,
        )
        cases.forEach { (sport, expected) -> assertEquals(sport, expected, StravaMapper.workoutType(sport)) }
        assertEquals(WorkoutType.RUNNING, StravaMapper.workoutType(null, "Run"))
        assertEquals(WorkoutType.OTHER, StravaMapper.workoutType(null, null))
    }
}
