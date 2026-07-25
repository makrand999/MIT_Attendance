package com.mit.attendance.model

import kotlinx.serialization.Serializable

@Serializable
data class CertificationItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val platform: String,
    val type: String, // "hardcoded" or "python"
    val completerKey: String? = null, // for hardcoded types
    val pythonScriptUrl: String? = null, // URL to the .py script
    val targetUrl: String? = null,
    var isDownloaded: Boolean = false
)
