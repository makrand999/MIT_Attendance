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

            Log.d("OkHttp", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.d("OkHttp", "➤ REQUEST:  ${request.method} ${request.url}")
            request.headers.forEach { (name, value) ->
                Log.d("OkHttp", "   Header: $name = $value")
            }

            val response = chain.proceed(request)

            Log.d("OkHttp", "◀ RESPONSE BY SERVER: ${response.code} ${response.message}")
            Log.d("OkHttp", "   URL: ${response.request.url}")
            response.headers.forEach { (name, value) ->
                Log.d("OkHttp", "   Header: $name = $value")
            }
            Log.d("OkHttp", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

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

        Log.d(TAG, "Initialised. Stored JSESSIONID: ${persistentJar.getSessionId()}")
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
            Log.d("CookieJar", "Restored JSESSIONID from disk: $savedId")
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
                Log.d("CookieJar", "Persisted JSESSIONID: ${cookie.value} for $host")
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host]?.values?.toList() ?: emptyList()

    fun getSessionId(): String? = prefs.getString("jsessionid_value", null)

    fun clear() {
        store.clear()
        prefs.edit().clear().apply()
        Log.d("CookieJar", "Session cleared")
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
                Log.e(TAG, "Timeout during login", e)
                LoginResult.ServerDown
            } catch (e: IOException) {
                Log.e(TAG, "IO error during login", e)
                LoginResult.ServerDown
            } catch (e: Exception) {
                Log.e(TAG, "Error during login", e)
                LoginResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun doLogin(email: String, password: String) {
        HttpClientHolder.clearSession()
        Log.d(TAG, "doLogin: cleared old session, logging in as $email")

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
        Log.d(TAG, "POST j_spring_security_check → $loginCode  Location: $location")

        if (loginCode == 302 && location.isNotEmpty()) {
            val redirectUrl = if (location.startsWith("http")) location else "$BASE_URL$location"
            client.newCall(
                Request.Builder()
                    .url(redirectUrl)
                    .get()
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "$BASE_URL/j_spring_security_check")
                    .build()
            ).execute().use { resp ->
                Log.d(TAG, "GET $redirectUrl → ${resp.code}")
            }
        }

        Log.d(TAG, "doLogin complete. JSESSIONID: ${HttpClientHolder.getSessionId()}")
    }

    // ── Session check ─────────────────────────────────────────────────────────

    /**
     * Hits the subjects endpoint and maps the outcome to a [SessionState]:
     *
     *   non-empty JSON array  → Alive   (session valid)
     *   empty [] / HTML       → Dead    (session gone, re-login needed)
     *   code 521 / timeout    → Offline (server down, do not re-login)
     *
     * Blocking — must always be called from Dispatchers.IO.
     */
    private fun checkSession(semId: Int): SessionState {
        return try {
            val resp = client.newCall(subjectRequest(semId)).execute()
            val code = resp.code
            val body = resp.body?.string() ?: ""
            resp.close()
            Log.d(TAG, "checkSession → code=$code  body=${body.take(120)}")

            when {
                isServerDown(code)    -> SessionState.Offline
                isValidJsonBody(body) -> {
                    HttpClientHolder.markActivity()
                    SessionState.Alive
                }
                else -> SessionState.Dead
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "checkSession: timeout — server likely down")
            SessionState.Offline
        } catch (e: IOException) {
            Log.w(TAG, "checkSession: IO error — server likely down")
            SessionState.Offline
        } catch (e: Exception) {
            Log.e(TAG, "checkSession: unexpected error", e)
            SessionState.Dead
        }
    }

    // ── Session gate (single re-auth point) ───────────────────────────────────

    /**
     * Every fetch call passes through here before touching the network.
     *
     * Flow:
     *   1. forceCheck=false AND session within TTL → skip network check (fast path)
     *   2. Hit endpoint to confirm state
     *      • Alive   → proceed
     *      • Offline → return failure, do NOT attempt login
     *      • Dead    → doLogin once, recheck, then proceed or fail
     *
     * forceCheck=true is used when a fetch returns [] despite a TTL-trusted session,
     * to distinguish "dead session returning []" from "genuinely no records".
     */
    private suspend fun ensureValidSession(
        email: String,
        password: String,
        semId: Int,
        forceCheck: Boolean = false
    ): SessionState {
        // Fast path — skip network check if session was recently active
        if (!forceCheck && HttpClientHolder.isSessionLikelyAlive()) {
            Log.d(TAG, "ensureValidSession: within TTL, reusing session")
            return SessionState.Alive
        }

        Log.d(TAG, "ensureValidSession: checking session (forceCheck=$forceCheck)...")
        val state = checkSession(semId)

        if (state == SessionState.Dead) {
            Log.w(TAG, "ensureValidSession: session dead — re-logging in")
            return try {
                doLogin(email, password)
                checkSession(semId)  // recheck after login
            } catch (e: Exception) {
                Log.e(TAG, "ensureValidSession: re-login threw exception", e)
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
                Log.w(TAG, "fetchSubjects: empty/invalid body — forcing session recheck")
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
            Log.d(TAG, "Subjects JSON (first 300): ${body.take(300)}")
            Result.success(gson.fromJson(body, object : TypeToken<List<SubjectApiResponse>>() {}.type))

        } catch (e: Exception) {
            Log.e(TAG, "fetchSubjects error", e)
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
                Log.w(TAG, "fetchAttendanceDetail: empty/invalid body — forcing session recheck")
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
            Log.e(TAG, "fetchAttendanceDetail error", e)
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

            Log.d(TAG, "STUDENT_INFO_RAW: $body")

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
                Log.e(TAG, "Failed to parse student info", e)
                StudentInfoResponse(null)
            }

            Result.success(data)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * 521 = Cloudflare "Web Server Is Down" — origin server unreachable.
     * Timeouts are handled separately in checkSession via exception catch.
     */
    private fun isServerDown(code: Int): Boolean = code == 521

    /**
     * A valid session response is a non-empty JSON array.
     *   []   → session invalid / no data  → Dead
     *   HTML → login redirect page        → Dead
     */
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
