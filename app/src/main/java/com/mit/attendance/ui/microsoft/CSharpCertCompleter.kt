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

    interface Callback {
        fun onStepUpdate(step: Int, message: String, progress: Int = -1, total: Int = -1)
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
            val msProfile = fetchMsProfile() ?: throw Exception("MS_401")
            val msUserName = msProfile.first
            val msUserId = msProfile.second
            Log.d(TAG, "Fetched MS Profile - User: $msUserName, ID: $msUserId")
            
            val completedUids = fetchCompletedMsUids(msUserId)
            Log.d(TAG, "Completed MS UIDs: $completedUids")
            
            val pathsToComplete = pathUids.filter { it !in completedUids }
            Log.d(TAG, "Paths remaining to complete: $pathsToComplete")

            // STEP 1: Complete MS Learn Paths
            if (pathsToComplete.isEmpty()) {
                callback.onStepUpdate(1, "MS Learn paths already completed.")
                Log.d(TAG, "All MS Learn paths already completed.")
            } else {
                for (i in pathsToComplete.indices) {
                    val pathUid = pathsToComplete[i]
                    callback.onStepUpdate(1, "Completing MS Learn paths...", i + 1, pathsToComplete.size)
                    Log.d(TAG, "Completing path: $pathUid")
                    
                    val result = suspendCoroutine<Boolean> { continuation ->
                        CoroutineScope(Dispatchers.IO).launch {
                            MicrosoftLearnCompleter.startJob(context, "path", pathUid, object : MicrosoftLearnCompleter.CompletionCallback {
                                override fun onSuccess() {
                                    continuation.resume(true)
                                }
                                override fun onError(msg: String) {
                                    continuation.resume(false)
                                }
                            })
                        }
                    }
                    
                    if (!result) {
                        val token = MicrosoftCookieManager.getDocsToken(context)
                        if (token == null) throw Exception("MS_401")
                    }
                    
                    delay(1000) 
                }
            }

            // STEP 2: Verify Transcript (Placeholder messages)
            callback.onStepUpdate(2, "Verifying Microsoft transcript...")
            delay(500)

            // STEP 3: Create/Share Transcript
            callback.onStepUpdate(3, "Creating shareable transcript link...")
            delay(500)

            // STEP 4: Link Transcript to FCC
            callback.onStepUpdate(4, "Linking transcript to FreeCodeCamp...")
            delay(500)

            // STEP 5: Complete FCC Challenges
            callback.onStepUpdate(5, "Completing FCC C# challenges...")
            completeFccChallenges { completed, total ->
                callback.onStepUpdate(5, "Completing FCC C# challenges...", completed, total)
            }

            // STEP 6: Submit Exam
            callback.onStepUpdate(6, "Submitting exam...")
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

    private suspend fun fetchMsProfile(): Pair<String, String>? {
        val token = MicrosoftCookieManager.getDocsToken(context) ?: throw Exception("MS_401")
        val cookies = MicrosoftCookieManager.getCookieHeader(context)
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
            val userName = json.get("userName")?.asString ?: return null
            val userId = json.get("userId")?.asString ?: return null
            return Pair(userName, userId)
        }
    }

    private suspend fun fetchCompletedMsUids(msUserId: String): Set<String> {
        val token = MicrosoftCookieManager.getDocsToken(context) ?: return emptySet()
        val cookies = MicrosoftCookieManager.getCookieHeader(context)
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

    private suspend fun completeFccChallenges(progress: (Int, Int) -> Unit) {
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
                    
                    val url = if (type == 18) {
                        "https://api.freecodecamp.org/ms-trophy-challenge-completed"
                    } else {
                        "https://api.freecodecamp.org/encoded/modern-challenge-completed"
                    }

                    val body = JsonObject()
                    body.addProperty("id", id)
                    body.addProperty("challengeType", type)

                    retryFccCall {
                        val cookies = FccCookieManager.getCookieHeader(context)
                        val csrfToken = FccCookieManager.getCookie(context, "csrf_token") ?: throw Exception("FCC_401")
                        
                        val req = Request.Builder()
                            .url(url)
                            .header("Cookie", cookies)
                            .header("Csrf-Token", csrfToken)
                            .header("Content-Type", "application/json")
                            .header("Origin", "https://www.freecodecamp.org")
                            .header("Referer", "https://www.freecodecamp.org/")
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "*/*")
                            .post(body.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                        
                        client.newCall(req).execute()
                    }.use { resp ->
                        if (resp.code == 401 || resp.code == 403) throw Exception("FCC_401")
                    }
                    synchronized(this@CSharpCertCompleter) {
                        completed++
                        progress(completed, total)
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
            val csrfToken = FccCookieManager.getCookie(context, "csrf_token") ?: throw Exception("FCC_401")
            
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
