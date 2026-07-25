package com.mit.attendance.ui.microsoft

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class GenericFccCertCompleter(private val context: Context, private val superBlocks: List<String>, private val certName: String) {
    private val TAG = "GenericFccCertCompleter"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val fccExecutor = Executors.newFixedThreadPool(10).asCoroutineDispatcher()

    interface Callback {
        fun onStepUpdate(step: Int, message: String, progress: Int = -1, total: Int = -1)
        fun onDetailUpdate(message: String, isError: Boolean = false) {}
        fun onComplete(success: Boolean, message: String)
    }

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    suspend fun start(callback: Callback) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting $certName certification process...")
            
            // STEP 1: Fetch User Completed Challenges
            callback.onStepUpdate(1, "Fetching already completed challenges...")
            val completedIds = fetchCompletedChallengeIds()
            Log.d(TAG, "User has already completed ${completedIds.size} challenges")

            // STEP 2: Fetch Target Challenges
            callback.onStepUpdate(2, "Fetching $certName challenges...")
            callback.onDetailUpdate("Loading challenge list for $certName.")
            val allChallenges = fetchChallenges()
            if (allChallenges.isEmpty()) {
                throw Exception("No challenges found for ${superBlocks.joinToString()}")
            }

            val pendingChallenges = allChallenges.filter { it.get("id").asString !in completedIds }
            Log.d(TAG, "Total: ${allChallenges.size}, Pending: ${pendingChallenges.size}")

            if (pendingChallenges.isEmpty()) {
                callback.onComplete(true, "🎉 You have already completed all challenges for $certName!")
                return@withContext
            }

            // STEP 3: Complete Challenges
            callback.onStepUpdate(3, "Completing challenges...", 0, pendingChallenges.size)
            completeChallenges(
                challenges = pendingChallenges,
                progress = { completed, total ->
                    callback.onStepUpdate(3, "Completing challenges...", completed, total)
                },
                detail = { message, isError ->
                    callback.onDetailUpdate(message, isError)
                }
            )

            callback.onComplete(true, "🎉 $certName certificate earned! Check your FreeCodeCamp profile.")
        } catch (e: Exception) {
            Log.e(TAG, "Error in GenericFccCertCompleter", e)
            val errorMsg = when (e.message) {
                "FCC_401" -> "FreeCodeCamp session expired. Please log in."
                else -> e.message ?: "An unknown error occurred."
            }
            callback.onComplete(false, errorMsg)
        }
    }

    private suspend fun fetchCompletedChallengeIds(): Set<String> {
        val url = "https://api.freecodecamp.org/user/get-session-user"
        val cookies = FccCookieManager.getCookieHeader(context)

        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookies)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptySet()
                val body = response.body?.string() ?: return emptySet()
                val json = gson.fromJson(body, JsonObject::class.java)
                val userObj = json.getAsJsonObject("user")

                val completedIds = mutableSetOf<String>()
                userObj.keySet().forEach { key ->
                    val profile = userObj.getAsJsonObject(key)
                    val completed = profile.getAsJsonArray("completedChallenges")
                    completed?.forEach {
                        it.asJsonObject.get("id")?.asString?.let { id -> completedIds.add(id) }
                    }
                }
                completedIds
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching session user data", e)
            emptySet()
        }
    }

    private suspend fun fetchChallenges(): List<JsonObject> {
        val list = mutableListOf<JsonObject>()
        
        for (superBlock in superBlocks) {
            val challengesUrl = "https://www.freecodecamp.org/page-data/learn/$superBlock/page-data.json"
            val request = Request.Builder()
                .url(challengesUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .build()
            
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Failed to fetch FCC challenges for $superBlock. Code: ${response.code}")
                        return@use
                    }
                    val body = response.body?.string() ?: return@use
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val nodes = json.getAsJsonObject("result")
                        .getAsJsonObject("data")
                        .getAsJsonObject("allChallengeNode")
                        .getAsJsonArray("nodes")
                    
                    for (node in nodes) {
                        val challenge = node.asJsonObject.getAsJsonObject("challenge")
                        if (challenge.get("superBlock").asString == superBlock) {
                            list.add(challenge)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching challenges for $superBlock", e)
            }
        }
        return list
    }

    private suspend fun completeChallenges(
        challenges: List<JsonObject>,
        progress: (Int, Int) -> Unit,
        detail: (String, Boolean) -> Unit
    ) {
        val total = challenges.size
        var completed = 0
        val failures = mutableListOf<String>()

        coroutineScope {
            challenges.map { challenge ->
                async(fccExecutor) {
                    val id = challenge.get("id").asString
                    val type = challenge.get("challengeType").asInt
                    val title = challenge.get("title")?.asString ?: id
                    
                    val url = "https://api.freecodecamp.org/encoded/modern-challenge-completed"

                    val body = JsonObject()
                    body.addProperty("id", id)
                    body.addProperty("challengeType", type)

                    try {
                        detail("Current challenge: $title", false)
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
                            if (!resp.isSuccessful) {
                                throw Exception("Challenge '$title' failed with code ${resp.code}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error completing challenge $id", e)
                        synchronized(failures) {
                            failures += "${title}: ${e.message ?: "unknown error"}"
                        }
                        detail("Failed challenge: $title (${e.message ?: "unknown error"})", true)
                        if (e.message == "FCC_401") {
                            throw e
                        }
                    }

                    synchronized(this@GenericFccCertCompleter) {
                        completed++
                        progress(completed, total)
                    }
                }
            }.awaitAll()
        }
        if (failures.isNotEmpty()) {
            throw Exception("Failed ${failures.size} challenge(s). First failure: ${failures.first()}")
        }
    }

    private suspend fun retryFccCall(block: suspend () -> Response): Response {
        var currentDelay = 1000L
        var lastResponse: Response? = null
        repeat(3) { attempt ->
            try {
                val response = block()
                lastResponse = response
                if (response.code == 429 || response.code == 503) {
                    response.close()
                    delay(currentDelay + Random.nextLong(500))
                    currentDelay *= 2
                } else {
                    return response
                }
            } catch (e: Exception) {
                if (e.message == "FCC_401") throw e
                if (attempt == 2) throw e
                delay(currentDelay + Random.nextLong(500))
                currentDelay *= 2
            }
        }
        return lastResponse ?: throw Exception("Failed after retries")
    }
}
