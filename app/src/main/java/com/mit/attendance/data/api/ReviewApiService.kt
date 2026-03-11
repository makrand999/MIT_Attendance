package com.mit.attendance.data.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mit.attendance.model.ReviewApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ReviewApiService {

    private val TAG = "ReviewApiService"
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val gson = Gson()
    private val SCRIPT_URL = "https://script.google.com/macros/s/AKfycby2Io6FwaYhgQaR8LmOheLSvpryFmMJN4WNqHlcyMJDe4uRDJvRiXBreKtlv5KmmFY/exec"

    suspend fun fetchReviews(email: String): Result<List<ReviewApiResponse>> = withContext(Dispatchers.IO) {
        try {
            val url = SCRIPT_URL.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("email", email)
                ?.build()

            if (url == null) {
                Log.e(TAG, "Invalid URL for email: $email")
                return@withContext Result.failure(Exception("Invalid URL"))
            }

            Log.d(TAG, "Fetching reviews from: $url")
            val request = Request.Builder().url(url).build()
            
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                Log.d(TAG, "Fetch Response - Code: ${response.code}, Message: ${response.message}")
                Log.d(TAG, "Fetch Response - Body: $body")
                
                if (response.isSuccessful) {
                    try {
                        val type = object : TypeToken<List<ReviewApiResponse>>() {}.type
                        val reviews: List<ReviewApiResponse> = gson.fromJson(body, type)
                        Log.d(TAG, "Successfully parsed ${reviews.size} reviews")
                        Result.success(reviews)
                    } catch (e: Exception) {
                        Log.e(TAG, "JSON Parsing error. Body might not be JSON: $body", e)
                        Result.failure(e)
                    }
                } else {
                    Log.e(TAG, "Server returned error code ${response.code}: $body")
                    Result.failure(Exception("Server error: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network or unexpected error during fetch", e)
            Result.failure(e)
        }
    }

    suspend fun sendReview(
        gender: String,
        targetEmail: String,
        rating: Float,
        comment: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val data = mapOf(
                "gender" to gender,
                "targetEmail" to targetEmail,
                "rating" to rating,
                "comment" to comment
            )
            val json = gson.toJson(data)
            Log.d(TAG, "Sending review payload: $json")
            
            val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(SCRIPT_URL).post(body).build()
            
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "Send Response - Code: ${response.code}, Message: ${response.message}")
                Log.d(TAG, "Send Response - Body: $responseBody")
                
                if (response.isSuccessful && responseBody.trim().contains("SUCCESS")) {
                    Log.d(TAG, "Review sent successfully confirmed by server")
                    Result.success("SUCCESS")
                } else {
                    Log.e(TAG, "Send failed. Code: ${response.code}, Body: $responseBody")
                    Result.failure(Exception("Failed to send review: $responseBody"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending review", e)
            Result.failure(e)
        }
    }
}
