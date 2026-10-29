package com.mit.attendance.auth

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

object GitHubRequests {
    /** Never forward a student's PAT to an arbitrary URL supplied by a script catalog. */
    fun builder(context: Context, url: String): Request.Builder {
        val parsed = url.toHttpUrl()
        return Request.Builder().url(parsed).apply {
            if (parsed.isHttps && parsed.host in setOf("api.github.com", "raw.githubusercontent.com",
                    "gist.githubusercontent.com", "github.com", "gist.github.com")) {
                GitHubAccountStore(context).account()?.let { header("Authorization", "Bearer ${it.token}") }
            }
        }
    }
}
