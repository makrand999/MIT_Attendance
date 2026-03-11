package com.mit.attendance.model

import androidx.room.Entity
import androidx.room.PrimaryKey

data class ReviewApiResponse(
    val timestamp: String?,
    val gender: String?,
    val rating: Double?,
    val comment: String?
)

@Entity(tableName = "reviews")
data class ReviewEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: String,
    val gender: String,
    val rating: Float,
    val comment: String,
    val fetchedAt: Long = System.currentTimeMillis()
)
