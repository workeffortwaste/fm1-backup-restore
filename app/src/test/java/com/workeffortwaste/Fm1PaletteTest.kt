package com.workeffortwaste

import org.junit.Assert.assertEquals
import org.junit.Test

class Fm1PaletteTest {
    @Test fun renamedPalettesAcceptExistingSavedPreferences() {
        assertEquals(Fm1Palette.GREEN, Fm1Palette.fromPreference("teal"))
        assertEquals(Fm1Palette.BLUE, Fm1Palette.fromPreference("graphite"))
    }

    @Test fun palettePreferencesRoundTrip() {
        Fm1Palette.entries.forEach { palette ->
            assertEquals(palette, Fm1Palette.fromPreference(palette.preferenceValue))
        }
        assertEquals("Green", Fm1Palette.GREEN.label)
        assertEquals("Blue", Fm1Palette.BLUE.label)
    }
}
