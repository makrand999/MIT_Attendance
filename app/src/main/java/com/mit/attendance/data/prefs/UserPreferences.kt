package com.mit.attendance.data.prefs

import android.content.Context
import com.mit.attendance.auth.GitHubAccountStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.mit.attendance.model.UserCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

class UserPreferences(private val context: Context) {

    companion object {
        private val KEY_EMAIL                     = stringPreferencesKey("email")
        private val KEY_PASSWORD                  = stringPreferencesKey("password")
        private val KEY_SEM_ID                    = intPreferencesKey("sem_id")
        private val KEY_IS_LOGGED_IN              = booleanPreferencesKey("is_logged_in")
        private val KEY_NOTIFICATIONS             = booleanPreferencesKey("notifications_enabled")
        private val KEY_GENDER                    = stringPreferencesKey("gender")
        private val KEY_EXAM_CELL_USERID          = stringPreferencesKey("exam_cell_userid")
        private val KEY_HAS_REQUESTED_NOTIF_PERM  = booleanPreferencesKey("has_requested_notif_perm")
        private val KEY_DIVISION_ID               = intPreferencesKey("division_id")
        private val KEY_PRACTICAL_TOKEN           = stringPreferencesKey("practical_token")
        private val KEY_PRACTICAL_PASSWORD        = stringPreferencesKey("practical_password")
        private val KEY_TIMETABLE_IMAGE_URI       = stringPreferencesKey("timetable_image_uri")
        private val KEY_TIMETABLE_ROTATION        = intPreferencesKey("timetable_rotation")
        private val KEY_UPDATE_LOCKED             = booleanPreferencesKey("update_locked")
        private val KEY_BG_DETAIL_URI             = stringPreferencesKey("bg_detail_uri")
        private val KEY_BG_NAV_URI                = stringPreferencesKey("bg_nav_uri")
        
        // Profile keys
        private val KEY_ROLL_NO                   = stringPreferencesKey("roll_no")
        private val KEY_MOBILE_NO                 = stringPreferencesKey("mobile_no")
        private val KEY_FIRST_NAME                = stringPreferencesKey("first_name")
        private val KEY_COURSE_NAME               = stringPreferencesKey("course_name")
        private val KEY_DASHBOARD_FETCHED         = booleanPreferencesKey("dashboard_fetched")
        
        // Exam Cell Payload keys
        private val KEY_EXAM_CELL_VIEWSTATE       = stringPreferencesKey("exam_cell_viewstate")
        private val KEY_EXAM_CELL_GENERATOR       = stringPreferencesKey("exam_cell_generator")
        private val KEY_EXAM_CELL_VALIDATION      = stringPreferencesKey("exam_cell_validation")
        private val KEY_EXAM_CELL_LOGIN_URL       = stringPreferencesKey("exam_cell_login_url")
        private val KEY_EXAM_CELL_FULL_BODY       = stringPreferencesKey("exam_cell_full_body")

        // Alerts keys
        private val KEY_COURSE_GIST_ID            = stringPreferencesKey("course_gist_id")
        private val KEY_ALERT_VERSION             = intPreferencesKey("alert_version")
        private val KEY_PRACTICAL_THEORY_PROMPT   = stringPreferencesKey("practical_theory_prompt")
        private val KEY_PRACTICAL_CONCL_PROMPT    = stringPreferencesKey("practical_conclusion_prompt")
    }

    val isLoggedIn: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_IS_LOGGED_IN] ?: false }

    val notificationsEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_NOTIFICATIONS] ?: true }

    val gender: Flow<String?> = context.dataStore.data
        .map { it[KEY_GENDER] }

    val examCellUserId: Flow<String?> = context.dataStore.data
        .map { it[KEY_EXAM_CELL_USERID] }

    val hasRequestedNotifPermission: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_HAS_REQUESTED_NOTIF_PERM] ?: false }

    val timetableImageUri: Flow<String?> = context.dataStore.data
        .map { it[KEY_TIMETABLE_IMAGE_URI] }

    val timetableRotation: Flow<Int> = context.dataStore.data
        .map { it[KEY_TIMETABLE_ROTATION] ?: 0 }

    val isUpdateLocked: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_UPDATE_LOCKED] ?: false }

    val bgDetailUri: Flow<String?> = context.dataStore.data
        .map { it[KEY_BG_DETAIL_URI] }

    val bgNavUri: Flow<String?> = context.dataStore.data
        .map { it[KEY_BG_NAV_URI] }


    val rollNo: Flow<String?> = context.dataStore.data.map { it[KEY_ROLL_NO] }
    val mobileNo: Flow<String?> = context.dataStore.data.map { it[KEY_MOBILE_NO] }
    val firstName: Flow<String?> = context.dataStore.data.map { it[KEY_FIRST_NAME] }
    val courseName: Flow<String?> = context.dataStore.data.map { it[KEY_COURSE_NAME] }
    val courseGistId: Flow<String?> = context.dataStore.data.map { it[KEY_COURSE_GIST_ID] }
    val alertVersion: Flow<Int> = context.dataStore.data.map { it[KEY_ALERT_VERSION] ?: 0 }

    suspend fun saveCredentials(email: String, password: String, semId: Int) {
        val previous = getCredentialsSnapshot()
        val changed = previous.email != email || previous.password != password
        if (changed) {
            com.mit.attendance.campus.CampusSessionStore(context).signOut()
            com.mit.attendance.data.db.AppDatabase.getDatabase(context).campusDao().clearActive()
        }
        context.dataStore.edit {
            it[KEY_EMAIL]        = email
            it[KEY_PASSWORD]     = password
            it[KEY_SEM_ID]       = semId
            it[KEY_IS_LOGGED_IN] = true
        }
        if (changed) com.mit.attendance.campus.CampusSessionStore(context).signOut()
    }

    suspend fun saveGender(gender: String) {
        context.dataStore.edit {
            it[KEY_GENDER] = gender
        }
    }

    suspend fun saveProfile(rollNo: String?, mobileNo: String?, firstName: String?, courseName: String?) {
        context.dataStore.edit {
            it[KEY_ROLL_NO] = rollNo ?: ""
            it[KEY_MOBILE_NO] = mobileNo ?: ""
            it[KEY_FIRST_NAME] = firstName ?: ""
            it[KEY_COURSE_NAME] = courseName ?: ""
            it[KEY_DASHBOARD_FETCHED] = true
        }
    }

    suspend fun isDashboardFetched(): Boolean =
        context.dataStore.data.first()[KEY_DASHBOARD_FETCHED] ?: false

    suspend fun saveExamCellUserId(userId: String) {
        context.dataStore.edit {
            it[KEY_EXAM_CELL_USERID] = userId
        }
    }
    
    suspend fun saveExamCellPayload(viewState: String, generator: String, validation: String, url: String) {
        context.dataStore.edit {
            it[KEY_EXAM_CELL_VIEWSTATE] = viewState
            it[KEY_EXAM_CELL_GENERATOR] = generator
            it[KEY_EXAM_CELL_VALIDATION] = validation
            it[KEY_EXAM_CELL_LOGIN_URL] = url
        }
    }

    suspend fun saveExamCellFullBody(body: String, url: String) {
        context.dataStore.edit {
            it[KEY_EXAM_CELL_FULL_BODY] = body
            it[KEY_EXAM_CELL_LOGIN_URL] = url
        }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NOTIFICATIONS] = enabled }
        com.mit.attendance.campus.CampusSessionStore(context).read()?.let { com.mit.attendance.campus.CampusPush.bind(context,it) }
    }

    suspend fun setHasRequestedNotifPermission(requested: Boolean) {
        context.dataStore.edit { it[KEY_HAS_REQUESTED_NOTIF_PERM] = requested }
    }

    suspend fun saveDivisionId(divisionId: Int) {
        context.dataStore.edit { it[KEY_DIVISION_ID] = divisionId }
    }

    suspend fun savePracticalToken(token: String) {
        context.dataStore.edit { it[KEY_PRACTICAL_TOKEN] = token }
    }

    suspend fun savePracticalPassword(password: String?) {
        context.dataStore.edit {
            if (password.isNullOrBlank()) it.remove(KEY_PRACTICAL_PASSWORD)
            else it[KEY_PRACTICAL_PASSWORD] = password
        }
    }

    suspend fun saveTimetableImageUri(uri: String) {
        context.dataStore.edit { it[KEY_TIMETABLE_IMAGE_URI] = uri }
    }

    suspend fun saveTimetableRotation(rotation: Int) {
        context.dataStore.edit { it[KEY_TIMETABLE_ROTATION] = rotation }
    }

    suspend fun setUpdateLocked(locked: Boolean) {
        context.dataStore.edit { it[KEY_UPDATE_LOCKED] = locked }
    }

    suspend fun saveBgDetailUri(uri: String?) {
        context.dataStore.edit { 
            if (uri == null) it.remove(KEY_BG_DETAIL_URI)
            else it[KEY_BG_DETAIL_URI] = uri 
        }
    }

    suspend fun saveBgNavUri(uri: String?) {
        context.dataStore.edit { 
            if (uri == null) it.remove(KEY_BG_NAV_URI)
            else it[KEY_BG_NAV_URI] = uri 
        }
    }

    suspend fun saveCourseGistId(gistId: String) {
        context.dataStore.edit { it[KEY_COURSE_GIST_ID] = gistId }
    }

    suspend fun saveAlertVersion(version: Int) {
        context.dataStore.edit { it[KEY_ALERT_VERSION] = version }
    }

    suspend fun savePracticalTheoryPrompt(prompt: String?) {
        context.dataStore.edit {
            if (prompt.isNullOrBlank()) it.remove(KEY_PRACTICAL_THEORY_PROMPT)
            else it[KEY_PRACTICAL_THEORY_PROMPT] = prompt
        }
    }

    suspend fun savePracticalConclusionPrompt(prompt: String?) {
        context.dataStore.edit {
            if (prompt.isNullOrBlank()) it.remove(KEY_PRACTICAL_CONCL_PROMPT)
            else it[KEY_PRACTICAL_CONCL_PROMPT] = prompt
        }
    }

    /**
     * Clears only account-specific ERP data, preserving user settings 
     * like background images, notification settings, and timetable.
     */
    suspend fun clearAccountData() {
        com.mit.attendance.campus.CampusSessionStore(context).signOut()
        com.mit.attendance.data.db.AppDatabase.getDatabase(context).campusDao().clearActive()
        GitHubAccountStore(context).disconnect()
        context.dataStore.edit {
            it.remove(KEY_EMAIL)
            it.remove(KEY_PASSWORD)
            it.remove(KEY_SEM_ID)
            it[KEY_IS_LOGGED_IN] = false
            it.remove(KEY_GENDER)
            it.remove(KEY_EXAM_CELL_USERID)
            it.remove(KEY_DIVISION_ID)
            it.remove(KEY_PRACTICAL_TOKEN)
            it.remove(KEY_PRACTICAL_PASSWORD)
            it.remove(KEY_ROLL_NO)
            it.remove(KEY_MOBILE_NO)
            it.remove(KEY_FIRST_NAME)
            it.remove(KEY_COURSE_NAME)
            it.remove(KEY_DASHBOARD_FETCHED)
            it.remove(KEY_EXAM_CELL_VIEWSTATE)
            it.remove(KEY_EXAM_CELL_GENERATOR)
            it.remove(KEY_EXAM_CELL_VALIDATION)
            it.remove(KEY_EXAM_CELL_LOGIN_URL)
            it.remove(KEY_EXAM_CELL_FULL_BODY)
            it.remove(KEY_COURSE_GIST_ID)
            it.remove(KEY_ALERT_VERSION)
        }
    }

    suspend fun clearAll() {
        com.mit.attendance.campus.CampusSessionStore(context).signOut()
        com.mit.attendance.data.db.AppDatabase.getDatabase(context).campusDao().clearActive()
        GitHubAccountStore(context).disconnect()
        context.dataStore.edit { it.clear() }
    }

    suspend fun getEmail(): String? {
        return context.dataStore.data.first()[KEY_EMAIL]
    }

    suspend fun getGender(): String? {
        return context.dataStore.data.first()[KEY_GENDER]
    }

    suspend fun getExamCellUserId(): String? {
        return context.dataStore.data.first()[KEY_EXAM_CELL_USERID]
    }
    
    suspend fun getExamCellPayload(): Map<String, String>? {
        val prefs = context.dataStore.data.first()
        val viewState = prefs[KEY_EXAM_CELL_VIEWSTATE] ?: return null
        val generator = prefs[KEY_EXAM_CELL_GENERATOR] ?: ""
        val validation = prefs[KEY_EXAM_CELL_VALIDATION] ?: ""
        val url = prefs[KEY_EXAM_CELL_LOGIN_URL] ?: ""
        val fullBody = prefs[KEY_EXAM_CELL_FULL_BODY] ?: ""
        return mapOf(
            "viewState" to viewState,
            "generator" to generator,
            "validation" to validation,
            "url" to url,
            "fullBody" to fullBody
        )
    }

    suspend fun getDivisionId(): Int? {
        return context.dataStore.data.first()[KEY_DIVISION_ID]
    }

    suspend fun getPracticalToken(): String? {
        return context.dataStore.data.first()[KEY_PRACTICAL_TOKEN]
    }

    suspend fun getPracticalPassword(): String? {
        return context.dataStore.data.first()[KEY_PRACTICAL_PASSWORD]
    }

    suspend fun getPracticalTheoryPrompt(): String? {
        return context.dataStore.data.first()[KEY_PRACTICAL_THEORY_PROMPT]
    }

    suspend fun getPracticalConclusionPrompt(): String? {
        return context.dataStore.data.first()[KEY_PRACTICAL_CONCL_PROMPT]
    }

    suspend fun getTimetableImageUri(): String? {
        return context.dataStore.data.first()[KEY_TIMETABLE_IMAGE_URI]
    }

    suspend fun getTimetableRotation(): Int {
        return context.dataStore.data.first()[KEY_TIMETABLE_ROTATION] ?: 0
    }

    suspend fun getCourseNameSnapshot(): String? {
        return context.dataStore.data.first()[KEY_COURSE_NAME]
    }

    suspend fun getFirstNameSnapshot(): String? {
        return context.dataStore.data.first()[KEY_FIRST_NAME]
    }

    suspend fun getRollNoSnapshot(): String? {
        return context.dataStore.data.first()[KEY_ROLL_NO]
    }

    suspend fun getCourseGistIdSnapshot(): String? {
        return context.dataStore.data.first()[KEY_COURSE_GIST_ID]
    }

    suspend fun getAlertVersionSnapshot(): Int {
        return context.dataStore.data.first()[KEY_ALERT_VERSION] ?: 0
    }

    /**
     * Returns a snapshot of credentials as a [UserCredentials] for one-time use
     * (e.g. in suspend functions that need email/password/semId together).
     */
    suspend fun getCredentialsSnapshot(): UserCredentials {
        val prefs = context.dataStore.data.first()
        return UserCredentials(
            email    = prefs[KEY_EMAIL]    ?: "",
            password = prefs[KEY_PASSWORD] ?: "",
            semId    = prefs[KEY_SEM_ID]   ?: 1
        )
    }

    suspend fun isLoggedInSnapshot(): Boolean =
        context.dataStore.data.first()[KEY_IS_LOGGED_IN] ?: false

    suspend fun areNotificationsEnabledSnapshot(): Boolean =
        context.dataStore.data.first()[KEY_NOTIFICATIONS] ?: true

    suspend fun hasRequestedNotifPermissionSnapshot(): Boolean =
        context.dataStore.data.first()[KEY_HAS_REQUESTED_NOTIF_PERM] ?: false

    suspend fun isUpdateLockedSnapshot(): Boolean =
        context.dataStore.data.first()[KEY_UPDATE_LOCKED] ?: false


}
