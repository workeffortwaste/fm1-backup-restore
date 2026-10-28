package com.workeffortwaste

import android.content.Context
import android.app.UiModeManager
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.Build
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.edit
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.workeffortwaste.protocol.FlashRestore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Random

/**
 * Screenshot-only runner: connected, backup and restore at 75%, and Settings for each theme.
 * Run through `screenshotThemes`, which pulls the PNGs into the repository's `screenshots` directory.
 */
class ScreenshotCapture {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val backupDirectory = File(context.filesDir, "backups")
    private val store = BackupStore(backupDirectory)
    private val seeded = mutableListOf<File>()
    private val outputDirectory = File(context.getExternalFilesDir(null), "theme-screenshots")

    private data class Theme(
        val fileName: String,
        val appearance: String,
        val palette: String?,
    )

    private val themes = listOf(
        Theme("custom-black", "fm1", "black"),
        Theme("custom-orange", "fm1", "orange"),
        Theme("custom-purple", "fm1", "purple"),
        Theme("custom-green", "fm1", "green"),
        Theme("custom-blue", "fm1", "blue"),
        Theme("material-you", "material_you", null),
    )

    private val pinnedName = "Factory FM-1_015"
    private val connectedFirmware = "FELUCCA v1.6.1"
    private val shortLibrary = listOf("FELUCCA v1.6.1", "JIANT 0.9.18", "SLOOP v2.8")

    private val setupPreferences get() = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
    private val backupPreferences get() = context.getSharedPreferences("backup_library", Context.MODE_PRIVATE)
    private var originalSetup: Map<String, Any?> = emptyMap()
    private var originalBackupPreferences: Map<String, Any?> = emptyMap()

    @Before
    fun prepare() {
        originalSetup = setupPreferences.all
        originalBackupPreferences = backupPreferences.all
        outputDirectory.deleteRecursively()
        check(outputDirectory.mkdirs()) { "Could not create screenshot directory: $outputDirectory" }
        shell("dumpsys deviceidle whitelist +${context.packageName}")
    }

    @After
    fun cleanUp() {
        seeded.forEach { it.delete() }
        restore("setup", originalSetup)
        restore("backup_library", originalBackupPreferences)
        syncLaunchMode()
        shell("dumpsys deviceidle whitelist -${context.packageName}")
    }

    private fun restore(name: String, values: Map<String, Any?>) {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) {
            clear()
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command)
            .let(ParcelFileDescriptor::AutoCloseInputStream).bufferedReader().use { it.readText() }

    /** Replaces the dummy library; the factory image is always pinned and the rest are newest-first. */
    private fun seed(names: List<String>) {
        seeded.forEach { it.delete() }
        seeded.clear()
        backupDirectory.mkdirs()
        val image = ByteArray(FlashRestore.FLASH_SIZE).also { Random(1).nextBytes(it) }
        val hash = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        val ordered = listOf(pinnedName) + names
        ordered.forEachIndexed { index, name ->
            val identity = name.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
            val created = now - index * 86_400_000L * 2
            val file = File(backupDirectory, "fm1-$created-${index + 1}-$hash-fw-$identity.bin")
            file.writeBytes(image)
            seeded += file
        }
        backupPreferences.edit(commit = true) { putStringSet("pinned_backup_ids", setOf(seeded.first().name)) }
    }

    private fun stateOf(model: BackupModel): MutableState<BackupState> {
        val delegate = BackupModel::class.java.getDeclaredField("state\$delegate")
            .apply { isAccessible = true }
            .get(model)
        @Suppress("UNCHECKED_CAST")
        return delegate as MutableState<BackupState>
    }

    /** Shows the seeded library regardless of what an earlier service instance already loaded. */
    private fun BackupState.withSeededLibrary(): BackupState {
        val entries = store.list()
        return copy(
            backups = entries,
            pinnedBackupIds = entries.filter { it.firmwareIdentity == pinnedName }.map { it.id }.toSet(),
            libraryLoading = false,
            libraryError = "",
        )
    }

    private val connected: BackupState.() -> BackupState = {
        copy(
            deviceMode = DeviceMode.DOWNLOAD,
            discoveryActive = true,
            ready = true,
            firmwareIdentity = connectedFirmware,
            status = statusMessage("FM-1 successfully connected", "Ready to backup or restore full flash"),
        )
    }

    private fun transfer(operation: FlashOperation, detail: String): BackupState.() -> BackupState = {
        connected().copy(
            busy = true,
            transfer = operation,
            progress = 0.75f,
            progressKnown = true,
            status = statusMessage(
                if (operation == FlashOperation.BACKUP) "Backing up FM-1" else "Restoring FM-1",
                detail,
            ),
        )
    }

    private fun setTheme(theme: Theme) {
        setupPreferences.edit(commit = true) {
            putBoolean("complete", true)
            putString("appearance_theme", theme.appearance)
            if (theme.palette != null) putString("fm1_palette", theme.palette)
        }
        syncLaunchMode()
    }

    private fun syncLaunchMode() {
        if (Build.VERSION.SDK_INT >= 31) {
            val mode = when {
                AppearanceTheme.fromPreference(setupPreferences.getString("appearance_theme", null)) ==
                    AppearanceTheme.MATERIAL_YOU ->
                    UiModeManager.MODE_NIGHT_AUTO
                Fm1Palette.fromPreference(setupPreferences.getString("fm1_palette", null)).isDark ->
                    UiModeManager.MODE_NIGHT_YES
                else -> UiModeManager.MODE_NIGHT_NO
            }
            context.getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
        }
    }

    /** Launches the real activity, adjusts the service state, runs [navigate], and saves one PNG. */
    private fun capture(
        name: String,
        change: BackupState.() -> BackupState = { this },
        navigate: () -> Unit = {},
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var model: BackupModel? = null
            compose.waitUntil(10_000) {
                scenario.onActivity { activity ->
                    model = ViewModelProvider(activity)[TransferConnection::class.java].model
                }
                model != null
            }
            val target = requireNotNull(model)
            val original = target.state
            try {
                stateOf(target).value = target.state.withSeededLibrary().change()
                compose.waitForIdle()
                navigate()
                compose.waitForIdle()
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
                    "Could not capture $name"
                }
                val file = File(outputDirectory, "$name.png")
                file.outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not save $file" }
                }
            } finally {
                scenario.onActivity { activity ->
                    if (Build.VERSION.SDK_INT >= 31) {
                        activity.splashScreen.setSplashScreenTheme(
                            if (AppearanceTheme.fromPreference(originalSetup["appearance_theme"] as? String) ==
                                AppearanceTheme.FM1
                            ) {
                                Fm1Palette.fromPreference(originalSetup["fm1_palette"] as? String).splashTheme
                            } else android.content.res.Resources.ID_NULL,
                        )
                    }
                }
                stateOf(target).value = original
            }
        }
    }

    @Test
    fun captureEveryTheme() {
        seed(shortLibrary)
        themes.forEach { theme ->
            setTheme(theme)
            capture("${theme.fileName}-connected", connected)
            capture("${theme.fileName}-backup-75", transfer(FlashOperation.BACKUP, "Reading flash"))
            capture(
                "${theme.fileName}-restore-75",
                transfer(FlashOperation.RESTORE, "Writing application flash"),
            )
            capture("${theme.fileName}-settings", connected) {
                compose.onNodeWithContentDescription("More options").performClick()
                compose.onNodeWithText("Settings").performClick()
            }
        }
    }
}
