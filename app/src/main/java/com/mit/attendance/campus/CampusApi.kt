package com.mit.attendance.campus

import android.content.Context
import java.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.mit.attendance.model.UserCredentials
import com.mit.attendance.data.prefs.UserPreferences
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

interface CampusApi {
    @POST("auth/erp-login") suspend fun signIn(@Body body: CampusCredentials): CampusLogin
    @GET("auth/me") suspend fun profile(@Header("Authorization") authorization: String): CampusMe
}
class CampusCredentials(val username: String, val password: String) {
    override fun toString() = "CampusCredentials(redacted)"
}
class CampusLogin(val token: String, val user: CampusProfile) {
    override fun toString() = "CampusLogin(redacted)"
}
data class CampusMe(val user: CampusProfile)

object CampusHttp {
    const val API_BASE = "https://mitasia.in/akatsuki/api/"
    const val WEB_WORKSPACE = "https://mitasia.in/akatsuki/"
    // No cookies, GitHub interceptors, logging, redirects, or user-controlled destinations.
    internal fun client() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(30, TimeUnit.SECONDS).build()
    fun create(): CampusApi = create(client())
    internal fun create(client: OkHttpClient): CampusApi = Retrofit.Builder().baseUrl(API_BASE)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create()).build().create(CampusApi::class.java)
}

class CampusRepository internal constructor(private val api: CampusApi, val store: CampusSessions,
    private val savedCredentials: suspend () -> UserCredentials?) {
    companion object { private val reconnectLock = Mutex() }
    constructor(context: Context) : this(CampusHttp.create(), CampusSessionStore(context), {
        val preferences = UserPreferences(context.applicationContext)
        if (preferences.isLoggedInSnapshot()) preferences.getCredentialsSnapshot() else null
    })
    suspend fun cached(): CampusSession? {
        val username = savedCredentials()?.email?.trim() ?: return null
        return store.read()?.takeIf { it.erpUsername == username }
    }
    private suspend fun connect(epoch: String, expected: CampusSession? = null): CampusSession {
        check(store.epoch() == epoch) { "Campus session changed." }
        val credentials = savedCredentials() ?: error("Sign in to the attendance app first.")
        check(credentials.email.isNotBlank() && credentials.password.isNotEmpty()) {
            "ERP credentials are missing. Sign in to the attendance app again."
        }
        val username = credentials.email.trim()
        val password = credentials.password
        val old = store.read()
        val response = api.signIn(CampusCredentials(username.trim(), password))
        // /me validates the signed token; decoding exp here is only for local expiry handling.
        val me = api.profile("Bearer ${response.token}").user
        check(me.erp_verified && me.id == response.user.id) { "Campus identity could not be verified." }
        check(expected == null || (me.id == expected.profile.id && username == expected.erpUsername)) {
            "Campus identity changed. Open Campus again."
        }
        val payload = response.token.split('.').getOrNull(1) ?: error("Invalid campus session.")
        val expiry = Gson().fromJson(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8), JsonObject::class.java).get("exp").asLong
        val current = savedCredentials()
        check(current != null && current.email.trim() == username && current.password == password) {
            "ERP account changed. Open Campus again."
        }
        val generation = old?.takeIf { it.profile.id == me.id && it.erpUsername == username }?.generation
            ?: UUID.randomUUID().toString()
        val session = CampusSession(response.token, expiry, me, generation = generation, erpUsername = username,
            selectedClass = old?.takeIf { it.profile.id == me.id }?.selectedClass)
        check(session.isValid()) { "Campus session has expired. Sign in again." }
        check(store.commitLogin(epoch, old?.generation, session)) { "Campus session changed. Sign in again." }
        return session
    }
    suspend fun refresh(): CampusSession {
        val epoch = store.epoch()
        val credentials = savedCredentials() ?: error("Sign in to the attendance app first.")
        val session = store.read()
        if (session == null || session.erpUsername != credentials.email.trim()) return reconnectLock.withLock { connect(epoch) }
        if (!session.isValid()) return reconnect(session)
        try {
            val profile = api.profile("Bearer ${session.token}").user
            check(profile.id == session.profile.id && profile.erp_verified) { "Campus identity could not be verified." }
            check(savedCredentials()?.email?.trim() == session.erpUsername) { "ERP account changed." }
            val updated = CampusSession(session.token, session.expiresAt, profile, session.generation, session.selectedClass, session.erpUsername)
            check(store.replace(session.generation, updated)) { "Campus session changed." }
            return updated
        } catch (e: HttpException) {
            if (e.code() != 401) throw e
            // One bounded reconnect using the app's existing credentials; no retry loop.
            return reconnect(session)
        }
    }
    /** One reconnect per failed operation; concurrent requests share the newly issued token. */
    suspend fun reconnect(expected: CampusSession): CampusSession = reconnectLock.withLock {
        val current = store.read()
        check(current != null && current.generation == expected.generation &&
            current.profile.id == expected.profile.id && current.selectedClass == expected.selectedClass &&
            savedCredentials()?.email?.trim() == expected.erpUsername) { "Campus account or class changed." }
        if (current.token != expected.token && current.isValid()) return@withLock current
        try { connect(store.epoch(), current) }
        catch (e: HttpException) {
            if (e.code() == 401) store.invalidate(expected.generation)
            throw e
        }
    }
}
fun campusError(error: Throwable): String {
    if (error is CancellationException) throw error
    return when (error) {
        is HttpException -> when (error.code()) {
            401 -> "Saved ERP credentials were rejected. Sign in to the attendance app again."
            403 -> "Your campus account is not authorized."
            409 -> "This account needs administrator review."
            429 -> "Too many attempts. Try again in a minute."
            502, 503 -> "The campus ERP is unavailable. Cached attendance and notes are still available."
            else -> "Campus request failed (${error.code()}). Try again."
        }
        is java.io.IOException -> "Campus is unreachable. Showing the cached profile, if available."
        is IllegalStateException, is IllegalArgumentException -> error.message ?: "Sign in again."
        else -> "Campus request failed. Try again."
    }
}
