package com.mit.attendance.campus

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.mit.attendance.R
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class CampusSyncWorker(private val context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val session = CampusSessionStore(context).read() ?: return Result.success()
        if (session.profile.id != inputData.getString("account") || session.generation != inputData.getString("generation") || session.selectedClass != inputData.getString("class")) return Result.success()
        return try {
            val alerts = CampusAlertsRepository(context).sync(session)
            for (alert in alerts) CampusNotifications.show(context, session, alert.classId, alert.id, alert.version)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: CampusFailure) { if (e.status in listOf(401,403,404)) Result.failure() else Result.retry() }
        catch (_: IllegalStateException) { Result.success() }
        catch (_: Exception) { if (runAttemptCount >= 5) Result.failure() else Result.retry() }
    }
    companion object {
        fun enqueue(context: Context, session: CampusSession) {
            if (session.selectedClass == null) return
            val tag = "campus:${session.profile.id}:${session.generation}"
            val data = workDataOf("account" to session.profile.id,"generation" to session.generation,"class" to session.selectedClass)
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniqueWork("$tag:${session.selectedClass}:now", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CampusSyncWorker>().setInputData(data).addTag(tag).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
            manager.enqueueUniquePeriodicWork("$tag:${session.selectedClass}:periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CampusSyncWorker>(30,TimeUnit.MINUTES).setInputData(data).addTag(tag).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        }
    }
}
object CampusNotifications {
    suspend fun show(context: Context, expected: CampusSession, classId: String, id: String, version: Long, reminder: Boolean = false) {
        val current = CampusSessionStore(context).read() ?: return
        if (current.generation != expected.generation || current.profile.id != expected.profile.id || current.selectedClass != classId) return
        val repository = CampusAlertsRepository(context)
        val alert = repository.dao.alert(current.profile.id,classId,id) ?: return
        val state = repository.dao.state(current.profile.id,classId,id)
        if (alert.status != "PUBLISHED" || alert.version != version || state?.hidden == true || state?.trashed == true) return
        if (alert.expiresAt?.let { java.time.Instant.parse(it).isBefore(java.time.Instant.now()) } == true) return
        if (!com.mit.attendance.data.prefs.UserPreferences(context).areNotificationsEnabledSnapshot()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("class_alerts","Class alerts",NotificationManager.IMPORTANCE_DEFAULT))
        val uri = android.net.Uri.Builder().scheme("mitattendance").authority("campus").appendPath("alerts").appendPath(classId).appendPath(id).build()
        val intent = Intent(context,CampusAlertsActivity::class.java).setData(uri).putExtra("account",current.profile.id).putExtra("class",classId).putExtra("alert",id)
        val pending = PendingIntent.getActivity(context,uri.toString().hashCode(),intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context,"class_alerts").setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(if (reminder) "Class alert reminder" else "New class alert").setContentText("Open Campus to view your class update.")
            .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        repository.assertCurrent(current,classId)
        NotificationManagerCompat.from(context).notify("campus:${current.profile.id}:$classId:$id", if (reminder) 2 else 1,notification)
    }
}
