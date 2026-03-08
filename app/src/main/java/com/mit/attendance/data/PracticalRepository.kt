package com.mit.attendance.data

import com.mit.attendance.data.api.PracticalApiService
import com.mit.attendance.model.*

/**
 * Handles login (using stored email + default password) and all
 * practical-related network calls.
 *
 * Usage:
 *   val repo = PracticalRepository(userPreferences.getEmail())
 *   repo.login()        // call once; token cached inside apiService
 *   repo.getSubjects()
 *   ...
 */
class PracticalRepository(private val email: String) {

    private val apiService = PracticalApiService()
    private val defaultPassword = "default123"

    var divisionId: Int? = null
        private set

    // ── Auth ───────────────────────────────────────────────────────────────

    suspend fun login(): Result<PracticalLoginResponse> {
        val result = apiService.login(email, defaultPassword)
        return result
    }

    // ── Subjects ───────────────────────────────────────────────────────────

    suspend fun getPracticalSubjects(): Result<List<PracticalSubject>> {
        val result = apiService.getStudentSubjects()
        return result.map { resp ->
            divisionId = resp.divisionId
            resp.subjects.filter { it.practical }
        }
    }

    // ── Practicals ─────────────────────────────────────────────────────────

    suspend fun getPracticals(subjectId: Int): Result<PracticalsResponse> {
        val divId = divisionId ?: return Result.failure(Exception("Division ID not loaded"))
        return apiService.getPracticals(subjectId, divId)
    }

    // ── Submit ─────────────────────────────────────────────────────────────

    suspend fun submitPractical(
        subjectId: Int,
        practicalId: Int,
        practicalNumber: Int,
        theory: String,
        conclusion: String,
        language: String = "python"
    ): Result<String> {
        val divId = divisionId ?: return Result.failure(Exception("Division ID not loaded"))
        return apiService.submitPractical(
            subjectId       = subjectId,
            divisionId      = divId,
            practicalId     = practicalId,
            practicalNumber = practicalNumber,
            language        = language,
            theory          = theory,
            conclusion      = conclusion
        )
    }
}