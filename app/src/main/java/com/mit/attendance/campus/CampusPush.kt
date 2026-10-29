package com.mit.attendance.campus

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.mit.attendance.BuildConfig
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Disabled without public Firebase client configuration. No server credential belongs in the APK. */
object CampusPush {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    fun configure(context:Context):Boolean {
        if(listOf(BuildConfig.FIREBASE_PROJECT_ID,BuildConfig.FIREBASE_APPLICATION_ID,BuildConfig.FIREBASE_API_KEY,BuildConfig.FIREBASE_SENDER_ID).any{it.isBlank()})return false
        synchronized(this){
            if(FirebaseApp.getApps(context).isEmpty())FirebaseApp.initializeApp(context,FirebaseOptions.Builder()
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY).setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build())
        }
        return true
    }
    private fun installation(context:Context,account:String):String {
        require(account.matches(Regex("[A-Za-z0-9-]{1,100}")))
        val file=File(context.noBackupFilesDir,"campus/push-$account");file.parentFile?.mkdirs()
        synchronized(this){
            if(file.exists())return file.readText()
            return UUID.randomUUID().toString().also{file.writeText(it)}
        }
    }
    fun bind(context:Context,session:CampusSession,token:String?=null) {
        if(!configure(context))return
        FirebaseMessaging.getInstance().isAutoInitEnabled=true
        if(token==null){FirebaseMessaging.getInstance().token.addOnSuccessListener{bind(context.applicationContext,session,it)};return}
        scope.launch {
            runCatching{
                if(CampusSessionStore(context).read()?.generation!=session.generation)return@runCatching
                val enabled=CampusTransport.authenticated(context,session).request("health").asJsonObject.getAsJsonObject("class_alerts")?.get("push")?.asBoolean==true
                if(!enabled)return@runCatching
                val allowed=com.mit.attendance.data.prefs.UserPreferences(context).areNotificationsEnabledSnapshot()
                CampusTransport.authenticated(context,session).request("v1/device-installations/${installation(context,session.profile.id)}","PUT",
                    jsonObject("provider" to "FCM","provider_token" to token,"alerts_enabled" to allowed).toString())
                if(CampusSessionStore(context).read()?.generation!=session.generation)unbind(context,session)
            }
        }
    }
    fun unbind(context:Context,session:CampusSession){
        val file=File(context.noBackupFilesDir,"campus/push-${session.profile.id}")
        if(!file.exists())return
        val id=file.readText()
        scope.launch{runCatching{CampusTransport(session).request("v1/device-installations/$id","DELETE")}}
    }
}
class CampusMessagingService:FirebaseMessagingService(){
    override fun onNewToken(token:String){CampusSessionStore(this).read()?.let{CampusPush.bind(applicationContext,it,token)}}
    override fun onMessageReceived(message:RemoteMessage){
        val session=CampusSessionStore(this).read()?:return
        if(message.data["user_id"]!=session.profile.id||message.data["class_id"]!=session.selectedClass)return
        // Expected-account work fetches authorized content and shares poll/socket notification deduplication.
        CampusSyncWorker.enqueue(applicationContext,session)
    }
}
