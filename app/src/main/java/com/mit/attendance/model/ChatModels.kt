package com.mit.attendance.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val text: String,
    val gender: String, // "Male" or "Female"
    val timestamp: Long = System.currentTimeMillis()
)
