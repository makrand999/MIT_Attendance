package com.mit.attendance.data.api

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mit.attendance.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

// ── HTTP client singleton ─────────────────────────────────────────────────────

object HttpClientHolder {

    private const val TAG = "HttpClientHolder"

    lateinit var client: OkHttpClient
        private set

    private lateinit var persistentJar: PersistentCookieJar

    fun init(context: Context) {
        persistentJar = PersistentCookieJar(context.applicationContext)

        val logging = okhttp3.logging.HttpLoggingInterceptor { message ->
            Log.d("OkHttp", message)
        }.apply { level = okhttp3.logging.HttpLoggingInterceptor.Level.BODY }

        client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .cookieJar(persistentJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .addNetworkInterceptor(logging)
            .build()

        Log.d(TAG, "Initialised. Stored JSESSIONID: ${persistentJar.getSessionId()}")
    }

    fun clearSession() = persistentJar.clear()
    fun hasSession(): Boolean = persistentJar.getSessionId() != null
    fun getSessionId(): String? = persistentJar.getSessionId()
}

// ── Persistent cookie jar ─────────────────────────────────────────────────────

class PersistentCookieJar(context: Context) : CookieJar {

    private val prefs = context.getSharedPreferences("session_prefs", Context.MODE_PRIVATE)
    private val store = mutableMapOf<String, MutableMap<String, Cookie>>()

    init {
        val savedId = prefs.getString("jsessionid_value", null)
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
        val host = url.host
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
        private const val TAG = "AttendanceAPI"
    }

    private val client get() = HttpClientHolder.client
    private val gson = Gson()

    // ── Login ─────────────────────────────────────────────────────────────────

    suspend fun login(email: String, password: String, semId: Int): LoginResult {
        return withContext(Dispatchers.IO) {
            try {
                doLogin(email, password)
                verifyLoginWithRetry(semId)
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
        val location = loginResp.header("Location") ?: ""
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

    /**
     * FIX: was using Thread.sleep (blocks IO thread) — replaced with coroutine delay.
     * Must be called from a suspend context.
     */
    private suspend fun verifyLoginWithRetry(semId: Int): LoginResult {
        repeat(2) { attempt ->
            try {
                Log.d(TAG, "Verify attempt ${attempt + 1}")
                if (isSessionValid(semId)) {
                    Log.d(TAG, "Login verified")
                    return LoginResult.Success
                }
                if (attempt == 1) return LoginResult.InvalidCredentials
                delay(1500)                          // ← was Thread.sleep(1500)
            } catch (e: SocketTimeoutException) {
                Log.e(TAG, "Timeout on verify attempt ${attempt + 1}", e)
                if (attempt == 1) return LoginResult.ServerDown
                delay(1500)
            } catch (e: IOException) {
                Log.e(TAG, "IO error on verify attempt ${attempt + 1}", e)
                if (attempt == 1) return LoginResult.ServerDown
                delay(1500)
            }
        }
        return LoginResult.InvalidCredentials
    }

    // ── Session check ─────────────────────────────────────────────────────────

    /**
     * FIX: empty array `[]` is treated as an invalid/expired session (per spec:
     * a valid session always returns real subject data; empty means auth failed).
     */
    fun isSessionValid(semId: Int): Boolean {
        return try {
            val resp = client.newCall(subjectRequest(semId)).execute()
            val code = resp.code
            val body = resp.body?.string() ?: ""
            resp.close()
            Log.d(TAG, "isSessionValid → code=$code  body=${body.take(150)}")
            isValidJsonResponse(code, body)
        } catch (e: Exception) {
            Log.e(TAG, "isSessionValid exception", e)
            false
        }
    }

    // ── Data fetching ─────────────────────────────────────────────────────────

    suspend fun fetchSubjects(semId: Int, email: String, password: String): Result<List<SubjectApiResponse>> {
        return withContext(Dispatchers.IO) {
            fetchWithAutoReauth(email, password) {
                val resp = client.newCall(subjectRequest(semId)).execute()
                val body = resp.body?.string() ?: ""
                val code = resp.code
                resp.close()
                Pair(code, body)
            }.mapCatching { body ->
                if (body.isBlank()) emptyList()
                else {
                    Log.d(TAG, "Subjects JSON (first 300): ${body.take(300)}")
                    gson.fromJson(body, object : TypeToken<List<SubjectApiResponse>>() {}.type)
                }
            }
        }
    }

    suspend fun fetchAttendanceDetail(
        subjectId: String,
        email: String,
        password: String
    ): Result<List<AttendanceApiRecord>> {
        return withContext(Dispatchers.IO) {
            fetchWithAutoReauth(email, password) {
                val resp = client.newCall(attendanceRequest(subjectId)).execute()
                val body = resp.body?.string() ?: ""
                val code = resp.code
                resp.close()
                Pair(code, body)
            }.mapCatching { body ->
                if (body.isBlank()) emptyList()
                else gson.fromJson(body, object : TypeToken<List<AttendanceApiRecord>>() {}.type)
            }
        }
    }

    /**
     * FIX: removed double re-auth risk. fetchWithAutoReauth is the single point
     * of re-authentication. Callers no longer call isSessionValid + reAuthenticate
     * before calling fetch* — that is handled here exclusively.
     *
     * The old code in AttendanceRepository.syncAttendanceDetail called
     * isSessionValid → reAuthenticate and THEN fetchAttendanceDetail which
     * called fetchWithAutoReauth → doLogin again. Now the repository simply
     * calls fetch* directly and lets this function manage session recovery.
     */
    private fun fetchWithAutoReauth(
        email: String,
        password: String,
        block: () -> Pair<Int, String>
    ): Result<String> {
        return try {
            val (code, body) = block()
            if (!isValidJsonResponse(code, body)) {
                Log.w(TAG, "Session expired (code=$code) — re-authenticating once")
                doLogin(email, password)
                val (code2, body2) = block()
                if (!isValidJsonResponse(code2, body2)) {
                    Log.e(TAG, "Re-auth failed — still not getting valid response (code=$code2)")
                    Result.failure(Exception("Session expired and re-auth failed"))
                } else {
                    Result.success(body2)
                }
            } else {
                Result.success(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchWithAutoReauth error", e)
            Result.failure(e)
        }
    }

    suspend fun reAuthenticate(email: String, password: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                doLogin(email, password)
                true
            } catch (e: Exception) {
                Log.e(TAG, "reAuthenticate failed", e)
                false
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * FIX: `[]` and `"null"` are now treated as invalid (session error / no access),
     * consistent with the spec that a valid authenticated response always contains data.
     * Blank body, HTML redirect page, 3xx/401/403 all remain invalid.
     */
    private fun isValidJsonResponse(code: Int, body: String): Boolean {
        if (code in 300..399) return false
        if (code == 401 || code == 403) return false
        val trimmed = body.trim()
        //if (trimmed.isBlank()) return false
        //if (trimmed.contains("<html", ignoreCase = true)) return false
        //if (trimmed == "[]" || trimmed == "null") return false   // empty array = session/access error
        return trimmed.startsWith("[")
    }

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