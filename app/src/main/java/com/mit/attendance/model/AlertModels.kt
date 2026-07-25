package com.mit.attendance.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey
    val id: String,
    val class_code: String,
    val type: String,
    val subject: String,
    val topic: String,
    val description: String,
    val end_date: String,
    val posted_by: String,
    val posted_at: String,
    val seen: Int = 0,
    val isHidden: Boolean = false,
    val isTrashed: Boolean = false,
    val trashedAt: Long? = null
)

data class MetaInfo(
    @SerializedName("v") val v: Int,
    @SerializedName("updated") val updated: String
)

data class AlertGist(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String,
    @SerializedName("subject") val subject: String,
    @SerializedName("topic") val topic: String,
    @SerializedName("description") val description: String,
    @SerializedName("end_date") val endDate: String,
    @SerializedName("posted_by") val postedBy: String,
    @SerializedName("posted_at") val postedAt: String
)

data class GistFile(
    @SerializedName("content") val content: String
)

data class GistRequest(
    @SerializedName("description") val description: String? = null,
    @SerializedName("files") val files: Map<String, GistFile>
)

data class GistResponse(
    @SerializedName("id") val id: String,
    @SerializedName("files") val files: Map<String, GistFileResponse>
)

data class GistFileResponse(
    @SerializedName("filename") val filename: String,
    @SerializedName("content") val content: String? = null,
    @SerializedName("raw_url") val raw_url: String? = null
)
