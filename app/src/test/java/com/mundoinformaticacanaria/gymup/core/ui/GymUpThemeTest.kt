package com.mundoinformaticacanaria.gymup.core.ui

import com.mundoinformaticacanaria.gymup.core.model.ThemeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GymUpThemeTest {
    @Test
    fun systemModeFollowsSystemAppearance() {
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = false))
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true))
    }

    @Test
    fun explicitModesOverrideSystemAppearance() {
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark = true))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark = false))
    }
}
