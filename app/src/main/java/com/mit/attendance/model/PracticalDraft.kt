package com.mit.attendance.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "practical_drafts")
data class PracticalDraft(
    @PrimaryKey val practicalId: Int,
    val language: String = "python",
    val theory: String,
    val code: String = "",
    val output: String = "",
    val error: String = "",
    val conclusion: String,
    val pdfUri: String? = null,
    val pdfName: String? = null,
    val lastServerSyncAt: Long? = null,
    val lastServerVerifiedAt: Long? = null,
    val pendingSyncAction: String? = null,
    val lastSyncError: String? = null,
    val lastSaved: Long = System.currentTimeMillis()
)
