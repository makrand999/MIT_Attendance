package com.mit.attendance.campus

import android.content.Context
import com.google.gson.JsonObject
import com.mit.attendance.data.db.AppDatabase

/** Android-only cache and adapters for the independently deployed Akatsuki API. */
class AkatsukiRepository(context: Context) {
    val store = CampusSessionStore(context)
    val db = AppDatabase.getDatabase(context)
    val dao = db.campusDao()
    private val app = context.applicationContext

    fun assertCurrent(session: CampusSession) {
        val current = store.read()
        check(current != null && current.generation == session.generation &&
            current.profile.id == session.profile.id) { "Akatsuki account changed. Reconnect to continue." }
    }

    /** The deployed API exposes applications through authorized paginated lists. */
    suspend fun application(session: CampusSession, id: String, jobId: String): JsonObject {
        require(id.matches(Regex("[A-Za-z0-9-]{1,100}")))
        require(jobId.isEmpty() || jobId.matches(Regex("[A-Za-z0-9-]{1,100}")))
        val ownerView = jobId.isNotEmpty()
        val path = if (ownerView) "jobs/$jobId/applications" else "applications/my"
        var offset = 0
        while (offset <= 100000) {
            assertCurrent(session)
            val rows = CampusTransport.authenticated(app, session)
                .request("$path?limit=100&offset=$offset").asJsonArray
            assertCurrent(session)
            rows.firstOrNull { it.asJsonObject.string("id") == id }?.let { item ->
                val detail = item.asJsonObject.deepCopy()
                detail.addProperty("can_manage", ownerView)
                if (!ownerView) detail.addProperty("applicant_name", session.profile.name)
                if (ownerView) {
                    val job = CampusTransport.authenticated(app, session).request("jobs/$jobId").asJsonObject
                    assertCurrent(session)
                    detail.addProperty("job_title", job.string("title"))
                    detail.addProperty("job_status", job.string("status"))
                }
                return detail
            }
            if (rows.size() < 100) break
            offset += rows.size()
        }
        throw CampusFailure(404, "application_not_found", "This application is not available to your account.")
    }
}
