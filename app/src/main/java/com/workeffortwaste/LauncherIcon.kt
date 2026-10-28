package com.workeffortwaste

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

internal object LauncherIcon {
    fun selected(context: Context): Fm1Palette =
        Fm1Palette.entries.first { palette ->
            isEnabled(
                context.packageManager,
                componentName(context, palette),
                defaultEnabled = palette == Fm1Palette.BLACK,
            )
        }

    fun update(context: Context, selectedPalette: Fm1Palette) {
        val packageManager = context.packageManager
        val selectedComponent = componentName(context, selectedPalette)

        setEnabled(
            packageManager,
            selectedComponent,
            enabled = true,
            defaultEnabled = selectedPalette == Fm1Palette.BLACK,
        )
        Fm1Palette.entries
            .filterNot { it == selectedPalette }
            .forEach { palette ->
                setEnabled(
                    packageManager,
                    componentName(context, palette),
                    enabled = false,
                    defaultEnabled = palette == Fm1Palette.BLACK,
                )
            }
    }

    internal fun componentName(context: Context, palette: Fm1Palette) =
        ComponentName(context, "${context.packageName}.${palette.launcherAlias}")

    private fun isEnabled(
        packageManager: PackageManager,
        component: ComponentName,
        defaultEnabled: Boolean,
    ): Boolean = when (packageManager.getComponentEnabledSetting(component)) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> defaultEnabled
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        else -> false
    }

    private fun setEnabled(
        packageManager: PackageManager,
        component: ComponentName,
        enabled: Boolean,
        defaultEnabled: Boolean,
    ) {
        val currentlyEnabled = isEnabled(packageManager, component, defaultEnabled)
        if (currentlyEnabled != enabled) {
            packageManager.setComponentEnabledSetting(
                component,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
