package com.mit.attendance.data

import android.util.Log
import com.mit.attendance.data.api.AttendanceApiService
import com.mit.attendance.data.api.HttpClientHolder
import com.mit.attendance.data.db.AppDatabase
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AttendanceRepository(private val context: android.content.Context) {

    private val TAG = "AttendanceRepo"

    val api = AttendanceApiService()
    private val db = AppDatabase.getDatabase(context)
    val prefs = UserPreferences(context)

    private val subjectDao = db.subjectDao()
    private val attendanceDao = db.attendanceDao()

    // ── User Info ─────────────────────────────────────────────────────────────

    fun getGenderFlow(): Flow<String?> = prefs.gender

    suspend fun getGenderSnapshot(): String? = prefs.getGender()

    // ── Subjects ──────────────────────────────────────────────────────────────

    fun getSubjectsFlow(): Flow<List<SubjectUiModel>> =
        subjectDao.getAllSubjectsWithNewCounts().map { rows ->
            rows.map { (entity, newCount, actualPresent, actualAbsent, actualTotal) ->
                val present = if (actualTotal > 0L) actualPresent.toInt() else entity.totalPresent
                val absent  = if (actualTotal > 0L) actualAbsent.toInt()  else entity.totalAbsent
                val total   = if (actualTotal > 0L) actualTotal.toInt()   else entity.totalLecture
                val pct     = if (total > 0) (present * 100f / total) else entity.percentage

                SubjectUiModel(
                    id             = entity.subjectName,
                    name           = entity.subjectName,
                    present        = present,
                    absent         = absent,
                    total          = total,
                    percentage     = pct,
                    hasNewEntries  = newCount > 0
                )
            }
        }

    private suspend fun syncSubjects(semId: Int, email: String, password: String) = withContext(Dispatchers.IO) {
        val result = api.fetchSubjects(semId, email, password)
        if (result.isFailure) {
            Log.w(TAG, "syncSubjects failed: ${result.exceptionOrNull()?.message}")
            return@withContext
        }
        
        val apiResponse = result.getOrThrow()
        
        // 1. Map to entities with aggressive cleaning
        val entities = apiResponse.mapNotNull { s ->
            val id = s.encoSubjectwiseStudentId ?: return@mapNotNull null
            // Standardize whitespace: trim and replace multiple spaces with one
            val name = s.subject?.trim()?.replace(Regex("\\s+"), " ") ?: "Unknown"
            val present = s.presentCount ?: 0
            val absent = s.absentCount ?: 0
            val total = s.totalLecture ?: (present + absent)
            val pct = s.percentage ?: if (total > 0) (present * 100f / total) else 0f
            
            SubjectEntity(
                subjectName = name,
                subjectId = id,
                totalPresent = present,
                totalAbsent = absent,
                totalLecture = total,
                percentage = pct
            )
        }
        
        if (entities.isNotEmpty()) {
            // 2. Purge subjects that no longer exist on the server (handles renames/removals)
            val serverNames = entities.map { it.subjectName }
            subjectDao.deleteNotIn(serverNames)
            attendanceDao.purgeOrphanedRecords()

            // 3. Upsert the fresh list
            subjectDao.upsertSubjects(entities)
            Log.d(TAG, "Synced ${entities.size} subjects (purged old ones)")
        }
    }

    // ── Attendance detail ─────────────────────────────────────────────────────

    fun getAttendanceFlow(subjectName: String): Flow<List<AttendanceUiModel>> =
        attendanceDao.getAttendanceForSubject(subjectName).map { entities ->
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

    suspend fun syncAttendanceDetail(subjectName: String): SyncResult = withContext(Dispatchers.IO) {
        val creds = prefs.getCredentialsSnapshot()
        val email = creds.email
        val password = creds.password
        val semId    = creds.semId
        if (email.isEmpty()) return@withContext SyncResult.SessionError

        val subject = subjectDao.getSubjectByName(subjectName)
        if (subject == null) {
            Log.e(TAG, "Subject not found in DB: $subjectName")
            return@withContext SyncResult.SessionError
        }
        val subjectId = subject.subjectId

        val result = api.fetchAttendanceDetail(subjectId, semId, email, password)
        if (result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: ""
            Log.e(TAG, "fetchAttendanceDetail failed for $subjectName: $msg")
            return@withContext if (msg.contains("offline", ignoreCase = true))
                SyncResult.ServerOffline
            else
                SyncResult.NetworkError
        }

        val apiRecords = result.getOrThrow()
        if (apiRecords.isEmpty()) return@withContext SyncResult.NoChange

        // Map existing records for quick lookup: Key -> Existing Record
        val existingMap = attendanceDao.getAttendanceForSubjectList(subjectName)
            .associateBy { Triple(it.subjectName, it.date, it.startTime) }

        val incoming = apiRecords.mapNotNull { r ->
            val date = r.Date ?: return@mapNotNull null
            val stime = r.stime?.trim() ?: ""
            val etime = r.etime?.trim() ?: ""
            
            val status = when (r.presenty?.trim()?.lowercase()) {
                "p", "present", "1" -> "P"
                "a", "absent", "0" -> "A"
                else -> return@mapNotNull null // Ignore records that are neither Present nor Absent
            }

            val key = Triple(subjectName, date, stime)
            val existing = existingMap[key]

            // Mark as 'new' if:
            // 1. It's not in our database yet
            // 2. The status has changed (e.g. Absent -> Present)
            // 3. It was already marked as new (preserve its 'new' badge)
            val isNew = when {
                existing == null -> true
                existing.status != status -> true
                else -> existing.isNew
            }

            AttendanceEntity(
                subjectName = subjectName,
                date = date,
                status = status,
                startTime = stime,
                endTime = etime,
                isNew = isNew,
                seenAt = if (existing != null && existing.status == status) existing.seenAt else null
            )
        }

        // Use the newly added upsert method to update existing records
        val insertedRows = attendanceDao.upsertRecords(incoming)
        
        // Count how many records were actually "new changes" that the user hasn't seen yet
        val newChangesCount = incoming.count { 
            val wasAlreadyNew = existingMap[Triple(it.subjectName, it.date, it.startTime)]?.isNew == true
            it.isNew && !wasAlreadyNew 
        }

        return@withContext if (newChangesCount > 0) SyncResult.Success(newChangesCount) else SyncResult.NoChange
    }

    suspend fun markAttendanceAsSeen(subjectName: String) = withContext(Dispatchers.IO) {
        attendanceDao.markAllAsSeen(subjectName, System.currentTimeMillis())
    }

    // ── Login / logout ────────────────────────────────────────────────────────

    suspend fun login(email: String, password: String, semId: Int): LoginResult = withContext(Dispatchers.IO) {
        // Emergency cleanup on login to prevent cross-account/cross-sem ghosting
        db.clearAllTables()
        
        val result = api.login(email, password, semId)
        if (result is LoginResult.Success) {
            prefs.saveCredentials(email, password, semId)
            // Fetch and save gender after successful login
            try {
                val infoResult = api.fetchStudentInfo(email, password, semId)
                infoResult.getOrNull()?.Gender?.let { gender ->
                    prefs.saveGender(gender)
                    Log.d(TAG, "Saved user gender: $gender")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch student info during login", e)
            }
        }
        return@withContext result
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        prefs.clearAll()
        subjectDao.deleteAll()
        HttpClientHolder.clearSession()
    }

    // ── Sync ──────────────────────────────────────────────────────────────────

    suspend fun initialSync(): Int = withContext(Dispatchers.IO) {
        val creds = prefs.getCredentialsSnapshot()
        if (creds.email.isEmpty()) return@withContext 0

        // Auto-fix duplicates before normal sync
        autoFixDuplicates()

        // Sync gender if not present
        if (prefs.getGender() == null) {
            api.fetchStudentInfo(creds.email, creds.password, creds.semId).getOrNull()?.Gender?.let {
                prefs.saveGender(it)
            }
        }

        // Sync reviews every time app opens
        ReviewRepository(context).syncReviews()

        syncSubjects(creds.semId, creds.email, creds.password)
        val subjects = subjectDao.getAllSubjectsList()
        val results = coroutineScope {
            subjects.map { subject ->
                async { syncAttendanceDetail(subject.subjectName) }
            }.awaitAll()
        }
        return@withContext results.sumOf { if (it is SyncResult.Success) it.newCount else 0 }
    }

    suspend fun backgroundSync(): Int = withContext(Dispatchers.IO) {
        val creds = prefs.getCredentialsSnapshot()
        if (creds.email.isEmpty()) return@withContext 0

        // Auto-fix duplicates before normal sync
        autoFixDuplicates()

        syncSubjects(creds.semId, creds.email, creds.password)
        var totalNew = 0
        for (subject in subjectDao.getAllSubjectsList()) {
            val r = syncAttendanceDetail(subject.subjectName)
            if (r is SyncResult.Success) totalNew += r.newCount
        }
        return@withContext totalNew
    }

    suspend fun autoFixDuplicates() = withContext(Dispatchers.IO) {
        val subjects = subjectDao.getAllSubjectsList()
        if (subjects.isEmpty()) return@withContext
        val uniqueNames = subjects.map { it.subjectName.trim().lowercase() }.toSet()
        if (subjects.size > uniqueNames.size) {
            Log.w(TAG, "Emergency Duplicate Cleanup triggered")
            performCleanSync()
        }
    }

    suspend fun performCleanSync() = withContext(Dispatchers.IO) {
        val creds = prefs.getCredentialsSnapshot()
        if (creds.email.isEmpty()) return@withContext
        db.clearAllTables()
        login(creds.email, creds.password, creds.semId)
        syncSubjects(creds.semId, creds.email, creds.password)
        val subjects = subjectDao.getAllSubjectsList()
        for (subject in subjects) {
            syncAttendanceDetail(subject.subjectName)
            markAttendanceAsSeen(subject.subjectName)
        }
    }
}
