package com.mit.attendance.campus

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "campus_alerts", primaryKeys = ["account", "classId", "id"])
data class CachedCampusAlert(val account: String, val classId: String, val id: String,
    val subjectId: String?, val status: String, val version: Long, val topic: String,
    val description: String, val authorId: String?, val authorName: String,
    val type: String, val dueAt: String?, val expiresAt: String?, val json: String)
@Entity(tableName = "campus_states", primaryKeys = ["account", "classId", "alertId"])
data class CampusPersonalState(val account: String, val classId: String, val alertId: String,
    val seen: Boolean = false, val hidden: Boolean = false, val trashed: Boolean = false, val pendingKey: String? = null, val confirmedJson: String? = null)
@Entity(tableName = "campus_cursor", primaryKeys = ["account", "classId"])
data class CampusCursor(val account: String, val classId: String, val cursor: String, val initialized: Boolean)
@Entity(tableName = "campus_directory", primaryKeys = ["account", "kind", "parent", "id"])
data class CampusDirectory(val account: String, val kind: String, val parent: String, val id: String, val json: String)
@Entity(tableName = "campus_outgoing")
data class CampusOutgoing(@PrimaryKey val key: String, val account: String, val classId: String,
    val resourceId: String, val method: String, val path: String, val body: String,
    val status: String = "PENDING", val error: String = "", val created: Long = System.currentTimeMillis())
@Entity(tableName = "campus_subject_links", primaryKeys = ["account", "classId", "localName"])
data class CampusSubjectLink(val account: String, val classId: String, val localName: String, val subjectId: String)
@Entity(tableName = "campus_active")
data class CampusActive(@PrimaryKey val slot: Int = 1, val account: String, val classId: String, val generation: String)
@Entity(tableName = "campus_notifications", primaryKeys = ["account", "classId", "alertId", "version"])
data class CampusNotification(val account: String, val classId: String, val alertId: String, val version: Long)

@Dao
interface CampusDao {
    @Query("SELECT * FROM campus_alerts WHERE account=:account AND classId=:classId AND status!='DELETED' ORDER BY rowid DESC")
    fun alerts(account: String, classId: String): Flow<List<CachedCampusAlert>>
    @Query("SELECT * FROM campus_alerts WHERE account=:account AND classId=:classId AND id=:id")
    suspend fun alert(account: String, classId: String, id: String): CachedCampusAlert?
    @Query("SELECT * FROM campus_alerts WHERE account=:account AND classId=:classId") suspend fun alertSnapshot(account:String,classId:String):List<CachedCampusAlert>
    @Upsert suspend fun saveAlert(alert: CachedCampusAlert)
    @Query("DELETE FROM campus_alerts WHERE account=:account AND classId=:classId") suspend fun resetAlerts(account: String, classId: String)
    @Query("SELECT * FROM campus_states WHERE account=:account AND classId=:classId") fun states(account: String, classId: String): Flow<List<CampusPersonalState>>
    @Query("SELECT * FROM campus_states WHERE account=:account AND classId=:classId AND alertId=:id") suspend fun state(account: String, classId: String, id: String): CampusPersonalState?
    @Upsert suspend fun saveState(state: CampusPersonalState)
    @Query("SELECT * FROM campus_cursor WHERE account=:account AND classId=:classId") suspend fun cursor(account: String, classId: String): CampusCursor?
    @Upsert suspend fun saveCursor(cursor: CampusCursor)
    @Query("DELETE FROM campus_cursor WHERE account=:account AND classId=:classId") suspend fun resetCursor(account: String, classId: String)
    @Query("SELECT * FROM campus_directory WHERE account=:account AND kind=:kind AND parent=:parent ORDER BY id") suspend fun directory(account: String, kind: String, parent: String = ""): List<CampusDirectory>
    @Upsert suspend fun saveDirectory(rows: List<CampusDirectory>)
    @Query("DELETE FROM campus_directory WHERE account=:account AND kind=:kind AND parent=:parent") suspend fun clearDirectory(account: String, kind: String, parent: String = "")
    @Insert suspend fun enqueue(item: CampusOutgoing)
    @Upsert suspend fun saveOutgoing(item: CampusOutgoing)
    @Query("SELECT * FROM campus_outgoing WHERE account=:account AND classId=:classId ORDER BY created,key") suspend fun outgoing(account: String, classId: String): List<CampusOutgoing>
    @Query("SELECT * FROM campus_outgoing WHERE account=:account AND classId=:classId ORDER BY created,key") fun observeOutgoing(account: String, classId: String): Flow<List<CampusOutgoing>>
    @Query("DELETE FROM campus_outgoing WHERE `key`=:key AND account=:account") suspend fun removeOutgoing(account: String, key: String)
    @Upsert suspend fun link(link: CampusSubjectLink)
    @Query("SELECT * FROM campus_subject_links WHERE account=:account AND classId=:classId") suspend fun links(account: String, classId: String): List<CampusSubjectLink>
    @Upsert suspend fun active(active: CampusActive)
    @Query("DELETE FROM campus_active") suspend fun clearActive()
    @Query("DELETE FROM campus_active WHERE generation=:generation AND classId=:classId") suspend fun clearActiveGeneration(generation: String, classId: String?)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun notified(notification: CampusNotification): Long
}
