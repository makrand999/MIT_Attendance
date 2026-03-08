package com.mit.attendance.model

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.concurrent.atomic.AtomicBoolean

// ── API response models ───────────────────────────────────────────────────────

data class SubjectApiResponse(
    val subject: String?,
    val encoSubjectwiseStudentId: String?,
    val presentCount: Int?,
    val absentCount: Int?,
    val totalLecture: Int?,
    val percentage: Float?
)

data class AttendanceApiRecord(
    val Date: String?,
    val presenty: String?,
    val stime: String?,
    val etime: String?
)

// ── Room entities ─────────────────────────────────────────────────────────────

@Entity(tableName = "subjects")
data class SubjectEntity(
    @PrimaryKey 
    @ColumnInfo(collate = ColumnInfo.NOCASE) // Prevent "Math" vs "math" duplicates
    val subjectName: String,
    val subjectId: String,
    val totalPresent: Int,
    val totalAbsent: Int,
    val totalLecture: Int,
    val percentage: Float,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "attendance_records", primaryKeys = ["subjectName", "date", "startTime"])
data class AttendanceEntity(
    @ColumnInfo(collate = ColumnInfo.NOCASE)
    val subjectName: String,
    val date: String,
    val status: String,       // "P" or "A"
    val startTime: String,
    val endTime: String,
    val isNew: Boolean = false,
    val seenAt: Long? = null
)

// ── UI models ─────────────────────────────────────────────────────────────────

data class SubjectUiModel(
    val id: String,
    val name: String,
    val present: Int,
    val absent: Int,
    val total: Int,
    val percentage: Float,
    val hasNewEntries: Boolean = false
)

data class AttendanceUiModel(
    val date: String,
    val status: String,
    val startTime: String,
    val endTime: String,
    val isNew: Boolean
)

// ── Credentials ───────────────────────────────────────────────────────────────

data class UserCredentials(
    val email: String,
    val password: String,
    val semId: Int
)

// ── Result types ──────────────────────────────────────────────────────────────

sealed class LoginResult {
    object Success : LoginResult()
    object InvalidCredentials : LoginResult()
    object ServerDown : LoginResult()
    data class Error(val message: String) : LoginResult()
}

sealed class SyncResult {
    data class Success(val newCount: Int) : SyncResult()
    object NoChange : SyncResult()
    object NetworkError : SyncResult()
    object SessionError : SyncResult()
    object ServerOffline : SyncResult()
}

class SingleLiveEvent<T> : MutableLiveData<T>() {
    private val pending = AtomicBoolean(false)
    override fun observe(owner: LifecycleOwner, observer: Observer<in T>) {
        super.observe(owner) { value ->
            if (pending.compareAndSet(true, false)) {
                observer.onChanged(value)
            }
        }
    }
    override fun setValue(value: T?) {
        pending.set(true)
        super.setValue(value)
    }
    fun call(value: T) = postValue(value)
}
