package com.mit.attendance.data

import com.mit.attendance.data.api.PracticalApiService
import com.mit.attendance.data.db.PracticalDao
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.model.*

/**
 * Handles login and all practical-related network calls with Room caching.
 */
class PracticalRepository(
    private val email: String,
    private val practicalDao: PracticalDao,
    private val userPrefs: UserPreferences
) {

    private val apiService = PracticalApiService()
    private val defaultPassword = "default123"

    var divisionId: Int? = null
        private set

    /**
     * Call this before making any other calls to restore session state from preferences.
     */
    suspend fun restoreSession() {
        if (divisionId == null) {
            divisionId = userPrefs.getDivisionId()
        }
        val token = userPrefs.getPracticalToken()
        if (token != null) {
            apiService.setToken(token)
        }
    }

    // ── Auth ───────────────────────────────────────────────────────────────

    suspend fun login(): Result<PracticalLoginResponse> {
        val res = apiService.login(email, defaultPassword)
        res.onSuccess { 
            userPrefs.savePracticalToken(it.token)
        }
        return res
    }

    // ── Subjects ───────────────────────────────────────────────────────────

    suspend fun getPracticalSubjects(): Result<List<PracticalSubject>> {
        val networkResult = apiService.getStudentSubjects()
        
        return if (networkResult.isSuccess) {
            val resp = networkResult.getOrThrow()
            divisionId = resp.divisionId
            
            // Persist divisionId for offline use
            divisionId?.let { userPrefs.saveDivisionId(it) }
            
            val filteredSubjects = resp.subjects.filter { it.practical }
            
            // Cache to DB
            practicalDao.upsertSubjects(filteredSubjects)
            
            // Pre-fetch practicals for all subjects for offline availability
            val divId = divisionId
            if (divId != null) {
                filteredSubjects.forEach { sub ->
                    // Launch background fetch
                    fetchAndCachePracticals(sub.id, divId)
                }
            }
            
            Result.success(filteredSubjects)
        } else {
            // Try loading from Cache
            val cached = practicalDao.getAllSubjects()
            if (cached.isNotEmpty()) {
                if (divisionId == null) {
                    divisionId = userPrefs.getDivisionId()
                }
                Result.success(cached)
            } else {
                networkResult.map { emptyList() }
            }
        }
    }

    private suspend fun fetchAndCachePracticals(subjectId: Int, divId: Int) {
        apiService.getPracticals(subjectId, divId).onSuccess { resp ->
            val toSave = mutableListOf<Practical>()
            resp.submitted.forEach { 
                it.subjectId = subjectId
                it.isSubmitted = true
                toSave.add(it)
            }
            resp.notSubmitted.forEach { 
                it.subjectId = subjectId
                it.isSubmitted = false
                toSave.add(it)
            }
            practicalDao.deletePracticalsForSubject(subjectId)
            practicalDao.upsertPracticals(toSave)
        }
    }

    // ── Practicals ─────────────────────────────────────────────────────────

    suspend fun getPracticals(subjectId: Int): Result<PracticalsResponse> {
        if (divisionId == null) {
            divisionId = userPrefs.getDivisionId()
        }

        val divId = divisionId 
        val networkResult = if (divId != null) {
            apiService.getPracticals(subjectId, divId)
        } else {
            Result.failure(Exception("Connection failed: Division ID unknown"))
        }

        return if (networkResult.isSuccess) {
            val resp = networkResult.getOrThrow()
            
            val toSave = mutableListOf<Practical>()
            resp.submitted.forEach { 
                it.subjectId = subjectId
                it.isSubmitted = true
                toSave.add(it)
            }
            resp.notSubmitted.forEach { 
                it.subjectId = subjectId
                it.isSubmitted = false
                toSave.add(it)
            }

            practicalDao.deletePracticalsForSubject(subjectId)
            practicalDao.upsertPracticals(toSave)

            Result.success(resp)
        } else {
            val cached = practicalDao.getPracticalsForSubject(subjectId)
            if (cached.isNotEmpty()) {
                val submitted = cached.filter { it.isSubmitted }
                val notSubmitted = cached.filter { !it.isSubmitted }
                Result.success(PracticalsResponse(submitted, notSubmitted))
            } else {
                networkResult
            }
        }
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
        if (divisionId == null) {
            divisionId = userPrefs.getDivisionId()
        }
        val divId = divisionId ?: return Result.failure(Exception("Offline: Cannot submit without division ID"))
        
        val result = apiService.submitPractical(
            subjectId       = subjectId,
            divisionId      = divId,
            practicalId     = practicalId,
            practicalNumber = practicalNumber,
            language        = language,
            theory          = theory,
            conclusion      = conclusion
        )

        if (result.isSuccess) {
            // Update local cache immediately so it's available offline even without a refresh
            practicalDao.updatePracticalSubmission(practicalId, theory, conclusion)
        }

        return result
    }
}
