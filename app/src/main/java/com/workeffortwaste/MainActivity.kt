package com.workeffortwaste

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import java.text.DateFormat
import java.util.Date

internal enum class AppearanceTheme(val preferenceValue: String, val label: String) {
    FM1("fm1", "FM-1 Themes"),
    MATERIAL_YOU("material_you", "Material You");

    companion object {
        fun fromPreference(value: String?) =
            entries.firstOrNull { it.preferenceValue == value } ?: FM1
    }
}

internal enum class Fm1Palette(
    val preferenceValue: String,
    val label: String,
    val isDark: Boolean,
    val splashTheme: Int,
    val launcherAlias: String,
) {
    BLACK("black", "Black", true, R.style.Fm1SplashBlack, "LauncherBlack"),
    // Keep launcher component names stable so existing home-screen shortcuts survive upgrades.
    BLUE("blue", "Blue", false, R.style.Fm1SplashBlue, "LauncherGraphite"),
    GREEN("green", "Green", true, R.style.Fm1SplashGreen, "LauncherTeal"),
    GRAY("gray", "Gray", false, R.style.Fm1SplashGray, "LauncherGray"),
    ORANGE("orange", "Orange", false, R.style.Fm1SplashOrange, "LauncherOrange"),
    PURPLE("purple", "Purple", false, R.style.Fm1SplashPurple, "LauncherPurple");

    companion object {
        fun fromPreference(value: String?) = when (value) {
            "teal" -> GREEN
            "graphite" -> BLUE
            else -> entries.firstOrNull { it.preferenceValue == value } ?: BLACK
        }
    }
}

private val fm1PurpleLightColorScheme = lightColorScheme(
    primary = Color(0xFF713B78),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4A0D8),
    onPrimaryContainer = Color(0xFF321438),
    secondary = Color(0xFF76517F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC391CE),
    onSecondaryContainer = Color(0xFF301738),
    tertiary = Color(0xFF794875),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD1A0D1),
    onTertiaryContainer = Color(0xFF351633),
    background = Color(0xFFE8B9D8),
    onBackground = Color(0xFF211820),
    surface = Color(0xFFE8B9D8),
    onSurface = Color(0xFF211820),
    surfaceVariant = Color(0xFFD2ADD5),
    onSurfaceVariant = Color(0xFF50434E),
    outline = Color(0xFF82727F),
    outlineVariant = Color(0xFFD4C2D0),
    surfaceDim = Color(0xFFC391CE),
    surfaceBright = Color(0xFFECC4E3),
    surfaceContainerLowest = Color(0xFFF5D9EE),
    surfaceContainerLow = Color(0xFFE8B9DE),
    surfaceContainer = Color(0xFFE8B9D8),
    surfaceContainerHigh = Color(0xFFD3A0D5),
    surfaceContainerHighest = Color(0xFFC391CE),
    inverseSurface = Color(0xFF362C35),
    inverseOnSurface = Color(0xFFF9EDF7),
    inversePrimary = Color(0xFFE4A6D3),
)

private val fm1BlueLightColorScheme = lightColorScheme(
    primary = Color(0xFF4B5256),
    onPrimary = Color(0xFFF2EFE9),
    primaryContainer = Color(0xFF4B5256),
    onPrimaryContainer = Color(0xFFF2EFE9),
    secondary = Color(0xFF5F6366),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF9FB4BC),
    onSecondaryContainer = Color(0xFF1D2A30),
    tertiary = Color(0xFF5F6366),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF4B5256),
    onTertiaryContainer = Color(0xFFF2EFE9),
    background = Color(0xFFE9E1D6),
    onBackground = Color(0xFF2A2D2F),
    surface = Color(0xFFE9E1D6),
    onSurface = Color(0xFF2A2D2F),
    surfaceVariant = Color(0xFFDDD4C8),
    onSurfaceVariant = Color(0xFF5F6366),
    outline = Color(0xFF8A8D8E),
    outlineVariant = Color(0xFFC9C1B5),
    surfaceDim = Color(0xFFD8CFC3),
    surfaceBright = Color(0xFFDDD4C8),
    surfaceContainerLowest = Color(0xFFF4EEE6),
    surfaceContainerLow = Color(0xFFEEE7DC),
    surfaceContainer = Color(0xFFE9E1D6),
    surfaceContainerHigh = Color(0xFFDDD4C8),
    surfaceContainerHighest = Color(0xFFD2C9BC),
    inverseSurface = Color(0xFF2E3335),
    inverseOnSurface = Color(0xFFECE8E1),
    inversePrimary = Color(0xFFB9C2C6),
)

private val fm1GrayLightColorScheme = lightColorScheme(
    primary = Color(0xFF3B3E41),
    onPrimary = Color(0xFFF2EFE9),
    primaryContainer = Color(0xFF3B3E41),
    onPrimaryContainer = Color(0xFFF2EFE9),
    secondary = Color(0xFF55585B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF3B3E41),
    onSecondaryContainer = Color(0xFFF2EFE9),
    tertiary = Color(0xFF55585B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF3B3E41),
    onTertiaryContainer = Color(0xFFF2EFE9),
    background = Color(0xFFE9E1D6),
    onBackground = Color(0xFF26282A),
    surface = Color(0xFFE9E1D6),
    onSurface = Color(0xFF26282A),
    surfaceVariant = Color(0xFFDDD4C8),
    onSurfaceVariant = Color(0xFF55585B),
    outline = Color(0xFF8A8D8E),
    outlineVariant = Color(0xFFC9C1B5),
    surfaceDim = Color(0xFFD8CFC3),
    surfaceBright = Color(0xFFDDD4C8),
    surfaceContainerLowest = Color(0xFFF4EEE6),
    surfaceContainerLow = Color(0xFFEEE7DC),
    surfaceContainer = Color(0xFFE9E1D6),
    surfaceContainerHigh = Color(0xFFDDD4C8),
    surfaceContainerHighest = Color(0xFFD2C9BC),
    inverseSurface = Color(0xFF2E3133),
    inverseOnSurface = Color(0xFFECE8E1),
    inversePrimary = Color(0xFFBCBEC0),
)

internal val fm1BlackColorScheme = darkColorScheme(
    primary = Color(0xFFFF5A5F),
    onPrimary = Color(0xFF2A0C0E),
    primaryContainer = Color(0xFFFF5A5F),
    onPrimaryContainer = Color(0xFF2A0C0E),
    secondary = Color(0xFFC0C6C8),
    onSecondary = Color(0xFF2A2D2E),
    secondaryContainer = Color(0xFF41484A),
    onSecondaryContainer = Color(0xFFEEF1F1),
    tertiary = Color(0xFFC0C6C8),
    onTertiary = Color(0xFF2A2D2E),
    tertiaryContainer = Color(0xFF41484A),
    onTertiaryContainer = Color(0xFFE6EDEF),
    background = Color(0xFF292B2D),
    onBackground = Color(0xFFF0F0EE),
    surface = Color(0xFF292B2D),
    onSurface = Color(0xFFF0F0EE),
    surfaceVariant = Color(0xFF343739),
    onSurfaceVariant = Color(0xFFC0C6C8),
    outline = Color(0xFF8A9092),
    outlineVariant = Color(0xFF41484A),
    surfaceDim = Color(0xFF202224),
    surfaceBright = Color(0xFF4A4F51),
    surfaceContainerLowest = Color(0xFF1B1C1E),
    surfaceContainerLow = Color(0xFF25272A),
    surfaceContainer = Color(0xFF292B2D),
    surfaceContainerHigh = Color(0xFF343739),
    surfaceContainerHighest = Color(0xFF3E4143),
    inverseSurface = Color(0xFFF0F0EE),
    inverseOnSurface = Color(0xFF292B2D),
    inversePrimary = Color(0xFFB3383D),
)

private val fm1GreenColorScheme = darkColorScheme(
    primary = Color(0xFF62CFB4),
    onPrimary = Color(0xFF0F2D26),
    primaryContainer = Color(0xFF62CFB4),
    onPrimaryContainer = Color(0xFF0F2D26),
    secondary = Color(0xFFC0C6C8),
    onSecondary = Color(0xFF2A2D2E),
    secondaryContainer = Color(0xFF62CFB4),
    onSecondaryContainer = Color(0xFF0F2D26),
    tertiary = Color(0xFFC0C6C8),
    onTertiary = Color(0xFF2A2D2E),
    tertiaryContainer = Color(0xFF62CFB4),
    onTertiaryContainer = Color(0xFF0F2D26),
    background = Color(0xFF292B2D),
    onBackground = Color(0xFFF0F0EE),
    surface = Color(0xFF292B2D),
    onSurface = Color(0xFFF0F0EE),
    surfaceVariant = Color(0xFF343739),
    onSurfaceVariant = Color(0xFFC0C6C8),
    outline = Color(0xFF8A9092),
    outlineVariant = Color(0xFF41484A),
    surfaceDim = Color(0xFF202224),
    surfaceBright = Color(0xFF4A4F51),
    surfaceContainerLowest = Color(0xFF1B1C1E),
    surfaceContainerLow = Color(0xFF25272A),
    surfaceContainer = Color(0xFF292B2D),
    surfaceContainerHigh = Color(0xFF343739),
    surfaceContainerHighest = Color(0xFF3E4143),
    inverseSurface = Color(0xFFF0F0EE),
    inverseOnSurface = Color(0xFF292B2D),
    inversePrimary = Color(0xFF2F7F6D),
)

private val fm1OrangeColorScheme = lightColorScheme(
    primary = Color(0xFF5C1D0B),
    onPrimary = Color(0xFFFFE9DC),
    primaryContainer = Color(0xFF5C1D0B),
    onPrimaryContainer = Color(0xFFFFE9DC),
    secondary = Color(0xFF511B0B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF7B18D),
    onSecondaryContainer = Color(0xFF310F05),
    tertiary = Color(0xFF511B0B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA93220),
    onTertiaryContainer = Color(0xFFFFF3E9),
    background = Color(0xFFD95035),
    onBackground = Color(0xFF310F05),
    surface = Color(0xFFD95035),
    onSurface = Color(0xFF310F05),
    surfaceVariant = Color(0xFFE96044),
    onSurfaceVariant = Color(0xFF511B0B),
    outline = Color(0xFF511B0B),
    outlineVariant = Color(0xFFA93220),
    surfaceDim = Color(0xFFA93220),
    surfaceBright = Color(0xFFE96044),
    surfaceContainerLowest = Color(0xFFF7B18D),
    surfaceContainerLow = Color(0xFFE96044),
    surfaceContainer = Color(0xFFD95035),
    surfaceContainerHigh = Color(0xFFE96044),
    surfaceContainerHighest = Color(0xFFF7B18D),
    inverseSurface = Color(0xFF310F05),
    inverseOnSurface = Color(0xFFFFE6DC),
    inversePrimary = Color(0xFFFFB59A),
)

/** Owns UI/permission wiring and binds to the service-owned worker without controlling its lifetime. */
class MainActivity : ComponentActivity() {
    private lateinit var connection: TransferConnection
    private lateinit var updater: AppUpdateModel
    private val model get() = connection.model
    private val updateDeviceIdle: Boolean
        get() = model?.state?.canInstallAppUpdate == true

    private val updateInstaller = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { updater.installerClosed() }
    private val updateInstallPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (!packageManager.canRequestPackageInstalls()) {
            updater.installationFailed(
                IllegalStateException("Allow installs from this app to install the update."),
            )
        }
    }

    @Suppress("DEPRECATION")
    private val appVersionName: String by lazy {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }
    private var batteryExempt by mutableStateOf(false)
    private var batterySettingsError by mutableStateOf("")
    private var notificationsEnabled by mutableStateOf(false)
    private var notificationSettingsError by mutableStateOf("")
    private var resultSummary by mutableStateOf<String?>(null)
    private val setupPreferences get() = getSharedPreferences("setup", Context.MODE_PRIVATE)

    /** Package-scoped callback prevents another app from receiving the USB permission response. */
    private val permissionAction get() = "$packageName.USB_PERMISSION"

    /** Routes only device discovery and permission events; never performs USB I/O on the UI thread. */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val model = model ?: return
            // Typed parcelables were added in API 33; the legacy API is required on Android 8–12.
            @Suppress("DEPRECATION")
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            when (intent.action) {
                permissionAction -> model.permissionResult(
                    device,
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
                )

                UsbManager.ACTION_USB_DEVICE_DETACHED -> model.detached(device)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> model.refresh()
            }
        }
    }

    /** Registers foreground discovery and renders the Material screen without owning its transfer state. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val backupMilestonePromptDueAtLaunch = BackupMilestonePrompt.isDue(setupPreferences)
        connection = ViewModelProvider(this)[TransferConnection::class.java]
        updater = ViewModelProvider(this)[AppUpdateModel::class.java]
        resultSummary = savedInstanceState?.getString(TransferService.RESULT_SUMMARY)
            ?: intent.getStringExtra(TransferService.RESULT_SUMMARY)
        intent.removeExtra(TransferService.RESULT_SUMMARY)
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            // Explicit receiver export flags do not exist on the supported API 26–32 devices.
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        enableEdgeToEdge()
        setContent {
            var appearanceTheme by remember {
                mutableStateOf(
                    AppearanceTheme.fromPreference(
                        setupPreferences.getString("appearance_theme", null),
                    ),
                )
            }
            var fm1Palette by remember {
                mutableStateOf(
                    Fm1Palette.fromPreference(setupPreferences.getString("fm1_palette", null)),
                )
            }
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = if (appearanceTheme == AppearanceTheme.FM1) {
                fm1Palette.isDark
            } else {
                systemDarkTheme
            }
            val colors = if (appearanceTheme == AppearanceTheme.FM1) {
                when (fm1Palette) {
                    Fm1Palette.PURPLE -> fm1PurpleLightColorScheme
                    Fm1Palette.BLUE -> fm1BlueLightColorScheme
                    Fm1Palette.BLACK -> fm1BlackColorScheme
                    Fm1Palette.GREEN -> fm1GreenColorScheme
                    Fm1Palette.GRAY -> fm1GrayLightColorScheme
                    Fm1Palette.ORANGE -> fm1OrangeColorScheme
                }
            } else if (Build.VERSION.SDK_INT >= 31) {
                if (systemDarkTheme) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (systemDarkTheme) {
                darkColorScheme()
            } else {
                expressiveLightColorScheme()
            }
            val mainTitleColor = when {
                appearanceTheme == AppearanceTheme.MATERIAL_YOU -> colors.onSecondaryContainer
                fm1Palette == Fm1Palette.PURPLE -> colors.onSecondaryContainer
                else -> colors.onSurface
            }
            SideEffect {
                val barStyle = SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    colors.surfaceContainer.toArgb(),
                ) { darkTheme }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
                if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            }
            LaunchedEffect(appearanceTheme, fm1Palette, darkTheme) {
                if (Build.VERSION.SDK_INT >= 31) {
                    splashScreen.setSplashScreenTheme(
                        if (appearanceTheme == AppearanceTheme.FM1) fm1Palette.splashTheme
                        else android.content.res.Resources.ID_NULL,
                    )
                    getSystemService(UiModeManager::class.java).setApplicationNightMode(
                        when {
                            appearanceTheme == AppearanceTheme.MATERIAL_YOU -> UiModeManager.MODE_NIGHT_AUTO
                            darkTheme -> UiModeManager.MODE_NIGHT_YES
                            else -> UiModeManager.MODE_NIGHT_NO
                        },
                    )
                }
            }
            MaterialExpressiveTheme(colorScheme = colors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    var showWelcome by rememberSaveable {
                        mutableStateOf(!setupPreferences.getBoolean("complete", false))
                    }
                    var showBackupMilestonePrompt by rememberSaveable {
                        mutableStateOf(backupMilestonePromptDueAtLaunch)
                    }
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    BackHandler(enabled = showSettings) { showSettings = false }
                    if (showBackupMilestonePrompt && resultSummary == null) {
                        AlertDialog(
                            onDismissRequest = {
                                showBackupMilestonePrompt = false
                                if (!BackupMilestonePrompt.dismiss(setupPreferences)) {
                                    Log.w("MainActivity", "Could not persist backup milestone prompt dismissal")
                                }
                            },
                            title = { Text("Thanks for using FM-1 Backup & Restore") },
                            text = {
                                Text(
                                    buildAnnotatedString {
                                        append("Thank you for completing five backups and using this app! ")
                                        append("Don't worry, this is the only time this pop-up will appear.\n\n")
                                        append("If you find the app useful, support development via ")
                                        withLink(aboutLink("https://github.com/sponsors/workeffortwaste")) {
                                            append("GitHub Sponsors")
                                        }
                                        append(" or ")
                                        withLink(aboutLink("https://ko-fi.com/defaced")) {
                                            append("Ko-fi")
                                        }
                                        append(".")
                                    },
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showBackupMilestonePrompt = false
                                        if (!BackupMilestonePrompt.dismiss(setupPreferences)) {
                                            Log.w(
                                                "MainActivity",
                                                "Could not persist backup milestone prompt dismissal",
                                            )
                                        }
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Done") }
                            },
                        )
                    }
                    resultSummary?.let { summary ->
                        AlertDialog(
                            onDismissRequest = { resultSummary = null },
                            title = { Text("Transfer result") },
                            text = {
                                SelectionContainer {
                                    Text(summary, modifier = Modifier.verticalScroll(rememberScrollState()))
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = { resultSummary = null },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Close") }
                            },
                        )
                    }
                    if (showWelcome) {
                        WelcomeScreen(darkTheme = darkTheme) {
                            setupPreferences.edit { putBoolean("complete", true) }
                            model?.refresh()
                            showWelcome = false
                        }

                    } else {
                        LaunchedEffect(Unit) { updater.check(manual = false) }
                        AnimatedContent(
                            targetState = showSettings,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                val direction = if (targetState) 1 else -1
                                (slideInHorizontally(tween(250)) { direction * it / 8 } +
                                        fadeIn(tween(250))) togetherWith
                                        (slideOutHorizontally(tween(250)) { -direction * it / 8 } +
                                                fadeOut(tween(150)))
                            },
                            label = "Settings navigation",
                        ) { settingsVisible ->
                            if (settingsVisible) {
                                SettingsScreen(
                                    onBack = { showSettings = false },
                                    appearanceTheme = appearanceTheme,
                                    darkTheme = darkTheme,
                                    onAppearanceThemeChange = { selected ->
                                        appearanceTheme = selected
                                        setupPreferences.edit {
                                            putString("appearance_theme", selected.preferenceValue)
                                        }
                                    },
                                    fm1Palette = fm1Palette,
                                    onFm1PaletteChange = { selected ->
                                        fm1Palette = selected
                                        setupPreferences.edit {
                                            putString("fm1_palette", selected.preferenceValue)
                                        }
                                    },
                                )
                            } else {
                                val connectedModel = model
                                if (connectedModel == null) Text("Connecting to transfer service…")
                                else BackupScreen(
                                    connectedModel,
                                    ::connect,
                                    onSettings = { showSettings = true },
                                    titleColor = mainTitleColor,
                                    darkTheme = darkTheme,
                                )
                            }
                        }
                        AppUpdateDialog(
                            state = updater.state,
                            deviceIdle = updateDeviceIdle,
                            installerOpen = updater.installerOpen,
                            onDismiss = updater::dismiss,
                            onDownload = updater::download,
                            onInstall = ::installAppUpdate,
                        )
                    }
                }
            }
        }
    }

    private fun installAppUpdate() {
        if (!updateDeviceIdle) {
            updater.installationFailed(
                IllegalStateException("Wait for the FM-1 operation to finish before installing."),
            )
            return
        }
        try {
            if (!packageManager.canRequestPackageInstalls()) {
                updateInstallPermission.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()),
                )
                return
            }
            val apk = updater.verifyForInstallation()
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", apk)
            updater.installerOpened()
            updateInstaller.launch(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        } catch (failure: Exception) {
            updater.installationFailed(failure)
        }
    }

    /** Re-reads OS-managed setup permissions and USB state after returning from Settings or a picker. */
    override fun onResume() {
        super.onResume()
        refreshSetupStatus()
        model?.refresh()
    }

    /** Updates an already-running activity when the service delivers a new transfer result. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resultSummary = intent.getStringExtra(TransferService.RESULT_SUMMARY)
        intent.removeExtra(TransferService.RESULT_SUMMARY)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(TransferService.RESULT_SUMMARY, resultSummary)
    }

    /** Opens the relevant battery page, using the direct exemption request only until permission is granted. */
    private fun requestScreenOffTransfers() {
        try {
            val settingsIntent = if (batteryExempt) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            } else {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri())
            }
            startActivity(settingsIntent)
            batterySettingsError = ""
        } catch (failure: Exception) {
            Log.w("MainActivity", "Could not open battery settings", failure)
            batterySettingsError = "Allow unrestricted battery use in Android Settings."
        }
    }

    /** Re-reads battery and notification access because users grant these outside the app. */
    private fun refreshSetupStatus() {
        batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val manager = getSystemService(NotificationManager::class.java)
        notificationsEnabled = manager.areNotificationsEnabled() &&
                manager.notificationChannels.none { it.importance == NotificationManager.IMPORTANCE_NONE }
        batterySettingsError = ""
        notificationSettingsError = ""
    }

    /** Opens this app's notification settings after the runtime permission flow is complete. */
    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
            notificationSettingsError = ""
        } catch (failure: Exception) {
            Log.w("MainActivity", "Could not open notification settings", failure)
            notificationSettingsError = "Change notification access in Android Settings."
        }
    }

    /** Hands a support URL to Android's default handler and logs when no handler can open it. */
    private fun openSupportPage(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (failure: Exception) {
            Log.w("MainActivity", "Could not open support page", failure)
        }
    }

    /** Requests notification permission when appropriate; otherwise lets the user manage it in Settings. */
    @Composable
    private fun notificationSettingsAction(): () -> Unit {
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            setupPreferences.edit { putBoolean("notifications_requested", true) }
            refreshSetupStatus()
        }
        return {
            if (Build.VERSION.SDK_INT >= 33) {
                val needsPermission = checkSelfPermission(POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                val mayRequest = !setupPreferences.getBoolean("notifications_requested", false) ||
                        shouldShowRequestPermissionRationale(POST_NOTIFICATIONS)
                if (needsPermission && mayRequest) permissionLauncher.launch(POST_NOTIFICATIONS)
                else openNotificationSettings()
            } else {
                openNotificationSettings()
            }
        }
    }

    /** Shared setup controls used by both first-run onboarding and the Settings screen. */
    @Composable
    private fun SetupOptions(
        darkTheme: Boolean,
        onBatteryClick: () -> Unit,
        onNotificationsClick: () -> Unit,
    ) {
        val listContainerColor = if (darkTheme) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceBright
        val segmentedColors = ListItemDefaults.segmentedColors(
            containerColor = listContainerColor,
            disabledContainerColor = listContainerColor,
        )
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            SegmentedListItem(
                onClick = { if (!batteryExempt) onBatteryClick() },
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
                colors = segmentedColors,
                enabled = !batteryExempt,
                trailingContent = {
                    Switch(
                        checked = batteryExempt,
                        enabled = !batteryExempt,
                        onCheckedChange = { if (it) onBatteryClick() },
                    )
                },
                content = { Text("Allow background transfers") },
            )
            SegmentedListItem(
                onClick = onNotificationsClick,
                shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
                colors = segmentedColors,
                trailingContent = {
                    Switch(checked = notificationsEnabled, onCheckedChange = { onNotificationsClick() })
                },
                content = { Text("Notifications") },
            )
        }
        if (batterySettingsError.isNotEmpty()) Text(batterySettingsError)
        if (notificationSettingsError.isNotEmpty()) Text(notificationSettingsError)
    }

    /** Presents first-run setup and saves completion only when the user chooses to continue. */
    @Composable
    private fun WelcomeScreen(darkTheme: Boolean, onFinish: () -> Unit) {
        val onNotificationsClick = notificationSettingsAction()
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) { contentPadding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(contentPadding)
                    .consumeWindowInsets(contentPadding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_wave),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp),
                )
                Text(
                    "Welcome",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                SetupOptions(darkTheme, ::requestScreenOffTransfers, onNotificationsClick)
                Text(
                    "Background transfers are required to keep backups and restores running when " +
                            "the screen is off or you switch apps. Enable Allow background transfers " +
                            "to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = onFinish,
                    enabled = batteryExempt,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Start FM-1 Backup & Restore") }
            }
        }
    }

    /** Provides setup permissions, app information, and project-support links outside onboarding. */
    @Composable
    private fun SettingsScreen(
        onBack: () -> Unit,
        appearanceTheme: AppearanceTheme,
        darkTheme: Boolean,
        onAppearanceThemeChange: (AppearanceTheme) -> Unit,
        fm1Palette: Fm1Palette,
        onFm1PaletteChange: (Fm1Palette) -> Unit,
    ) {
        val onNotificationsClick = notificationSettingsAction()
        val appIcon = remember { LauncherIcon.selected(this@MainActivity) }
        var pendingIcon by remember { mutableStateOf<Fm1Palette?>(null) }
        pendingIcon?.let { selected ->
            AlertDialog(
                onDismissRequest = { pendingIcon = null },
                title = { Text("Change app icon?") },
                text = { Text("The app will close to apply the ${selected.label} icon. Reopen it from your home screen or app drawer.") },
                confirmButton = {
                    TextButton(
                        enabled = updateDeviceIdle,
                        onClick = {
                            if (updateDeviceIdle) {
                                LauncherIcon.update(this@MainActivity, selected)
                                finishAndRemoveTask()
                            }
                        },
                    ) { Text("Apply and close") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingIcon = null }) { Text("Cancel") }
                },
            )
        }
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) { contentPadding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(contentPadding)
                    .consumeWindowInsets(contentPadding).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeading("Permissions")
                        SetupOptions(darkTheme, ::requestScreenOffTransfers, onNotificationsClick)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeading("Appearance")
                        AppearanceOptions(
                            darkTheme = darkTheme,
                            appearanceTheme = appearanceTheme,
                            onAppearanceThemeChange = onAppearanceThemeChange,
                            fm1Palette = fm1Palette,
                            onFm1PaletteChange = onFm1PaletteChange,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionHeading("App icon")
                        if (!updateDeviceIdle) {
                            Text("Wait for the current device operation to finish before changing the app icon.")
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                            Fm1Palette.entries.forEachIndexed { index, palette ->
                                val selectIcon = {
                                    if (palette != appIcon && updateDeviceIdle) pendingIcon = palette
                                }
                                SegmentedListItem(
                                    onClick = selectIcon,
                                    enabled = updateDeviceIdle,
                                    shapes = ListItemDefaults.segmentedShapes(
                                        index = index,
                                        count = Fm1Palette.entries.size,
                                    ),
                                    colors = ListItemDefaults.segmentedColors(
                                        containerColor = if (darkTheme) MaterialTheme.colorScheme.surfaceContainerHigh
                                        else MaterialTheme.colorScheme.surfaceBright,
                                    ),
                                    trailingContent = {
                                        RadioButton(
                                            selected = appIcon == palette,
                                            onClick = selectIcon,
                                            enabled = updateDeviceIdle,
                                        )
                                    },
                                    content = { Text("${palette.label} icon") },
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionHeading("About")
                        InfoBox(iconResource = R.drawable.ic_wave) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        "FM1 Backup & Restore",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.semantics { heading() },
                                    )
                                    InfoBoxDetail("Version: $appVersionName")
                                    Text(
                                        buildAnnotatedString {
                                            append("Author: ")
                                            withLink(aboutLink("https://defaced.dev/?utm_source=fm-backup-restore")) {
                                                append("defaced")
                                            }
                                            append(" / ")
                                            withLink(aboutLink("https://github.com/workeffortwaste")) {
                                                append("workeffortwaste")
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(top = 16.dp),
                                    )
                                    Text(
                                        buildAnnotatedString {
                                            append("Source: ")
                                            withLink(aboutLink("https://github.com/workeffortwaste/fm1-backup-restore")) {
                                                append("fm1-backup-restore")
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        buildAnnotatedString {
                                            append("Bluesky: ")
                                            withLink(aboutLink("https://bsky.app/profile/defaced.dev")) {
                                                append("@defaced.dev")
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = { openSupportPage("https://github.com/sponsors/workeffortwaste") },
                                        shapes = ButtonDefaults.shapes(),
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    ) { Text("GitHub Sponsors", textAlign = TextAlign.Center) }
                                    Button(
                                        onClick = { openSupportPage("https://ko-fi.com/defaced") },
                                        shapes = ButtonDefaults.shapes(),
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    ) { Text("Ko-fi", textAlign = TextAlign.Center) }
                                }
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeading("App updates")
                        UpdateSettings(updater, updateDeviceIdle)
                    }
                }
            }
        }
    }

    @Composable
    private fun aboutLink(url: String) = LinkAnnotation.Url(
        url = url,
        styles = TextLinkStyles(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            ),
        ),
        linkInteractionListener = { openSupportPage(url) },
    )

    @Composable
    private fun AppearanceOptions(
        darkTheme: Boolean,
        appearanceTheme: AppearanceTheme,
        onAppearanceThemeChange: (AppearanceTheme) -> Unit,
        fm1Palette: Fm1Palette,
        onFm1PaletteChange: (Fm1Palette) -> Unit,
    ) {
        val listContainerColor = if (darkTheme) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceBright
        }
        val segmentedColors = ListItemDefaults.segmentedColors(containerColor = listContainerColor)
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                AppearanceTheme.entries.forEachIndexed { index, theme ->
                    SegmentedListItem(
                        onClick = { onAppearanceThemeChange(theme) },
                        shapes = ListItemDefaults.segmentedShapes(
                            index = index,
                            count = AppearanceTheme.entries.size,
                        ),
                        colors = segmentedColors,
                        trailingContent = {
                            RadioButton(
                                selected = appearanceTheme == theme,
                                onClick = { onAppearanceThemeChange(theme) },
                            )
                        },
                        content = { Text(theme.label) },
                    )
                }
            }
            if (appearanceTheme == AppearanceTheme.FM1) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading("FM-1 Themes")
                    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        Fm1Palette.entries.forEachIndexed { index, palette ->
                            SegmentedListItem(
                                onClick = { onFm1PaletteChange(palette) },
                                shapes = ListItemDefaults.segmentedShapes(
                                    index = index,
                                    count = Fm1Palette.entries.size,
                                ),
                                colors = segmentedColors,
                                trailingContent = {
                                    RadioButton(
                                        selected = fm1Palette == palette,
                                        onClick = { onFm1PaletteChange(palette) },
                                    )
                                },
                                content = { Text(palette.label) },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Requests permission for the selected device or starts the model's identity/probe operation. */
    private fun connect() {
        val model = model ?: return
        val device = model.state.device ?: return
        if (model.manager.hasPermission(device)) {
            model.connect()
        } else {
            model.awaitingPermission(device)
            // Android must add USB result extras, so the package-scoped PendingIntent is mutable.
            // FLAG_MUTABLE was introduced in API 31 and cannot be requested on older phones.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0,
                Intent(permissionAction).setPackage(packageName), flags
            )
            model.manager.requestPermission(device, permissionIntent)
        }
    }

    /** Matches this activity instance's receiver registration, including configuration recreation. */
    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    /** Keeps file pickers and destructive confirmations separate from service-owned transfers. */
    @Composable
    private fun BackupScreen(
        model: BackupModel,
        onConnect: () -> Unit,
        onSettings: () -> Unit,
        titleColor: Color,
        darkTheme: Boolean,
    ) {
        val state = model.state
        val v15RecoveryState = model.v15RecoveryState
        val firmwareExportState = model.firmwareExportState
        LaunchedEffect(state.autoConnectPending, state.device, state.busy, updater.installerOpen) {
            if (state.autoConnectPending && state.device != null && !updater.installerOpen) {
                onConnect()
            }
        }
        val discoveringDevice = batteryExempt && state.discoveryActive &&
                state.deviceMode != DeviceMode.MULTIPLE &&
                ((state.deviceMode == DeviceMode.SEARCHING && !state.discoveryTimedOut) ||
                        state.findingBootloader || state.readingFirmwareIdentity)
        val noDevice = batteryExempt && state.discoveryActive && state.deviceMode == DeviceMode.SEARCHING &&
                state.discoveryTimedOut && !state.findingBootloader && !state.readingFirmwareIdentity
        var retainedDeviceStatus by rememberSaveable { mutableStateOf("") }
        val currentDeviceStatus = state.deviceStatus.takeIf {
            batteryExempt && state.discoveryActive &&
                    (state.findingBootloader ||
                            (state.deviceMode != DeviceMode.SEARCHING && !state.readingFirmwareIdentity))
        }
        LaunchedEffect(currentDeviceStatus, noDevice, batteryExempt, state.discoveryActive) {
            if (noDevice || !batteryExempt || !state.discoveryActive) {
                retainedDeviceStatus = ""
            } else if (currentDeviceStatus != null) {
                retainedDeviceStatus = currentDeviceStatus
            }
        }
        var choosingFile by rememberSaveable { mutableStateOf(false) }
        var savedRestore by rememberSaveable { mutableStateOf<String?>(null) }
        var deleteSource by rememberSaveable { mutableStateOf<String?>(null) }
        var shaSource by rememberSaveable { mutableStateOf<String?>(null) }
        var renameSource by rememberSaveable { mutableStateOf<String?>(null) }
        var renameName by rememberSaveable { mutableStateOf("") }
        var importName by rememberSaveable { mutableStateOf("") }
        var exportSource by rememberSaveable { mutableStateOf<String?>(null) }
        var backupActions by rememberSaveable { mutableStateOf<String?>(null) }
        var showAppMenu by rememberSaveable { mutableStateOf(false) }
        var showSupportMenu by rememberSaveable { mutableStateOf(false) }
        var usbDiagnostics by rememberSaveable { mutableStateOf<String?>(null) }

        // Clear the transient backup hash before dispatching a new user action.
        fun onButtonClick(action: () -> Unit) {
            model.dismissTransferResult()
            action()
        }
        usbDiagnostics?.let { details ->
            AlertDialog(
                onDismissRequest = { usbDiagnostics = null },
                title = { Text("USB diagnostics") },
                text = {
                    SelectionContainer {
                        Text(details, modifier = Modifier.verticalScroll(rememberScrollState()))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onButtonClick { usbDiagnostics = null } }) { Text("Close") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("USB diagnostics", details))
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Copy") }
                },
            )
        }
        val listState = rememberLazyListState()
        val overscrollEffect = rememberOverscrollEffect()
        val topBarState = rememberTopAppBarState()
        val libraryOverflows by remember {
            derivedStateOf {
                topBarState.heightOffset < 0f || listState.canScrollBackward ||
                        backupLibraryOverflows(listState.layoutInfo, topBarState.heightOffset)
            }
        }
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            state = topBarState,
            canScroll = { libraryOverflows },
        )
        val headerScrollBehavior = remember(scrollBehavior) {
            object : TopAppBarScrollBehavior by scrollBehavior {
                override val isPinned: Boolean get() = !libraryOverflows
            }
        }
        val importPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { sourceUri ->
            choosingFile = false
            if (sourceUri != null) model.selectImportFile(sourceUri)
        }
        val documentPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { destinationUri ->
            choosingFile = false
            val id = exportSource
            exportSource = null
            if (destinationUri != null && id != null) model.exportBackup(id, destinationUri)
        }
        val zipPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { destinationUri ->
            choosingFile = false
            if (destinationUri != null) model.exportAllFirmware(destinationUri)
        }
        // Keep menu dismissal and each action's follow-up UI state in one place.
        val handleBackupAction: (BackupAction, BackupEntry) -> Unit = { action, entry ->
            backupActions = null
            when (action) {
                BackupAction.RESTORE -> savedRestore = entry.id
                BackupAction.EXPORT -> {
                    exportSource = entry.id
                    choosingFile = true
                    documentPicker.launch(BackupStore.exportFileName(entry))
                }

                BackupAction.TOGGLE_PIN -> onButtonClick { model.togglePinnedBackup(entry.id) }
                BackupAction.SHA -> shaSource = entry.id
                BackupAction.RENAME -> {
                    renameName = entry.firmwareIdentity.orEmpty()
                    renameSource = entry.id
                }

                BackupAction.DELETE -> deleteSource = entry.id
            }
        }
        val pickerAvailable = !choosingFile && !state.libraryLoading && savedRestore == null && deleteSource == null &&
            state.selectedImport == null && !firmwareExportState.showDialog
        val editable = !state.busy && !v15RecoveryState.working && pickerAvailable
        val operationEligible = !state.busy && state.ready && batteryExempt && !state.libraryLoading &&
            !v15RecoveryState.working && !firmwareExportState.showDialog
        if (savedRestore != null) {
            val savedEntry = state.backups.find { it.id == savedRestore }
            RestoreConfirmationDialog(
                backupName = savedEntry?.firmwareIdentity ?: "Firmware unknown",
                backupDate = savedEntry?.let { backupDate(it.id) },
                sha256 = savedEntry?.sha256,
                restoreEnabled = operationEligible,
                onConfirm = {
                    onButtonClick {
                        val saved = savedRestore
                        savedRestore = null
                        if (saved != null) model.restoreSavedBackup(saved)
                    }
                },
                onDismiss = {
                    onButtonClick { savedRestore = null }
                },
            )
        }
        LaunchedEffect(state.selectedImport?.sourceName) {
            state.selectedImport?.sourceName?.let { importName = it.dropLast(4) }
        }
        state.selectedImport?.takeIf { !state.busy }?.let {
            val valid = importName.isNotBlank() && importName.length <= BackupStore.MAX_NAME_LENGTH &&
                    importName.all { character -> character.code in 32..126 }
            AlertDialog(
                onDismissRequest = { onButtonClick(model::cancelImport) },
                title = { Text("Save imported backup") },
                text = {
                    OutlinedTextField(
                        value = importName,
                        onValueChange = { importName = it },
                        label = { Text("File name") },
                        trailingIcon = { Text(".bin") },
                        singleLine = true,
                        isError = !valid,
                        supportingText = {
                            Text("Required · printable ASCII, up to ${BackupStore.MAX_NAME_LENGTH} characters")
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = valid && !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { onButtonClick { model.saveImportedBackup(importName) } },
                    ) { Text("Save") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { onButtonClick(model::cancelImport) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Cancel") }
                },
            )
        }
        shaSource?.let { id ->
            state.backups.find { it.id == id }?.let { entry ->
                AlertDialog(
                    onDismissRequest = { shaSource = null },
                    title = { Text("SHA-256") },
                    text = {
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    entry.firmwareIdentity ?: "Firmware unknown",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(entry.sha256)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { onButtonClick { shaSource = null } },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Close")
                        }
                    },
                )
            }
        }
        renameSource?.let { id ->
            val valid = renameName.isNotBlank() && renameName.length <= BackupStore.MAX_NAME_LENGTH &&
                    renameName.all { it.code in 32..126 }
            AlertDialog(
                onDismissRequest = { renameSource = null },
                title = { Text("Rename backup") },
                text = {
                    OutlinedTextField(
                        value = renameName,
                        onValueChange = { renameName = it },
                        label = { Text("ID") },
                        singleLine = true,
                        isError = !valid,
                        supportingText = { Text("Required · printable ASCII, up to ${BackupStore.MAX_NAME_LENGTH} characters") },
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = valid && !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { onButtonClick { renameSource = null; model.renameBackup(id, renameName) } },
                    ) { Text("Rename") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { onButtonClick { renameSource = null } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Cancel")
                    }
                },
            )
        }
        deleteSource?.let { id ->
            val entry = state.backups.find { it.id == id }
            AlertDialog(
                onDismissRequest = { deleteSource = null },
                title = { Text("Delete this backup?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry?.firmwareIdentity ?: "Firmware unknown", style = MaterialTheme.typography.titleSmall)
                        Text(backupDate(id))
                        Text("This permanently deletes the private copy from this app. Exported copies are not deleted.")
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { onButtonClick { deleteSource = null; model.deleteBackup(id) } },
                    ) { Text("Delete backup") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { onButtonClick { deleteSource = null } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Cancel")
                    }
                },
            )
        }
        V15RecoveryDialog(
            state = v15RecoveryState,
            onDismiss = model::dismissV15RecoveryDialog,
            onCreate = model::createV15RecoveryBackup,
        )
        FirmwareExportDialog(
            state = firmwareExportState,
            exportEnabled = !choosingFile && !state.busy,
            onDismiss = model::dismissFirmwareExportDialog,
            onCancel = model::cancelFirmwareExport,
            onExport = {
                choosingFile = true
                zipPicker.launch("fm1-backups-${java.time.LocalDate.now()}.zip")
            },
        )
        if (state.showTransferProgress) {
            TransferProgressScreen(
                operation = requireNotNull(state.transfer),
                status = state.status,
                progress = if (state.progressKnown) state.progress else null,
                titleColor = titleColor,
            )
            return
        }
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            floatingActionButton = {
                if (operationEligible && pickerAvailable) {
                    ExtendedFloatingActionButton(
                        onClick = { onButtonClick(model::backup) },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_save),
                                contentDescription = null,
                            )
                        },
                        text = { Text("Backup FM-1") },
                    )
                }
            },
            topBar = {
                TwoRowsTopAppBar(
                    title = { expanded ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_launcher_wave),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(if (expanded) 48.dp else 40.dp),
                            )
                            if (expanded) {
                                Text(
                                    "FM-1 Backup & Restore",
                                    textAlign = TextAlign.Center,
                                    color = titleColor,
                                )
                            }
                        }
                    },
                    titleHorizontalAlignment = Alignment.CenterHorizontally,
                    expandedHeight = 140.dp,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        titleContentColor = titleColor,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    subtitle = { expanded ->
                        if (expanded) Text(
                            if (noDevice || !batteryExempt || !state.discoveryActive) ""
                            else currentDeviceStatus ?: retainedDeviceStatus,
                            minLines = 1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    },
                    navigationIcon = {
                        Box {
                            IconButton(
                                onClick = { showSupportMenu = true },
                                enabled = !state.busy,
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_coffee),
                                    contentDescription = "Support this project",
                                )
                            }
                            DropdownMenuPopup(
                                expanded = showSupportMenu && !state.busy,
                                onDismissRequest = { showSupportMenu = false },
                            ) {
                                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                                    DropdownMenuItem(
                                        text = { Text("GitHub Sponsors") },
                                        shape = MenuDefaults.leadingItemShape,
                                        onClick = {
                                            showSupportMenu = false
                                            openSupportPage("https://github.com/sponsors/workeffortwaste")
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Ko-fi") },
                                        shape = MenuDefaults.trailingItemShape,
                                        onClick = {
                                            showSupportMenu = false
                                            openSupportPage("https://ko-fi.com/defaced")
                                        },
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                onButtonClick {
                                    if (state.ready) model.disconnect() else model.refreshDevice()
                                }
                            },
                            enabled = editable && batteryExempt,
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (state.ready) R.drawable.ic_eject else R.drawable.ic_refresh,
                                ),
                                contentDescription = if (state.ready) "Disconnect FM-1" else "Refresh",
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { showAppMenu = true },
                                enabled = editable,
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_more_vert),
                                    contentDescription = "More options",
                                )
                            }
                            DropdownMenuPopup(
                                expanded = showAppMenu && editable,
                                onDismissRequest = { showAppMenu = false },
                            ) {
                                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                                    DropdownMenuItem(
                                        text = { Text("Import from file") },
                                        shape = MenuDefaults.leadingItemShape,
                                        enabled = editable && batteryExempt,
                                        onClick = {
                                            showAppMenu = false
                                            choosingFile = true
                                            importPicker.launch(
                                                arrayOf(
                                                    "application/octet-stream",
                                                    "application/x-binary",
                                                    "*/*"
                                                )
                                            )
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Export all backups") },
                                        shape = MenuDefaults.middleItemShape,
                                        enabled = editable && state.backups.isNotEmpty(),
                                        onClick = {
                                            showAppMenu = false
                                            onButtonClick(model::showFirmwareExportDialog)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Create recovery image") },
                                        shape = MenuDefaults.middleItemShape,
                                        enabled = editable && batteryExempt,
                                        onClick = {
                                            showAppMenu = false
                                            model.showV15RecoveryDialog()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        shape = MenuDefaults.middleItemShape,
                                        onClick = {
                                            showAppMenu = false
                                            onSettings()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("USB diagnostics") },
                                        shape = MenuDefaults.trailingItemShape,
                                        onClick = {
                                            showAppMenu = false
                                            usbDiagnostics = model.discoveryDiagnostics()
                                        },
                                    )
                                }
                            }
                        }
                    },
                    scrollBehavior = headerScrollBehavior,
                )
            },
        ) { contentPadding ->
            LazyColumn(
                state = listState,
                userScrollEnabled = libraryOverflows,
                overscrollEffect = if (libraryOverflows) overscrollEffect else null,
                modifier = Modifier.fillMaxSize().padding(contentPadding)
                    .consumeWindowInsets(contentPadding).imePadding()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                contentPadding = PaddingValues(bottom = 112.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(
                            top = 24.dp,
                            bottom = 24.dp - ListItemDefaults.SegmentedGap,
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val showStatus = discoveringDevice || noDevice || state.status.isNotEmpty
                        val connectionFailed = state.status.heading.startsWith("Connection failed") ||
                                state.status.heading.startsWith("Bootloader request failed") ||
                                state.status.heading.startsWith("Restore verification failed")
                        val statusIcon = if (connectionFailed) R.drawable.ic_error
                        else if (discoveringDevice || state.busy) R.drawable.ic_usb
                        else R.drawable.ic_info
                        val statusPresentation = when {
                            state.status.isNotEmpty -> state.status
                            noDevice -> discoveryStatusMessage(noDevice = true)
                            discoveringDevice -> discoveryStatusMessage(noDevice = false)
                            else -> StatusMessage(state.deviceStatus, "")
                        }

                        if (showStatus) {
                            StatusMessageCard(
                                message = statusPresentation,
                                iconResource = statusIcon,
                                isError = connectionFailed,
                                progressDescription = state.deviceStatus.takeIf {
                                    !connectionFailed && (discoveringDevice || state.busy)
                                },
                            )
                        }
                        if (state.restoreRetryAvailable && !state.busy) {
                            OutlinedButton(
                                onClick = { onButtonClick(model::retryRestore) },
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text("Retry restore") }
                        }
                        val connectingToDevice = state.busy && (
                                state.findingBootloader ||
                                        state.status.heading.startsWith("Reading FM-1 firmware identity") ||
                                        state.status.heading.startsWith("Waiting for USB permission") ||
                                        state.status.heading.startsWith("Waiting for FM-1") ||
                                        state.status.heading.startsWith("Connecting to FM-1")
                                )
                        if (!state.busy && !state.restoreRetryAvailable &&
                            (state.deviceMode == DeviceMode.MIDI || (state.device != null && !state.ready))
                        ) {
                            ConnectionSection(
                                state,
                                editable,
                                onEnterBootloader = { onButtonClick(model::enterBootloader) },
                                onConnect = { onButtonClick(onConnect) },
                            )
                        }
                        if (!batteryExempt) {
                            Button(
                                onClick = { onButtonClick(onSettings) },
                                enabled = editable,
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text("Settings") }
                        }
                        if (state.busy && !state.readingFirmwareIdentity && !connectingToDevice) {
                            OutlinedButton(
                                onClick = { onButtonClick(model::cancel) },
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text("Cancel operation") }
                        }
                    }
                }
                item {
                    if (state.pinnedBackups.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(bottom = 8.dp - ListItemDefaults.SegmentedGap),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SectionHeading("Pinned")
                        }
                    }
                }
                itemsIndexed(state.pinnedBackups, key = { _, entry -> entry.id }) { index, entry ->
                    BackupEntryRow(
                        entry = entry,
                        index = index,
                        count = state.pinnedBackups.size,
                        pinned = true,
                        darkTheme = darkTheme,
                        actionsOpen = backupActions == entry.id,
                        enabled = editable,
                        operationEligible = operationEligible,
                        pickerAvailable = pickerAvailable,
                        onOpenActions = { backupActions = entry.id },
                        onDismissActions = { backupActions = null },
                        onAction = { action, selected -> handleBackupAction(action, selected) },
                    )
                }
                if (state.unpinnedBackups.isNotEmpty() || state.backups.isEmpty() ||
                    state.libraryLoading || state.libraryError.isNotEmpty()
                ) {
                    item {
                        Column(
                            modifier = Modifier.padding(
                                top = if (state.pinnedBackups.isEmpty()) 0.dp
                                else 24.dp - ListItemDefaults.SegmentedGap,
                                bottom = if (state.backups.isEmpty()) 0.dp else 8.dp - ListItemDefaults.SegmentedGap,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SectionHeading("Backups")
                            if (state.libraryLoading) Text("Loading verified backups…")
                            if (state.libraryError.isNotEmpty()) Text(state.libraryError)
                            if (!state.libraryLoading && state.libraryError.isEmpty() && state.backups.isEmpty()) {
                                InfoBox {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("No current backups", style = MaterialTheme.typography.titleSmall)
                                        InfoBoxDetail("Connect your FM-1 and create your first full flash backup.")
                                    }
                                }
                            }
                        }
                    }
                }
                itemsIndexed(state.unpinnedBackups, key = { _, entry -> entry.id }) { index, entry ->
                    BackupEntryRow(
                        entry = entry,
                        index = index,
                        count = state.unpinnedBackups.size,
                        pinned = false,
                        darkTheme = darkTheme,
                        actionsOpen = backupActions == entry.id,
                        enabled = editable,
                        operationEligible = operationEligible,
                        pickerAvailable = pickerAvailable,
                        onOpenActions = { backupActions = entry.id },
                        onDismissActions = { backupActions = null },
                        onAction = { action, selected -> handleBackupAction(action, selected) },
                    )
                }
            }
        }
    }

    private enum class BackupAction { RESTORE, EXPORT, TOGGLE_PIN, SHA, RENAME, DELETE }

    /** Shows backup metadata and forwards menu choices to the screen that owns the related dialogs. */
    @Composable
    private fun BackupEntryRow(
        entry: BackupEntry,
        index: Int,
        count: Int,
        pinned: Boolean,
        darkTheme: Boolean,
        actionsOpen: Boolean,
        enabled: Boolean,
        operationEligible: Boolean,
        pickerAvailable: Boolean,
        onOpenActions: () -> Unit,
        onDismissActions: () -> Unit,
        onAction: (BackupAction, BackupEntry) -> Unit,
    ) {
        val label = backupLabel(entry.id)
        val listContainerColor = if (darkTheme) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceBright
        SegmentedListItem(
            onClick = onOpenActions,
            shapes = if (count == 1) {
                ListItemDefaults.shapes(shape = MaterialTheme.shapes.large)
            } else {
                ListItemDefaults.segmentedShapes(index = index, count = count)
            },
            enabled = enabled,
            colors = ListItemDefaults.segmentedColors(
                containerColor = listContainerColor,
                disabledContainerColor = listContainerColor,
            ),
            content = { Text(entry.firmwareIdentity ?: "Firmware unknown") },
            supportingContent = { Text("${backupDate(entry.id)} \u00b7 1 MiB") },
            trailingContent = {
                Box {
                    FilledTonalIconButton(
                        onClick = onOpenActions,
                        enabled = enabled,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = "Actions for $label"
                        },
                    ) {
                        Icon(painter = painterResource(R.drawable.ic_more_vert), contentDescription = null)
                    }
                    DropdownMenuPopup(expanded = actionsOpen, onDismissRequest = onDismissActions) {
                        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                            DropdownMenuItem(
                                text = { Text("Restore") },
                                shape = MenuDefaults.leadingItemShape,
                                enabled = operationEligible && pickerAvailable,
                                onClick = { onAction(BackupAction.RESTORE, entry) },
                            )
                            DropdownMenuItem(
                                text = { Text("Export") },
                                shape = MenuDefaults.middleItemShape,
                                enabled = enabled,
                                onClick = { onAction(BackupAction.EXPORT, entry) },
                            )
                            DropdownMenuItem(
                                text = { Text(if (pinned) "Unpin" else "Pin") },
                                shape = MenuDefaults.middleItemShape,
                                enabled = enabled,
                                onClick = { onAction(BackupAction.TOGGLE_PIN, entry) },
                            )
                            DropdownMenuItem(
                                text = { Text("SHA-256") },
                                shape = MenuDefaults.middleItemShape,
                                onClick = { onAction(BackupAction.SHA, entry) },
                            )
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                shape = MenuDefaults.middleItemShape,
                                enabled = enabled,
                                onClick = { onAction(BackupAction.RENAME, entry) },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                shape = MenuDefaults.trailingItemShape,
                                enabled = enabled,
                                onClick = { onAction(BackupAction.DELETE, entry) },
                            )
                        }
                    }
                }
            },
        )
    }

    /** Connection guidance and explicit user actions; discovering a device never starts a backup. */
    @Composable
    private fun ConnectionSection(
        state: BackupState,
        editable: Boolean,
        onEnterBootloader: () -> Unit,
        onConnect: () -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.deviceMode == DeviceMode.MIDI) {
                Button(
                    shapes = ButtonDefaults.shapes(),
                    onClick = onEnterBootloader,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Connect to FM-1") }
            } else if (state.device != null && !state.ready) {
                Button(
                    onClick = onConnect,
                    shapes = ButtonDefaults.shapes(),
                    enabled = editable && !state.multipleDevices,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Connect to FM-1") }
            }
        }
    }

    /** Formats the timestamp embedded in a backup ID, falling back to the original ID if malformed. */
    private fun backupDate(id: String): String {
        val timestamp = id.split('-').getOrNull(1)?.toLongOrNull() ?: return id
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(timestamp))
    }

    /** Builds a user-facing backup label from its timestamp and trailing ID suffix. */
    private fun backupLabel(id: String): String {
        val parts = id.split('-')
        val timestamp = parts.getOrNull(1)?.toLongOrNull() ?: return id
        return "FM-1 ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(timestamp))}" +
                " · ${parts.getOrNull(2)?.takeLast(6).orEmpty()}"
    }

    @Composable
    private fun SectionHeading(title: String) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
        )
    }
}

internal fun backupLibraryOverflows(layout: LazyListLayoutInfo, topBarHeightOffset: Float): Boolean {
    if (layout.totalItemsCount == 0) return false
    val visibleItems = layout.visibleItemsInfo
    if (visibleItems.size < layout.totalItemsCount) return true
    val first = visibleItems.firstOrNull() ?: return false
    val last = visibleItems.last()
    val contentHeight = last.offset + last.size - first.offset +
            layout.beforeContentPadding + layout.afterContentPadding
    // A collapsed header enlarges the viewport; judge fit against its expanded size.
    val expandedViewportHeight = layout.viewportSize.height + topBarHeightOffset
    return contentHeight > expandedViewportHeight
}
