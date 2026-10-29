package com.mit.attendance.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GitHubAccount(val username: String, val token: String)
data class NotesConnection(val subject: String, val folder: String, val source: String, val privateRepo: String)

/** Credentials and bindings are account-scoped and excluded from Android backups. */
class GitHubAccountStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "github").apply { mkdirs() }

    companion object {
        private val lock = Any()
        private const val KEY_ALIAS = "mit_github_pat_v1"
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun account(): GitHubAccount? = synchronized(lock) {
        val file = AtomicFile(File(directory, "account.bin"))
        if (!file.baseFile.exists()) return@synchronized null
        try {
            val bytes = file.readFully()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            GitHubAccount(json.getString("username"), json.getString("token"))
        } catch (_: Exception) {
            // Key invalidation or corrupt storage requires reconnecting, never a shared-token fallback.
            null
        }
    }

    fun requireAccount(): GitHubAccount = account() ?: error("Connect your GitHub account from Notes first.")

    fun save(username: String, token: String) = synchronized(lock) {
        require(username.matches(Regex("[A-Za-z0-9-]+")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plaintext = JSONObject().put("username", username).put("token", token).toString().toByteArray()
        write(File(directory, "account.bin"), cipher.iv + cipher.doFinal(plaintext))
    }

    fun disconnect() = synchronized(lock) {
        AtomicFile(File(directory, "account.bin")).delete()
    }

    fun connections(username: String): List<NotesConnection> = synchronized(lock) {
        val file = bindingFile(username)
        if (!file.exists()) return@synchronized emptyList()
        val entries = JSONArray(String(AtomicFile(file).readFully(), Charsets.UTF_8))
        (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            NotesConnection(entry.getString("subject"), entry.getString("folder"),
                entry.getString("source"), entry.getString("privateRepo"))
        }
    }

    fun connect(username: String, connection: NotesConnection) = synchronized(lock) {
        val entries = connections(username).filterNot { it.subject == connection.subject } + connection
        val json = JSONArray()
        entries.forEach { json.put(JSONObject().put("subject", it.subject).put("folder", it.folder)
            .put("source", it.source).put("privateRepo", it.privateRepo)) }
        write(bindingFile(username), json.toString().toByteArray())
    }

    private fun bindingFile(username: String): File {
        require(username.matches(Regex("[A-Za-z0-9-]+")))
        return File(directory, "notes-${username.lowercase(java.util.Locale.ROOT)}.json")
    }

    private fun write(destination: File, bytes: ByteArray) {
        val atomic = AtomicFile(destination)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
