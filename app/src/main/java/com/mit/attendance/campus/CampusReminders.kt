package com.mit.attendance.campus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.*

object CampusReminders {
    private fun pending(context: Context, account: String, classId: String, id: String, generation: String? = null, version: Long = 0): PendingIntent {
        val uri = Uri.Builder().scheme("mitattendance").authority("reminder").appendPath(account).appendPath(classId).appendPath(id).appendPath(generation ?: "").build()
        val intent = Intent(context,CampusReminderReceiver::class.java).setData(uri).putExtra("generation",generation).putExtra("version",version)
        return PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    fun schedule(context: Context, session: CampusSession, alert: CachedCampusAlert, at: Long) {
        require(at > System.currentTimeMillis())
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = pending(context,alert.account,alert.classId,alert.id,session.generation,alert.version)
        if (android.os.Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending)
        else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending)
    }
    fun cancel(context: Context, account: String, classId: String, id: String, generation: String? = null) {
        val expected=generation ?: CampusSessionStore(context).read()?.generation ?: return
        val uri=Uri.Builder().scheme("mitattendance").authority("reminder").appendPath(account).appendPath(classId).appendPath(id).appendPath(expected).build()
        val existing=PendingIntent.getBroadcast(context,0,Intent(context,CampusReminderReceiver::class.java).setData(uri),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if(existing!=null){context.getSystemService(AlarmManager::class.java).cancel(existing);existing.cancel()}
    }
}
class CampusReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val parts = intent.data?.pathSegments ?: return
        if (parts.size != 4) return
        val session = CampusSessionStore(context).read() ?: return
        if (session.profile.id != parts[0] || session.generation != parts[3] || session.generation != intent.getStringExtra("generation")) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { CampusNotifications.show(context,session,parts[1],parts[2],intent.getLongExtra("version",0),true) }
            finally { pending.finish() }
        }
    }
}
