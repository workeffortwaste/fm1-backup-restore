package com.workeffortwaste

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherIconTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun launcherIconCanBeSelectedIndependently() {
        val originalPalette = LauncherIcon.selected(context)
        try {
            Fm1Palette.entries.forEach { palette ->
                LauncherIcon.update(context, palette)
                assertEnabledPalette(palette)
                assertEquals(palette, LauncherIcon.selected(context))
            }
        } finally {
            LauncherIcon.update(context, originalPalette)
        }
    }

    private fun assertEnabledPalette(expected: Fm1Palette) {
        Fm1Palette.entries.forEach { palette ->
            val component = LauncherIcon.componentName(context, palette)
            val componentState = context.packageManager.getComponentEnabledSetting(component)
            val isEnabled = componentState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                componentState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && palette == Fm1Palette.BLACK
            assertEquals(
                "Unexpected enabled launcher icon for ${palette.preferenceValue}",
                palette == expected,
                isEnabled,
            )
        }
    }
}
