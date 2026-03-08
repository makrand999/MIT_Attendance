package com.mit.attendance.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object GeminiService {

    private const val API_KEY = "---api--key--"
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
            runCatching {
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
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) error("Gemini API error (${resp.code}): $text")

                val root = JSONObject(text)
                root.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
            }
        }
}