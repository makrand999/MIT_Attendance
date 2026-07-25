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

class ResponsiveWebDesignCompleter(private val context: Context) {
    private val TAG = "ResponsiveWebDesignCompleter"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val fccExecutor = Executors.newFixedThreadPool(10).asCoroutineDispatcher()

    interface Callback {
        fun onStepUpdate(step: Int, message: String, progress: Int = -1, total: Int = -1)
        fun onComplete(success: Boolean, message: String)
    }

    private val SUPER_BLOCK = "responsive-web-design-v9"
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    suspend fun start(callback: Callback) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting Responsive Web Design certification process...")
            
            // STEP 1: Fetch Challenges
            callback.onStepUpdate(1, "Fetching Responsive Web Design challenges...")
            val challenges = fetchChallenges()
            if (challenges.isEmpty()) {
                throw Exception("No challenges found for $SUPER_BLOCK")
            }
            Log.d(TAG, "Fetched ${challenges.size} challenges")

            // STEP 2: Complete Challenges
            callback.onStepUpdate(2, "Completing challenges...", 0, challenges.size)
            completeChallenges(challenges) { completed, total ->
                callback.onStepUpdate(2, "Completing challenges...", completed, total)
            }

            callback.onComplete(true, "🎉 Responsive Web Design certificate earned! Check your FreeCodeCamp profile.")
        } catch (e: Exception) {
            Log.e(TAG, "Error in ResponsiveWebDesignCompleter", e)
            val errorMsg = when (e.message) {
                "FCC_401" -> "FreeCodeCamp session expired. Please log in."
                else -> e.message ?: "An unknown error occurred."
            }
            callback.onComplete(false, errorMsg)
        }
    }

    private suspend fun fetchChallenges(): List<JsonObject> {
        val challengesUrl = "https://www.freecodecamp.org/page-data/learn/$SUPER_BLOCK/page-data.json"
        val request = Request.Builder()
            .url(challengesUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()
        
        return client.newCall(request).execute().use { response ->
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
                if (challenge.get("superBlock").asString == SUPER_BLOCK) {
                    list.add(challenge)
                }
            }
            list
        }
    }

    private suspend fun completeChallenges(challenges: List<JsonObject>, progress: (Int, Int) -> Unit) {
        val total = challenges.size
        var completed = 0

        coroutineScope {
            challenges.map { challenge ->
                async(fccExecutor) {
                    val id = challenge.get("id").asString
                    val type = challenge.get("challengeType").asInt
                    
                    val url = "https://api.freecodecamp.org/encoded/modern-challenge-completed"

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
                    synchronized(this@ResponsiveWebDesignCompleter) {
                        completed++
                        progress(completed, total)
                    }
                }
            }.awaitAll()
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
