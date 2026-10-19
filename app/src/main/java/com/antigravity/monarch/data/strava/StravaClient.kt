package com.antigravity.monarch.data.strava

import android.content.Context
import com.antigravity.monarch.BuildConfig
import com.antigravity.monarch.data.net.Http
import com.antigravity.monarch.data.net.formEncode
import com.antigravity.monarch.data.security.SecretCipher
import com.antigravity.monarch.domain.model.ActivitySource
import com.antigravity.monarch.domain.repository.ExternalWorkout
import com.antigravity.monarch.domain.repository.TimeProvider
import com.antigravity.monarch.domain.repository.WorkoutFeed
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URLDecoder
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

data class StravaConfig(val clientId: String, val clientSecret: String) {
    val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    companion object {
        fun fromBuildConfig() = StravaConfig(BuildConfig.STRAVA_CLIENT_ID, BuildConfig.STRAVA_CLIENT_SECRET)
    }
}

/** Persistence seam for tokens so the client can be tested without Android. */
interface StravaTokenStore {
    suspend fun load(): StravaTokens?
    suspend fun save(tokens: StravaTokens)
    suspend fun clear()
    suspend fun savePendingState(state: String?)
    suspend fun pendingState(): String?
}

/** Tokens encrypted with the Keystore and kept out of backups (see backup rules). */
@Singleton
class EncryptedStravaTokenStore @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: SecretCipher,
) : StravaTokenStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): StravaTokens? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_TOKENS, null)?.let(cipher::decrypt)?.let { runCatching { json.decodeFromString(StravaTokens.serializer(), it) }.getOrNull() }
    }

    override suspend fun save(tokens: StravaTokens) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_TOKENS, cipher.encrypt(json.encodeToString(StravaTokens.serializer(), tokens))).apply()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { prefs.edit().remove(KEY_TOKENS).remove(KEY_STATE).apply() }

    override suspend fun savePendingState(state: String?) = withContext(Dispatchers.IO) {
        prefs.edit().apply { if (state == null) remove(KEY_STATE) else putString(KEY_STATE, state) }.apply()
    }

    override suspend fun pendingState(): String? = withContext(Dispatchers.IO) { prefs.getString(KEY_STATE, null) }

    companion object {
        const val PREFS = "strava_auth"
        private const val KEY_TOKENS = "tokens"
        private const val KEY_STATE = "oauth_state"
    }
}

sealed interface StravaStatus {
    data object NotConfigured : StravaStatus
    data object Disconnected : StravaStatus
    data class Connected(val athleteName: String?) : StravaStatus
}

sealed interface StravaAuthResult {
    data class Connected(val athleteName: String?) : StravaAuthResult
    data class Failed(val message: String) : StravaAuthResult
}

/**
 * Direct Strava connection (OAuth 2, scope `activity:read_all`) used as a [WorkoutFeed].
 *
 * Flow: [authorizationUrl] opens Strava's mobile authorize page (the Strava app if installed),
 * which redirects to `monarch://localhost/strava` with a code; [handleRedirect] verifies the
 * CSRF state and exchanges the code. Access tokens last 6 hours and are refreshed on demand;
 * the newest refresh token always replaces the old one, as Strava requires.
 *
 * Note: Strava's token exchange needs the client secret, so it ships inside the app. That is
 * acceptable for a personal build; a public release should move the exchange to a backend.
 */
@Singleton
class StravaClient @Inject constructor(
    private val http: Http,
    private val store: StravaTokenStore,
    private val time: TimeProvider,
    private val config: StravaConfig,
) : WorkoutFeed {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val refreshLock = Mutex()
    private val _status = MutableStateFlow<StravaStatus>(if (config.isConfigured) StravaStatus.Disconnected else StravaStatus.NotConfigured)
    val status: StateFlow<StravaStatus> = _status.asStateFlow()

    override val source = ActivitySource.STRAVA

    suspend fun refreshStatus(): StravaStatus {
        val s = when {
            !config.isConfigured -> StravaStatus.NotConfigured
            else -> store.load()?.let { StravaStatus.Connected(it.athleteName) } ?: StravaStatus.Disconnected
        }
        _status.value = s
        return s
    }

    override suspend fun isConnected(): Boolean = config.isConfigured && store.load() != null

    suspend fun authorizationUrl(): String {
        check(config.isConfigured) { "Strava isn't configured in this build" }
        val state = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        store.savePendingState(state)
        return "$AUTHORIZE_URL?" + formEncode(
            linkedMapOf(
                "client_id" to config.clientId,
                "redirect_uri" to REDIRECT_URI,
                "response_type" to "code",
                "approval_prompt" to "auto",
                "scope" to SCOPE,
                "state" to state,
            ),
        )
    }

    /** Handles the query string of `monarch://localhost/strava?...`. */
    suspend fun handleRedirect(query: String?): StravaAuthResult {
        val params = parseQuery(query)
        val expected = store.pendingState()
        store.savePendingState(null)
        params["error"]?.let { return StravaAuthResult.Failed(if (it == "access_denied") "Strava access was declined" else "Strava error: $it") }
        if (expected == null || params["state"] != expected) return StravaAuthResult.Failed("Sign-in session expired. Please try again.")
        val code = params["code"] ?: return StravaAuthResult.Failed("Strava didn't return an authorization code")
        val granted = params["scope"].orEmpty().split(',')
        if (granted.none { it == "activity:read_all" || it == "activity:read" }) {
            return StravaAuthResult.Failed("Monarch needs permission to read your activities")
        }
        val response = http.postForm(
            TOKEN_URL,
            mapOf("client_id" to config.clientId, "client_secret" to config.clientSecret, "code" to code, "grant_type" to "authorization_code"),
        )
        if (!response.ok) return StravaAuthResult.Failed("Strava sign-in failed (HTTP ${response.code})")
        val token = runCatching { json.decodeFromString(StravaTokenResponse.serializer(), response.body) }.getOrNull()
            ?: return StravaAuthResult.Failed("Unexpected response from Strava")
        val name = token.athlete?.let { listOfNotNull(it.firstname, it.lastname).joinToString(" ").ifBlank { null } }
        store.save(StravaTokens(token.accessToken, token.refreshToken, token.expiresAt, name))
        _status.value = StravaStatus.Connected(name)
        return StravaAuthResult.Connected(name)
    }

    /** Revokes Monarch's access at Strava (best effort) and forgets the tokens locally. */
    suspend fun disconnect() {
        val tokens = store.load()
        if (tokens != null) {
            val basic = Base64.getEncoder().encodeToString("${config.clientId}:${config.clientSecret}".toByteArray())
            val revoked = runCatching {
                http.postForm(REVOKE_URL, mapOf("token" to tokens.refreshToken, "token_type_hint" to "refresh_token"), mapOf("Authorization" to "Basic $basic")).ok
            }.getOrDefault(false)
            if (!revoked) runCatching { http.postForm(DEAUTHORIZE_URL, mapOf("access_token" to tokens.accessToken)) }
        }
        store.clear()
        _status.value = if (config.isConfigured) StravaStatus.Disconnected else StravaStatus.NotConfigured
    }

    override suspend fun workoutsBetween(from: Instant, to: Instant): List<ExternalWorkout> {
        val out = mutableListOf<StravaActivity>()
        for (page in 1..MAX_PAGES) {
            val url = "$API/athlete/activities?" + formEncode(
                linkedMapOf("after" to from.epochSecond.toString(), "before" to to.epochSecond.toString(), "page" to "$page", "per_page" to "$PAGE_SIZE"),
            )
            val body = authorizedGet(url)
            val batch = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(StravaActivity.serializer()), body)
            out += batch
            if (batch.size < PAGE_SIZE) break
        }
        return out.mapNotNull(StravaMapper::toExternal)
    }

    private suspend fun authorizedGet(url: String): String {
        var response = http.get(url, mapOf("Authorization" to "Bearer ${validAccessToken(forceRefresh = false)}"))
        if (response.code == 401) {
            response = http.get(url, mapOf("Authorization" to "Bearer ${validAccessToken(forceRefresh = true)}"))
            if (response.code == 401) {
                store.clear()
                _status.value = StravaStatus.Disconnected
                throw IOException("Strava access expired. Reconnect in Settings.")
            }
        }
        if (response.code == 429) throw IOException("Strava rate limit reached. Try again later.")
        if (!response.ok) throw IOException("Strava HTTP ${response.code}")
        return response.body
    }

    /** Returns a usable access token, refreshing it when it expires within five minutes. */
    internal suspend fun validAccessToken(forceRefresh: Boolean): String = refreshLock.withLock {
        val tokens = store.load() ?: throw IOException("Strava isn't connected")
        val now = time.nowEpochMs() / 1000
        if (!forceRefresh && tokens.expiresAt - now > REFRESH_MARGIN_S) return tokens.accessToken
        val response = http.postForm(
            TOKEN_URL,
            mapOf("client_id" to config.clientId, "client_secret" to config.clientSecret, "grant_type" to "refresh_token", "refresh_token" to tokens.refreshToken),
        )
        if (response.code == 400 || response.code == 401) {
            store.clear()
            _status.value = StravaStatus.Disconnected
            throw IOException("Strava access was revoked. Reconnect in Settings.")
        }
        if (!response.ok) throw IOException("Strava token refresh failed (HTTP ${response.code})")
        val fresh = json.decodeFromString(StravaTokenResponse.serializer(), response.body)
        store.save(tokens.copy(accessToken = fresh.accessToken, refreshToken = fresh.refreshToken, expiresAt = fresh.expiresAt))
        fresh.accessToken
    }

    companion object {
        const val AUTHORIZE_URL = "https://www.strava.com/oauth/mobile/authorize"
        const val TOKEN_URL = "https://www.strava.com/oauth/token"
        const val REVOKE_URL = "https://www.strava.com/oauth/revoke"
        const val DEAUTHORIZE_URL = "https://www.strava.com/oauth/deauthorize"
        const val API = "https://www.strava.com/api/v3"
        /** Strava whitelists `localhost` as a callback domain; the custom scheme routes it back to the app. */
        const val REDIRECT_URI = "monarch://localhost/strava"
        const val SCOPE = "activity:read_all"
        private const val REFRESH_MARGIN_S = 300
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 3

        fun parseQuery(query: String?): Map<String, String> = query.orEmpty().split('&').filter { it.isNotBlank() }.associate { part ->
            val (k, v) = part.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}
