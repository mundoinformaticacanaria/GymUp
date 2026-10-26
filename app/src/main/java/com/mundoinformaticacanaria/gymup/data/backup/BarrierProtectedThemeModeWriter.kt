package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.core.model.ThemeMode

/**
 * Serializes the user-visible theme preference mutation with an exclusive backup.
 *
 * The unprotected writer is normally UserPreferencesRepository::setThemeMode.
 * This adapter is deliberately not installed in AppContainer: the same barrier
 * must also guard every Room and private-image mutation, and recovery must run
 * before seeding or exposing the UI. Reads of the theme are not blocked.
 *
 * Backup/recovery must call the underlying unwrapped preferences writer while
 * holding the non-reentrant exclusive barrier, never this adapter.
 */
internal class BarrierProtectedThemeModeWriter(
    private val setThemeModeUnprotected: suspend (ThemeMode) -> Unit,
    private val barrier: BackupDomainWriteBarrier,
) {
    suspend fun setThemeMode(themeMode: ThemeMode) {
        barrier.withDomainWrite { setThemeModeUnprotected(themeMode) }
    }
}
