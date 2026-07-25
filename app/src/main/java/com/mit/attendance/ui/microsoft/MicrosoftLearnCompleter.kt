package com.mit.attendance.ui.microsoft

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.mit.attendance.data.prefs.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.random.Random

object MicrosoftLearnCompleter {
    private const val TAG = "MSLearnCompleter"
    private const val BASE_URL = "https://learn.microsoft.com"
    private val gson = Gson()

    private const val MAX_RETRIES = 4
    private const val BACKOFF_BASE = 2.0
    private const val BACKOFF_JITTER = 1.0

    private val C_SHARP_PATHS = listOf(
        "learn.wwl.get-started-c-sharp-part-1",
        "learn.wwl.get-started-c-sharp-part-2",
        "learn.wwl.get-started-c-sharp-part-3",
        "learn.wwl.get-started-c-sharp-part-4",
        "learn.wwl.get-started-c-sharp-part-5",
        "learn.wwl.get-started-c-sharp-part-6"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply {
            maxRequests = 100
            maxRequestsPerHost = 100
        })
        .build()

    interface CompletionCallback {
        fun onSuccess()
        fun onError(msg: String)
        fun onStatus(message: String, isError: Boolean = false) {}
    }

    data class UnitModel(
        val uid: String,
        val url: String?,
        val points: Int,
        val title: String? = null,
        @SerializedName("module_assessment")
        val isModuleAssessment: Boolean = false
    )

    data class ModuleModel(
        val uid: String,
        val title: String? = null,
        val units: List<UnitModel>? = null
    )

    private fun getProxyClient(proxyStr: String?): OkHttpClient {
        if (proxyStr == null) return client
        return try {
            val parts = proxyStr.split(":")
            if (parts.size != 4) return client
            
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(parts[0], parts[1].toInt()))
            client.newBuilder()
                .proxy(proxy)
                .proxyAuthenticator { _, response ->
                    val credential = Credentials.basic(parts[2], parts[3])
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
                .build()
        } catch (ignored: Exception) {
            client
        }
    }

    private suspend fun executeWithRetry(
        request: Request,
        proxyClient: OkHttpClient,
        label: String
    ): okhttp3.Response {
        var lastException: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                val response = withContext(Dispatchers.IO) {
                    proxyClient.newCall(request).execute()
                }
                
                if (response.code == 429 || response.code == 503) {
                    val wait = (BACKOFF_BASE.pow(attempt.toDouble()) + Random.nextDouble(BACKOFF_JITTER)).toLong() * 1000
                    Log.d(TAG, "  [rate-limit] ${response.code} on $label — waiting ${wait}ms (attempt ${attempt + 1})")
                    response.close()
                    delay(wait)
                    continue
                }
                
                if (response.code == 401) {
                    throw Exception("401")
                }

                return response
            } catch (e: Exception) {
                if (e.message == "401") throw e
                lastException = e
                val wait = (BACKOFF_BASE.pow(attempt.toDouble()) + Random.nextDouble(BACKOFF_JITTER)).toLong() * 1000
                Log.d(TAG, "  [conn-error] ${e.message} on $label — retrying in ${wait}ms")
                delay(wait)
            }
        }
        throw lastException ?: Exception("Max retries exceeded for $label")
    }

    private suspend fun <T> withRetry(
        request: Request,
        proxyClient: OkHttpClient,
        label: String,
        block: (okhttp3.Response) -> T
    ): T {
        val response = executeWithRetry(request, proxyClient, label)
        return response.use(block)
    }

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
                val proxies = UserPreferences(context).getCertificationProxies()
                val failures = mutableListOf<String>()
                val useProxies = proxies.isNotEmpty()
                withContext(Dispatchers.Main) {
                    if (useProxies) {
                        callback.onStatus("Using ${proxies.size} imported proxies.")
                    } else {
                        callback.onStatus("No proxies imported. Falling back to direct connection.")
                    }
                }
                
                coroutineScope {
                    when (type) {
                        "csharp_all" -> {
                            C_SHARP_PATHS.mapIndexed { index, pathUid ->
                                async {
                                    val proxy = if (useProxies) proxies[index % proxies.size] else null
                                    val proxyClient = getProxyClient(proxy)
                                    try {
                                        completePath(pathUid, token, cookieHeader, proxyClient, callback)
                                    } catch (e: Exception) {
                                        val failure = "Path $pathUid failed: ${e.message ?: "unknown error"}"
                                        synchronized(failures) {
                                            failures += failure
                                        }
                                        withContext(Dispatchers.Main) {
                                            callback.onStatus(failure, true)
                                        }
                                    }
                                }
                            }.awaitAll()
                        }
                        "path" -> {
                            val proxyClient = getProxyClient(if (useProxies) proxies.random() else null)
                            completePath(uid, token, cookieHeader, proxyClient, callback)
                        }
                        else -> {
                            val proxyClient = getProxyClient(if (useProxies) proxies.random() else null)
                            completeModule(uid, token, cookieHeader, proxyClient, callback)
                        }
                    }
                }

                if (failures.isNotEmpty()) {
                    throw Exception("Microsoft Learn failed in ${failures.size} part(s). First failure: ${failures.first()}")
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

    private suspend fun completePath(
        pathUid: String, 
        token: String, 
        cookies: String, 
        proxyClient: OkHttpClient,
        callback: CompletionCallback
    ) {
        withContext(Dispatchers.Main) {
            callback.onStatus("Processing path: $pathUid")
        }
        val url = "$BASE_URL/api/hierarchy/paths/$pathUid?locale=en-gb"
        Log.d(TAG, "  [PATH] Fetching hierarchy: $url")
        val request = buildRequest(url, token, cookies).get().build()
        
        val body = withRetry(request, proxyClient, "path/$pathUid") { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to fetch path $pathUid (${response.code})")
            } else {
                response.body?.string()
            }
        } ?: throw Exception("Empty response for path $pathUid")

        val json = gson.fromJson(body, JsonObject::class.java)
        val modulesJson = json.getAsJsonArray("modules") ?: throw Exception("No modules found for path $pathUid")
        Log.d(TAG, "  [PATH] Found ${modulesJson.size()} modules")
        
        for (moduleElement in modulesJson) {
            val module = gson.fromJson(moduleElement, ModuleModel::class.java)
            completeModule(module.uid, token, cookies, proxyClient, callback, module.units)
        }
    }

    private suspend fun completeModule(
        moduleUid: String, 
        token: String, 
        cookies: String, 
        proxyClient: OkHttpClient,
        callback: CompletionCallback,
        providedUnits: List<UnitModel>? = null
    ) {
        withContext(Dispatchers.Main) {
            callback.onStatus("Processing module: $moduleUid")
        }
        Log.d(TAG, "    [MODULE] Processing: $moduleUid")
        val units = providedUnits ?: fetchModuleUnits(moduleUid, token, cookies, proxyClient)
        if (units.isEmpty()) {
            throw Exception("No units found for module $moduleUid")
        }

        Log.d(TAG, "    [MODULE] Processing ${units.size} units")
        coroutineScope {
            units.mapIndexed { index, unit ->
                async {
                    delay(index * 50L)
                    completeUnit(unit, token, cookies, proxyClient, callback)
                }
            }.awaitAll()
        }
    }

    private suspend fun fetchModuleUnits(
        moduleUid: String, 
        token: String, 
        cookies: String, 
        proxyClient: OkHttpClient
    ): List<UnitModel> {
        val url = "$BASE_URL/api/hierarchy/modules/$moduleUid?locale=en-gb"
        Log.d(TAG, "      [API] Fetching units: $url")
        val request = buildRequest(url, token, cookies).get().build()
        
        return withRetry(request, proxyClient, "module/$moduleUid") { response ->
            if (!response.isSuccessful) return@withRetry emptyList()
            val body = response.body?.string() ?: return@withRetry emptyList()
            val json = gson.fromJson(body, JsonObject::class.java)
            val unitsJson = json.getAsJsonArray("units") ?: return@withRetry emptyList()
            unitsJson.map { gson.fromJson(it, UnitModel::class.java) }
        }
    }

    private suspend fun completeUnit(
        unit: UnitModel,
        token: String,
        cookies: String,
        proxyClient: OkHttpClient,
        callback: CompletionCallback
    ) {
        val unitTitle = unit.title ?: unit.uid
        val unitUrl = unit.url ?: throw Exception("Missing URL for unit ${unit.uid}")
        withContext(Dispatchers.Main) {
            callback.onStatus("Completing unit: $unitTitle")
        }
        
        Log.d(TAG, "      [UNIT] Starting: $unitTitle (Points: ${unit.points})")

        var numQuestions = 0
        if (unit.points > 100) {
            numQuestions = countQuizQuestions(unitUrl, token, cookies, proxyClient)
        }

        val putUrl = "$BASE_URL/api/progress/units/${unit.uid}/?locale=en-us"
        val referer = "$BASE_URL$unitUrl"
        val mediaType = "application/json".toMediaType()

        if (numQuestions == 0) {
            val request = buildRequest(putUrl, token, cookies)
                .header("Referer", referer)
                .put("".toRequestBody(mediaType))
                .build()

            withRetry(request, proxyClient, "unit/${unit.uid}") { response ->
                Log.d(TAG, "      [UNIT] Status: ${response.code} for $unitTitle")
            }
        } else {
            Log.d(TAG, "      [QUIZ] Found $numQuestions questions. Attempting Attempt 1.")
            val initialPayload = JsonArray()
            for (i in 0 until numQuestions) {
                val obj = JsonObject()
                obj.addProperty("id", i.toString())
                val answers = JsonArray()
                answers.add("0")
                obj.add("answers", answers)
                initialPayload.add(obj)
            }

            val request = buildRequest(putUrl, token, cookies)
                .header("Referer", referer)
                .put(initialPayload.toString().toRequestBody(mediaType))
                .build()

            val body = withRetry(request, proxyClient, "quiz-init/${unit.uid}") { response ->
                if (!response.isSuccessful) {
                    throw Exception("Quiz attempt 1 failed for $unitTitle (${response.code})")
                } else {
                    response.body?.string()
                }
            } ?: throw Exception("Empty quiz response for $unitTitle")

            val json = gson.fromJson(body, JsonObject::class.java)
            val details = json.getAsJsonArray("details") ?: throw Exception("No quiz details returned for $unitTitle")
            
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
                
                withRetry(retryRequest, proxyClient, "quiz-retry/${unit.uid}") { retryResp ->
                    Log.d(TAG, "      [QUIZ] Attempt 2 Status: ${retryResp.code}")
                }
            }
        }
    }

    private suspend fun countQuizQuestions(
        unitUrl: String, 
        token: String, 
        cookies: String, 
        proxyClient: OkHttpClient
    ): Int {
        val url = if (unitUrl.startsWith("http")) unitUrl else "$BASE_URL/en-us$unitUrl"
        val request = buildRequest(url, token, cookies)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .get()
            .build()

        return try {
            withRetry(request, proxyClient, "count-q/$unitUrl") { response ->
                if (!response.isSuccessful) return@withRetry 0
                val html = response.body?.string() ?: return@withRetry 0
                val doc = Jsoup.parse(html)
                val count = doc.select(".quiz-question, [data-bi-name='quiz-question']").size
                if (count == 0) {
                    "class=\"quiz-question\"".toRegex().findAll(html).count()
                } else {
                    count
                }
            }
        } catch (ignored: Exception) {
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
