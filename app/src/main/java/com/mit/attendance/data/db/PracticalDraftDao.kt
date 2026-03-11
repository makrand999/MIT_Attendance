package com.mit.attendance.data.db

import androidx.room.*
import com.mit.attendance.model.PracticalDraft

@Dao
interface PracticalDraftDao {

    @Query("SELECT * FROM practical_drafts WHERE practicalId = :id")
    suspend fun getDraft(id: Int): PracticalDraft?

    @Upsert
    suspend fun upsertDraft(draft: PracticalDraft)

    @Query("DELETE FROM practical_drafts WHERE practicalId = :id")
    suspend fun deleteDraft(id: Int)
}