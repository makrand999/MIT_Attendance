package com.mit.attendance.data

import android.content.Context
import android.util.Log
import com.mit.attendance.data.api.ReviewApiService
import com.mit.attendance.data.db.AppDatabase
import com.mit.attendance.data.db.ReviewDao
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.model.ReviewEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ReviewRepository(context: Context) {

    private val TAG = "ReviewRepo"
    private val api = ReviewApiService()
    private val db = AppDatabase.getDatabase(context)
    private val reviewDao = db.reviewDao()
    private val prefs = UserPreferences(context)

    fun getReviewsFlow(): Flow<List<ReviewEntity>> = reviewDao.getAllReviews()

    suspend fun syncReviews(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val email = prefs.getCredentialsSnapshot().email
            Log.d(TAG, "Syncing reviews for email: $email")
            if (email.isEmpty()) {
                Log.w(TAG, "Email is empty, cannot sync reviews")
                return@withContext Result.failure(Exception("Not logged in"))
            }

            val result = api.fetchReviews(email)
            if (result.isSuccess) {
                val apiReviews = result.getOrThrow()
                Log.d(TAG, "Fetched ${apiReviews.size} reviews from API")
                if (apiReviews.isNotEmpty()) {
                    val entities = apiReviews.map {
                        ReviewEntity(
                            timestamp = it.timestamp ?: "",
                            gender = it.gender ?: "Unknown",
                            rating = it.rating?.toFloat() ?: 0f,
                            comment = it.comment ?: ""
                        )
                    }
                    reviewDao.insertReviews(entities)
                    Log.d(TAG, "Inserted ${entities.size} reviews into database")
                    return@withContext Result.success(entities.size)
                }
                return@withContext Result.success(0)
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                Log.e(TAG, "API fetch failed: $error")
                return@withContext Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncReviews unexpected error", e)
            Result.failure(e)
        }
    }

    suspend fun sendReview(targetEmail: String, rating: Float, comment: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val gender = prefs.getGender() ?: "Unknown"
            Log.d(TAG, "Sending review to $targetEmail (from gender: $gender)")
            api.sendReview(gender, targetEmail, rating, comment)
        } catch (e: Exception) {
            Log.e(TAG, "sendReview error", e)
            Result.failure(e)
        }
    }
}
