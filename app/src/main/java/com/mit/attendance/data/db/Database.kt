package com.mit.attendance.data.db

import android.content.Context
import androidx.room.*
import com.mit.attendance.model.*
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
     * Joins subjects with attendance_records to count how many 'isNew' entries exist per subject.
     * Also provides actual totals based on the attendance records.
     */
    @Query("""
        SELECT 
            s.*, 
            COUNT(CASE WHEN a.isNew = 1 THEN 1 END) as newCount,
            COUNT(CASE WHEN a.status = 'P' THEN 1 END) as actualPresent,
            COUNT(CASE WHEN a.status = 'A' THEN 1 END) as actualAbsent,
            COUNT(a.subjectName) as actualTotal
        FROM subjects s
        LEFT JOIN attendance_records a ON s.subjectName = a.subjectName
        GROUP BY s.subjectName
        ORDER BY s.subjectName ASC
    """)
    fun getAllSubjectsWithNewCounts(): Flow<List<SubjectWithStats>>

    data class SubjectWithStats(
        @Embedded val subject: SubjectEntity,
        val newCount: Int,
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

    @Upsert
    suspend fun upsertPracticals(practicals: List<Practical>)

    @Query("UPDATE practicals SET theory = :theory, conclusion = :conclusion, isSubmitted = 1 WHERE id = :id")
    suspend fun updatePracticalSubmission(id: Int, theory: String, conclusion: String)

    @Query("DELETE FROM practicals WHERE subjectId = :subjectId")
    suspend fun deletePracticalsForSubject(subjectId: Int)
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities = [
        SubjectEntity::class, 
        AttendanceEntity::class, 
        ReviewEntity::class, 
        PracticalDraft::class,
        PracticalSubject::class,
        Practical::class
    ], 
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subjectDao(): SubjectDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun reviewDao(): ReviewDao
    abstract fun practicalDraftDao(): PracticalDraftDao
    abstract fun practicalDao(): PracticalDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "attendance_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
