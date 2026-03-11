package com.mit.attendance.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "practical_drafts")
data class PracticalDraft(
    @PrimaryKey val practicalId: Int,
    val theory: String,
    val conclusion: String,
    val lastSaved: Long = System.currentTimeMillis()
)