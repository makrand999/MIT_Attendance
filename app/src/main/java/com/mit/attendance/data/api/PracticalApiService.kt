package com.mit.attendance.data.api

import com.google.gson.Gson
import com.mit.attendance.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class PracticalApiService(private val baseUrl: String = "http://10.10.30.203") {

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Shared client — caller must set token via newBuilder interceptor
    private var authToken: String? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder().apply {
                    header("Accept", "application/json, text/plain, */*")
                    header("User-Agent",
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36")
                    header("Origin", baseUrl)
                    authToken?.let {
                        header("Authorization", "Bearer $it")
                        header("Referer", "$baseUrl/dashboard")
                    }
                }.build()
                chain.proceed(req)
            }
            .build()
    }

    // ── Login ──────────────────────────────────────────────────────────────

    suspend fun login(email: String, password: String): Result<PracticalLoginResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = gson.toJson(PracticalLoginRequest(email, password))
                    .toRequestBody(JSON)
                val req = Request.Builder()
                    .url("$baseUrl/lascore/login/")
                    .post(body)
                    .header("Content-Type", "application/json")
                    .header("Referer", "$baseUrl/login")
                    .build()
                val resp = client.newCall(req).execute()
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) error("Login failed (${resp.code}): $text")
                val result = gson.fromJson(text, PracticalLoginResponse::class.java)
                authToken = result.token
                result
            }
        }

    // ── Subjects ───────────────────────────────────────────────────────────

    suspend fun getStudentSubjects(): Result<StudentSubjectsResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/lascore/subjects/student-subject")
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) error("Subjects failed (${resp.code}): $text")
                gson.fromJson(text, StudentSubjectsResponse::class.java)
            }
        }

    // ── Practicals ─────────────────────────────────────────────────────────

    suspend fun getPracticals(
        subjectId: Int,
        divisionId: Int
    ): Result<PracticalsResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/lascore/practical/subject-practicals/student/$subjectId/$divisionId")
                .get()
                .header("Referer", "$baseUrl/dashboard/studentPracticalView/$subjectId/1")
                .build()
            val resp = client.newCall(req).execute()
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) error("Practicals failed (${resp.code}): $text")
            gson.fromJson(text, PracticalsResponse::class.java)
        }
    }

    // ── Submit ─────────────────────────────────────────────────────────────

    suspend fun submitPractical(
        subjectId: Int,
        divisionId: Int,
        practicalId: Int,
        practicalNumber: Int,
        language: String,
        theory: String,
        conclusion: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = PracticalSubmitPayload(
                practical  = practicalId,
                division   = divisionId,
                subjects   = subjectId,
                language   = language,
                theory     = theory,
                code       = "",
                output     = "",
                error      = "",
                conclusion = conclusion
            )
            val payloadJson = gson.toJson(payload)
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("payload", null,
                    payloadJson.toRequestBody("application/json".toMediaType()))
                .build()

            val req = Request.Builder()
                .url("$baseUrl/lascore/practical/save-student-pract")
                .post(body)
                .header("Referer",
                    "$baseUrl/dashboard/studentPracticalView/$subjectId/$practicalNumber")
                .build()
            val resp = client.newCall(req).execute()
            val text = resp.body?.string() ?: ""
            if (resp.code !in 200..201) error("Submit failed (${resp.code}): $text")
            text
        }
    }

    fun setToken(token: String) {
        authToken = token
    }
}