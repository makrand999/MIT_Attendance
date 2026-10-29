package com.mit.attendance.campus

import android.app.NotificationManager
import kotlinx.coroutines.launch
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.work.WorkManager
import com.google.gson.Gson
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CampusProfile(val id: String, val name: String, val roll_number: String,
    val department: String, val erp_verified: Boolean)

/** No credentials in toString, backups, preferences, GitHub storage, or logs. */
class CampusSession(val token: String, val expiresAt: Long, val profile: CampusProfile,
    val generation: String = UUID.randomUUID().toString(), val selectedClass: String? = null, val erpUsername: String = "") {
    override fun toString() = "CampusSession(redacted)"
    fun isValid() = token.isNotEmpty() && expiresAt > System.currentTimeMillis() / 1000
}

interface CampusSessions {
    fun read(): CampusSession?
    fun epoch(): String
    fun commitLogin(expectedEpoch: String, expectedGeneration: String?, session: CampusSession): Boolean
    fun replace(expectedGeneration: String?, session: CampusSession): Boolean
    fun invalidate(expectedGeneration: String)
    fun signOut()
    fun selectClass(expectedGeneration: String, classId: String?) { error("Class selection is unavailable") }
}

class CampusSessionStore(context: Context) : CampusSessions {
    private val context = context.applicationContext
    private val file = AtomicFile(File(this.context.noBackupFilesDir, "campus/session.bin"))
    private val gson = Gson()
    companion object {
        private val lock = Any()
        private const val ALIAS = "mit_campus_session_v1"
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): CampusSession? = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized null
        try {
            val bytes = file.readFully()
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            gson.fromJson(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8), CampusSession::class.java)
        } catch (_: Exception) { file.delete(); null }
    }
    private fun write(session: CampusSession) {
        file.baseFile.parentFile?.mkdirs()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(gson.toJson(session).toByteArray(Charsets.UTF_8))
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
    /** Compare-and-set prevents an old request from writing after logout/account switch. */
    override fun replace(expectedGeneration: String?, session: CampusSession): Boolean = synchronized(lock) {
        val old = read()
        if (old?.generation != expectedGeneration) return@synchronized false
        if (old != null && old.generation == session.generation && old.selectedClass != session.selectedClass) return@synchronized false
        if (old != null && old.generation == session.generation && old.expiresAt > session.expiresAt) return@synchronized false
        if (old?.profile?.id != session.profile.id || old.generation != session.generation) cancel(old)
        write(session)
        true
    }
    override fun invalidate(expectedGeneration: String): Unit = synchronized(lock) {
        read()?.takeIf { it.generation == expectedGeneration }?.let {
            cancel(it)
            write(CampusSession("", 0, it.profile, selectedClass = it.selectedClass, erpUsername = it.erpUsername))
        }
        Unit
    }
    fun invalidateToken(expected: CampusSession): Unit = synchronized(lock) {
        if (read()?.let { it.generation == expected.generation && it.token == expected.token } == true)
            invalidate(expected.generation)
    }
    override fun selectClass(expectedGeneration: String, classId: String?): Unit = synchronized(lock) {
        read()?.takeIf { it.generation == expectedGeneration }?.let {
            if (it.selectedClass != classId) cancel(it)
            write(CampusSession(it.token, it.expiresAt, it.profile, it.generation, classId, it.erpUsername))
        }
        Unit
    }
    override fun signOut() = synchronized(lock) {
        val old = read()
        old?.let { CampusPush.unbind(context,it) }
        // An epoch marker prevents an in-flight first login from recreating a signed-out session.
        if (old != null) cancel(old)
        file.delete()
        epochs[context.packageName] = UUID.randomUUID().toString()
    }
    override fun epoch(): String = synchronized(lock) { epochs.getOrPut(context.packageName) { UUID.randomUUID().toString() } }
    override fun commitLogin(expectedEpoch: String, expectedGeneration: String?, session: CampusSession): Boolean = synchronized(lock) {
        if (epoch() != expectedEpoch) return@synchronized false
        val saved = replace(expectedGeneration, session)
        if (saved) epochs[context.packageName] = UUID.randomUUID().toString()
        saved
    }
    private fun cancel(session: CampusSession?) {
        session ?: return
        CampusConnections.close(session.generation)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val dao=com.mit.attendance.data.db.AppDatabase.getDatabase(context).campusDao()
            dao.clearActiveGeneration(session.generation, session.selectedClass)
            session.selectedClass?.let {classId ->
                for(alert in dao.alertSnapshot(session.profile.id,classId)) {
                    val current=read()
                    if(current?.generation!=session.generation||current.selectedClass!=classId)
                        CampusReminders.cancel(context,session.profile.id,classId,alert.id,session.generation)
                }
            }
        }
        WorkManager.getInstance(context).cancelAllWorkByTag("campus:${session.profile.id}:${session.generation}")
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag?.startsWith("campus:${session.profile.id}:") == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }
}
private val epochs = mutableMapOf<String, String>() // Accessed only under the shared store lock.
