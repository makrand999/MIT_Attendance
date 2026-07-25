package com.mit.attendance.data.api

import com.google.gson.Gson
import com.google.gson.JsonParser
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
                ensureActiveSession(resp, text, "Subjects")
                if (!resp.isSuccessful) error("Subjects failed (${resp.code}): $text")
                gson.fromJson(text, StudentSubjectsResponse::class.java)
            }
        }

    suspend fun checkServerAvailability(): Result<PracticalServerStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/")
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                resp.close()
                when {
                    resp.isSuccessful || resp.code in listOf(301, 302, 401, 403, 404, 405) ->
                        PracticalServerStatus.AVAILABLE
                    resp.code in listOf(502, 503, 504, 521, 522) ->
                        PracticalServerStatus.SERVER_DOWN
                    else ->
                        PracticalServerStatus.UNKNOWN
                }
            }.recover {
                val lower = it.message?.lowercase().orEmpty()
                when {
                    lower.contains("unable to resolve host") ||
                        lower.contains("failed to connect") ||
                        lower.contains("network is unreachable") ||
                        lower.contains("connection refused") ||
                        lower.contains("timeout") ||
                        lower.contains("timed out") -> PracticalServerStatus.WIFI_REQUIRED
                    else -> PracticalServerStatus.UNKNOWN
                }
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
            ensureActiveSession(resp, text, "Practicals")
            if (!resp.isSuccessful) error("Practicals failed (${resp.code}): $text")
            gson.fromJson(text, PracticalsResponse::class.java)
        }
    }

    suspend fun getStudentPracticalDraft(practicalId: Int): Result<PracticalRemoteEntry?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/lascore/practical/get-student-draft/$practicalId")
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val text = resp.body?.string() ?: ""
                if (resp.code == 404) return@runCatching null
                ensureActiveSession(resp, text, "Draft fetch")
                if (!resp.isSuccessful) error("Draft fetch failed (${resp.code}): $text")
                parseRemoteEntry(text, "draft")
            }
        }

    suspend fun getStudentPracticalSubmission(practicalId: Int): Result<PracticalRemoteEntry?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/lascore/practical/submission/student-view/$practicalId")
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val text = resp.body?.string() ?: ""
                if (resp.code == 404) return@runCatching null
                ensureActiveSession(resp, text, "Submission fetch")
                if (!resp.isSuccessful) error("Submission fetch failed (${resp.code}): $text")
                parseRemoteEntry(text, "submission")
            }
        }

    // ── Draft + Submit ─────────────────────────────────────────────────────

    suspend fun saveStudentPracticalDraft(
        payload: PracticalEditorPayload,
        attachment: PracticalAttachment? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildMultipartBody(gson.toJson(payload), attachment)

            val req = Request.Builder()
                .url("$baseUrl/lascore/practical/save-student-pract")
                .post(body)
                .build()
            val resp = client.newCall(req).execute()
            val text = resp.body?.string() ?: ""
            ensureActiveSession(resp, text, "Draft save")
            if (resp.code !in 200..201) error("Draft save failed (${resp.code}): $text")
            text
        }
    }

    suspend fun createStudentPracticalSubmission(
        payload: PracticalSubmissionPayload,
        attachment: PracticalAttachment? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildMultipartBody(gson.toJson(payload), attachment)
            val req = Request.Builder()
                .url("$baseUrl/lascore/practical/submission/create")
                .post(body)
                .build()
            val resp = client.newCall(req).execute()
            val text = resp.body?.string() ?: ""
            ensureActiveSession(resp, text, "Submission")
            if (resp.code !in 200..201) error("Submission failed (${resp.code}): $text")
            text
        }
    }

    suspend fun updateStudentPracticalSubmission(
        submissionId: Int,
        payload: PracticalSubmissionUpdatePayload,
        attachment: PracticalAttachment? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildMultipartBody(gson.toJson(payload), attachment)
            val req = Request.Builder()
                .url("$baseUrl/lascore/practical/submission/update/$submissionId")
                .post(body)
                .build()
            val resp = client.newCall(req).execute()
            val text = resp.body?.string() ?: ""
            ensureActiveSession(resp, text, "Submission update")
            if (resp.code !in 200..201) error("Submission update failed (${resp.code}): $text")
            text
        }
    }

    private fun buildMultipartBody(payloadJson: String, attachment: PracticalAttachment?): MultipartBody {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "payload",
                null,
                payloadJson.toRequestBody("application/json".toMediaType())
            )

        if (attachment != null) {
            builder.addFormDataPart(
                "practical_doc",
                attachment.fileName,
                attachment.bytes.toRequestBody(attachment.mimeType.toMediaType())
            )
        }

        return builder.build()
    }

    private fun parseRemoteEntry(text: String, envelopeKey: String): PracticalRemoteEntry? {
        if (text.isBlank()) return null
        val element = JsonParser.parseString(text)
        if (!element.isJsonObject) {
            return null
        }
        val obj = element.asJsonObject
        val nested = obj.get(envelopeKey)
        return when {
            nested != null && nested.isJsonObject -> gson.fromJson(nested, PracticalRemoteEntry::class.java)
            else -> gson.fromJson(obj, PracticalRemoteEntry::class.java)
        }
    }

    fun setToken(token: String) {
        authToken = token
    }

    private fun ensureActiveSession(response: Response, text: String, label: String) {
        if (response.code in listOf(401, 403, 419) || looksLikeExpiredSession(text)) {
            error("$label session expired (${response.code}): $text")
        }
    }

    private fun looksLikeExpiredSession(text: String): Boolean {
        val lower = text.lowercase()
        return (lower.contains("session") && lower.contains("expired")) ||
            (lower.contains("token") && lower.contains("expired")) ||
            lower.contains("jwt expired") ||
            lower.contains("unauthorized") ||
            lower.contains("unauthenticated") ||
            lower.contains("invalid token") ||
            lower.contains("login required") ||
            lower.contains("please login") ||
            lower.contains("please log in") ||
            lower.contains("not logged in")
    }
}
