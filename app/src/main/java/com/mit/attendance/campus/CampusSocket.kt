package com.mit.attendance.campus

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap

object CampusConnections {
    val sockets = ConcurrentHashMap<String,WebSocket>()
    fun close(generation:String) {sockets.keys.filter {it.startsWith("$generation:")}.forEach {sockets.remove(it)?.cancel()}}
}
class CampusSocket(private val store:CampusSessionStore, private val context:Context?=null) {
    suspend fun run(session:CampusSession, changed:suspend ()->Unit):Unit=coroutineScope {
        val signals=Channel<Unit>(Channel.CONFLATED)
        val consumer=launch {for(signal in signals)changed()}
        val seen=linkedSetOf<String>()
        var backoff=1000L
        var reconnectAttempted=false
        try {
            while(isActive&&store.read()?.generation==session.generation) {
                var active=store.read()?:break
                if(!active.isValid()) {
                    val app=context?:break
                    active=try {CampusRepository(app).reconnect(active)}catch(e:CancellationException){throw e}catch(_:Exception){break}
                    reconnectAttempted=true
                }
                val done=CompletableDeferred<Boolean>()
                val socket=CampusHttp.client().newWebSocket(Request.Builder().url("wss://mitasia.in/akatsuki/ws").header("Authorization","Bearer ${active.token}").build(),object:WebSocketListener(){
                    override fun onOpen(webSocket:WebSocket,response:Response){reconnectAttempted=false;backoff=1000L;signals.trySend(Unit)}
                    override fun onMessage(webSocket:WebSocket,text:String){
                        if(store.read()?.generation!=session.generation){webSocket.cancel();return}
                        val event=runCatching {JsonParser.parseString(text).asJsonObject}.getOrNull()?:return
                        val id=event.string("event_id")
                        synchronized(seen){if(id!=null&&!seen.add(id))return;if(seen.size>256)seen.remove(seen.first())}
                        if(event.string("event")!in listOf("connected","user_typing"))signals.trySend(Unit)
                    }
                    override fun onClosed(webSocket:WebSocket,code:Int,reason:String){done.complete(false)}
                    override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){done.complete(response?.code==401)}
                })
                val connectionKey="${session.generation}:${java.util.UUID.randomUUID()}"
                CampusConnections.sockets[connectionKey]=socket
                if(store.read()?.generation!=session.generation)socket.cancel()
                val unauthorized=try {withTimeoutOrNull(((active.expiresAt-System.currentTimeMillis()/1000).coerceAtLeast(1))*1000){done.await()}==true}
                finally {socket.cancel();CampusConnections.sockets.remove(connectionKey,socket)}
                if(!isActive||store.read()?.generation!=session.generation)break
                if(unauthorized) {
                    val current=store.read()?:break
                    if(current.token==active.token) {
                        val app=context
                        if(reconnectAttempted||app==null){store.invalidateToken(active);break}
                        try {CampusRepository(app).reconnect(active)}catch(e:CancellationException){throw e}catch(_:Exception){break}
                        reconnectAttempted=true
                    }
                }
                delay(backoff);backoff=(backoff*2).coerceAtMost(60000)
            }
        } finally {signals.close();consumer.cancel()}
    }
}
