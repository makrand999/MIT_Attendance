package com.mit.attendance.data.db

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.*
import com.mit.attendance.model.*
import com.mit.attendance.campus.*
import kotlinx.coroutines.flow.Flow

// ── Subject DAO ───────────────────────────────────────────────────────────────

@Dao
interface SubjectDao {

    @Query("SELECT * FROM subjects ORDER BY subjectName ASC")
    fun getAllSubjects(): Flow<List<SubjectEntity>>

    @Query("SELECT * FROM subjects ORDER BY subjectName ASC")
    suspend fun getAllSubjectsList(): List<SubjectEntity>

    @Query("SELECT * FROM subjects WHERE subjectName = :name LIMIT 1")
    suspend fun getSubjectByName(name: String): SubjectEntity?

    /**
     * Joins subjects with attendance_records and alerts to count how many 'isNew' entries exist per subject.
     * Also provides actual totals based on the attendance records.
     */
    @Query("""
        SELECT 
            s.*, 
            (SELECT COUNT(*) FROM attendance_records a WHERE a.subjectName = s.subjectName AND a.isNew = 1) as newCount,
            (SELECT COUNT(*) FROM campus_alerts al JOIN campus_active ac ON ac.account=al.account AND ac.classId=al.classId JOIN campus_subject_links sl ON sl.account=al.account AND sl.classId=al.classId AND sl.subjectId=al.subjectId LEFT JOIN campus_states st ON st.account=al.account AND st.classId=al.classId AND st.alertId=al.id WHERE sl.localName=s.subjectName AND al.status='PUBLISHED' AND COALESCE(st.seen,0)=0 AND COALESCE(st.hidden,0)=0 AND COALESCE(st.trashed,0)=0) as newAlertCount,
            (SELECT COUNT(*) FROM attendance_records a WHERE a.subjectName = s.subjectName AND a.status = 'P') as actualPresent,
            (SELECT COUNT(*) FROM attendance_records a WHERE a.subjectName = s.subjectName AND a.status = 'A') as actualAbsent,
            (SELECT COUNT(*) FROM attendance_records a WHERE a.subjectName = s.subjectName) as actualTotal
        FROM subjects s
        ORDER BY s.subjectName ASC
    """)
    fun getAllSubjectsWithNewCounts(): Flow<List<SubjectWithStats>>

    data class SubjectWithStats(
        @Embedded val subject: SubjectEntity,
        val newCount: Int,
        val newAlertCount: Int,
        val actualPresent: Long,
        val actualAbsent: Long,
        val actualTotal: Long
    )

    @Upsert
    suspend fun upsertSubjects(subjects: List<SubjectEntity>)

    @Query("DELETE FROM subjects WHERE subjectName NOT IN (:names)")
    suspend fun deleteNotIn(names: List<String>)

    @Query("DELETE FROM subjects")
    suspend fun deleteAll()
}

// ── Attendance DAO ────────────────────────────────────────────────────────────

@Dao
interface AttendanceDao {

    @Query("""
        SELECT * FROM attendance_records
        WHERE subjectName = :subjectName
        ORDER BY serverOrder DESC
    """)
    fun getAttendanceForSubject(subjectName: String): Flow<List<AttendanceEntity>>

    @Query("""
        SELECT * FROM attendance_records
        WHERE subjectName = :subjectName
        ORDER BY serverOrder DESC
    """)
    suspend fun getAttendanceForSubjectList(subjectName: String): List<AttendanceEntity>

    @Upsert
    suspend fun upsertRecords(records: List<AttendanceEntity>): List<Long>

    @Query("""
        UPDATE attendance_records
        SET isNew = 0, seenAt = :time
        WHERE subjectName = :subjectName AND isNew = 1
    """)
    suspend fun markAllAsSeen(subjectName: String, time: Long)

    /**
     * When a subject is purged, its attendance records should be cleaned up.
     */
    @Query("DELETE FROM attendance_records WHERE subjectName NOT IN (SELECT subjectName FROM subjects)")
    suspend fun purgeOrphanedRecords()

    @Query("DELETE FROM attendance_records WHERE subjectName = :subjectName")
    suspend fun deleteForSubject(subjectName: String)

    @Query("DELETE FROM attendance_records")
    suspend fun deleteAll()
}

// ── Practical DAO ─────────────────────────────────────────────────────────────

@Dao
interface PracticalDao {
    @Query("SELECT * FROM practical_subjects ORDER BY subjectname ASC")
    suspend fun getAllSubjects(): List<PracticalSubject>

    @Upsert
    suspend fun upsertSubjects(subjects: List<PracticalSubject>)

    @Query("SELECT * FROM practicals WHERE subjectId = :subjectId")
    suspend fun getPracticalsForSubject(subjectId: Int): List<Practical>

    @Query("SELECT * FROM practicals WHERE id = :id LIMIT 1")
    suspend fun getPracticalById(id: Int): Practical?

    @Query("UPDATE practicals SET localDraftSavedAt = :savedAt WHERE id = :id")
    suspend fun updateLocalDraftTimestamp(id: Int, savedAt: Long?)

    @Query("""
        UPDATE practicals
        SET pendingSyncAction = :pendingSyncAction,
            lastSyncError = :lastSyncError
        WHERE id = :id
    """)
    suspend fun updateSyncFailureState(id: Int, pendingSyncAction: String?, lastSyncError: String?)

    @Query("""
        UPDATE practicals
        SET lastServerSyncAt = :serverSyncAt,
            lastServerVerifiedAt = :serverVerifiedAt,
            pendingSyncAction = NULL,
            lastSyncError = NULL
        WHERE id = :id
    """)
    suspend fun markServerSyncSuccess(id: Int, serverSyncAt: Long, serverVerifiedAt: Long = serverSyncAt)

    @Query("""
        UPDATE practicals
        SET lastServerVerifiedAt = :verifiedAt
        WHERE id = :id
    """)
    suspend fun updateServerVerifiedAt(id: Int, verifiedAt: Long)

    @Upsert
    suspend fun upsertPracticals(practicals: List<Practical>)

    @Query("""
        UPDATE practicals
        SET theory = :theory,
            conclusion = :conclusion,
            language = :language,
            code = :code,
            output = :output,
            error = :error,
            practicalDoc = :practicalDoc,
            submissionId = :submissionId,
            submissionType = :submissionType,
            evaluated = :evaluated,
            isSubmitted = 1
        WHERE id = :id
    """)
    suspend fun updatePracticalSubmission(
        id: Int,
        theory: String,
        conclusion: String,
        language: String,
        code: String,
        output: String,
        error: String,
        practicalDoc: String?,
        submissionId: Int?,
        submissionType: String?,
        evaluated: Boolean
    )

    @Query("DELETE FROM practicals WHERE subjectId = :subjectId")
    suspend fun deletePracticalsForSubject(subjectId: Int)

    @Query("DELETE FROM practical_subjects")
    suspend fun deleteAllSubjects()

    @Query("DELETE FROM practicals")
    suspend fun deleteAllPracticals()
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities = [
        SubjectEntity::class, 
        AttendanceEntity::class, 
        ReviewEntity::class, 
        PracticalDraft::class,
        PracticalSubject::class,
        Practical::class,
        AlertEntity::class, CachedCampusAlert::class, CampusPersonalState::class, CampusCursor::class,
        CampusDirectory::class, CampusOutgoing::class, CampusSubjectLink::class, CampusActive::class, CampusNotification::class
    ], 
    version = 15,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subjectDao(): SubjectDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun reviewDao(): ReviewDao
    abstract fun practicalDraftDao(): PracticalDraftDao
    abstract fun practicalDao(): PracticalDao
    abstract fun campusDao(): CampusDao
    abstract fun alertDao(): AlertDao

    companion object {
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE practical_drafts ADD COLUMN language TEXT NOT NULL DEFAULT 'python'")
                db.execSQL("ALTER TABLE practical_drafts ADD COLUMN code TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE practical_drafts ADD COLUMN output TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE practical_drafts ADD COLUMN error TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE practical_drafts ADD COLUMN pdfUri TEXT")
                db.execSQL("ALTER TABLE practical_drafts ADD COLUMN pdfName TEXT")

                db.execSQL("ALTER TABLE practicals ADD COLUMN batch TEXT")
                db.execSQL("ALTER TABLE practicals ADD COLUMN output TEXT")
                db.execSQL("ALTER TABLE practicals ADD COLUMN error TEXT")
                db.execSQL("ALTER TABLE practicals ADD COLUMN submissionType TEXT")
                db.execSQL("ALTER TABLE practicals ADD COLUMN remainingTime TEXT")
                db.execSQL("ALTER TABLE practicals ADD COLUMN autoSubmitted INTEGER")
                db.execSQL("ALTER TABLE practicals ADD COLUMN practicalDoc TEXT")
                db.execSQL("ALTER TABLE practicals ADD COLUMN submissionId INTEGER")
                db.execSQL("ALTER TABLE practicals ADD COLUMN evaluated INTEGER")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alerts ADD COLUMN isTrashed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alerts ADD COLUMN trashedAt INTEGER")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE practical_drafts ADD COLUMN lastServerSyncAt INTEGER")
                db.execSQL("ALTER TABLE practical_drafts ADD COLUMN lastServerVerifiedAt INTEGER")
                db.execSQL("ALTER TABLE practical_drafts ADD COLUMN pendingSyncAction TEXT")
                db.execSQL("ALTER TABLE practical_drafts ADD COLUMN lastSyncError TEXT")

                db.execSQL("ALTER TABLE practicals ADD COLUMN localDraftSavedAt INTEGER")
                db.execSQL("ALTER TABLE practicals ADD COLUMN lastServerSyncAt INTEGER")
                db.execSQL("ALTER TABLE practicals ADD COLUMN lastServerVerifiedAt INTEGER")
                db.execSQL("ALTER TABLE practicals ADD COLUMN pendingSyncAction TEXT")
                db.execSQL("ALTER TABLE practicals ADD COLUMN lastSyncError TEXT")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS campus_alerts (account TEXT NOT NULL,classId TEXT NOT NULL,id TEXT NOT NULL,subjectId TEXT,status TEXT NOT NULL,version INTEGER NOT NULL,topic TEXT NOT NULL,description TEXT NOT NULL,authorId TEXT,authorName TEXT NOT NULL,type TEXT NOT NULL,dueAt TEXT,expiresAt TEXT,json TEXT NOT NULL,PRIMARY KEY(account,classId,id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS campus_states (account TEXT NOT NULL,classId TEXT NOT NULL,alertId TEXT NOT NULL,seen INTEGER NOT NULL,hidden INTEGER NOT NULL,trashed INTEGER NOT NULL,pendingKey TEXT,confirmedJson TEXT,PRIMARY KEY(account,classId,alertId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS campus_cursor (account TEXT NOT NULL,classId TEXT NOT NULL,cursor TEXT NOT NULL,initialized INTEGER NOT NULL,PRIMARY KEY(account,classId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS campus_directory (account TEXT NOT NULL,kind TEXT NOT NULL,parent TEXT NOT NULL,id TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(account,kind,parent,id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS campus_outgoing (`key` TEXT NOT NULL,account TEXT NOT NULL,classId TEXT NOT NULL,resourceId TEXT NOT NULL,method TEXT NOT NULL,path TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL,error TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(`key`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS campus_subject_links (account TEXT NOT NULL,classId TEXT NOT NULL,localName TEXT NOT NULL,subjectId TEXT NOT NULL,PRIMARY KEY(account,classId,localName))")
                db.execSQL("CREATE TABLE IF NOT EXISTS campus_active (slot INTEGER NOT NULL,account TEXT NOT NULL,classId TEXT NOT NULL,generation TEXT NOT NULL,PRIMARY KEY(slot))")
                db.execSQL("CREATE TABLE IF NOT EXISTS campus_notifications (account TEXT NOT NULL,classId TEXT NOT NULL,alertId TEXT NOT NULL,version INTEGER NOT NULL,PRIMARY KEY(account,classId,alertId,version))")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "attendance_database"
                )
                // Removed fallbackToDestructiveMigration() to prevent data loss on app update
                .addMigrations(MIGRATION_11_12)
                .addMigrations(MIGRATION_12_13)
                .addMigrations(MIGRATION_13_14)
                .addMigrations(MIGRATION_14_15)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
