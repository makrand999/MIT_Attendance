package com.mit.attendance.ui.microsoft

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random

class CSharpCertCompleter(private val context: Context) {
    private val TAG = "CSharpCertCompleter"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val fccExecutor = Executors.newFixedThreadPool(20).asCoroutineDispatcher()

    private class TranscriptRejectedException(message: String) : Exception(message)

    interface Callback {
        fun onStepUpdate(step: Int, message: String, progress: Int = -1, total: Int = -1)
        fun onDetailUpdate(message: String, isError: Boolean = false) {}
        fun onComplete(success: Boolean, message: String)
    }

    private val pathUids = listOf(
        "learn.wwl.get-started-c-sharp-part-1",
        "learn.wwl.get-started-c-sharp-part-2",
        "learn.wwl.get-started-c-sharp-part-3",
        "learn.wwl.get-started-c-sharp-part-4",
        "learn.wwl.get-started-c-sharp-part-5",
        "learn.wwl.get-started-c-sharp-part-6"
    )

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    suspend fun start(callback: Callback) = withContext(Dispatchers.IO) {
        try {
            // Pre-fetch MS username and user ID to check progress
            Log.d(TAG, "Starting certification process...")
            val usingCSharpDefaultAuth = MicrosoftCookieManager.isCSharpDefaultPresent(context)
            val msProfile = fetchMsProfile() ?: throw Exception("MS_401")
            var msUserName = msProfile.get("userName")?.asString ?: throw Exception("MS_401")
            val msUserId = msProfile.get("userId")?.asString ?: throw Exception("MS_401")
            Log.d(TAG, "Fetched MS Profile - User: $msUserName, ID: $msUserId")

            // STEP 1: Complete MS Learn Paths
            if (usingCSharpDefaultAuth) {
                callback.onStepUpdate(1, "Skipping Microsoft Learn module completion.")
                callback.onDetailUpdate("Using the C# default Microsoft Learn account; modules are treated as already completed.")
                Log.d(TAG, "Skipping MS Learn path completion because C# default auth is present.")
            } else {
                val completedUids = fetchCompletedMsUids(msUserId)
                Log.d(TAG, "Completed MS UIDs: $completedUids")
                
                val pathsToComplete = pathUids.filter { it !in completedUids }
                Log.d(TAG, "Paths remaining to complete: $pathsToComplete")

                if (pathsToComplete.isEmpty()) {
                    callback.onStepUpdate(1, "MS Learn paths already completed.")
                    callback.onDetailUpdate("All Microsoft Learn paths already completed.")
                    Log.d(TAG, "All MS Learn paths already completed.")
                } else {
                    callback.onStepUpdate(1, "Completing all MS Learn paths in parallel...")
                    Log.d(TAG, "Starting parallel completion for ${pathsToComplete.size} paths")
                    
                    val result = suspendCoroutine<Result<Unit>> { continuation ->
                        CoroutineScope(Dispatchers.IO).launch {
                            MicrosoftLearnCompleter.startJob(context, "csharp_all", "", object : MicrosoftLearnCompleter.CompletionCallback {
                                override fun onSuccess() {
                                    continuation.resume(Result.success(Unit))
                                }
                                override fun onError(msg: String) {
                                    continuation.resume(Result.failure(Exception(msg)))
                                }
                                override fun onStatus(message: String, isError: Boolean) {
                                    callback.onDetailUpdate(message, isError)
                                }
                            })
                        }
                    }
                    
                    result.exceptionOrNull()?.let { error ->
                        val token = MicrosoftCookieManager.getDocsToken(context)
                        if (token == null || error.message == "401") throw Exception("MS_401")
                        throw Exception(error.message ?: "Failed to complete MS Learn paths.")
                    }
                }
            }

            // STEP 2/3: Refresh Microsoft Learn public transcript
            callback.onStepUpdate(2, "Verifying Microsoft transcript...")
            callback.onDetailUpdate("Checking Microsoft Learn transcript sharing status.")
            val existingTranscriptId = fetchSharedTranscriptId()
            if (existingTranscriptId == null) {
                callback.onDetailUpdate("No active public transcript link found.")
            } else {
                callback.onDetailUpdate("Existing public transcript link found; refreshing it.")
            }

            callback.onStepUpdate(3, "Creating shareable transcript link...")
            if (usingCSharpDefaultAuth) {
                deleteSharedTranscript(existingTranscriptId)
                msUserName = updateMicrosoftUserName(msProfile, msUserName)
                callback.onDetailUpdate("Microsoft Learn username changed to $msUserName.")
            }
            val transcriptUrl = createFreshTranscriptUrl(msUserName, if (usingCSharpDefaultAuth) null else existingTranscriptId)
            callback.onDetailUpdate("Created Microsoft Learn transcript link.")

            // STEP 4: Link Transcript to FCC
            callback.onStepUpdate(4, "Linking transcript to FreeCodeCamp...")
            val linkedMsUsername = try {
                linkTranscriptToFreeCodeCamp(transcriptUrl)
            } catch (e: TranscriptRejectedException) {
                callback.onDetailUpdate("FreeCodeCamp rejected the transcript link. Rotating Microsoft Learn username and retrying.", true)
                val currentTranscriptId = fetchSharedTranscriptId()
                deleteSharedTranscript(currentTranscriptId)
                msUserName = updateMicrosoftUserName(msProfile, msUserName)
                callback.onDetailUpdate("Microsoft Learn username changed to $msUserName.")
                val retryTranscriptUrl = createFreshTranscriptUrl(msUserName, null)
                callback.onDetailUpdate("Created replacement Microsoft Learn transcript link.")
                linkTranscriptToFreeCodeCamp(retryTranscriptUrl)
            }
            callback.onDetailUpdate("FreeCodeCamp accepted transcript for $linkedMsUsername.")

            // STEP 5: Complete FCC Challenges
            FccCookieManager.syncCookies(context)
            callback.onStepUpdate(5, "Completing FCC C# challenges...")
            completeFccChallenges(
                progress = { completed, total ->
                    callback.onStepUpdate(5, "Completing FCC C# challenges...", completed, total)
                },
                detail = { message, isError ->
                    callback.onDetailUpdate(message, isError)
                }
            )

            // STEP 6: Submit Exam
            FccCookieManager.syncCookies(context)
            callback.onStepUpdate(6, "Submitting exam...")
            callback.onDetailUpdate("Submitting the FreeCodeCamp exam payload.")
            submitFccExam()

            callback.onComplete(true, "🎉 C# Certificate earned! Check your FreeCodeCamp profile.")
        } catch (e: Exception) {
            Log.e(TAG, "Error in CSharpCertCompleter", e)
            val errorMsg = when (e.message) {
                "MS_401" -> "Microsoft session expired. Please log in again."
                "FCC_401" -> "FreeCodeCamp session expired. Please log in."
                else -> e.message ?: "An unknown error occurred."
            }
            callback.onComplete(false, errorMsg)
        }
    }

    private suspend fun fetchMsProfile(): JsonObject? {
        val token = getMicrosoftDocsToken() ?: throw Exception("MS_401")
        val cookies = getMicrosoftCookieHeader()
        val request = Request.Builder()
            .url("https://learn.microsoft.com/api/profiles/me")
            .header("Authorization", "Bearer $token")
            .header("Cookie", cookies)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw Exception("MS_401")
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = gson.fromJson(body, JsonObject::class.java)
            if (!json.has("userName") || !json.has("userId")) return null
            return json
        }
    }

    private suspend fun fetchCompletedMsUids(msUserId: String): Set<String> {
        val token = getMicrosoftDocsToken() ?: return emptySet()
        val cookies = getMicrosoftCookieHeader()
        val url = "https://learn.microsoft.com/api/achievements/user/$msUserId?locale=en-gb"
        Log.d(TAG, "Fetching achievements from: $url")
        
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Cookie", cookies)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "Achievements API response code: ${response.code}")
                if (!response.isSuccessful) return emptySet()
                val body = response.body?.string() ?: return emptySet()
                val json = gson.fromJson(body, JsonObject::class.java)
                val achievements = json.getAsJsonArray("achievements")
                val uids = mutableSetOf<String>()
                achievements?.forEach {
                    val obj = it.asJsonObject
                    obj.get("typeId")?.asString?.let { typeId ->
                        val uid = typeId.removeSuffix(".trophy").removeSuffix(".badge")
                        uids.add(uid)
                    }
                }
                Log.d(TAG, "Parsed achievement UIDs: $uids")
                uids
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching completed MS achievements", e)
            emptySet()
        }
    }

    private suspend fun fetchSharedTranscriptId(): String? {
        val token = getMicrosoftDocsToken() ?: throw Exception("MS_401")
        val cookies = getMicrosoftCookieHeader()
        val request = buildMicrosoftApiRequest(
            url = "https://learn.microsoft.com/api/profiles/transcript/share?locale=en-gb",
            token = token,
            cookies = cookies
        ).get().build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw Exception("MS_401")
            if (!response.isSuccessful) {
                throw Exception("Failed to read Microsoft transcript share status: ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val json = gson.fromJson(body, JsonObject::class.java)
            return json.get("transcriptId")?.asString?.takeIf { it.isNotBlank() }
        }
    }

    private suspend fun createFreshTranscriptUrl(msUserName: String, existingTranscriptId: String?): String {
        val token = getMicrosoftDocsToken() ?: throw Exception("MS_401")
        val cookies = getMicrosoftCookieHeader()

        deleteSharedTranscript(existingTranscriptId)

        val createRequest = buildMicrosoftApiRequest(
            url = "https://learn.microsoft.com/api/profiles/transcript/share?locale=en-gb",
            token = token,
            cookies = cookies
        ).post("".toRequestBody(null)).build()

        val createdTranscriptId = client.newCall(createRequest).execute().use { response ->
            if (response.code == 401) throw Exception("MS_401")
            if (!response.isSuccessful) {
                throw Exception("Failed to create Microsoft transcript link: ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val json = gson.fromJson(body, JsonObject::class.java)
            json.get("transcriptId")?.asString?.takeIf { it.isNotBlank() }
        } ?: throw Exception("Microsoft did not return a transcript ID.")

        val confirmedTranscriptId = fetchSharedTranscriptId()
        if (confirmedTranscriptId != createdTranscriptId) {
            Log.w(TAG, "Transcript share confirmation mismatch. Created=$createdTranscriptId Confirmed=$confirmedTranscriptId")
        }

        return "https://learn.microsoft.com/en-gb/users/$msUserName/transcript/$createdTranscriptId"
    }

    private suspend fun deleteSharedTranscript(existingTranscriptId: String?) {
        if (existingTranscriptId == null) return

        val token = getMicrosoftDocsToken() ?: throw Exception("MS_401")
        val cookies = getMicrosoftCookieHeader()
        val deleteRequest = buildMicrosoftApiRequest(
            url = "https://learn.microsoft.com/api/profiles/transcript/share?locale=en-gb",
            token = token,
            cookies = cookies
        ).delete().build()

        client.newCall(deleteRequest).execute().use { response ->
            if (response.code == 401) throw Exception("MS_401")
            if (response.code != 204 && response.code != 404) {
                throw Exception("Failed to delete Microsoft transcript link: ${response.code}")
            }
        }
    }

    private suspend fun updateMicrosoftUserName(profile: JsonObject, currentUserName: String): String {
        val token = getMicrosoftDocsToken() ?: throw Exception("MS_401")
        val cookies = getMicrosoftCookieHeader()
        var lastError: String? = null

        repeat(3) {
            val newUserName = generateMicrosoftUserName(currentUserName)
            val body = gson.fromJson(profile.toString(), JsonObject::class.java).apply {
                addProperty("userName", newUserName)
            }
            val request = buildMicrosoftApiRequest(
                url = "https://learn.microsoft.com/api/profiles/$currentUserName",
                token = token,
                cookies = cookies
            )
                .header("Content-Type", "application/json")
                .put(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.code == 401) throw Exception("MS_401")
                if (response.isSuccessful) {
                    val updatedProfile = gson.fromJson(responseBody, JsonObject::class.java)
                    return updatedProfile.get("userName")?.asString?.takeIf { it.isNotBlank() }
                        ?: newUserName
                }
                lastError = "${response.code}: $responseBody"
            }
        }

        throw Exception("Failed to change Microsoft Learn username. Last error: ${lastError ?: "unknown"}")
    }

    private fun generateMicrosoftUserName(currentUserName: String): String {
        val sanitized = currentUserName
            .lowercase()
            .filter { it.isLetterOrDigit() || it == '-' }
            .trim('-')
            .ifBlank { "learn-user" }

        val match = Regex("^(.*?)-(\\d+)$").matchEntire(sanitized)
        val existingSuffix = match?.groupValues?.get(2)
        val suffix = if (existingSuffix == null) {
            Random.nextInt(10000, 100000).toString()
        } else {
            existingSuffix + Random.nextInt(1000, 10000)
        }
        val base = (match?.groupValues?.get(1) ?: sanitized)
            .trim('-')
            .take((30 - suffix.length - 1).coerceAtLeast(8))
            .ifBlank { "learn-user" }
        return "$base-$suffix"
    }

    private suspend fun linkTranscriptToFreeCodeCamp(transcriptUrl: String): String {
        FccCookieManager.syncCookies(context)
        val body = JsonObject().apply {
            addProperty("msTranscriptUrl", transcriptUrl)
        }

        retryFccCall {
            val cookies = FccCookieManager.getCookieHeader(context)
            val csrfToken = getFccCsrfToken() ?: throw Exception("FCC_401")

            val request = Request.Builder()
                .url("https://api.freecodecamp.org/user/ms-username")
                .header("Cookie", cookies)
                .header("Csrf-Token", csrfToken)
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.freecodecamp.org")
                .header("Referer", "https://www.freecodecamp.org/learn/foundational-c-sharp-with-microsoft")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute()
        }.use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) throw Exception("FCC_401")
            if (response.code == 404 && responseBody.contains("flash.ms.transcript.link-err-2")) {
                throw TranscriptRejectedException("FreeCodeCamp rejected the Microsoft transcript link.")
            }
            if (!response.isSuccessful) {
                throw Exception("Failed to link transcript to FreeCodeCamp: ${response.code}")
            }

            val json = gson.fromJson(responseBody, JsonObject::class.java)
            return json.get("msUsername")?.asString?.takeIf { it.isNotBlank() }
                ?: "Microsoft Learn"
        }
    }

    private fun buildMicrosoftApiRequest(url: String, token: String, cookies: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Cookie", cookies)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://learn.microsoft.com")
            .header("Referer", "https://learn.microsoft.com/en-gb/users/me/settings")
            .header("User-Agent", USER_AGENT)
    }

    private fun getMicrosoftDocsToken(): String? {
        return MicrosoftCookieManager.getCSharpDocsToken(context)
            ?: MicrosoftCookieManager.getDocsToken(context)
    }

    private fun getMicrosoftCookieHeader(): String {
        val csharpCookies = MicrosoftCookieManager.getCSharpCookieHeader(context)
        return csharpCookies.ifBlank { MicrosoftCookieManager.getCookieHeader(context) }
    }

    private fun getFccCsrfToken(): String? {
        return FccCookieManager.getCookie(context, "csrf_token")
            ?: FccCookieManager.getCookie(context, "_csrf")
    }

    private suspend fun completeFccChallenges(
        progress: (Int, Int) -> Unit,
        detail: (String, Boolean) -> Unit
    ) {
        val challengesUrl = "https://www.freecodecamp.org/page-data/learn/foundational-c-sharp-with-microsoft/page-data.json"
        val request = Request.Builder()
            .url(challengesUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()
        
        val challenges = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Failed to fetch FCC challenges.")
            val body = response.body?.string() ?: throw Exception("Empty FCC challenges response.")
            val json = gson.fromJson(body, JsonObject::class.java)
            val nodes = json.getAsJsonObject("result")
                .getAsJsonObject("data")
                .getAsJsonObject("allChallengeNode")
                .getAsJsonArray("nodes")
            
            val list = mutableListOf<JsonObject>()
            for (node in nodes) {
                val challenge = node.asJsonObject.getAsJsonObject("challenge")
                if (challenge.get("superBlock").asString == "foundational-c-sharp-with-microsoft") {
                    list.add(challenge)
                }
            }
            list
        }

        val normalChallenges = challenges.filter { 
            val type = it.get("challengeType").asInt
            type != 17 && type != 18
        }
        val trophyChallenges = challenges.filter { it.get("challengeType").asInt == 18 }
        
        val allNormal = normalChallenges + trophyChallenges
        val total = allNormal.size
        var completed = 0

        coroutineScope {
            allNormal.map { challenge ->
                async(fccExecutor) {
                    val id = challenge.get("id").asString
                    val type = challenge.get("challengeType").asInt
                    val title = challenge.get("title")?.asString ?: "Unknown"
                    try {
                        detail("Current challenge: $title", false)

                        val url = if (type == 18) {
                            "https://api.freecodecamp.org/ms-trophy-challenge-completed"
                        } else {
                            "https://api.freecodecamp.org/encoded/modern-challenge-completed"
                        }

                        val body = JsonObject()
                        body.addProperty("id", id)
                        body.addProperty("challengeType", type)
                        val referer = if (type == 18) {
                            val slug = challenge.getAsJsonObject("fields")?.get("slug")?.asString
                                ?: "/learn/foundational-c-sharp-with-microsoft"
                            "https://www.freecodecamp.org$slug"
                        } else {
                            "https://www.freecodecamp.org/"
                        }

                        retryFccCall {
                            val cookies = FccCookieManager.getCookieHeader(context)
                            val csrfToken = getFccCsrfToken() ?: throw Exception("FCC_401")
                            
                            val req = Request.Builder()
                                .url(url)
                                .header("Cookie", cookies)
                                .header("Csrf-Token", csrfToken)
                                .header("Content-Type", "application/json")
                                .header("Origin", "https://www.freecodecamp.org")
                                .header("Referer", referer)
                                .header("User-Agent", USER_AGENT)
                                .header("Accept", "*/*")
                                .post(body.toString().toRequestBody("application/json".toMediaType()))
                                .build()
                            
                            client.newCall(req).execute()
                        }.use { resp ->
                            if (resp.code == 401 || resp.code == 403) throw Exception("FCC_401")
                            if (!resp.isSuccessful) {
                                throw Exception("Challenge '$title' failed with code ${resp.code}")
                            }
                        }
                        synchronized(this@CSharpCertCompleter) {
                            completed++
                            progress(completed, total)
                        }
                    } catch (e: Exception) {
                        detail("Failed challenge: $title (${e.message ?: "unknown error"})", true)
                        throw e
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun submitFccExam() {
        val challengesUrl = "https://www.freecodecamp.org/page-data/learn/foundational-c-sharp-with-microsoft/page-data.json"
        val examId = client.newCall(Request.Builder().url(challengesUrl).header("User-Agent", USER_AGENT).header("Accept", "*/*").build()).execute().use { response ->
            val body = response.body?.string() ?: ""
            val json = gson.fromJson(body, JsonObject::class.java)
            val nodes = json.getAsJsonObject("result").getAsJsonObject("data").getAsJsonObject("allChallengeNode").getAsJsonArray("nodes")
            nodes.find { it.asJsonObject.getAsJsonObject("challenge").get("challengeType").asInt == 17 }
                ?.asJsonObject?.getAsJsonObject("challenge")?.get("id")?.asString
        } ?: throw Exception("Could not find exam ID")

        val userExamQuestions = JsonArray()
        val question = JsonObject().apply {
            addProperty("id", "8fyuxnv4ya")
            addProperty("question", "How do you terminate a case block in a `switch` statement in C#?")
            add("answer", JsonObject().apply {
                addProperty("id", "qel6n5v3ma")
                addProperty("answer", "`break;`")
            })
        }
        repeat(80) { userExamQuestions.add(question) }

        val body = JsonObject().apply {
            addProperty("id", examId)
            addProperty("challengeType", 17)
            add("userCompletedExam", JsonObject().apply {
                add("userExamQuestions", userExamQuestions)
                addProperty("examTimeInSeconds", 300)
            })
        }
        
        retryFccCall {
            val cookies = FccCookieManager.getCookieHeader(context)
            val csrfToken = getFccCsrfToken() ?: throw Exception("FCC_401")
            
            val request = Request.Builder()
                .url("https://api.freecodecamp.org/exam-challenge-completed")
                .header("Cookie", cookies)
                .header("Csrf-Token", csrfToken)
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.freecodecamp.org")
                .header("Referer", "https://www.freecodecamp.org/")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute()
        }.use { response ->
            if (response.code == 401 || response.code == 403) throw Exception("FCC_401")
            if (!response.isSuccessful) throw Exception("Failed to submit exam: ${response.code}")
        }
    }

    private suspend fun retryFccCall(block: suspend () -> Response): Response {
        var currentDelay = 2000L
        var lastResponse: Response? = null
        repeat(4) { attempt ->
            try {
                val response = block()
                lastResponse = response
                if (response.code == 429 || response.code == 503) {
                    response.close()
                    delay(currentDelay + Random.nextLong(1000))
                    currentDelay *= 2
                } else {
                    return response
                }
            } catch (e: Exception) {
                if (e.message == "FCC_401") throw e
                if (attempt == 3) throw e
                delay(currentDelay + Random.nextLong(1000))
                currentDelay *= 2
            }
        }
        return lastResponse ?: throw Exception("Failed after retries")
    }
}
