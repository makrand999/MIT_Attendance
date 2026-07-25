package com.mit.attendance.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mit.attendance.BuildConfig
import com.mit.attendance.data.api.AlertApiService
import com.mit.attendance.data.api.AlertRetrofitClient
import com.mit.attendance.data.db.AlertDao
import com.mit.attendance.data.db.AppDatabase
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.text.SimpleDateFormat
import java.util.*

class AlertRepository(private val context: Context) {

    private val alertDao: AlertDao = AppDatabase.getDatabase(context).alertDao()
    private val userPrefs = UserPreferences(context)
    private val gson = Gson()
    
    private val api: AlertApiService by lazy {
        AlertRetrofitClient.instance.create(AlertApiService::class.java)
    }

    private val GITHUB_PAT = "Bearer ${BuildConfig.GITHUB_TOKEN_ALERT}"
    private val PARENT_GIST_ID = "0abc3d0134a76f70a603f332c5374e4b"

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getAlertsFlow(): Flow<List<AlertEntity>> {
        return userPrefs.courseName.flatMapLatest { courseName ->
            if (!courseName.isNullOrBlank()) {
                alertDao.getAlerts(courseName)
            } else {
                flowOf(emptyList())
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getTrashAlertsFlow(): Flow<List<AlertEntity>> {
        return userPrefs.courseName.flatMapLatest { courseName ->
            if (!courseName.isNullOrBlank()) {
                alertDao.getTrashAlerts(courseName)
            } else {
                flowOf(emptyList())
            }
        }
    }

    suspend fun fetchAlerts(): Int {
        val courseName = userPrefs.getCourseNameSnapshot() ?: return 0
        if (courseName.isBlank()) return 0

        moveExpiredAlertsToTrash()

        var courseGistId = userPrefs.getCourseGistIdSnapshot()

        try {
            val parentResponse = api.getGist(GITHUB_PAT, PARENT_GIST_ID)
            if (parentResponse.isSuccessful) {
                val gist = parentResponse.body()
                val content = gist?.files?.get("alerts.json")?.content
                if (content != null) {
                    val type = object : TypeToken<Map<String, String>>() {}.type
                    val index: Map<String, String> = gson.fromJson(content, type)
                    val newGistId = index[courseName]
                    if (newGistId != null && newGistId != courseGistId) {
                        courseGistId = newGistId
                        userPrefs.saveCourseGistId(courseGistId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AlertRepo", "Failed to fetch parent gist index", e)
        }

        if (courseGistId != null) {
            try {
                val response = api.getGist(GITHUB_PAT, courseGistId)
                if (response.isSuccessful) {
                    val gist = response.body() ?: return 0
                    val metaContent = gist.files["meta.json"]?.content ?: return 0
                    val alertsContent = gist.files["alerts.json"]?.content ?: "[]"
                    
                    val meta: MetaInfo = gson.fromJson(metaContent, metaInfoType)
                    val localVersion = userPrefs.getAlertVersionSnapshot()
                    
                    if (meta.v > localVersion || localVersion == 0) {
                        val type = object : TypeToken<List<AlertGist>>() {}.type
                        val remoteAlerts: List<AlertGist> = gson.fromJson(alertsContent, type)
                        val existingById = alertDao.getAllAlertsSnapshot().associateBy { it.id }
                        val existingIds = alertDao.getAllAlertIds().toSet()
                        val newCount = remoteAlerts.count { it.id !in existingIds }

                        val entities = remoteAlerts.map { 
                            val existing = existingById[it.id]
                            val shouldTrash = existing?.isTrashed == true || isExpired(it.endDate)
                            AlertEntity(
                                id = it.id,
                                class_code = courseName,
                                type = it.type,
                                subject = it.subject,
                                topic = it.topic,
                                description = it.description,
                                end_date = it.endDate,
                                posted_by = it.postedBy,
                                posted_at = it.postedAt,
                                seen = existing?.seen ?: 0,
                                isHidden = existing?.isHidden ?: false,
                                isTrashed = shouldTrash,
                                trashedAt = if (shouldTrash) (existing?.trashedAt ?: System.currentTimeMillis()) else null
                            )
                        }
                        alertDao.insertAlerts(entities)
                        moveExpiredAlertsToTrash()
                        userPrefs.saveAlertVersion(meta.v)
                        return newCount
                    }
                }
            } catch (e: Exception) {
                Log.e("AlertRepo", "Failed to fetch course alerts", e)
            }
        }
        return 0
    }

    private val metaInfoType = object : TypeToken<MetaInfo>() {}.type

    suspend fun postAlert(
        type: String,
        subject: String,
        topic: String,
        description: String,
        endDate: String
    ): Boolean {
        try {
            val courseName = userPrefs.getCourseNameSnapshot() ?: return false
            val rollNo = userPrefs.getRollNoSnapshot() ?: "unknown"
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
            
            val newAlert = AlertGist(
                id = UUID.randomUUID().toString(),
                type = type,
                subject = subject,
                topic = topic,
                description = description,
                endDate = endDate,
                postedBy = rollNo,
                postedAt = now
            )

            val parentResponse = api.getGist(GITHUB_PAT, PARENT_GIST_ID)
            if (!parentResponse.isSuccessful) return false
            
            val parentGist = parentResponse.body() ?: return false
            val parentContent = parentGist.files["alerts.json"]?.content ?: "{}"
            val indexType = object : TypeToken<MutableMap<String, String>>() {}.type
            val index: MutableMap<String, String> = gson.fromJson(parentContent, indexType)
            
            var courseGistId = index[courseName]
            
            if (courseGistId == null) {
                val meta = MetaInfo(v = 1, updated = now)
                val alerts = listOf(newAlert)
                val files = mapOf(
                    "meta.json" to GistFile(gson.toJson(meta)),
                    "alerts.json" to GistFile(gson.toJson(alerts))
                )
                val createResponse = api.createGist(GITHUB_PAT, GistRequest("Alerts for $courseName", files))
                if (createResponse.isSuccessful) {
                    courseGistId = createResponse.body()?.id
                    if (courseGistId != null) {
                        index[courseName] = courseGistId
                        api.updateGist(GITHUB_PAT, PARENT_GIST_ID, GistRequest(files = mapOf("alerts.json" to GistFile(gson.toJson(index)))))
                        userPrefs.saveCourseGistId(courseGistId)
                    } else return false
                } else return false
            } else {
                val courseResponse = api.getGist(GITHUB_PAT, courseGistId)
                if (!courseResponse.isSuccessful) return false
                val courseGist = courseResponse.body() ?: return false
                val metaContent = courseGist.files["meta.json"]?.content ?: "{}"
                val alertsContent = courseGist.files["alerts.json"]?.content ?: "[]"
                
                val meta: MetaInfo = gson.fromJson(metaContent, MetaInfo::class.java)
                val alertsType = object : TypeToken<MutableList<AlertGist>>() {}.type
                val alerts: MutableList<AlertGist> = gson.fromJson(alertsContent, alertsType)
                
                alerts.add(newAlert)
                val updatedMeta = MetaInfo(v = meta.v + 1, updated = now)
                val updateFiles = mapOf(
                    "meta.json" to GistFile(gson.toJson(updatedMeta)),
                    "alerts.json" to GistFile(gson.toJson(alerts))
                )
                val updateResponse = api.updateGist(GITHUB_PAT, courseGistId, GistRequest(files = updateFiles))
                if (!updateResponse.isSuccessful) return false
            }
            
            userPrefs.saveAlertVersion(0)
            fetchAlerts()
            return true
        } catch (e: Exception) {
            Log.e("AlertRepo", "Error posting alert", e)
            return false
        }
    }

    suspend fun deleteAlert(alert: AlertEntity): Boolean {
        val currentRollNo = userPrefs.getRollNoSnapshot() ?: "unknown"
        if (alert.posted_by == currentRollNo) {
            // Delete from Gist and local
            return deleteAlertFromGist(alert)
        } else {
            // Hide locally only
            alertDao.hideAlert(alert.id)
            return true
        }
    }

    suspend fun moveExpiredAlertsToTrash() {
        alertDao.moveExpiredAlertsToTrash(todayKey())
    }

    suspend fun moveAlertToTrash(alert: AlertEntity): Boolean {
        alertDao.moveToTrash(alert.id)
        return true
    }

    suspend fun restoreAlert(alert: AlertEntity): Boolean {
        alertDao.restoreFromTrash(alert.id)
        return true
    }

    private suspend fun deleteAlertFromGist(alert: AlertEntity): Boolean {
        try {
            val courseName = alert.class_code
            val courseGistId = userPrefs.getCourseGistIdSnapshot() ?: return false
            
            val response = api.getGist(GITHUB_PAT, courseGistId)
            if (!response.isSuccessful) return false
            
            val gist = response.body() ?: return false
            val metaContent = gist.files["meta.json"]?.content ?: return false
            val alertsContent = gist.files["alerts.json"]?.content ?: "[]"
            
            val meta: MetaInfo = gson.fromJson(metaContent, MetaInfo::class.java)
            val alertsType = object : TypeToken<MutableList<AlertGist>>() {}.type
            val alerts: MutableList<AlertGist> = gson.fromJson(alertsContent, alertsType)
            
            val removed = alerts.removeIf { it.id == alert.id }
            if (!removed) {
                // Not in gist anymore, just delete locally
                alertDao.deleteAlert(alert.id)
                return true
            }
            
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
            val updatedMeta = MetaInfo(v = meta.v + 1, updated = now)
            val updateFiles = mapOf(
                "meta.json" to GistFile(gson.toJson(updatedMeta)),
                "alerts.json" to GistFile(gson.toJson(alerts))
            )
            
            val updateResponse = api.updateGist(GITHUB_PAT, courseGistId, GistRequest(files = updateFiles))
            if (updateResponse.isSuccessful) {
                alertDao.deleteAlert(alert.id)
                userPrefs.saveAlertVersion(updatedMeta.v)
                return true
            }
            return false
        } catch (e: Exception) {
            Log.e("AlertRepo", "Error deleting alert from gist", e)
            return false
        }
    }

    private fun isExpired(endDate: String): Boolean {
        if (endDate.isBlank()) return false
        return try {
            endDate.trim() < todayKey()
        } catch (_: Exception) {
            false
        }
    }

    private fun todayKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
