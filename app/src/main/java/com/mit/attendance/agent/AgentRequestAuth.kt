package com.mit.attendance.agent

import android.util.Base64
import com.mit.attendance.BuildConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AgentRequestAuth {

    fun buildQuery(email: String, timestampMs: Long = System.currentTimeMillis()): String {
        val normalizedEmail = email.trim().lowercase()
        val timestamp = timestampMs.toString()
        val signature = sign(normalizedEmail, timestamp)
        return "ts=${urlEncode(timestamp)}&sig=${urlEncode(signature)}"
    }

    private fun sign(email: String, timestamp: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(BuildConfig.SSH_PASSWORD.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val payload = "$email\n$timestamp".toByteArray(StandardCharsets.UTF_8)
        return Base64.encodeToString(
            mac.doFinal(payload),
            Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE
        )
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
