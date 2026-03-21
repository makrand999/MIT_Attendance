package com.mit.attendance.data.api

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mit.attendance.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

// ── Session state ─────────────────────────────────────────────────────────────
//
//  Alive   → JSESSIONID valid, server returned real JSON data
//  Dead    → JSESSIONID missing / expired, need to re-login
//  Offline → Server returned 521 or timed out — do NOT attempt login, retry later

sealed class SessionState {
    object Alive   : SessionState()
    object Dead    : SessionState()
    object Offline : SessionState()
}

// ── HTTP client singleton ─────────────────────────────────────────────────────

object HttpClientHolder {

    private const val TAG = "HttpClientHolder"

    lateinit var client: OkHttpClient
        private set

    private lateinit var persistentJar: PersistentCookieJar

    // Server session lives ~10 min; we conservatively trust ours for 9 min to
    // avoid an unnecessary network round-trip on every fetch call.
    private const val SESSION_TTL_MS = 9 * 60 * 1000L
    private var lastActivityMs: Long = 0L

    fun init(context: Context) {
        persistentJar = PersistentCookieJar(context.applicationContext)

        // Logs method, URL, response code and headers only — no body / HTML spam
        val cleanLogging = Interceptor { chain ->
            val request = chain.request()

            val response = chain.proceed(request)

            response // Never consume the body here — callers still need it
        }

        client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .cookieJar(persistentJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .addNetworkInterceptor(cleanLogging)
            .build()
    }

    fun clearSession() {
        persistentJar.clear()
        lastActivityMs = 0L
    }

    fun hasSession(): Boolean = persistentJar.getSessionId() != null
    fun getSessionId(): String? = persistentJar.getSessionId()

    /** Call after every successful API response to keep the TTL window fresh. */
    fun markActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    /**
     * Returns true if a JSESSIONID exists AND was active within the last 9 minutes.
     * Skips a network round-trip when the session is almost certainly still alive.
     */
    fun isSessionLikelyAlive(): Boolean {
        if (getSessionId() == null) return false
        return (System.currentTimeMillis() - lastActivityMs) < SESSION_TTL_MS
    }
}

// ── Persistent cookie jar ─────────────────────────────────────────────────────

class PersistentCookieJar(context: Context) : CookieJar {

    private val prefs = context.getSharedPreferences("session_prefs", Context.MODE_PRIVATE)
    private val store = mutableMapOf<String, MutableMap<String, Cookie>>()

    init {
        val savedId     = prefs.getString("jsessionid_value", null)
        val savedDomain = prefs.getString("jsessionid_domain", null)
        if (savedId != null && savedDomain != null) {
            val cookie = Cookie.Builder()
                .name("JSESSIONID")
                .value(savedId)
                .hostOnlyDomain(savedDomain)
                .path("/")
                .build()
            store.getOrPut(savedDomain) { mutableMapOf() }["JSESSIONID"] = cookie
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host    = url.host
        val hostMap = store.getOrPut(host) { mutableMapOf() }
        for (cookie in cookies) {
            hostMap[cookie.name] = cookie
            if (cookie.name == "JSESSIONID") {
                prefs.edit()
                    .putString("jsessionid_value", cookie.value)
                    .putString("jsessionid_domain", host)
                    .apply()
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host]?.values?.toList() ?: emptyList()

    fun getSessionId(): String? = prefs.getString("jsessionid_value", null)

    fun clear() {
        store.clear()
        prefs.edit().clear().apply()
    }
}

// ── API service ───────────────────────────────────────────────────────────────

class AttendanceApiService {

    companion object {
        private const val BASE_URL = "https://erp.mit.asia"
        private const val TAG      = "AttendanceAPI"
    }

    private val client get() = HttpClientHolder.client
    private val gson = Gson()

    // ── Login ─────────────────────────────────────────────────────────────────

    suspend fun login(email: String, password: String, semId: Int): LoginResult {
        return withContext(Dispatchers.IO) {
            try {
                doLogin(email, password)
                when (checkSession(semId)) {
                    SessionState.Alive   -> LoginResult.Success
                    SessionState.Dead    -> LoginResult.InvalidCredentials
                    SessionState.Offline -> LoginResult.ServerDown
                }
            } catch (e: SocketTimeoutException) {
                LoginResult.ServerDown
            } catch (e: IOException) {
                LoginResult.ServerDown
            } catch (e: Exception) {
                LoginResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun doLogin(email: String, password: String) {
        HttpClientHolder.clearSession()

        val body = FormBody.Builder()
            .add("j_username", email)
            .add("j_password", password)
            .build()

        val loginResp = client.newCall(
            Request.Builder()
                .url("$BASE_URL/j_spring_security_check")
                .post(body)
                .header("Origin", BASE_URL)
                .header("Referer", "$BASE_URL/login.htm")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "Mozilla/5.0")
                .build()
        ).execute()

        val loginCode = loginResp.code
        val location  = loginResp.header("Location") ?: ""
        loginResp.close()

        if (loginCode == 302 && location.isNotEmpty()) {
            val redirectUrl = if (location.startsWith("http")) location else "$BASE_URL$location"
            client.newCall(
                Request.Builder()
                    .url(redirectUrl)
                    .get()
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "$BASE_URL/j_spring_security_check")
                    .build()
            ).execute().use { resp -> }
        }
    }

    // ── Session check ─────────────────────────────────────────────────────────

    private fun checkSession(semId: Int): SessionState {
        return try {
            val resp = client.newCall(subjectRequest(semId)).execute()
            val code = resp.code
            val body = resp.body?.string() ?: ""
            resp.close()

            when {
                isServerDown(code)    -> SessionState.Offline
                isValidJsonBody(body) -> {
                    HttpClientHolder.markActivity()
                    SessionState.Alive
                }
                else -> SessionState.Dead
            }
        } catch (e: SocketTimeoutException) {
            SessionState.Offline
        } catch (e: IOException) {
            SessionState.Offline
        } catch (e: Exception) {
            SessionState.Dead
        }
    }

    // ── Session gate (single re-auth point) ───────────────────────────────────

    private suspend fun ensureValidSession(
        email: String,
        password: String,
        semId: Int,
        forceCheck: Boolean = false
    ): SessionState {
        // Fast path — skip network check if session was recently active
        if (!forceCheck && HttpClientHolder.isSessionLikelyAlive()) {
            return SessionState.Alive
        }

        val state = checkSession(semId)

        if (state == SessionState.Dead) {
            return try {
                doLogin(email, password)
                checkSession(semId)  // recheck after login
            } catch (e: Exception) {
                SessionState.Dead
            }
        }

        return state
    }

    // ── Data fetching ─────────────────────────────────────────────────────────

    suspend fun fetchSubjects(
        semId: Int,
        email: String,
        password: String
    ): Result<List<SubjectApiResponse>> = withContext(Dispatchers.IO) {
        try {
            // Ensure session is valid before fetching
            when (ensureValidSession(email, password, semId)) {
                SessionState.Offline -> return@withContext Result.failure(Exception("Server is offline. Please try again later."))
                SessionState.Dead    -> return@withContext Result.failure(Exception("Re-login failed. Check credentials."))
                SessionState.Alive   -> Unit
            }

            val resp = client.newCall(subjectRequest(semId)).execute()
            val code = resp.code
            val body = resp.body?.string() ?: ""
            resp.close()

            if (isServerDown(code)) {
                return@withContext Result.failure(Exception("Server is offline. Please try again later."))
            }

            // [] received — could be dead session that slipped through TTL, force recheck
            if (body.trim() == "[]" || !isValidJsonBody(body)) {
                when (ensureValidSession(email, password, semId, forceCheck = true)) {
                    SessionState.Offline -> return@withContext Result.failure(Exception("Server is offline. Please try again later."))
                    SessionState.Dead    -> return@withContext Result.failure(Exception("Re-login failed. Check credentials."))
                    SessionState.Alive   -> Unit
                }
                // Retry fetch once after forced recheck
                val resp2 = client.newCall(subjectRequest(semId)).execute()
                val body2 = resp2.body?.string() ?: ""
                resp2.close()
                HttpClientHolder.markActivity()
                return@withContext Result.success(
                    if (isValidJsonBody(body2))
                        gson.fromJson(body2, object : TypeToken<List<SubjectApiResponse>>() {}.type)
                    else
                        emptyList() // genuinely empty after verified session
                )
            }

            HttpClientHolder.markActivity()
            Result.success(gson.fromJson(body, object : TypeToken<List<SubjectApiResponse>>() {}.type))

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchAttendanceDetail(
        subjectId: String,
        semId: Int,
        email: String,
        password: String
    ): Result<List<AttendanceApiRecord>> = withContext(Dispatchers.IO) {
        try {
            // Ensure session is valid before fetching
            when (ensureValidSession(email, password, semId)) {
                SessionState.Offline -> return@withContext Result.failure(Exception("Server is offline. Please try again later."))
                SessionState.Dead    -> return@withContext Result.failure(Exception("Re-login failed. Check credentials."))
                SessionState.Alive   -> Unit
            }

            val resp = client.newCall(attendanceRequest(subjectId)).execute()
            val code = resp.code
            val body = resp.body?.string() ?: ""
            resp.close()

            if (isServerDown(code)) {
                return@withContext Result.failure(Exception("Server is offline. Please try again later."))
            }

            // [] received — could be dead session that slipped through TTL, force recheck
            // After recheck: if still [] then subject genuinely has no records yet
            if (body.trim() == "[]" || !isValidJsonBody(body)) {
                when (ensureValidSession(email, password, semId, forceCheck = true)) {
                    SessionState.Offline -> return@withContext Result.failure(Exception("Server is offline. Please try again later."))
                    SessionState.Dead    -> return@withContext Result.failure(Exception("Re-login failed. Check credentials."))
                    SessionState.Alive   -> Unit
                }
                // Retry fetch once after forced recheck
                val resp2 = client.newCall(attendanceRequest(subjectId)).execute()
                val body2 = resp2.body?.string() ?: ""
                resp2.close()
                HttpClientHolder.markActivity()
                return@withContext Result.success(
                    if (isValidJsonBody(body2))
                        gson.fromJson(body2, object : TypeToken<List<AttendanceApiRecord>>() {}.type)
                    else
                        emptyList() // genuinely empty after verified session
                )
            }

            HttpClientHolder.markActivity()
            Result.success(gson.fromJson(body, object : TypeToken<List<AttendanceApiRecord>>() {}.type))

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchStudentInfo(
        email: String,
        password: String,
        semId: Int
    ): Result<StudentInfoResponse> = withContext(Dispatchers.IO) {
        try {
            when (ensureValidSession(email, password, semId)) {
                SessionState.Offline -> return@withContext Result.failure(Exception("Server offline"))
                SessionState.Dead    -> return@withContext Result.failure(Exception("Session dead"))
                SessionState.Alive   -> Unit
            }

            val resp = client.newCall(studentInfoRequest()).execute()
            val body = resp.body?.string() ?: ""
            resp.close()

            if (body.contains("<html", ignoreCase = true)) {
                return@withContext Result.failure(Exception("Invalid response"))
            }

            // ERP usually returns a list [ { "Gender": "..." } ]
            val data = try {
                if (body.trim().startsWith("[")) {
                    val listType = object : TypeToken<List<StudentInfoResponse>>() {}.type
                    val list: List<StudentInfoResponse> = gson.fromJson(body, listType)
                    list.firstOrNull() ?: StudentInfoResponse(null)
                } else {
                    gson.fromJson(body, StudentInfoResponse::class.java)
                }
            } catch (e: Exception) {
                StudentInfoResponse(null)
            }

            Result.success(data)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isServerDown(code: Int): Boolean = code == 521

    private fun isValidJsonBody(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.contains("<html", ignoreCase = true)) return false
        if (trimmed == "[]" || trimmed == "null") return false
        return trimmed.startsWith("[")
    }

    private fun studentInfoRequest(): Request =
        Request.Builder()
            .url("$BASE_URL/stu_getStudentPersonalinfo.json")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$BASE_URL/stu_studentProfile.htm")
            .header("User-Agent", "Mozilla/5.0")
            .get()
            .build()

    private fun subjectRequest(semId: Int): Request =
        Request.Builder()
            .url("$BASE_URL/stu_getSubjectOnChangeWithSemId1.json?termId=$semId&refreshData=0&subjectwisestudentids=&subejctwiseBatchIds=&batchsemestercapacity=")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$BASE_URL/studentCourseFileNew.htm?shwA=%2700A%27")
            .header("User-Agent", "Mozilla/5.0")
            .get().build()

    private fun attendanceRequest(subjectId: String): Request =
        Request.Builder()
            .url("$BASE_URL/stu_getSubjectWiseStudentAttendanceCoursceFile.json?subjectWiseStudentID=$subjectId")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$BASE_URL/studentCourseFileNew.htm?shwA=%2700A%27")
            .header("User-Agent", "Mozilla/5.0")
            .get().build()

}
