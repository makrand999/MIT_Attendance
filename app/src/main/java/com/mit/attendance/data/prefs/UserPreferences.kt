package com.mit.attendance.data.prefs

import android.content.Context
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

    suspend fun saveCredentials(email: String, password: String, semId: Int) {
        context.dataStore.edit {
            it[KEY_EMAIL]        = email
            it[KEY_PASSWORD]     = password
            it[KEY_SEM_ID]       = semId
            it[KEY_IS_LOGGED_IN] = true
        }
    }

    suspend fun saveGender(gender: String) {
        context.dataStore.edit {
            it[KEY_GENDER] = gender
        }
    }

    suspend fun saveExamCellUserId(userId: String) {
        context.dataStore.edit {
            it[KEY_EXAM_CELL_USERID] = userId
        }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NOTIFICATIONS] = enabled }
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

    suspend fun clearAll() {
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

    suspend fun getDivisionId(): Int? {
        return context.dataStore.data.first()[KEY_DIVISION_ID]
    }

    suspend fun getPracticalToken(): String? {
        return context.dataStore.data.first()[KEY_PRACTICAL_TOKEN]
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
}
