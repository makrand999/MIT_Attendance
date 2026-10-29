package com.mit.attendance.campus

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CampusFailure(val status: Int, val code: String, message: String) : IOException(message)
/** Bound to one verified account/session. Never resolves redirects or arbitrary destinations. */
class CampusTransport(private val session: CampusSession, private val client: OkHttpClient = CampusHttp.client(),
    private val reconnect: (suspend (CampusSession) -> CampusSession)? = null,
    private val currentSession: (() -> CampusSession?)? = null) {
    companion object {
        fun authenticated(context: Context, session: CampusSession): CampusTransport {
            val app = context.applicationContext
            return CampusTransport(session, reconnect = { CampusRepository(app).reconnect(it) },
                currentSession = { CampusSessionStore(app).read() })
        }
    }
    suspend fun request(path: String, method: String = "GET", body: String? = null): JsonElement {
        val bytes = execute(path, method, body, 2 * 1024 * 1024)
        return JsonParser.parseString(bytes.toString(Charsets.UTF_8))
    }
    suspend fun image(id: String): ByteArray {
        require(id.matches(Regex("[A-Za-z0-9-]{1,100}")))
        return execute("images/$id", "GET", null, 5 * 1024 * 1024)
    }
    private suspend fun execute(path: String, method: String, body: String?, max: Int): ByteArray {
        var active = session
        currentSession?.let { read ->
            val current = read()
            check(current != null && current.generation == session.generation &&
                current.profile.id == session.profile.id && current.selectedClass == session.selectedClass) {
                "Campus account or class changed."
            }
            active = current
        }
        var reconnected = false
        if (!active.isValid() && reconnect != null) {
            active = reconnect.invoke(active)
            reconnected = true
        }
        return try { executeOnce(active, path, method, body, max) }
        catch (e: CampusFailure) {
            if (e.status != 401 || reconnect == null || reconnected) throw e
            executeOnce(reconnect.invoke(active), path, method, body, max)
        }
    }
    private suspend fun executeOnce(active: CampusSession, path: String, method: String, body: String?, max: Int): ByteArray {
        check(active.generation == session.generation && active.profile.id == session.profile.id &&
            active.selectedClass == session.selectedClass) { "Campus account or class changed." }
        currentSession?.let { read ->
            val current = read()
            check(current != null && current.generation == active.generation &&
                current.profile.id == active.profile.id && current.selectedClass == active.selectedClass) {
                "Campus account or class changed."
            }
        }
        require(!path.startsWith('/') && !path.contains("..") && !path.contains("://"))
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("mitasia.in").addPathSegments("akatsuki/api/").build().resolve(path) ?: error("Invalid campus route")
        require(url.toString().startsWith(CampusHttp.API_BASE))
        require((body?.toByteArray()?.size ?: 0) <= 64 * 1024)
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${active.token}")
            .method(method, if (method == "GET") null else (body ?: "{}").toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { r ->
                            val data = r.body?.source()?.let { source -> source.request(max.toLong() + 1); source.buffer.readByteArray() } ?: byteArrayOf()
                            require(data.size <= max) { "Campus response is too large." }
                            if (!r.isSuccessful) {
                                val detail = runCatching { JsonParser.parseString(data.toString(Charsets.UTF_8)).asJsonObject.get("error") }.getOrNull()
                                val message = if (detail?.isJsonObject == true) detail.asJsonObject.get("message")?.asString else detail?.takeIf { it.isJsonPrimitive }?.asString
                                val code = if (detail?.isJsonObject == true) detail.asJsonObject.get("code")?.asString else "request_failed"
                                throw CampusFailure(r.code, code ?: "request_failed", message?.take(400) ?: "Campus request failed (${r.code}).")
                            }
                            if (continuation.isActive) continuation.resume(data)
                        }
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }
}
