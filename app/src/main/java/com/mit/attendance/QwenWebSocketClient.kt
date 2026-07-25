package com.mit.attendance

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

class QwenWebSocketClient(
    serverUri: URI,
    private val onMessageReceived: (String) -> Unit,
    private val onOpenStarted: () -> Unit,
    private val onConnectionClosed: () -> Unit,
    private val onErrorOccurred: (Exception) -> Unit
) : WebSocketClient(serverUri) {

    override fun onOpen(handshakedata: ServerHandshake?) = onOpenStarted()

    override fun onMessage(message: String?) {
        message?.let { onMessageReceived(it) }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) = onConnectionClosed()

    override fun onError(ex: Exception?) {
        ex?.let { onErrorOccurred(it) }
    }

    fun sendInput(text: String) {
        send(JSONObject().put("text", text).toString())
    }
}
