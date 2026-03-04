package com.mit.attendance.data

import android.content.Context
import android.util.Log
import com.mit.attendance.data.api.AttendanceApiService
import com.mit.attendance.data.api.HttpClientHolder
import com.mit.attendance.data.db.AttendanceDatabase
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AttendanceRepository(context: Context) {

    private val TAG = "AttendanceRepo"

    val api = AttendanceApiService()
    private val db = AttendanceDatabase.getInstance(context)
    val prefs = UserPreferences(context)

    private val subjectDao = db.subjectDao()
    private val attendanceDao = db.attendanceDao()

    // ── Subjects ──────────────────────────────────────────────────────────────

    fun getSubjectsFlow(): Flow<List<SubjectUiModel>> =
        subjectDao.getAllSubjectsWithNewCounts().map { rows ->
            rows.map { (entity, newCount) ->
                SubjectUiModel(
                    id = entity.subjectId,
                    name = entity.subjectName,
                    present = entity.totalPresent,
                    absent = entity.totalAbsent,
                    total = entity.totalLecture,
                    percentage = entity.percentage,
                    hasNewEntries = newCount > 0
                )
            }
        }

    private suspend fun syncSubjects(semId: Int, email: String, password: String) {
        val result = api.fetchSubjects(semId, email, password)
        if (result.isFailure) {
            Log.w(TAG, "syncSubjects failed: ${result.exceptionOrNull()?.message}")
            return
        }
        val entities = result.getOrThrow().mapNotNull { s ->
            val id = s.encoSubjectwiseStudentId ?: return@mapNotNull null
            val present = s.presentCount ?: 0
            val absent = s.absentCount ?: 0
            val total = s.totalLecture ?: (present + absent)
            val pct = s.percentage ?: if (total > 0) (present * 100f / total) else 0f
            SubjectEntity(
                subjectId = id,
                subjectName = s.subject ?: "Unknown",
                totalPresent = present,
                totalAbsent = absent,
                totalLecture = total,
                percentage = pct
            )
        }
        if (entities.isNotEmpty()) {
            subjectDao.upsertSubjects(entities)
            Log.d(TAG, "Upserted ${entities.size} subjects")
        }
    }

    // ── Attendance detail ─────────────────────────────────────────────────────

    fun getAttendanceFlow(subjectId: String): Flow<List<AttendanceUiModel>> =
        attendanceDao.getAttendanceForSubject(subjectId).map { entities ->
            entities.map { e ->
                AttendanceUiModel(
                    date = e.date,
                    status = e.status,
                    startTime = e.startTime,
                    endTime = e.endTime,
                    isNew = e.isNew
                )
            }
        }

    /**
     * FIX: removed the pre-flight isSessionValid + reAuthenticate block.
     * fetchAttendanceDetail → fetchWithAutoReauth handles session recovery
     * internally and is the single source of truth. Having two re-auth paths
     * created a race condition where back-to-back logins could clobber each other.
     */
    suspend fun syncAttendanceDetail(subjectId: String): SyncResult {
        val creds = prefs.getCredentialsSnapshot()
        val email = creds.email
        val password = creds.password

        if (email.isEmpty()) return SyncResult.SessionError

        // FIX: guard against blank subjectId — would silently query with "" and return nothing
        if (subjectId.isBlank()) {
            Log.e(TAG, "syncAttendanceDetail called with blank subjectId")
            return SyncResult.SessionError
        }

        val result = api.fetchAttendanceDetail(subjectId, email, password)
        if (result.isFailure) {
            Log.e(TAG, "fetchAttendanceDetail failed: ${result.exceptionOrNull()?.message}")
            return SyncResult.NetworkError
        }

        val apiRecords = result.getOrThrow()
        if (apiRecords.isEmpty()) return SyncResult.NoChange

        val existingKeys = attendanceDao.getAttendanceForSubjectList(subjectId)
            .mapTo(HashSet()) { Triple(it.subjectId, it.date, it.startTime) }

        val incoming = apiRecords.mapNotNull { r ->
            val date = r.Date ?: return@mapNotNull null
            val stime = r.stime?.trim() ?: ""
            val etime = r.etime?.trim() ?: ""
            val status = when (r.presenty?.trim()?.lowercase()) {
                "p", "present", "1" -> "P"
                else -> "A"
            }
            AttendanceEntity(
                subjectId = subjectId,
                date = date,
                status = status,
                startTime = stime,
                endTime = etime,
                isNew = Triple(subjectId, date, stime) !in existingKeys
            )
        }

        val insertedRows = attendanceDao.insertIfNotExists(incoming)
        val newCount = insertedRows.count { it != -1L }
        Log.d(TAG, "syncAttendanceDetail $subjectId: ${incoming.size} incoming, $newCount new")

        return if (newCount > 0) SyncResult.Success(newCount) else SyncResult.NoChange
    }

    suspend fun markAttendanceAsSeen(subjectId: String) {
        attendanceDao.markAllAsSeen(subjectId)
    }

    // ── Login / logout ────────────────────────────────────────────────────────

    suspend fun login(email: String, password: String, semId: Int): LoginResult {
        val result = api.login(email, password, semId)
        if (result is LoginResult.Success) {
            prefs.saveCredentials(email, password, semId)
        }
        return result
    }

    suspend fun logout() {
        prefs.clearAll()
        subjectDao.deleteAll()
        HttpClientHolder.clearSession()
    }

    // ── Sync ──────────────────────────────────────────────────────────────────

    suspend fun initialSync(): Int {
        val creds = prefs.getCredentialsSnapshot()
        val email = creds.email
        val password = creds.password
        val semId = creds.semId
        if (email.isEmpty()) return 0

        syncSubjects(semId, email, password)

        val subjects = subjectDao.getAllSubjectsList()
        Log.d(TAG, "initialSync: pre-caching attendance for ${subjects.size} subjects")

        val results = coroutineScope {
            subjects.map { subject ->
                async { syncAttendanceDetail(subject.subjectId) }
            }.awaitAll()
        }

        return results.sumOf { if (it is SyncResult.Success) it.newCount else 0 }
    }

    suspend fun backgroundSync(): Int {
        val creds = prefs.getCredentialsSnapshot()
        val email = creds.email
        val password = creds.password
        val semId = creds.semId
        if (email.isEmpty()) return 0

        syncSubjects(semId, email, password)

        var totalNew = 0
        for (subject in subjectDao.getAllSubjectsList()) {
            val r = syncAttendanceDetail(subject.subjectId)
            if (r is SyncResult.Success) totalNew += r.newCount
        }
        return totalNew
    }
}