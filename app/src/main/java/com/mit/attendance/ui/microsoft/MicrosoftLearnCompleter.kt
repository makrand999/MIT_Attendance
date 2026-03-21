package com.mit.attendance.ui.microsoft

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

object MicrosoftLearnCompleter {
    private const val TAG = "MSLearnCompleter"
    private const val BASE_URL = "https://learn.microsoft.com"
    private val gson = Gson()
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    interface CompletionCallback {
        fun onSuccess()
        fun onError(msg: String)
    }

    data class UnitModel(
        val uid: String,
        val url: String?,
        val points: Int,
        val title: String? = null,
        val module_assessment: Boolean = false
    )

    data class ModuleModel(
        val uid: String,
        val title: String? = null,
        val units: List<UnitModel>? = null
    )

    suspend fun startJob(context: Context, type: String, uid: String, callback: CompletionCallback) {
        Log.d(TAG, "[START] Job for $type: $uid")
        withContext(Dispatchers.IO) {
            try {
                val token = MicrosoftCookieManager.getDocsToken(context)
                if (token == null) {
                    Log.e(TAG, "[ERROR] DocsToken not found in cookies")
                    withContext(Dispatchers.Main) { callback.onError("401") }
                    return@withContext
                }

                val cookieHeader = MicrosoftCookieManager.getCookieHeader(context)
                
                if (type == "path") {
                    completePath(context, uid, token, cookieHeader)
                } else {
                    completeModule(context, uid, token, cookieHeader)
                }

                Log.d(TAG, "[SUCCESS] All tasks completed for $uid")
                withContext(Dispatchers.Main) { callback.onSuccess() }
            } catch (e: Exception) {
                Log.e(TAG, "[FATAL] Job failed", e)
                withContext(Dispatchers.Main) { 
                    if (e.message?.contains("401") == true) callback.onError("401")
                    else callback.onError(e.message ?: "Unknown error")
                }
            }
        }
    }

    private suspend fun completePath(context: Context, pathUid: String, token: String, cookies: String) {
        val url = "$BASE_URL/api/hierarchy/paths/$pathUid?locale=en-gb"
        Log.d(TAG, "  [PATH] Fetching hierarchy: $url")
        val request = buildRequest(url, token, cookies).get().build()
        
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw Exception("401")
            if (!response.isSuccessful) {
                Log.e(TAG, "  [PATH] Failed to fetch path: ${response.code}")
                return
            }

            val body = response.body?.string() ?: return
            val json = gson.fromJson(body, JsonObject::class.java)
            val modulesJson = json.getAsJsonArray("modules") ?: return
            Log.d(TAG, "  [PATH] Found ${modulesJson.size()} modules")
            
            // Sequential module processing
            for (moduleElement in modulesJson) {
                val module = gson.fromJson(moduleElement, ModuleModel::class.java)
                completeModule(context, module.uid, token, cookies, module.units)
            }
        }
    }

    private suspend fun completeModule(
        context: Context, 
        moduleUid: String, 
        token: String, 
        cookies: String, 
        providedUnits: List<UnitModel>? = null
    ) {
        Log.d(TAG, "    [MODULE] Processing: $moduleUid")
        val units = providedUnits ?: fetchModuleUnits(moduleUid, token, cookies)
        if (units.isEmpty()) {
            Log.w(TAG, "    [MODULE] No units found for $moduleUid")
            return
        }

        Log.d(TAG, "    [MODULE] Processing ${units.size} units")
        coroutineScope {
            units.mapIndexed { index, unit ->
                async {
                    try {
                        // Staggered start like the Python script (INTER_UNIT_DELAY = 0.05s)
                        delay(index * 50L)
                        completeUnit(unit, token, cookies)
                    } catch (e: Exception) {
                        Log.e(TAG, "    [UNIT ERROR] Failed ${unit.uid}: ${e.message}")
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun fetchModuleUnits(moduleUid: String, token: String, cookies: String): List<UnitModel> {
        val url = "$BASE_URL/api/hierarchy/modules/$moduleUid?locale=en-gb"
        Log.d(TAG, "      [API] Fetching units: $url")
        val request = buildRequest(url, token, cookies).get().build()
        
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw Exception("401")
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            val json = gson.fromJson(body, JsonObject::class.java)
            val unitsJson = json.getAsJsonArray("units") ?: return emptyList()
            return unitsJson.map { gson.fromJson(it, UnitModel::class.java) }
        }
    }

    private suspend fun completeUnit(unit: UnitModel, token: String, cookies: String) {
        val unitTitle = unit.title ?: unit.uid
        val unitUrl = unit.url ?: return
        
        Log.d(TAG, "      [UNIT] Starting: $unitTitle (Points: ${unit.points})")

        var numQuestions = 0
        if (unit.points > 100) {
            numQuestions = countQuizQuestions(unitUrl, token, cookies)
        }

        val putUrl = "$BASE_URL/api/progress/units/${unit.uid}/?locale=en-us"
        val referer = "$BASE_URL$unitUrl"
        val mediaType = "application/json".toMediaType()

        if (numQuestions == 0) {
            val request = buildRequest(putUrl, token, cookies)
                .header("Referer", referer)
                .put("".toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 401) throw Exception("401")
                Log.d(TAG, "      [UNIT] Status: ${response.code} for $unitTitle")
            }
        } else {
            Log.d(TAG, "      [QUIZ] Found $numQuestions questions. Attempting Attempt 1.")
            val initialPayload = JsonArray()
            for (i in 0 until numQuestions) {
                val obj = JsonObject()
                obj.addProperty("id", i.toString())
                val answers = JsonArray()
                answers.add("0") // Dummy answer
                obj.add("answers", answers)
                initialPayload.add(obj)
            }

            val request = buildRequest(putUrl, token, cookies)
                .header("Referer", referer)
                .put(initialPayload.toString().toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 401) throw Exception("401")
                if (!response.isSuccessful) {
                    Log.e(TAG, "      [QUIZ] Attempt 1 failed: ${response.code}")
                    return
                }

                val body = response.body?.string() ?: return
                val json = gson.fromJson(body, JsonObject::class.java)
                val details = json.getAsJsonArray("details") ?: return
                
                var needsRetry = false
                val correctedPayload = JsonArray()
                
                details.forEach { detailElement ->
                    val detail = detailElement.asJsonObject
                    val isCorrect = detail.get("isCorrect")?.asBoolean ?: false
                    val qId = detail.get("id").asString
                    
                    val obj = JsonObject()
                    obj.addProperty("id", qId)
                    val answers = JsonArray()
                    
                    if (!isCorrect) {
                        needsRetry = true
                        val choices = detail.getAsJsonArray("choices")
                        val correctChoice = choices?.firstOrNull { it.asJsonObject.get("isCorrect").asBoolean }
                        val correctId = correctChoice?.asJsonObject?.get("id")?.asString ?: "0"
                        answers.add(correctId)
                    } else {
                        // Keep what worked
                        val existingAnswer = detail.getAsJsonArray("answers")?.get(0)?.asString ?: "0"
                        answers.add(existingAnswer)
                    }
                    obj.add("answers", answers)
                    correctedPayload.add(obj)
                }

                if (needsRetry) {
                    Log.d(TAG, "      [QUIZ] Resubmitting with corrected answers...")
                    val retryRequest = buildRequest(putUrl, token, cookies)
                        .header("Referer", referer)
                        .put(correctedPayload.toString().toRequestBody(mediaType))
                        .build()
                    client.newCall(retryRequest).execute().use { retryResp ->
                        Log.d(TAG, "      [QUIZ] Attempt 2 Status: ${retryResp.code}")
                    }
                }
            }
        }
    }

    private suspend fun countQuizQuestions(unitUrl: String, token: String, cookies: String): Int {
        val url = if (unitUrl.startsWith("http")) unitUrl else "$BASE_URL/en-us$unitUrl"
        val request = buildRequest(url, token, cookies)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return 0
                val html = response.body?.string() ?: return 0
                val doc = Jsoup.parse(html)
                // More reliable detection of quiz questions
                val count = doc.select(".quiz-question, [data-bi-name='quiz-question']").size
                if (count == 0) {
                    // Fallback to regex if Jsoup selector fails due to dynamic content or different structure
                    "class=\"quiz-question\"".toRegex().findAll(html).count()
                } else {
                    count
                }
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun buildRequest(url: String, token: String, cookies: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .header("Authorization", "Bearer $token")
            .header("Cookie", cookies)
    }
}
