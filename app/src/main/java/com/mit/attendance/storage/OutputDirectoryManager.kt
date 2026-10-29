package com.mit.attendance.storage

import android.content.Context
import java.io.File

private const val NOTES_ROOT = "Notes"
private const val BACKUPS_ROOT = "Backups"
private const val UPDATES_ROOT = "Updates"

object OutputDirectoryManager {

    fun getNotesFolder(context: Context): File {
        val root = File(context.getExternalFilesDir(null), NOTES_ROOT)
        if (!root.exists()) root.mkdirs()
        return root
    }

    fun getUpdatesFolder(context: Context): File {
        val root = File(context.getExternalFilesDir(null), UPDATES_ROOT)
        if (!root.exists()) root.mkdirs()
        return root
    }

    fun getBackupsFolder(context: Context): File {
        val root = File(context.getExternalFilesDir(null), BACKUPS_ROOT)
        if (!root.exists()) root.mkdirs()
        return root
    }

    fun getVersionFolder(context: Context, versionTag: String): File {
        val folder = File(getUpdatesFolder(context), versionTag)
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

}
