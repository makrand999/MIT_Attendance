package com.mit.attendance.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object GeminiService {

    private const val TAG = "GeminiService"
    //private const val API_KEY = "AIzaSyB7I7kNSHIk9iz_F-cspqZI-DpZkHUwJl0"
    private const val API_KEY = "no api key"
    private const val MODEL   = "gemini-2.0-flash"
    private const val URL     =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$API_KEY"

    private val client = OkHttpClient()
    private val JSON_MT = "application/json; charset=utf-8".toMediaType()

    // ── Public entry points ────────────────────────────────────────────────

    suspend fun generateTheory(aim: String, description: String?): Result<String> =
        generate(theoryPrompt(aim, description))

    suspend fun generateConclusion(aim: String, description: String?): Result<String> =
        generate(conclusionPrompt(aim, description))

    /**
     * Simple ping to verify if the API key and connectivity are working.
     */
    suspend fun ping(): Result<String> = generate("Say 'pong'")

    // ── Prompts ────────────────────────────────────────────────────────────

    private fun theoryPrompt(aim: String, desc: String?): String {
        val descPart = if (!desc.isNullOrBlank()) "\nDescription: $desc" else ""
        return """You are writing the Theory section of a university lab practical record.

Practical Aim: $aim$descPart

Rules you MUST follow:
- Write ONLY the theory content. No heading, no title, no "Theory:" prefix, no label of any kind.
- Do NOT include any introduction sentence like "Here is the theory..." or "The theory is...".
- Write in plain paragraphs. No markdown, no bullet points, no bold, no asterisks.
- Keep it concise, academically appropriate, and directly relevant to the aim.
- 3 to 5 short paragraphs maximum."""
    }

    private fun conclusionPrompt(aim: String, desc: String?): String {
        val descPart = if (!desc.isNullOrBlank()) "\nDescription: $desc" else ""
        return """You are writing the Conclusion section of a university lab practical record.

Practical Aim: $aim$descPart

Rules you MUST follow:
- Write ONLY the conclusion content. No heading, no title, no "Conclusion:" prefix, no label of any kind.
- Do NOT include any introduction sentence like "In conclusion..." or "To conclude...".
- Write in plain paragraphs. No markdown, no bullet points, no bold, no asterisks.
- Summarise what was learned or achieved in this practical.
- 1 to 3 short paragraphs maximum."""
    }

    // ── Core call ──────────────────────────────────────────────────────────

    private suspend fun generate(prompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Generating content...")
                
                val bodyJson = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.4)
                        put("maxOutputTokens", 1024)
                    })
                }.toString()

                val req = Request.Builder()
                    .url(URL)
                    .post(bodyJson.toRequestBody(JSON_MT))
                    .build()

                val resp = client.newCall(req).execute()
                val code = resp.code
                val text = resp.body?.string() ?: ""

                if (code == 429) {
                    val msg = "Rate limit hit (429). Please wait a minute before trying again."
                    Log.e(TAG, msg)
                    return@withContext Result.failure(Exception(msg))
                }

                if (!resp.isSuccessful) {
                    val errorDetail = "Gemini API error ($code): $text"
                    Log.e(TAG, errorDetail)
                    return@withContext Result.failure(Exception(errorDetail))
                }

                val root = JSONObject(text)
                val resultText = root.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
                
                Log.d(TAG, "Generation successful")
                Result.success(resultText)
                
            } catch (e: Exception) {
                Log.e(TAG, "Exception during generation: ${e.message}", e)
                Result.failure(e)
            }
        }
}