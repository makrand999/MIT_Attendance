package com.mit.attendance.model

import kotlinx.serialization.Serializable

@Serializable
data class TimetableDay(
    val time: String,
    val lecture: String
)

typealias TimetableMap = Map<String, List<TimetableDay>>
