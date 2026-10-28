package com.workeffortwaste

import android.content.SharedPreferences

internal object BackupMilestonePrompt {
    private const val SUCCESSFUL_BACKUPS_KEY = "successful_backups"
    private const val DISMISSED_KEY = "five_backup_prompt_dismissed"
    private const val BACKUP_THRESHOLD = 5

    fun recordSuccessfulBackup(preferences: SharedPreferences): Boolean {
        val count = preferences.getInt(SUCCESSFUL_BACKUPS_KEY, 0)
        if (count >= BACKUP_THRESHOLD) return true
        return preferences.edit()
            .putInt(SUCCESSFUL_BACKUPS_KEY, count + 1)
            .commit()
    }

    fun isDue(preferences: SharedPreferences): Boolean =
        preferences.getInt(SUCCESSFUL_BACKUPS_KEY, 0) >= BACKUP_THRESHOLD &&
            !preferences.getBoolean(DISMISSED_KEY, false)

    fun dismiss(preferences: SharedPreferences): Boolean =
        preferences.edit().putBoolean(DISMISSED_KEY, true).commit()
}
