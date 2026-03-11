package com.mit.attendance.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

// ── Login ──────────────────────────────────────────────────────────────────

data class PracticalLoginRequest(
    val email: String,
    val password: String
)

data class PracticalLoginResponse(
    val token: String,
    val user: PracticalUser?
)

data class PracticalUser(
    val name: String?
)

// ── Subjects ───────────────────────────────────────────────────────────────

data class StudentSubjectsResponse(
    @SerializedName("division_id") val divisionId: Int?,
    val subjects: List<PracticalSubject>
)

@Entity(tableName = "practical_subjects")
data class PracticalSubject(
    @PrimaryKey val id: Int,
    val subjectname: String,
    val subjectcode: String,
    val practical: Boolean
)

// ── Practicals ─────────────────────────────────────────────────────────────

data class PracticalsResponse(
    val submitted: List<Practical>,
    @SerializedName("not_submitted") val notSubmitted: List<Practical>
)

@Entity(tableName = "practicals")
data class Practical(
    @PrimaryKey val id: Int,
    @SerializedName("practical_number") val practicalNumber: Int,
    @SerializedName("practical_aim") val practicalAim: String,
    @SerializedName("practical_description") val practicalDescription: String?,
    @SerializedName("expected_output") val expectedOutput: String?,
    // submitted practicals carry these back
    val theory: String?,
    val conclusion: String?,
    val language: String?,
    val code: String?,
    
    // Fields for local caching
    var subjectId: Int = 0,
    var isSubmitted: Boolean = false
)

// ── Submit payload ─────────────────────────────────────────────────────────

data class PracticalSubmitPayload(
    val practical: Int,
    val division: Int,
    val batch: Any? = null,
    val subjects: Int,
    val language: String,
    val theory: String,
    val code: String,
    val output: String,
    val error: String,
    val conclusion: String
)
