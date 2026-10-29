package com.mit.attendance.campus

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mit.attendance.data.db.AppDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
fun jsonObject(vararg fields: Pair<String, Any?>): JsonObject = com.google.gson.Gson().toJsonTree(fields.toMap()).asJsonObject

class CampusAlertsRepository(private val context: Context, val store: CampusSessions = CampusSessionStore(context),
    val db: AppDatabase = AppDatabase.getDatabase(context),
    private val transport: (CampusSession) -> CampusTransport = { CampusTransport.authenticated(context, it) }) {
    val dao = db.campusDao()
    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
    fun assertCurrent(session: CampusSession, classId: String? = null) {
        val current = store.read()
        check(current?.generation == session.generation && current.profile.id == session.profile.id &&
            (classId == null || current.selectedClass == classId)) { "Campus account or class changed." }
    }
    suspend fun directory(session: CampusSession, kind: String, parent: String = ""): List<JsonObject> =
        dao.directory(session.profile.id, kind, parent).map { JsonParser.parseString(it.json).asJsonObject }
    suspend fun refreshDirectory(session: CampusSession): List<JsonObject> {
        assertCurrent(session)
        val capability=transport(session).request("health").asJsonObject.getAsJsonObject("class_alerts")
        if(capability?.get("api_version")?.asInt!=1)throw CampusFailure(503,"server_upgrade_required","Server alerts are not available yet. You can use the read-only legacy view.")
        val classes = pages(session, "v1/classes")
        db.withTransaction {
            assertCurrent(session)
            dao.clearDirectory(session.profile.id, "classes")
            dao.saveDirectory(classes.map { CampusDirectory(session.profile.id, "classes", "", it.string("id")!!, it.toString()) })
            assertCurrent(session)
        }
        return classes
    }
    suspend fun subjects(session: CampusSession, classId: String): List<JsonObject> {
        val subjects = pages(session, "v1/classes/$classId/subjects")
        db.withTransaction {
            assertCurrent(session, classId)
            dao.clearDirectory(session.profile.id, "subjects", classId)
            dao.saveDirectory(subjects.map { CampusDirectory(session.profile.id, "subjects", classId, it.string("id")!!, it.toString()) })
            assertCurrent(session, classId)
        }
        return subjects
    }
    private suspend fun pages(session: CampusSession, path: String): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var offset = 0
        do {
            assertCurrent(session)
            val page = transport(session).request("$path?limit=100&offset=$offset").asJsonObject.getAsJsonArray("items")
            result.addAll(page.map { it.asJsonObject }); offset += page.size()
            if (page.size() < 100) break
            check(offset <= 100000) { "Too many campus records." }
        } while (true)
        return result
    }
    suspend fun select(session: CampusSession, classId: String) {
        require(directory(session, "classes").any { it.string("id") == classId })
        assertCurrent(session)
        store.selectClass(session.generation, classId)
        db.withTransaction { assertCurrent(session, classId); dao.active(CampusActive(account = session.profile.id, classId = classId, generation = session.generation)); assertCurrent(session, classId) }
    }
    suspend fun queue(session: CampusSession, method: String, path: String, resource: String, body: JsonObject) {
        val classId = session.selectedClass ?: error("Select a class first.")
        val key = UUID.randomUUID().toString()
        if (method == "POST") body.addProperty("idempotency_key", key)
        db.withTransaction {
            assertCurrent(session, classId)
            dao.enqueue(CampusOutgoing(key, session.profile.id, classId, resource, method, path, body.toString()))
            assertCurrent(session, classId)
        }
        CampusSyncWorker.enqueue(context, session)
    }
    suspend fun personal(session: CampusSession, id: String, state: CampusPersonalState) {
        val classId = session.selectedClass ?: error("Select a class first.")
        val key = UUID.randomUUID().toString()
        val path = "v1/classes/$classId/alerts/$id/state"
        db.withTransaction {
            assertCurrent(session, classId)
            dao.saveState(state.copy(pendingKey = key))
            dao.enqueue(CampusOutgoing(key, session.profile.id, classId, id, "PUT", path,
                jsonObject("seen" to state.seen, "hidden" to state.hidden, "trashed" to state.trashed).toString()))
            assertCurrent(session, classId)
        }
        CampusSyncWorker.enqueue(context, session)
    }
    suspend fun retry(session: CampusSession, item: CampusOutgoing, cancel: Boolean = false) {
        db.withTransaction {
            assertCurrent(session, item.classId)
            check(item.account == session.profile.id)
            if (cancel) {
                dao.removeOutgoing(item.account, item.key)
                dao.state(item.account, item.classId, item.resourceId)?.takeIf { it.pendingKey == item.key }?.let {
                    val confirmed=it.confirmedJson?.let {json->JsonParser.parseString(json).asJsonObject}
                    dao.saveState(it.copy(seen=confirmed?.get("seen")?.asBoolean?:false,hidden=confirmed?.get("hidden")?.asBoolean?:false,trashed=confirmed?.get("trashed")?.asBoolean?:false,pendingKey=null))
                }
            } else {
                check(item.status != "CONFLICT") { "Reload the current alert and edit again to resolve this conflict." }
                dao.saveOutgoing(item.copy(status = "PENDING", error = ""))
            }
            assertCurrent(session, item.classId)
        }
        CampusSyncWorker.enqueue(context, session)
    }
    suspend fun sync(session: CampusSession): List<CachedCampusAlert> {
        val classId = session.selectedClass ?: return emptyList()
        return locks.getOrPut("${session.profile.id}/$classId") { Mutex() }.withLock {
            assertCurrent(session, classId)
            flush(session, classId)
            val notify = mutableListOf<CachedCampusAlert>()
            var announce = dao.cursor(session.profile.id, classId)?.initialized == true
            var reset = false
            do {
                assertCurrent(session, classId)
                val previous = dao.cursor(session.profile.id, classId)
                val cursor = previous?.cursor?.let { "&cursor=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: ""
                val page = try { transport(session).request("v1/classes/$classId/alerts/sync?limit=100$cursor").asJsonObject }
                catch (e: CampusFailure) {
                    if (e.status != 410 || reset) throw e
                    db.withTransaction { assertCurrent(session, classId); dao.resetAlerts(session.profile.id, classId); dao.resetCursor(session.profile.id, classId); assertCurrent(session, classId) }
                    announce = false; notify.clear(); reset = true; continue
                }
                val changes = (page.getAsJsonArray("changes").toList() + page.getAsJsonArray("user_state_changes").toList()).sortedBy { it.asJsonObject.get("sequence").asLong }
                val more = page.get("has_more").asBoolean
                db.withTransaction {
                    assertCurrent(session, classId)
                    for (element in changes) {
                        val change = element.asJsonObject
                        when (change.string("operation")) {
                            "upsert" -> {
                                val a = decode(session, classId, change.getAsJsonObject("alert"))
                                val old = dao.alert(a.account, classId, a.id)
                                if (old == null || a.version > old.version) {
                                    dao.saveAlert(a)
                                    if (old != null && old.version != a.version) CampusReminders.cancel(context, a.account, classId, a.id)
                                    if (announce && a.status == "PUBLISHED" && dao.notified(CampusNotification(a.account, classId, a.id, a.version)) != -1L) notify += a
                                }
                            }
                            "delete" -> {
                                val id = change.string("alert_id")!!
                                dao.alert(session.profile.id, classId, id)?.let { if (change.get("version").asLong >= it.version) dao.saveAlert(it.copy(status = "DELETED", version = change.get("version").asLong,topic="",description="",json="{}")) }
                                CampusReminders.cancel(context, session.profile.id, classId, id)
                            }
                            "state" -> {
                                val id = change.string("alert_id")!!
                                val old = dao.state(session.profile.id, classId, id)
                                val st = change.getAsJsonObject("state")
                                if (old?.pendingKey == null) {
                                    dao.saveState(CampusPersonalState(session.profile.id, classId, id, st.get("seen").asBoolean, st.get("hidden").asBoolean, st.get("trashed").asBoolean,confirmedJson=st.toString()))
                                } else dao.saveState(old.copy(confirmedJson=st.toString()))
                            }
                        }
                    }
                    dao.saveCursor(CampusCursor(session.profile.id, classId, page.string("next_cursor")!!, announce || !more))
                    assertCurrent(session, classId)
                }
                if (!more) break
            } while (true)
            restoreLegacyFlags(session,classId)
            assertCurrent(session, classId)
            notify.filter { dao.alert(it.account, classId, it.id)?.let { a -> a.status == "PUBLISHED" && a.version == it.version } == true }
        }
    }
    private suspend fun restoreLegacyFlags(session:CampusSession,classId:String) {
        val course=com.mit.attendance.data.prefs.UserPreferences(context).getCourseNameSnapshot() ?: return
        val legacy=db.alertDao().getAllAlertsSnapshot().filter {it.class_code==course}
        if(legacy.isEmpty())return
        val mappings=try {pages(session,"v1/classes/$classId/legacy-mappings")}catch(e:CampusFailure){if(e.status==404)return else throw e}
        val done=dao.directory(session.profile.id,"legacy_flags",classId).map {it.id}.toSet()
        for(mapping in mappings){
            val id=mapping.string("id") ?: continue
            if(id in done||mapping.string("source_course")!=course)continue
            val old=legacy.firstOrNull {it.id==mapping.string("legacy_alert_id")} ?: continue
            val target=mapping.string("alert_id") ?: continue
            val current=dao.state(session.profile.id,classId,target) ?: CampusPersonalState(session.profile.id,classId,target)
            if(current.pendingKey==null&&(old.seen!=0||old.isHidden||old.isTrashed))personal(session,target,current.copy(seen=current.seen||old.seen!=0,hidden=current.hidden||old.isHidden,trashed=current.trashed||old.isTrashed))
            db.withTransaction {assertCurrent(session,classId);dao.saveDirectory(listOf(CampusDirectory(session.profile.id,"legacy_flags",classId,id,"{}")));assertCurrent(session,classId)}
        }
    }
    private suspend fun flush(session: CampusSession, classId: String) {
        for (item in dao.outgoing(session.profile.id, classId).filter { it.status == "PENDING" }) {
            assertCurrent(session, classId)
            if (item.path.endsWith("/state") && dao.state(item.account, classId, item.resourceId)?.pendingKey != item.key) {
                dao.removeOutgoing(item.account, item.key); continue
            }
            try {
                val result = transport(session).request(item.path, item.method, item.body).asJsonObject
                db.withTransaction {
                    assertCurrent(session, classId)
                    if (result.has("class_id")) dao.saveAlert(decode(session, classId, result))
                    dao.state(item.account, classId, item.resourceId)?.takeIf { it.pendingKey == item.key }?.let { dao.saveState(it.copy(pendingKey = null,confirmedJson=result.toString())) }
                    dao.removeOutgoing(item.account, item.key)
                    assertCurrent(session, classId)
                }
            } catch (e: CampusFailure) {
                if (e.status == 401) { store.invalidate(session.generation); throw e }
                if(e.status==503&&e.code=="campus_read_only") {
                    db.withTransaction {assertCurrent(session,classId);dao.saveOutgoing(item.copy(error=e.message?:"Campus writes are paused."));assertCurrent(session,classId)}
                    return // Maintenance pauses outgoing writes, while authorized reads still synchronize.
                }
                if (e.status >= 500 || e.status == 429) throw e
                db.withTransaction {
                    assertCurrent(session, classId)
                    dao.saveOutgoing(item.copy(status = if (e.status == 409) "CONFLICT" else "FAILED", error = e.message ?: e.code))
                    assertCurrent(session, classId)
                }
            }
        }
    }
    private fun decode(session: CampusSession, classId: String, a: JsonObject): CachedCampusAlert {
        check(a.string("class_id") == classId)
        return CachedCampusAlert(session.profile.id, classId, a.string("id")!!, a.string("subject_id"), a.string("status")!!,
            a.get("version").asLong, a.string("topic")!!, a.string("description") ?: "", a.string("author_id"),
            a.string("author_name") ?: "Legacy author", a.string("type")!!, a.string("due_at"), a.string("expires_at"), a.toString())
    }
}
