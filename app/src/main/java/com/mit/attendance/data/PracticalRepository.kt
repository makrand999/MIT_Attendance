package com.mit.attendance.data

import com.mit.attendance.data.api.PracticalApiService
import com.mit.attendance.data.db.PracticalDao
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/**
 * Handles login and all practical-related network calls with Room caching.
 */
class PracticalRepository(
    private val email: String,
    private val practicalDao: PracticalDao,
    private val userPrefs: UserPreferences
) {
    companion object {
        const val SYNC_ACTION_SAVE_DRAFT = "save_draft"
        const val SYNC_ACTION_SUBMIT = "submit"
        const val SYNC_ACTION_UPDATE = "update"
    }

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

    suspend fun login(passwordOverride: String? = null): Result<PracticalLoginResponse> {
        val savedPassword = userPrefs.getPracticalPassword()
        val candidates = buildList {
            passwordOverride?.takeIf { it.isNotBlank() }?.let { add(it) }
            savedPassword?.takeIf { it.isNotBlank() }?.let { if (it !in this) add(it) }
            if (defaultPassword !in this) add(defaultPassword)
        }

        var lastFailure: Throwable? = null
        for (candidate in candidates) {
            val res = apiService.login(email, candidate)
            if (res.isSuccess) {
                res.getOrNull()?.let {
                    userPrefs.savePracticalToken(it.token)
                    if (candidate == defaultPassword) {
                        userPrefs.savePracticalPassword(null)
                    } else {
                        userPrefs.savePracticalPassword(candidate)
                    }
                }
                return res
            }

            val failure = res.exceptionOrNull()
            lastFailure = failure

            if (passwordOverride != null) {
                return Result.failure(normalizeLoginFailure(failure, customPrompt = true))
            }

            if (!isAuthStyleFailure(failure)) {
                return Result.failure(normalizeLoginFailure(failure, customPrompt = false))
            }
        }

        return Result.failure(normalizeLoginFailure(lastFailure, customPrompt = true))
    }

    suspend fun checkServerAvailability(): PracticalServerStatus {
        return apiService.checkServerAvailability().getOrDefault(PracticalServerStatus.UNKNOWN)
    }

    // ── Subjects ───────────────────────────────────────────────────────────

    suspend fun getCachedPracticalSubjects(): List<PracticalSubject> {
        return practicalDao.getAllSubjects()
    }

    suspend fun refreshPracticalSubjects(): Result<List<PracticalSubject>> {
        val networkResult = withFreshSessionRetry("subjects refresh") {
            apiService.getStudentSubjects()
        }

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
                supervisorScope {
                    filteredSubjects.map { subject ->
                        async {
                            runCatching {
                                fetchAndCachePracticals(subject.id, divId)
                            }
                        }
                    }.awaitAll()
                }
            }

            Result.success(filteredSubjects)
        } else {
            networkResult.map { emptyList<PracticalSubject>() }
        }
    }

    suspend fun getPracticalSubjects(): Result<List<PracticalSubject>> {
        val cached = practicalDao.getAllSubjects()
        if (cached.isNotEmpty()) {
            if (divisionId == null) {
                divisionId = userPrefs.getDivisionId()
            }
            return Result.success(cached)
        }

        val refreshed = refreshPracticalSubjects()
        if (refreshed.isSuccess) {
            return refreshed
        }

        return refreshed.map { emptyList<PracticalSubject>() }
    }

    suspend fun getCachedPracticals(subjectId: Int): PracticalsResponse? {
        val cached = practicalDao.getPracticalsForSubject(subjectId)
        return cached.takeIf { it.isNotEmpty() }?.map {
            it.copy(isSubmitted = false)
        }?.toPracticalsResponse()
    }

    suspend fun refreshPracticals(subjectId: Int): Result<PracticalsResponse> {
        if (divisionId == null) {
            divisionId = userPrefs.getDivisionId()
        }

        val divId = divisionId
        val networkResult = if (divId != null) {
            withFreshSessionRetry("practical list refresh") {
                apiService.getPracticals(subjectId, divId)
            }
        } else {
            Result.failure(Exception("Connection failed: Division ID unknown"))
        }

        return networkResult.map { resp ->
            cachePracticals(subjectId, resp)
            resp.toCachedResponse(subjectId)
        }
    }

    private suspend fun fetchAndCachePracticals(subjectId: Int, divId: Int) {
        withFreshSessionRetry("practical list cache") {
            apiService.getPracticals(subjectId, divId)
        }.onSuccess { resp ->
            cachePracticals(subjectId, resp)
        }
    }

    // ── Practicals ─────────────────────────────────────────────────────────

    suspend fun getPracticals(subjectId: Int): Result<PracticalsResponse> {
        getCachedPracticals(subjectId)?.let { return Result.success(it) }

        val networkResult = refreshPracticals(subjectId)
        return if (networkResult.isSuccess) {
            networkResult
        } else {
            getCachedPracticals(subjectId)?.let { Result.success(it) } ?: networkResult
        }
    }

    // ── Submit ─────────────────────────────────────────────────────────────

    suspend fun submitPractical(
        subjectId: Int,
        practicalId: Int,
        submissionId: Int?,
        theory: String,
        conclusion: String,
        language: String = "python",
        code: String = "",
        output: String = "",
        error: String = "",
        pdfAttachment: PracticalAttachment? = null,
        removePdf: Boolean = false
    ): Result<String> {
        if (divisionId == null) {
            divisionId = userPrefs.getDivisionId()
        }
        val divId = divisionId ?: return Result.failure(Exception("Offline: Cannot submit without division ID"))

        val normalizedBatch = practicalDao.getPracticalById(practicalId)?.batch
            ?.takeUnless { it.isBlank() || it.equals("All", ignoreCase = true) }

        val submissionType = when {
            pdfAttachment != null && code.isNotBlank() -> "both"
            pdfAttachment != null -> "pdf"
            else -> "code"
        }

        val serverSubmissionId = submissionId ?: withFreshSessionRetry("submission verification") {
            apiService.getStudentPracticalSubmission(practicalId)
        }.getOrNull()?.id

        val result = if (serverSubmissionId != null) {
            withFreshSessionRetry("practical update") {
                apiService.updateStudentPracticalSubmission(
                    submissionId = serverSubmissionId,
                    payload = PracticalSubmissionUpdatePayload(
                        submissionId = serverSubmissionId,
                        practical = practicalId,
                        division = divId,
                        batch = normalizedBatch,
                        subjects = subjectId,
                        theory = theory,
                        code = code,
                        output = output,
                        conclusion = conclusion,
                        error = error,
                        submissionType = submissionType,
                        removePdf = removePdf,
                        activeSection = if (pdfAttachment != null) "files" else "terminal"
                    ),
                    attachment = pdfAttachment
                )
            }
        } else {
            withFreshSessionRetry("practical submit") {
                apiService.createStudentPracticalSubmission(
                    payload = PracticalSubmissionPayload(
                        practical = practicalId,
                        division = divId,
                        batch = normalizedBatch,
                        subjects = subjectId,
                        theory = theory,
                        code = code,
                        output = output,
                        conclusion = conclusion,
                        error = error,
                        remainingTime = "00:00:00",
                        autoSubmitted = false,
                        submissionType = submissionType
                    ),
                    attachment = pdfAttachment
                )
            }
        }

        if (result.isSuccess) {
            val syncedAt = System.currentTimeMillis()
            val existing = practicalDao.getPracticalById(practicalId)
            practicalDao.updatePracticalSubmission(
                id = practicalId,
                theory = theory,
                conclusion = conclusion,
                language = language,
                code = code,
                output = output,
                error = error,
                practicalDoc = when {
                    removePdf -> null
                    pdfAttachment != null -> pdfAttachment.fileName
                    else -> existing?.practicalDoc
                },
                submissionId = serverSubmissionId,
                submissionType = submissionType,
                evaluated = false
            )
            practicalDao.markServerSyncSuccess(practicalId, syncedAt)
        } else {
            practicalDao.updateSyncFailureState(
                practicalId,
                if (serverSubmissionId != null) SYNC_ACTION_UPDATE else SYNC_ACTION_SUBMIT,
                result.exceptionOrNull()?.message
            )
        }

        return result
    }

    suspend fun saveServerDraft(
        subjectId: Int,
        practicalId: Int,
        theory: String,
        conclusion: String,
        language: String = "python",
        code: String = "",
        output: String = "",
        error: String = "",
        pdfAttachment: PracticalAttachment? = null
    ): Result<String> {
        if (divisionId == null) {
            divisionId = userPrefs.getDivisionId()
        }
        val divId = divisionId ?: return Result.failure(Exception("Offline: Cannot submit without division ID"))

        val normalizedBatch = practicalDao.getPracticalById(practicalId)?.batch
            ?.takeUnless { it.isBlank() || it.equals("All", ignoreCase = true) }

        val result = withFreshSessionRetry("server draft save") {
            apiService.saveStudentPracticalDraft(
                payload = PracticalEditorPayload(
                    practical = practicalId,
                    division = divId,
                    batch = normalizedBatch,
                    subjects = subjectId,
                    language = language,
                    theory = theory,
                    code = code,
                    output = output,
                    error = error,
                    conclusion = conclusion
                ),
                attachment = pdfAttachment
            )
        }

        if (result.isSuccess) {
            val syncedAt = System.currentTimeMillis()
            val practical = practicalDao.getPracticalById(practicalId)
            if (practical?.isSubmitted == true) {
                practicalDao.updatePracticalSubmission(
                    id = practicalId,
                    theory = theory,
                    conclusion = conclusion,
                    language = language,
                    code = code,
                    output = output,
                    error = error,
                    practicalDoc = pdfAttachment?.fileName ?: practical.practicalDoc,
                    submissionId = practical.submissionId,
                    submissionType = practical.submissionType,
                    evaluated = practical.evaluated == true
                )
            }
            practicalDao.markServerSyncSuccess(practicalId, syncedAt)
        } else {
            practicalDao.updateSyncFailureState(
                practicalId,
                SYNC_ACTION_SAVE_DRAFT,
                result.exceptionOrNull()?.message
            )
        }

        return result
    }

    suspend fun loadRemoteEditorState(practicalId: Int): Result<PracticalEditorState?> {
        val submissionResult = withFreshSessionRetry("server submission load") {
            apiService.getStudentPracticalSubmission(practicalId)
        }
        if (submissionResult.isSuccess) {
            submissionResult.getOrNull()?.let { submission ->
                practicalDao.updateServerVerifiedAt(practicalId, System.currentTimeMillis())
                return Result.success(submission.toEditorState(RemoteEditorSource.SUBMISSION))
            }
        }

        val draftResult = withFreshSessionRetry("server draft load") {
            apiService.getStudentPracticalDraft(practicalId)
        }
        if (draftResult.isSuccess) {
            draftResult.getOrNull()?.let { draft ->
                practicalDao.updateServerVerifiedAt(practicalId, System.currentTimeMillis())
                return Result.success(draft.toEditorState(RemoteEditorSource.DRAFT))
            }
        }

        return if (submissionResult.isFailure) {
            submissionResult.map { null }
        } else if (draftResult.isFailure) {
            draftResult.map { null }
        } else {
            Result.success(null)
        }
    }

    private suspend fun cachePracticals(subjectId: Int, response: PracticalsResponse) {
        val existingById = practicalDao.getPracticalsForSubject(subjectId).associateBy { it.id }
        val practicalsToCache = response.toCachedPracticals(subjectId, existingById)
        practicalDao.deletePracticalsForSubject(subjectId)
        if (practicalsToCache.isNotEmpty()) {
            practicalDao.upsertPracticals(practicalsToCache)
        }
    }

    private fun PracticalsResponse.toCachedPracticals(
        subjectId: Int,
        existingById: Map<Int, Practical>
    ): List<Practical> {
        val totalPracticals = submitted.size + notSubmitted.size
        val cachedPracticals = ArrayList<Practical>(totalPracticals)
        val verifiedAt = System.currentTimeMillis()

        submitted.forEach { practical ->
            val existing = existingById[practical.id]
            cachedPracticals.add(
                practical.copy(
                    subjectId = subjectId,
                    isSubmitted = true,
                    localDraftSavedAt = existing?.localDraftSavedAt,
                    lastServerSyncAt = existing?.lastServerSyncAt,
                    lastServerVerifiedAt = verifiedAt,
                    pendingSyncAction = existing?.pendingSyncAction,
                    lastSyncError = existing?.lastSyncError
                )
            )
        }
        notSubmitted.forEach { practical ->
            val existing = existingById[practical.id]
            cachedPracticals.add(
                practical.copy(
                    subjectId = subjectId,
                    isSubmitted = false,
                    localDraftSavedAt = existing?.localDraftSavedAt,
                    lastServerSyncAt = existing?.lastServerSyncAt,
                    lastServerVerifiedAt = verifiedAt,
                    pendingSyncAction = existing?.pendingSyncAction,
                    lastSyncError = existing?.lastSyncError
                )
            )
        }

        return cachedPracticals
    }

    private fun PracticalsResponse.toCachedResponse(subjectId: Int): PracticalsResponse {
        return PracticalsResponse(
            submitted = submitted.map { it.copy(subjectId = subjectId, isSubmitted = true) },
            notSubmitted = notSubmitted.map { it.copy(subjectId = subjectId, isSubmitted = false) }
        )
    }

    private fun List<Practical>.toPracticalsResponse(): PracticalsResponse {
        val (submittedPracticals, pendingPracticals) = partition { it.isSubmitted }
        return PracticalsResponse(submitted = submittedPracticals, notSubmitted = pendingPracticals)
    }

    private fun PracticalRemoteEntry.toEditorState(source: RemoteEditorSource): PracticalEditorState {
        return PracticalEditorState(
            source = source,
            submissionId = if (source == RemoteEditorSource.SUBMISSION) id else null,
            language = language ?: "python",
            theory = theory.orEmpty(),
            code = code.orEmpty(),
            output = output.orEmpty(),
            error = error.orEmpty(),
            conclusion = conclusion.orEmpty(),
            submissionType = submissionType,
            practicalDoc = practicalDoc,
            evaluated = evaluated == true
        )
    }

    private suspend fun <T> withFreshSessionRetry(
        operationName: String,
        action: suspend () -> Result<T>
    ): Result<T> {
        val firstAttempt = action()
        if (firstAttempt.isSuccess || !isSessionExpiredFailure(firstAttempt.exceptionOrNull())) {
            return firstAttempt
        }

        val loginResult = login()
        if (loginResult.isFailure) {
            return Result.failure(normalizeSessionRenewalFailure(operationName, loginResult.exceptionOrNull()))
        }

        val secondAttempt = action()
        return if (secondAttempt.isFailure && isSessionExpiredFailure(secondAttempt.exceptionOrNull())) {
            Result.failure(Exception("Practical session expired again after fresh login. Reopen Practicals on college Wi-Fi and retry."))
        } else {
            secondAttempt
        }
    }

    private fun isSessionExpiredFailure(error: Throwable?): Boolean {
        val message = error?.message?.lowercase().orEmpty()
        return (message.contains("session") && message.contains("expired")) ||
            (message.contains("token") && message.contains("expired")) ||
            message.contains("jwt expired") ||
            message.contains("unauthorized") ||
            message.contains("unauthenticated") ||
            message.contains("invalid token") ||
            message.contains("login required") ||
            message.contains("please login") ||
            message.contains("please log in") ||
            message.contains("not logged in") ||
            message.contains("401") ||
            message.contains("403") ||
            message.contains("419")
    }

    private fun normalizeSessionRenewalFailure(operationName: String, error: Throwable?): Throwable {
        val message = error?.message.orEmpty()
        return when (message) {
            "PRACTICAL_PASSWORD_REQUIRED",
            "PRACTICAL_WIFI_REQUIRED",
            "PRACTICAL_SERVER_DOWN" -> Exception(message)
            else -> Exception("Fresh practical login failed while trying $operationName: ${message.ifBlank { "unknown error" }}")
        }
    }

    private fun isAuthStyleFailure(error: Throwable?): Boolean {
        val message = error?.message?.lowercase().orEmpty()
        return message.contains("user not found") ||
            message.contains("invalid") ||
            message.contains("credential") ||
            message.contains("password") ||
            message.contains("401") ||
            message.contains("403")
    }

    private fun normalizeLoginFailure(error: Throwable?, customPrompt: Boolean): Throwable {
        val raw = error?.message.orEmpty()
        val lower = raw.lowercase()
        return when {
            lower.contains("unable to resolve host") ||
                lower.contains("failed to connect") ||
                lower.contains("network is unreachable") ||
                lower.contains("connection refused") ||
                lower.contains("timeout") ||
                lower.contains("timed out") -> Exception("PRACTICAL_WIFI_REQUIRED")

            lower.contains("521") ||
                lower.contains("522") ||
                lower.contains("502") ||
                lower.contains("503") ||
                lower.contains("504") -> Exception("PRACTICAL_SERVER_DOWN")

            customPrompt && isAuthStyleFailure(error) -> Exception("PRACTICAL_PASSWORD_REQUIRED")

            else -> Exception(raw.ifBlank { "Practical login failed" })
        }
    }
}
