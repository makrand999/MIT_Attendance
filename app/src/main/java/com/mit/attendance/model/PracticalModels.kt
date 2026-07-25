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
    val batch: String? = null,
    // submitted practicals carry these back
    val theory: String?,
    val conclusion: String?,
    val language: String?,
    val code: String?,
    val output: String? = null,
    val error: String? = null,
    @SerializedName("submission_type") val submissionType: String? = null,
    @SerializedName("remainingtime") val remainingTime: String? = null,
    @SerializedName("autosubmitted") val autoSubmitted: Boolean? = null,
    @SerializedName("practical_doc") val practicalDoc: String? = null,
    @SerializedName("submission_id") val submissionId: Int? = null,
    val evaluated: Boolean? = null,

    // Fields for local caching
    var subjectId: Int = 0,
    var isSubmitted: Boolean = false,
    var localDraftSavedAt: Long? = null,
    var lastServerSyncAt: Long? = null,
    var lastServerVerifiedAt: Long? = null,
    var pendingSyncAction: String? = null,
    var lastSyncError: String? = null
)

// ── Submit payload ─────────────────────────────────────────────────────────

data class PracticalEditorPayload(
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

data class PracticalSubmissionPayload(
    val practical: Int,
    val division: Int,
    val batch: Any? = null,
    val subjects: Int,
    val theory: String,
    val code: String,
    val output: String,
    val conclusion: String,
    val error: String,
    @SerializedName("remainingtime") val remainingTime: String,
    @SerializedName("autosubmitted") val autoSubmitted: Boolean,
    @SerializedName("submission_type") val submissionType: String
)

data class PracticalSubmissionUpdatePayload(
    @SerializedName("submission_id") val submissionId: Int,
    val practical: Int,
    val division: Int,
    val batch: Any? = null,
    val subjects: Int,
    val theory: String,
    val code: String,
    val output: String,
    val conclusion: String,
    val error: String,
    @SerializedName("submission_type") val submissionType: String,
    @SerializedName("remove_pdf") val removePdf: Boolean,
    @SerializedName("active_section") val activeSection: String
)

data class PracticalServerDraftEnvelope(
    val draft: PracticalRemoteEntry? = null
)

data class PracticalServerSubmissionEnvelope(
    val submission: PracticalRemoteEntry? = null
)

data class PracticalRemoteEntry(
    val id: Int? = null,
    val practical: Int? = null,
    val division: Int? = null,
    val batch: Any? = null,
    val subjects: Int? = null,
    val language: String? = null,
    val theory: String? = null,
    val code: String? = null,
    val output: String? = null,
    val conclusion: String? = null,
    val error: String? = null,
    @SerializedName("submission_type") val submissionType: String? = null,
    @SerializedName("remainingtime") val remainingTime: String? = null,
    @SerializedName("autosubmitted") val autoSubmitted: Boolean? = null,
    @SerializedName("practical_doc") val practicalDoc: String? = null,
    val evaluated: Boolean? = null
)

data class PracticalAttachment(
    val fileName: String,
    val bytes: ByteArray,
    val mimeType: String = "application/pdf"
)

data class PracticalEditorState(
    val source: RemoteEditorSource,
    val submissionId: Int? = null,
    val language: String = "python",
    val theory: String = "",
    val code: String = "",
    val output: String = "",
    val error: String = "",
    val conclusion: String = "",
    val submissionType: String? = null,
    val practicalDoc: String? = null,
    val evaluated: Boolean = false
)

enum class RemoteEditorSource {
    DRAFT,
    SUBMISSION
}

enum class PracticalServerStatus {
    AVAILABLE,
    WIFI_REQUIRED,
    SERVER_DOWN,
    UNKNOWN
}
