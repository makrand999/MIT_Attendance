package com.mit.attendance.data.db

import android.content.Context
import androidx.room.*
import com.mit.attendance.model.AttendanceEntity
import com.mit.attendance.model.SubjectEntity
import kotlinx.coroutines.flow.Flow

// ── Type Converters ───────────────────────────────────────────────────────────

class Converters {
    @TypeConverter fun fromBoolean(value: Boolean): Int = if (value) 1 else 0
    @TypeConverter fun toBoolean(value: Int): Boolean = value == 1
}

// ── JOIN result ───────────────────────────────────────────────────────────────

/**
 * Returned by [SubjectDao.getAllSubjectsWithNewCounts].
 * Avoids calling countNewEntries() per-subject inside Flow.map{} which would
 * cause N cascading DB reads and repeated list emissions that look like duplicates.
 */
data class SubjectWithNewCount(
    @Embedded val entity: SubjectEntity,
    val newCount: Int
)

// ── Subject DAO ───────────────────────────────────────────────────────────────

@Dao
interface SubjectDao {

    /**
     * Single query joining subjects + unseen attendance count.
     * Room fires ONE Flow emission per change instead of N per subject.
     */
    @Query("""
        SELECT s.*, COUNT(CASE WHEN a.isNew = 1 THEN 1 END) AS newCount
        FROM subjects s
        LEFT JOIN attendance_records a ON s.subjectId = a.subjectId
        GROUP BY s.subjectId
        ORDER BY s.subjectName
    """)
    fun getAllSubjectsWithNewCounts(): Flow<List<SubjectWithNewCount>>

    @Query("SELECT * FROM subjects ORDER BY subjectName")
    suspend fun getAllSubjectsList(): List<SubjectEntity>

    /** REPLACE handles both insert (new row) and update (existing row) atomically. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubjects(subjects: List<SubjectEntity>)

    @Query("DELETE FROM subjects")
    suspend fun deleteAll()
}

// ── Attendance DAO ────────────────────────────────────────────────────────────

@Dao
interface AttendanceDao {

    @Query("""
        SELECT * FROM attendance_records
        WHERE subjectId = :subjectId
        ORDER BY date DESC, startTime DESC
    """)
    fun getAttendanceForSubject(subjectId: String): Flow<List<AttendanceEntity>>

    @Query("""
        SELECT * FROM attendance_records
        WHERE subjectId = :subjectId
        ORDER BY date DESC, startTime DESC
    """)
    suspend fun getAttendanceForSubjectList(subjectId: String): List<AttendanceEntity>

    /**
     * Only inserts rows whose composite primary key (subjectId, date, startTime)
     * does not already exist. Returns -1L for skipped rows.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(records: List<AttendanceEntity>): List<Long>

    @Query("""
        UPDATE attendance_records
        SET isNew = 0, seenAt = :time
        WHERE subjectId = :subjectId AND isNew = 1
    """)
    suspend fun markAllAsSeen(subjectId: String, time: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM attendance_records WHERE subjectId = :subjectId AND isNew = 1")
    suspend fun countNewEntries(subjectId: String): Int

    @Query("SELECT COUNT(*) FROM attendance_records WHERE isNew = 1")
    suspend fun totalNewEntriesAllSubjects(): Int

    @Query("DELETE FROM attendance_records WHERE subjectId = :subjectId")
    suspend fun deleteForSubject(subjectId: String)
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities = [SubjectEntity::class, AttendanceEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AttendanceDatabase : RoomDatabase() {

    abstract fun subjectDao(): SubjectDao
    abstract fun attendanceDao(): AttendanceDao

    companion object {
        @Volatile private var INSTANCE: AttendanceDatabase? = null

        fun getInstance(context: Context): AttendanceDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AttendanceDatabase::class.java,
                    "attendance_db"
                ).build().also { INSTANCE = it }
            }
    }
}