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

data class SubjectWithNewCount(
    @Embedded val entity: SubjectEntity,
    val newCount: Long,
    val actualPresent: Long,
    val actualAbsent: Long,
    val actualTotal: Long
)

// ── Subject DAO ───────────────────────────────────────────────────────────────

@Dao
interface SubjectDao {

    @Query("""
    SELECT 
        s.*,
        COUNT(CASE WHEN a.isNew = 1 THEN 1 END) AS newCount,
        COUNT(CASE WHEN a.status = 'P' THEN 1 END) AS actualPresent,
        COUNT(CASE WHEN a.status = 'A' THEN 1 END) AS actualAbsent,
        COUNT(a.date) AS actualTotal
    FROM subjects s
    LEFT JOIN attendance_records a ON s.subjectName = a.subjectName
    GROUP BY s.subjectName
    ORDER BY s.subjectName
""")
    fun getAllSubjectsWithNewCounts(): Flow<List<SubjectWithNewCount>>

    @Query("SELECT * FROM subjects ORDER BY subjectName")
    suspend fun getAllSubjectsList(): List<SubjectEntity>
    
    @Query("SELECT * FROM subjects WHERE subjectName = :name LIMIT 1")
    suspend fun getSubjectByName(name: String): SubjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubjects(subjects: List<SubjectEntity>)

    /**
     * Deletes subjects that are not in the provided list of names.
     * This "purges" old subjects that the server no longer reports.
     */
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
        ORDER BY date DESC, startTime DESC
    """)
    fun getAttendanceForSubject(subjectName: String): Flow<List<AttendanceEntity>>

    @Query("""
        SELECT * FROM attendance_records
        WHERE subjectName = :subjectName
        ORDER BY date DESC, startTime DESC
    """)
    suspend fun getAttendanceForSubjectList(subjectName: String): List<AttendanceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(records: List<AttendanceEntity>): List<Long>

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

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities = [SubjectEntity::class, AttendanceEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subjectDao(): SubjectDao
    abstract fun attendanceDao(): AttendanceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mit_attendance_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
