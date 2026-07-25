package com.mit.attendance.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mit.attendance.model.AlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts WHERE class_code = :classCode AND isHidden = 0 AND isTrashed = 0 ORDER BY posted_at DESC")
    fun getAlerts(classCode: String): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE class_code = :classCode AND isTrashed = 1 ORDER BY COALESCE(trashedAt, 0) DESC, posted_at DESC")
    fun getTrashAlerts(classCode: String): Flow<List<AlertEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlerts(alerts: List<AlertEntity>)

    @Query("SELECT id FROM alerts")
    suspend fun getAllAlertIds(): List<String>

    @Query("SELECT * FROM alerts")
    suspend fun getAllAlertsSnapshot(): List<AlertEntity>

    @Query("UPDATE alerts SET seen = 1 WHERE id = :alertId")
    suspend fun markAsSeen(alertId: String)

    @Query("UPDATE alerts SET isHidden = 1 WHERE id = :alertId")
    suspend fun hideAlert(alertId: String)

    @Query("UPDATE alerts SET isTrashed = 1, trashedAt = :trashedAt WHERE id = :alertId")
    suspend fun moveToTrash(alertId: String, trashedAt: Long = System.currentTimeMillis())

    @Query("UPDATE alerts SET isTrashed = 0, trashedAt = NULL WHERE id = :alertId")
    suspend fun restoreFromTrash(alertId: String)

    @Query("UPDATE alerts SET isTrashed = 1, trashedAt = :trashedAt WHERE end_date != '' AND end_date < :today AND isTrashed = 0")
    suspend fun moveExpiredAlertsToTrash(today: String, trashedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM alerts WHERE id = :alertId")
    suspend fun deleteAlert(alertId: String)

    @Query("DELETE FROM alerts")
    suspend fun deleteAll()
}
