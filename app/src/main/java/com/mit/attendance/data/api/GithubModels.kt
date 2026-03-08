package com.mit.attendance.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GithubRelease(
    @SerialName("tag_name")
    val tagName: String,          // e.g. "v2"
    val name: String,              // Release title
    val body: String,              // Release notes
    val assets: List<GithubAsset>
)

@Serializable
data class GithubAsset(
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
    val name: String
)
