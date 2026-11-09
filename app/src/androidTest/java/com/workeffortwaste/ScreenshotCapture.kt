package com.workeffortwaste

import android.content.Context
import android.app.UiModeManager
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Random
import org.json.JSONObject

/** Captures themed, device-family, transfer, Settings, release-note, and bootloader screens. */
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
        Theme("custom-gray", "fm1", "gray"),
        Theme("material-you", "material_you", null),
    )

    private val pinnedName = "Factory FM-1_015"
    private val connectedFirmware = "FM-1_015"
    private val connectedEditorFirmware = "FELUCCA v1.6.1"
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

    /** Seeds the library with deterministic identities and recovery metadata. */
    private fun seed(
        names: List<String>,
        includePinnedFactory: Boolean = true,
        family: DeviceFamily = DeviceFamily.FM1,
        familyOverrides: Map<String, DeviceFamily> = emptyMap(),
        firmwareLabelOverrides: Map<String, String> = emptyMap(),
        metadataOverrides: Map<String, BackupMetadata> = emptyMap(),
    ) {
        seeded.forEach { it.delete() }
        seeded.clear()
        backupDirectory.mkdirs()
        val image = ByteArray(FlashRestore.FLASH_SIZE).also { Random(1).nextBytes(it) }
        val hash = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        val factoryName = when (family) {
            DeviceFamily.FM1 -> pinnedName
            DeviceFamily.SMK37_PRO, DeviceFamily.SMK37_ELITE,
            DeviceFamily.STARRYKEY37_PLAY, DeviceFamily.MKE_P37 -> "Factory ${family.displayName}"
            DeviceFamily.POCKET_X -> "Factory Pocket X"
            DeviceFamily.BLACKBOX -> "Factory BlackBox"
            DeviceFamily.POCKET_AMP -> "Factory Pocket AMP"
            DeviceFamily.UNKNOWN -> "Factory device"
        }
        val ordered = (if (includePinnedFactory) listOf(factoryName) else emptyList()) + names
        ordered.forEachIndexed { index, name ->
            val identity = name.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
            val created = now - index * 86_400_000L * 2
            val file = File(backupDirectory, "fm1-$created-${index + 1}-$hash-fw-$identity.bin")
            file.writeBytes(image)
            seeded += file
            store.updateMetadata(
                file.name,
                metadataOverrides[name] ?: BackupMetadata(
                    classification = classificationFor(familyOverrides[name] ?: family),
                    userFamily = if ((familyOverrides[name] ?: family) == DeviceFamily.UNKNOWN) {
                        DeviceFamily.UNKNOWN
                    } else null,
                    userFirmwareLabel = firmwareLabelOverrides[name]
                        ?: if (name == "SLOOP v2.8") "Custom SLOOP tag" else null,
                    editorFirmwareLabel = name.takeIf { it.startsWith("FELUCCA") },
                ),
            )
            seeded += File(backupDirectory, "fm1-$created-${index + 1}-$hash.meta")
        }
        backupPreferences.edit(commit = true) {
            putStringSet(
                "pinned_backup_ids",
                if (includePinnedFactory) setOf(seeded.first().name) else emptySet(),
            )
        }
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
        val pinnedIds = backupPreferences.getStringSet("pinned_backup_ids", emptySet()).orEmpty()
        return copy(
            backups = entries,
            pinnedBackupIds = entries.filter { it.id in pinnedIds }.map { it.id }.toSet(),
            libraryLoading = false,
            libraryError = "",
        )
    }

    private fun firmwareIdentityFor(family: DeviceFamily): String? = when (family) {
        DeviceFamily.FM1 -> connectedFirmware
        DeviceFamily.SMK37_PRO -> "SMK-37 Pro_016"
        DeviceFamily.SMK37_ELITE -> "SMK-37 Elite_016"
        DeviceFamily.STARRYKEY37_PLAY -> "STARRYKEY-37 PLAY_016"
        DeviceFamily.MKE_P37 -> "MKE-P37_015"
        DeviceFamily.POCKET_X -> "PocketX_924"
        DeviceFamily.BLACKBOX -> "BlackBox_924"
        DeviceFamily.POCKET_AMP -> "Pocket AMP_105"
        DeviceFamily.UNKNOWN -> null
    }

    private fun classificationFor(family: DeviceFamily): DeviceClassification = when (family) {
        DeviceFamily.FM1 -> classifyDeviceFamily("FM-1_015", "4_1_0_0", 0x856014, false)
        DeviceFamily.SMK37_PRO, DeviceFamily.SMK37_ELITE,
        DeviceFamily.STARRYKEY37_PLAY, DeviceFamily.MKE_P37 ->
            classifyDeviceFamily(firmwareIdentityFor(family), "4_1_0_0", 0xEB6014, true)
        DeviceFamily.POCKET_X -> classifyDeviceFamily("PocketX_924", "2_1_0_0", 0x856014, false)
        DeviceFamily.BLACKBOX ->
            classifyDeviceFamily("BlackBox_924", "4_1_0_0", 0x856014, false, "PB01_00_0")
        DeviceFamily.POCKET_AMP ->
            classifyDeviceFamily("Pocket AMP_105", "4_1_0_0", 0x856014, false, "PB01_00_0")
        DeviceFamily.UNKNOWN -> DeviceClassification()
    }

    private fun connectedFor(family: DeviceFamily): BackupState.() -> BackupState = {
        val firmware = firmwareIdentityFor(family)
        copy(
            deviceMode = DeviceMode.DOWNLOAD,
            discoveryActive = true,
            ready = true,
            firmwareIdentity = firmware,
            editorFirmwareLabel = if (family == DeviceFamily.FM1) connectedEditorFirmware else null,
            deviceClassification = classificationFor(family),
            status = statusMessage(
                "${family.displayName} successfully connected",
                "Ready to backup or restore full flash",
            ),
        )
    }

    private val connected: BackupState.() -> BackupState = connectedFor(DeviceFamily.FM1)

    private fun transfer(
        operation: FlashOperation,
        detail: String,
        family: DeviceFamily = DeviceFamily.FM1,
    ): BackupState.() -> BackupState {
        val connected = connectedFor(family)
        return {
            connected().copy(
                busy = true,
                transfer = operation,
                progress = 0.75f,
                progressKnown = true,
                status = statusMessage(
                    if (operation == FlashOperation.BACKUP) "Backing up ${family.displayName}"
                    else "Restoring ${family.displayName}",
                    detail,
                ),
            )
        }
    }

    private fun setTheme(theme: Theme) {
        setupPreferences.edit(commit = true) {
            putBoolean("complete", true)
            putBoolean("hide_incompatible_backups", false)
            putBoolean("hide_device_tags", false)
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
        configure: (MainActivity) -> Unit = {},
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
                scenario.onActivity(configure)
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

    private fun captureLatestReleaseNotes() {
        val release = AppRelease(
            version = "v0.9",
            notes = "## Changes\n\nStable release-notes fixture for screenshots.\n\n" +
                    "[Full Changelog](https://github.com/workeffortwaste/fm1-backup-restore/compare/v0.8...v0.9)",
            apkUrl = "https://github.com/workeffortwaste/fm1-backup-restore/releases/download/v0.9/app.apk",
            size = 1024 * 1024,
            sha256 = "ab".repeat(32),
        )
        seed(shortLibrary)
        setTheme(themes.first { it.fileName == "custom-green" })
        capture("custom-green-update-latest", connected, configure = { activity ->
            val updater = ViewModelProvider(activity)[AppUpdateModel::class.java]
            val delegate = AppUpdateModel::class.java.getDeclaredField("state\$delegate")
                .apply { isAccessible = true }.get(updater)
            @Suppress("UNCHECKED_CAST")
            val state = delegate as MutableState<AppUpdateState>
            state.value = AppUpdateState(release = release, showDialog = true)
        }) {
            compose.waitUntil(10_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText("Full Changelog", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun setBootloaderVerification(activity: MainActivity, state: BootloaderVerificationState) {
        val model = ViewModelProvider(activity)[TransferConnection::class.java].model
        val delegate = BackupModel::class.java.getDeclaredField("bootloaderVerificationState\$delegate")
            .apply { isAccessible = true }
            .get(model)
        @Suppress("UNCHECKED_CAST")
        (delegate as MutableState<BootloaderVerificationState>).value = state
    }

    private fun verifiedMbootloaderV12ScreenshotState(): BootloaderVerificationState {
        val connection = URL(
            "https://github.com/jvitkauskas/m-bootloader/releases/download/v1.2/fm1-uboot.boot",
        ).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        val container = try {
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
        check(sha256(container) == "9e5ec0e39dc55c167f8e289cfdac612d7d340b5f46100e1be2540ef4d6ec2f13") {
            "Downloaded m-bootloader v1.2 does not match the published release SHA-256"
        }

        val region = ByteArray(FlashRestore.PROTECTED_SIZE) { 0xff.toByte() }
        val flashHeader = ByteArray(32)
        put16(flashHeader, 0, crc16(flashHeader.copyOfRange(2, 32)))
        enc(flashHeader).copyInto(region)
        val bootEntry = ByteArray(32)
        put16(bootEntry, 2, crc16(container))
        put32(bootEntry, 4, 0xa0)
        put32(bootEntry, 8, container.size)
        "uboot.boot".toByteArray(Charsets.US_ASCII).copyInto(bootEntry, 16)
        put16(bootEntry, 0, crc16(bootEntry.copyOfRange(2, 32)))
        enc(bootEntry).copyInto(region, 0x20)
        enc(container.copyOfRange(0, 16)).copyInto(region, 0xa0)
        enc(container.copyOfRange(16, container.size)).copyInto(region, 0xb0)

        val verification = com.workeffortwaste.protocol.BootloaderVerifier.identify(region)
        check(verification.identity == "m-bootloader 1.2-22e2559" && verification.matchesPublishedRelease) {
            "m-bootloader v1.2 did not verify in the simulated flash layout"
        }
        return BootloaderVerificationState(
            showDialog = true,
            sha256 = verification.sha256,
            identity = verification.identity,
            matchesPublishedRelease = true,
            message = verification.message,
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun crc16(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xff) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xffff
            }
        }
        return crc
    }

    private fun enc(bytes: ByteArray): ByteArray {
        var key = 0xffff
        return ByteArray(bytes.size) { index ->
            val value = bytes[index].toInt() xor (key and 0xff)
            key = ((key shl 1) xor (if (key and 0x8000 != 0) 0x1021 else 0)) and 0xffff
            value.toByte()
        }
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
    }

    private fun captureBootloaderVerificationVariants() {
        setTheme(themes.first { it.fileName == "custom-green" })
        val unrecognizedRegion = ByteArray(FlashRestore.PROTECTED_SIZE) { (it * 13 % 251).toByte() }
        val variants = listOf(
            "stock" to BootloaderVerificationState(
                showDialog = true,
                sha256 = com.workeffortwaste.protocol.BootloaderVerifier.STOCK_V15_SHA256,
                matchesStockV15 = true,
                message = "Stock v15 bootloader SHA-256 matches the verified official image.",
            ),
            "mbootloader-v1.2" to verifiedMbootloaderV12ScreenshotState(),
            "unrecognized" to BootloaderVerificationState(
                showDialog = true,
                sha256 = sha256(unrecognizedRegion),
                message = "Unrecognized bootloader. It may be custom, corrupted, or another stock version.",
            ),
        )
        variants.forEach { (name, state) ->
            capture("custom-green-bootloader-$name", connected, configure = { activity ->
                setBootloaderVerification(activity, state)
            }) {
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Protected region SHA-256")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.mainClock.advanceTimeBy(1000)
                SystemClock.sleep(600)
                instrumentation.waitForIdleSync()
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
            seed(emptyList(), includePinnedFactory = false)
            capture("${theme.fileName}-empty", connected) {
                compose.onNodeWithText("Already started flashing custom firmware?").assertIsDisplayed()
            }
            seed(shortLibrary)
        }
        captureBlackThemeForSmk37AndPocketX()
        captureSmkRestoreWarning()
        captureUnknownDevicePicker()
        captureTagEditDialog()
        captureMixedFm1Library()
        captureLatestReleaseNotes()
        captureBootloaderVerificationVariants()
    }

    private fun captureBlackThemeForSmk37AndPocketX() {
        val blackTheme = themes.first { it.fileName == "custom-black" }
        val devices = listOf(
            Triple(
                "smk37",
                DeviceFamily.SMK37_PRO,
                listOf("Snoop37_016", "SMK studio setup", "SLOOP v2.8"),
            ),
            Triple(
                "pocketx",
                DeviceFamily.POCKET_X,
                listOf("PocketX_924", "OpenPocket 0.9.4", "Pocket X session"),
            ),
            Triple(
                "blackbox",
                DeviceFamily.BLACKBOX,
                listOf("BlackBox_924", "OpenPocket 0.9.4", "BlackBox session"),
            ),
            Triple(
                "pocketamp",
                DeviceFamily.POCKET_AMP,
                listOf("Pocket AMP_105", "Onyx 1.01", "Pocket AMP session"),
            ),
        )
        devices.forEach { (name, family, backups) ->
            setTheme(blackTheme)
            seed(backups, family = family)
            capture("custom-black-$name-connected", connectedFor(family))
            capture(
                "custom-black-$name-backup-75",
                transfer(FlashOperation.BACKUP, "Reading flash", family),
            )
        }
    }

    private fun captureSmkRestoreWarning() {
        setTheme(themes.first { it.fileName == "custom-black" })
        seed(listOf("SMK-37 restore source"), includePinnedFactory = false, family = DeviceFamily.SMK37_PRO)
        capture("custom-black-smk37-restore-warning", connectedFor(DeviceFamily.SMK37_PRO), navigate = {
            compose.onNodeWithContentDescription("Actions for SMK-37", substring = true).performClick()
            compose.onNodeWithText("Restore").performClick()
            compose.onNodeWithText(
                "Only restore a SMK-37 Pro backup to the exact physical keyboard it was created with, due to device specific calibration data.",
            ).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("I confirm this backup was created with this exact physical SMK-37 Pro.")
                .performScrollTo().assertIsDisplayed()
            compose.mainClock.advanceTimeBy(1000)
            SystemClock.sleep(600)
            instrumentation.waitForIdleSync()
        })
    }

    @Test
    fun captureRecoveryIdentificationScenarios() {
        setTheme(themes.first { it.fileName == "custom-black" })
        seed(emptyList(), includePinnedFactory = false)
        val unknown = classifyDeviceFamily(null, null, 0x856014, false)
        capture("direct-recovery-unrecognised", change = {
            connectedFor(DeviceFamily.UNKNOWN).invoke(this).copy(
                recoverySelectionRequired = true,
                deviceClassification = unknown,
                status = recoveryConnectionStatus(unknown, selectionRequired = true),
            )
        }, navigate = {
            compose.onNodeWithText("Select connected device").assertIsDisplayed()
            compose.onNodeWithText("FM-1").assertIsDisplayed()
            for (prefix in listOf(
                "Choose the connected product", "Detection candidate:", "M-UPGRADE:",
                "Boot SPI:", "Boot RESET:", "JEDEC:", "SMK trim marker:", "Evidence:",
            )) {
                check(compose.onAllNodesWithText(prefix, substring = true).fetchSemanticsNodes().isEmpty())
            }
            compose.onNodeWithText("Use this device").assertIsNotEnabled()
        })

        val conflict = classifyDeviceFamily("PocketX_009", "4_1_0_0", 0x856014, false, "PB01_00_0")
        capture("midi-to-recovery-identity-conflict", change = {
            connectedFor(DeviceFamily.POCKET_X).invoke(this).copy(
                firmwareIdentity = "PocketX_009",
                recoverySelectionRequired = false,
                manualFamilySelection = null,
                deviceClassification = conflict,
                status = recoveryConnectionStatus(conflict, selectionRequired = false),
            )
        }, navigate = {
            compose.onNodeWithText("Conflicting evidence").assertIsDisplayed()
            compose.onNodeWithText("Device identity could not be confirmed").assertIsDisplayed()
            compose.onNodeWithText(
                "Recovery checks disagree with the MIDI identity. Backup and restore are blocked.",
            ).assertIsDisplayed()
            check(compose.onAllNodesWithText("Backup Device").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription("Return Device to MIDI mode").assertIsEnabled()
            check(compose.onAllNodesWithText("Select connected device").fetchSemanticsNodes().isEmpty())
        })
    }

    @Test
    fun captureRecoveryPickerDisconnect() {
        setTheme(themes.first { it.fileName == "custom-black" })
        seed(emptyList(), includePinnedFactory = false)
        var target: BackupModel? = null
        capture("recovery-picker-disconnected", change = {
            connectedFor(DeviceFamily.UNKNOWN).invoke(this).copy(recoverySelectionRequired = true)
        }, configure = { activity ->
            target = ViewModelProvider(activity)[TransferConnection::class.java].model
        }, navigate = {
            compose.onNodeWithText("Select connected device").assertIsDisplayed()
            compose.onNodeWithText("FM-1").performClick()
            compose.onNodeWithText("Use this device").assertIsEnabled()
            compose.runOnIdle {
                val model = requireNotNull(target)
                stateOf(model).value = model.state.rediscoverAfterDisconnect()
            }
            compose.waitForIdle()
            check(compose.onAllNodesWithText("Select connected device").fetchSemanticsNodes().isEmpty())
            check(compose.onAllNodesWithText("Use this device").fetchSemanticsNodes().isEmpty())
        })
    }

    @Test
    fun captureDirectRecoveryDeviceChoices() {
        captureUnknownDevicePicker()
    }

    private fun captureUnknownDevicePicker() {
        setTheme(themes.first { it.fileName == "custom-black" })
        seed(emptyList(), includePinnedFactory = false)
        capture("custom-black-unknown-device-selection", change = {
            connectedFor(DeviceFamily.UNKNOWN).invoke(this).copy(
                recoverySelectionRequired = true,
                deviceClassification = classifyDeviceFamily(null, "4_1_0_0", 0xEB6014, true),
            )
        }, navigate = {
            compose.onNodeWithText("Select connected device").assertIsDisplayed()
            compose.onNodeWithText("Use this device").assertIsNotEnabled()
            DeviceFamily.entries.filter { it != DeviceFamily.UNKNOWN }.forEach { family ->
                compose.onNodeWithText(family.displayName).performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithText(DeviceFamily.MKE_P37.displayName).performScrollTo().performClick()
            compose.onNodeWithText("Use this device").assertIsEnabled().performClick()
            compose.onNodeWithText("MKE-P37 · Recovery mode", substring = true).assertIsDisplayed()
            compose.mainClock.advanceTimeBy(1000)
            SystemClock.sleep(600)
            instrumentation.waitForIdleSync()
        })
    }

    private fun captureTagEditDialog() {
        setTheme(themes.first { it.fileName == "custom-black" })
        seed(listOf("FM-1_909"), includePinnedFactory = false)
        capture("custom-black-tag-edit-dialog", connected, navigate = {
            compose.onNodeWithContentDescription("Actions for FM-1", substring = true).performClick()
            compose.onNodeWithText("Tag").performClick()
            compose.onNodeWithText("Custom tag").assertIsDisplayed()
            compose.onNodeWithText("Give your backup a custom tag so you know which device it came from.")
                .assertIsDisplayed()
            compose.onNodeWithText("Device tag").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(1000)
            SystemClock.sleep(600)
            instrumentation.waitForIdleSync()
        })
    }

    @Test
    fun captureMixedLibraryFirmwareLabels() {
        captureMixedFm1Library()
    }

    private fun captureMixedFm1Library() {
        setTheme(themes.first { it.fileName == "custom-black" })
        val onyxBuild = "Pocket_AMP_Onyx_1.01-2611051015"
        val firmware = linkedMapOf(
            "JIANT V10.6c" to BackupMetadata(
                classification = classifyDeviceFamily("FM-1_909", "4_1_0_0", 0x856014, false),
                editorFirmwareLabel = "JIANT V10.6c",
            ),
            "SLOOP Pro 2.8.4" to BackupMetadata(
                classification = classifyDeviceFamily("SMK-37 Pro_900", "4_1_0_0", 0xEB6014, true),
                editorFirmwareLabel = "FELUCCA SLOOP PRO 2.8.4",
            ),
            "OpenPocket v33" to BackupMetadata(
                classification = classifyDeviceFamily("PocketX_933", "2_1_0_0", 0x856014, false),
                editorFirmwareLabel = "v33",
            ),
        )
        val backups = firmware.keys.toList() + onyxBuild
        seed(
            backups,
            familyOverrides = mapOf(
                onyxBuild to DeviceFamily.POCKET_AMP,
            ),
            firmwareLabelOverrides = mapOf(onyxBuild to "Pocket AMP_105"),
            metadataOverrides = firmware,
        )
        capture("custom-black-mixed-fm1-connected", connected) {
            backups.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
            val entries = store.list()
            firmware.forEach { (name, metadata) ->
                val entry = entries.single { it.firmwareIdentity == name }
                check(entry.metadata == metadata)
                check(entry.taggedFamily == metadata.classification.family)
                check(!entry.familyIsUserTagged)
                check(entry.metadata.userFirmwareLabel == null)
                compose.onNodeWithText(
                    requireNotNull(metadata.classification.rawMidiIdentity), substring = true,
                ).assertIsDisplayed()
            }
            compose.onNodeWithText("Pocket AMP_105", substring = true).assertIsDisplayed()
            compose.onNodeWithText("PA").assertIsDisplayed()
            check(compose.onAllNodesWithText("Unknown", substring = true).fetchSemanticsNodes().isEmpty())
            check(compose.onAllNodesWithText("Device type unknown", substring = true).fetchSemanticsNodes().isEmpty())
        }
    }

}
