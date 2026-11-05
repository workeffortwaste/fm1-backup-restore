package com.workeffortwaste

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.workeffortwaste.protocol.BotTransport
import com.workeffortwaste.protocol.BootloaderVerifier
import com.workeffortwaste.protocol.BootloaderRecovery
import com.workeffortwaste.protocol.Fm1Protocol
import com.workeffortwaste.protocol.Fm1Identity
import com.workeffortwaste.protocol.FlashBackup
import com.workeffortwaste.protocol.FlashInfo
import com.workeffortwaste.protocol.FlashRestore
import com.workeffortwaste.protocol.MidiIdentityEvidence
import com.workeffortwaste.protocol.ProtocolException
import com.workeffortwaste.protocol.RestoreImage
import com.workeffortwaste.protocol.RestoreVerificationException
import com.workeffortwaste.protocol.V15RecoveryImage
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class DeviceMode(val label: String) {
    SEARCHING("Discovering supported device"),
    MIDI("MIDI mode"),
    DOWNLOAD("Recovery mode"),
    MULTIPLE("Multiple supported connections \u00b7 Connect only one device"),
}

internal fun discoverDeviceMode(midiDevices: Int, downloadDevices: Int): DeviceMode = when {
    midiDevices + downloadDevices > 1 -> DeviceMode.MULTIPLE
    downloadDevices == 1 -> DeviceMode.DOWNLOAD
    midiDevices == 1 -> DeviceMode.MIDI
    else -> DeviceMode.SEARCHING
}

internal fun primaryBackupName(editorLabel: String?, mUpgradeIdentity: String?): String? =
    editorLabel?.takeIf { it.isNotBlank() } ?: mUpgradeIdentity

internal const val V15_RECOVERY_BACKUP_NAME = "FM-1_015 RECOVERY"
internal const val V15_RECOVERY_FIRMWARE_TAG = "FM-1_015"

internal fun v15RecoveryBackupMetadata() = BackupMetadata(
    userFamily = DeviceFamily.FM1,
    userFirmwareLabel = V15_RECOVERY_FIRMWARE_TAG,
)

internal fun shouldUpgradeLegacyBackupLibrary(
    isDebugBuild: Boolean,
    migrationDone: Boolean,
    hasUnknownBackups: Boolean,
): Boolean = !migrationDone || isDebugBuild && hasUnknownBackups

data class BackupLibraryUpgradeProgress(
    val completed: Int = 0,
    val total: Int? = null,
) {
    val fraction: Float?
        get() = total?.takeIf { it > 0 }?.let { (completed.toFloat() / it).coerceIn(0f, 1f) }
    val message: String
        get() = when (total) {
            null -> "Checking saved backups for device type"
            0 -> "No saved backups to update"
            else -> "Checking backup ${completed.coerceAtMost(total)} of $total"
        }
}

enum class FlashOperation(private val actionTitle: String, val label: String) {
    BACKUP("Backing up", "Backup"),
    RESTORE("Restoring", "Restore");

    fun displayTitle(deviceName: String): String = "$actionTitle $deviceName"

    val title: String get() = displayTitle("FM-1")
}

enum class TransferOutcome {
    SUCCESS,
    FAILURE,
    CANCELLED,
}

data class SelectedImport(val sourceName: String, val sha256: String)

data class FirmwareImportState(
    val showDialog: Boolean = false,
    val working: Boolean = false,
    val cancelling: Boolean = false,
    val completed: Boolean = false,
    val message: String = "",
)

data class FirmwareExportState(
    val showDialog: Boolean = false,
    val entries: List<BackupEntry> = emptyList(),
    val working: Boolean = false,
    val cancelling: Boolean = false,
    val progress: Int = 0,
    val message: String = "",
    val completed: Boolean = false,
    val singleBackup: Boolean = false,
)

data class V15RecoveryState(
    val showDialog: Boolean = false,
    val working: Boolean = false,
    val progress: Int? = null,
    val phase: String = "",
    val message: String = "",
    val completed: Boolean = false,
)

data class BootloaderVerificationState(
    val showDialog: Boolean = false,
    val working: Boolean = false,
    val bytesRead: Int = 0,
    val sha256: String = "",
    val matchesStockV15: Boolean? = null,
    val identity: String? = null,
    val matchesPublishedRelease: Boolean = false,
    val message: String = "",
    val recovery: EmergencyRecoveryState? = null,
) {
    val hasKnownHash: Boolean
        get() = matchesStockV15 == true || (identity != null && matchesPublishedRelease)
    val canRecover: Boolean
        get() = canRecover(debugOverride = false)

    /** Debug bypasses only hash recognition; the caller must separately require the verified connection. */
    fun canRecover(debugOverride: Boolean): Boolean =
        sha256.isNotEmpty() && (!hasKnownHash || debugOverride) && !working
}

enum class EmergencyRecoveryStage { DOWNLOADING, CONFIRM, FLASHING, SUCCESS, FAILURE }

data class EmergencyRecoveryState(
    val stage: EmergencyRecoveryStage = EmergencyRecoveryStage.DOWNLOADING,
    val phase: String = "Downloading stock bootloader",
    val progress: Float? = 0f,
    val message: String = "",
)

internal fun String.withTerminalPeriod(): String = when {
    isBlank() || endsWith(".") || endsWith("...") || endsWith("\u2026") -> this
    else -> trimEnd().trimEnd('!', '?') + "."
}

internal fun String.asStatusHeading(): String = trim().trimEnd('.', ':', '!', '?', '\u2026')

internal fun String.asStatusDetail(): String {
    val content = trim().trimEnd('.', '!', '?', '\u2026')
    return if (content.isEmpty()) "" else "$content."
}

data class StatusMessage(val heading: String = "", val secondary: String = "") {
    val isEmpty: Boolean get() = heading.isEmpty() && secondary.isEmpty()
    val isNotEmpty: Boolean get() = !isEmpty

    fun asNotificationText(): String = when {
        isEmpty -> ""
        secondary.isEmpty() -> heading.withTerminalPeriod()
        else -> "${heading.withTerminalPeriod()} $secondary"
    }
}

internal fun statusMessage(heading: String, secondary: String): StatusMessage = StatusMessage(
    heading.asStatusHeading(),
    secondary.asStatusDetail(),
)

internal fun discoveryStatusMessage(noDevice: Boolean): StatusMessage = if (noDevice) {
    statusMessage("No supported device connected", "Refresh or connect a device via USB")
} else {
    statusMessage("Discovering supported device", "Searching for USB connection")
}

private val CONNECTION_STATUS_PREFIXES = listOf(
    "Reported firmware", "Firmware identity unavailable", "Waiting for ", "Connecting to ",
    "Ready to connect to ", "Returning ", "Switching ",
    "Waiting for USB permission", "USB permission denied", "USB permission request cancelled",
    "Connection failed", "Firmware query failed", "Bootloader request failed", "Waiting for MIDI connection",
    "Bootloader request cancelled", "Stopped waiting",
)

private fun isConnectionStatus(heading: String): Boolean =
    heading.endsWith(" successfully connected") || CONNECTION_STATUS_PREFIXES.any(heading::startsWith)

internal fun BackupState.afterDiscoveryTimeout(): BackupState {
    if (deviceMode != DeviceMode.SEARCHING || findingBootloader || busy) return this
    return copy(
        discoveryTimedOut = true,
        status = if (isConnectionStatus(status.heading)) {
            StatusMessage()
        } else {
            status
        },
    )
}

internal fun BackupState.withCurrentBackupHash(): BackupState =
    if (hash.isNotEmpty() && (busy || !ready || status.heading != "Backup saved")) copy(hash = "") else this

internal fun BackupState.rediscoverAfterDisconnect(): BackupState = copy(
    device = null,
    deviceMode = DeviceMode.SEARCHING,
    multipleDevices = false,
    ready = false,
    discoveryTimedOut = false,
    autoConnectPending = false,
    firmwareIdentity = null,
    editorFirmwareLabel = null,
    deviceClassification = DeviceClassification(),
    manualFamilySelection = null,
    status = StatusMessage(),
    hash = "",
)

internal fun BackupState.returnToMidi(): BackupState {
    val deviceName = connectedDeviceDisplayName
    return copy(
        device = null,
        multipleDevices = false,
        deviceMode = DeviceMode.SEARCHING,
        discoveryActive = true,
        busy = true,
        discoveryTimedOut = false,
        findingBootloader = false,
        autoConnectPending = false,
        ready = false,
        readingFirmwareIdentity = false,
        status = statusMessage("Returning $deviceName to MIDI mode", "Restarting firmware"),
        firmwareIdentity = null,
        editorFirmwareLabel = null,
        deviceClassification = DeviceClassification(),
        manualFamilySelection = null,
        progress = 0f,
        transfer = null,
        progressKnown = false,
        restoreRetryAvailable = false,
        hash = "",
    )
}

internal fun BackupState.withManualFamilySelection(family: DeviceFamily): BackupState {
    require(family != DeviceFamily.UNKNOWN)
    val conflict = deviceClassification.origin == FamilyEvidenceOrigin.CONFLICT
    val classification = if (conflict) deviceClassification else deviceClassification.copy(
        family = family,
        origin = FamilyEvidenceOrigin.MANUAL,
        confidence = FamilyEvidenceConfidence.LOW,
        explanation = "Family was manually selected and is not verified",
    )
    return copy(
        deviceClassification = classification,
        manualFamilySelection = family,
        status = if (conflict) {
            statusMessage("Conflicting device evidence — backup only", deviceClassification.explanation)
        } else {
            statusMessage("Device family manually selected", "Manually selected — not verified")
        },
    )
}

/**
 * @property device Sole eligible USB candidate, or null when missing/ambiguous.
 * @property multipleDevices Whether discovery requires the user to disconnect other candidates.
 * @property busy Permission, MIDI, connection, backup, or restore work currently blocks editing.
 * @property ready Identity, NOR metadata, and the sample read succeeded for the active session.
 * @property status Structured operation heading and secondary guidance.
 * @property progress Fraction completed within the current transfer or verification phase.
 * @property hash Verified SHA-256, withheld until the operation's complete verification succeeds.
 * @property hashLabel Names what the displayed hash covers.
 */
data class BackupState(
    val device: UsbDevice? = null,
    val multipleDevices: Boolean = false,
    val deviceMode: DeviceMode = DeviceMode.SEARCHING,
    val discoveryActive: Boolean = false,
    val discoveryTimedOut: Boolean = false,
    val findingBootloader: Boolean = false,
    /** Set once after Connect to Device finds the bootloader USB interface; the UI then connects. */
    val autoConnectPending: Boolean = false,
    val busy: Boolean = false,
    val readingFirmwareIdentity: Boolean = false,
    val ready: Boolean = false,
    val status: StatusMessage = StatusMessage(),
    val firmwareIdentity: String? = null,
    val editorFirmwareLabel: String? = null,
    val deviceClassification: DeviceClassification = DeviceClassification(),
    val manualFamilySelection: DeviceFamily? = null,
    val connectionDiagnostics: String = "",
    val backups: List<BackupEntry> = emptyList(),
    val pinnedBackupIds: Set<String> = emptySet(),
    val libraryLoading: Boolean = true,
    val libraryError: String = "",
    val legacyBackupUpgrade: BackupLibraryUpgradeProgress? = null,
    val progress: Float = 0f,
    val transfer: FlashOperation? = null,
    val progressKnown: Boolean = false,
    val selectedImport: SelectedImport? = null,
    val restoreRetryAvailable: Boolean = false,
    val hash: String = "",
    val hashLabel: String = "SHA-256",
) {
    val showTransferProgress: Boolean get() = busy && transfer != null
    val isVerifiedFm1RecoverySession: Boolean
        get() = ready && deviceMode == DeviceMode.DOWNLOAD &&
                deviceClassification.family == DeviceFamily.FM1 &&
                deviceClassification.origin in setOf(
                    FamilyEvidenceOrigin.MIDI_PLUS_RECOVERY,
                    FamilyEvidenceOrigin.RECOVERY_SPI_JEDEC,
                )
    val canOfferFm1RecoverySnapshot: Boolean
        get() = when (deviceMode) {
            DeviceMode.SEARCHING -> true
            DeviceMode.MIDI -> midiFamilyHint == DeviceFamily.FM1
            DeviceMode.DOWNLOAD -> isVerifiedFm1RecoverySession
            DeviceMode.MULTIPLE -> false
        }
    val pinnedBackups: List<BackupEntry> get() = backups.filter { it.id in pinnedBackupIds }
    val unpinnedBackups: List<BackupEntry> get() = backups.filterNot { it.id in pinnedBackupIds }
    val midiFamilyHint: DeviceFamily get() = DeviceFamily.fromMidiIdentity(firmwareIdentity)
    val requiresManualFamilySelection: Boolean
        get() = manualFamilySelection == null && deviceClassification.origin in setOf(
            FamilyEvidenceOrigin.UNKNOWN,
            FamilyEvidenceOrigin.MIDI_HINT,
            FamilyEvidenceOrigin.CONFLICT,
        )
    val canBackUpWithFamily: Boolean
        get() = when {
            deviceClassification.origin == FamilyEvidenceOrigin.CONFLICT -> manualFamilySelection != null
            requiresManualFamilySelection -> false
            else -> effectiveDeviceFamily != DeviceFamily.UNKNOWN
        }
    val effectiveDeviceFamily: DeviceFamily
        get() = when {
            deviceClassification.origin == FamilyEvidenceOrigin.CONFLICT -> DeviceFamily.UNKNOWN
            manualFamilySelection != null -> manualFamilySelection
            deviceClassification.family != DeviceFamily.UNKNOWN -> deviceClassification.family
            else -> midiFamilyHint
        }
    val connectedDeviceDisplayName: String
        get() = (manualFamilySelection ?: effectiveDeviceFamily)
            .takeIf { it != DeviceFamily.UNKNOWN }?.displayName ?: "???"

    val deviceStatus: String
        get() = when {
            deviceMode == DeviceMode.MULTIPLE -> deviceMode.label
            deviceClassification.origin == FamilyEvidenceOrigin.CONFLICT ->
                manualFamilySelection?.let { "${it.displayName} · Conflicting evidence · Backup only" }
                    ?: "??? · Conflicting device evidence"
                deviceClassification.origin != FamilyEvidenceOrigin.CONFLICT &&
                    midiFamilyHint != DeviceFamily.UNKNOWN && !firmwareIdentity.isNullOrBlank() &&
                    (findingBootloader || deviceMode == DeviceMode.MIDI || deviceMode == DeviceMode.DOWNLOAD) -> {
                val detail = if (deviceMode == DeviceMode.DOWNLOAD) "Recovery mode" else "MIDI mode"
                val displayIdentity = editorFirmwareLabel?.takeIf { it.isNotBlank() } ?: firmwareIdentity
                "$connectedDeviceDisplayName \u00b7 $displayIdentity$manualQualifier \u00b7 $detail"
            }
            findingBootloader && deviceMode != DeviceMode.DOWNLOAD ->
                "$connectedDeviceDisplayName$manualQualifier \u00b7 MIDI mode"
            ready && deviceMode == DeviceMode.DOWNLOAD ->
                "$connectedDeviceDisplayName$manualQualifier \u00b7 Recovery mode"
            deviceMode == DeviceMode.MIDI -> "$connectedDeviceDisplayName$manualQualifier \u00b7 MIDI mode"
            deviceMode == DeviceMode.DOWNLOAD -> "$connectedDeviceDisplayName$manualQualifier \u00b7 Recovery mode"
            else -> deviceMode.label
        }

    private val manualQualifier: String
        get() = if (manualFamilySelection != null || deviceClassification.origin == FamilyEvidenceOrigin.MANUAL) {
            " \u00b7 Manual"
        } else ""
}

/**
 * Coordinates the main-thread UI and one serialized worker owning all protocol I/O.
 * Owned by TransferService, not an activity, so UI lifecycle changes cannot stop flash work.
 */
class BackupModel(
    private val application: Application,
    private val beginTransfer: (FlashOperation) -> Boolean,
    private val endTransfer: (TransferOutcome) -> Unit,
) {
    /** Shared with the activity solely for Android's user-mediated USB permission request. */
    val manager: UsbManager = application.getSystemService(UsbManager::class.java)

    /** Compose observes snapshots; background work must publish through [post]. */
    var state by mutableStateOf(BackupState())
        private set
    var v15RecoveryState by mutableStateOf(V15RecoveryState())
        private set
    var firmwareExportState by mutableStateOf(FirmwareExportState())
        private set
    var firmwareImportState by mutableStateOf(FirmwareImportState())
        private set
    var bootloaderVerificationState by mutableStateOf(BootloaderVerificationState())
        private set

    /** Delivers worker results to Compose and serializes them with activity/broadcast callbacks. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Serializes MIDI, loader startup, transfers, and cleanup without concurrent USB commands. */
    private val worker = Executors.newSingleThreadExecutor()
    private val recoveryDownloader = Executors.newSingleThreadExecutor()

    /** Main-thread cancellation flag checked by the worker between bounded I/O commands. */
    private val cancelled = AtomicBoolean()

    /** Worker-owned session handles, cleared together so a failed session cannot be reused. */
    private var transport: BotTransport? = null

    /** Command encoder for the current worker-owned transport. */
    private var protocol: Fm1Protocol? = null

    /** Metadata retained only after a successful NOR probe; required again when backing up. */
    private var flash: FlashInfo? = null

    /** Device path is also read on the main thread to correlate detach and refresh notifications. */
    @Volatile
    private var activeDeviceName: String? = null

    /** Main-thread permission correlation; late responses after cancellation must be ignored. */
    private var pendingPermission: String? = null
    private val backupStore by lazy { BackupStore(File(application.filesDir, "backups")) }
    private val backupPreferences by lazy { application.getSharedPreferences("backup_library", Context.MODE_PRIVATE) }
    private var selectedImportBytes: ByteArray? = null
    /** Retries must keep the original source family, target family, and USB path. */
    private data class RestoreAttempt(
        val source: () -> InputStream,
        val sourceFamily: DeviceFamily,
        val targetFamily: DeviceFamily,
        val targetDeviceName: String,
    )
    private var retryRestoreAttempt: RestoreAttempt? = null
    private var firmwareDeviceName: String? = null
    private var firmwareMidiId: Int? = null
    private var queriedMidiId: Int? = null

    /** USB path of a loader this app started and verified; cleared whenever that device goes away. */
    @Volatile
    private var loaderDeviceName: String? = null
    private var verifiedBootloaderDeviceName: String? = null
    private var recoveryBootloader: ByteArray? = null

    /** Confirmation belongs to one verified USB session; reconnecting requires fresh verification. */
    val emergencyRecoveryConnected: Boolean
        get() = state.isVerifiedFm1RecoverySession && !state.busy &&
                verifiedBootloaderDeviceName != null &&
                state.device?.deviceName == verifiedBootloaderDeviceName &&
                loaderDeviceName == verifiedBootloaderDeviceName
    private var discoveryTimerPending = false
    private val discoveryTimeout = Runnable {
        discoveryTimerPending = false
        if (state.discoveryActive && state.deviceMode == DeviceMode.SEARCHING) {
            if (state.busy || state.findingBootloader) {
                updateDiscoveryTimer(true)
            } else {
                state = state.afterDiscoveryTimeout()
            }
        }
    }

    /** Bounds the indeterminate search; events or an explicit Refresh restart it. */
    private fun updateDiscoveryTimer(searching: Boolean) {
        if (!searching) {
            mainHandler.removeCallbacks(discoveryTimeout)
            discoveryTimerPending = false
            if (state.discoveryTimedOut) state = state.copy(discoveryTimedOut = false)
        } else if (!state.discoveryTimedOut && !discoveryTimerPending) {
            discoveryTimerPending = true
            mainHandler.postDelayed(discoveryTimeout, DISCOVERY_TIMEOUT_MS)
        }
    }

    private val connectionTrace = ArrayDeque<String>()

    private fun traceConnection(message: String) {
        val snapshot = synchronized(connectionTrace) {
            if (connectionTrace.size >= 40) connectionTrace.removeFirst()
            connectionTrace.addLast("${SystemClock.elapsedRealtime()} ms: $message")
            connectionTrace.joinToString("\n")
        }
        Log.i("BackupModel", message)
        post { it.copy(connectionDiagnostics = snapshot) }
    }

    private val midiManager = application.getSystemService(MidiManager::class.java)

    /** Keeps an idle, probed loader session active; only read-only target info is requested. */
    private val keepAlive = object : Runnable {
        override fun run() {
            if (state.ready && (!state.busy ||
                        bootloaderVerificationState.recovery?.stage == EmergencyRecoveryStage.DOWNLOADING)
            ) {
                worker.execute {
                    val active = protocol ?: return@execute
                    try {
                        active.flashInfo()
                    } catch (failure: Exception) {
                        Log.w("BackupModel", "Idle loader keepalive failed", failure)
                        try {
                            closeSession()
                        } catch (closeFailure: IOException) {
                            Log.w("BackupModel", "Could not close failed idle session", closeFailure)
                        }
                        post { current ->
                            if (current.busy &&
                                bootloaderVerificationState.recovery?.stage != EmergencyRecoveryStage.DOWNLOADING
                            ) current else current.copy(
                                ready = false,
                                status = if (bootloaderVerificationState.recovery != null) current.status
                                else statusMessage(
                                    "Connection failed",
                                    "${current.connectedDeviceDisplayName.takeUnless { it == "???" } ?: "Device"} stopped responding. Refresh",
                                ),
                            )
                        }
                    }
                }
            }
            mainHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }
    private val midiCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = refresh()
        override fun onDeviceRemoved(device: MidiDeviceInfo) = refresh()
    }

    init {
        val legacyUpgradeDone = backupPreferences.getBoolean(LEGACY_BACKUP_UPGRADE_KEY, false)
        state = state.copy(
            pinnedBackupIds = backupPreferences.getStringSet(PINNED_BACKUP_IDS, emptySet()).orEmpty().toSet(),
            legacyBackupUpgrade = if (legacyUpgradeDone) null else BackupLibraryUpgradeProgress(),
        )
        // The transport-specific callback overload requires API 33.
        @Suppress("DEPRECATION")
        midiManager?.registerDeviceCallback(midiCallback, mainHandler)
        refresh()
        mainHandler.postDelayed(keepAlive, KEEPALIVE_INTERVAL_MS)
        worker.execute {
            try {
                val entries = backupStore.list()
                val shouldUpgrade = shouldUpgradeLegacyBackupLibrary(
                    BuildConfig.DEBUG,
                    legacyUpgradeDone,
                    entries.any { it.needsLegacyFamilyUpgrade },
                )
                val backups = if (shouldUpgrade) {
                    mainHandler.post {
                        state = state.copy(
                            legacyBackupUpgrade = BackupLibraryUpgradeProgress(total = entries.size),
                        )
                    }
                    backupStore.upgradeLegacyFamilies(entries) { completed, total ->
                        mainHandler.post {
                            state = state.copy(legacyBackupUpgrade = BackupLibraryUpgradeProgress(completed, total))
                        }
                    }.also {
                        if (!legacyUpgradeDone && !backupPreferences.edit()
                                .putBoolean(LEGACY_BACKUP_UPGRADE_KEY, true).commit()
                        ) {
                            Log.w("BackupModel", "Could not persist legacy backup upgrade marker")
                        }
                    }
                } else entries
                post {
                    val pinned = it.pinnedBackupIds intersect backups.mapTo(mutableSetOf()) { entry -> entry.id }
                    if (pinned != it.pinnedBackupIds) savePinnedBackups(pinned)
                    it.copy(
                        backups = backups,
                        pinnedBackupIds = pinned,
                        libraryLoading = false,
                        legacyBackupUpgrade = null,
                    )
                }
            } catch (failure: Exception) {
                post {
                    it.copy(
                        libraryLoading = false,
                        legacyBackupUpgrade = null,
                        libraryError = "Could not load backups: ${failure.message}",
                    )
                }
            }
        }
    }

    /** Read-only enumeration for diagnosing ID matching without opening ports or sending commands. */
    fun discoveryDiagnostics(): String {
        if (!state.discoveryActive) return "Finish Welcome and allow background operation in Settings first."
        val usb = manager.deviceList.values.sortedBy { it.deviceName }.joinToString("\n") { device ->
            val interfaces = (0 until device.interfaceCount).joinToString(", ") { index ->
                val item = device.getInterface(index)
                "${item.interfaceClass}/${item.interfaceSubclass}/${item.interfaceProtocol}"
            }
            "USB %04X:%04X; interfaces: %s".format(device.vendorId, device.productId, interfaces)
        }.ifEmpty { "USB host: no USB devices found." }
        val midi = midiManager?.let(AndroidFm1Midi::diagnostics) ?: "Android MIDI service unavailable."
        val midiEvidence = "M-UPGRADE identity: ${state.firmwareIdentity ?: "Unknown"}; " +
            "editor label: ${state.editorFirmwareLabel ?: "Unknown"}"
        val classification = state.deviceClassification
        val familyEvidence = "Family: ${classification.family.displayName}; origin=${classification.origin}; " +
            "confidence=${classification.confidence}; SPI=${classification.spiSetting ?: "Unknown"}; " +
            "JEDEC=${classification.jedecId?.let { "%06X".format(it) } ?: "Unknown"}; " +
            "SMK trim marker=${classification.smkTrimMarkerPresent}; " +
            "manual family=${state.manualFamilySelection?.displayName ?: "None"}; ${classification.explanation}"
        return "Supported MIDI IDs: ${com.workeffortwaste.protocol.Fm1BootloaderMidi.SUPPORTED_USB_IDS}" +
                "; interface 1/3\n\n$usb\n\n$midi\n\nConnection trace:\n" +
            "$midiEvidence\n$familyEvidence\n${state.connectionDiagnostics.ifEmpty { "No connection attempt recorded." }}"
    }

    fun dismissBootloaderVerification() {
        if (!bootloaderVerificationState.working) {
            bootloaderVerificationState = BootloaderVerificationState()
            recoveryBootloader = null
            verifiedBootloaderDeviceName = null
        }
    }

    /** Reads and hashes the protected 16 KiB bootloader region without modifying flash. */
    fun verifyBootloader() {
        if (!state.isVerifiedFm1RecoverySession || state.busy || bootloaderVerificationState.working) return
        cancelled.set(false)
        verifiedBootloaderDeviceName = null
        recoveryBootloader = null
        bootloaderVerificationState = BootloaderVerificationState(showDialog = true, working = true)
        state = state.copy(busy = true)
        worker.execute {
            try {
                val activeProtocol = protocol ?: throw ProtocolException("Connect to the FM-1 first")
                val deviceName = activeDeviceName
                val result = BootloaderVerifier.read(activeProtocol::readFlash, ::checkCancelled) { bytesRead ->
                    mainHandler.post {
                        bootloaderVerificationState = bootloaderVerificationState.copy(bytesRead = bytesRead)
                    }
                }
                mainHandler.post {
                    state = state.copy(busy = false)
                    verifiedBootloaderDeviceName = deviceName.takeIf {
                        state.ready && state.device?.deviceName == it && loaderDeviceName == it
                    }
                    bootloaderVerificationState = bootloaderVerificationState.copy(
                        working = false,
                        sha256 = result.sha256,
                        matchesStockV15 = result.matchesStockV15,
                        identity = result.identity,
                        matchesPublishedRelease = result.matchesPublishedRelease,
                        message = result.message,
                    )
                }
            } catch (failure: Exception) {
                val wasCancelled = failure is CancellationException
                if (!wasCancelled) {
                    try {
                        closeSession()
                    } catch (closeFailure: Exception) {
                        Log.w("BackupModel", "Could not close bootloader verification session", closeFailure)
                    }
                }

                mainHandler.post {
                    state = state.copy(
                        busy = false,
                        ready = wasCancelled && state.ready,
                    )
                    bootloaderVerificationState = bootloaderVerificationState.copy(
                        working = false,
                        message = if (wasCancelled) "Verification cancelled. No flash was changed."
                        else "Could not verify the bootloader: ${failure.message ?: failure.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    /** Downloads on a separate executor so the USB worker can keep the RAM loader alive while waiting. */
    fun prepareEmergencyRecovery() {
        if (!bootloaderVerificationState.canRecover(BuildConfig.DEBUG) || !emergencyRecoveryConnected ||
            bootloaderVerificationState.recovery != null
        ) return
        recoveryBootloader = null
        bootloaderVerificationState = bootloaderVerificationState.copy(
            working = true, recovery = EmergencyRecoveryState(),
        )
        state = state.copy(busy = true)
        recoveryDownloader.execute {
            try {
                var lastProgress = -1
                val bytes = downloadV15Package { downloaded ->
                    val percent = (downloaded * 100 / V15RecoveryImage.PACKAGE_SIZE).toInt()
                    if (percent != lastProgress) {
                        lastProgress = percent
                        updateEmergencyRecovery { it.copy(progress = percent / 100f) }
                    }
                }
                updateEmergencyRecovery { it.copy(phase = "Verifying stock bootloader", progress = null) }
                val bootloader = BootloaderRecovery.fromPackage(bytes)
                mainHandler.post {
                    recoveryBootloader = bootloader
                    state = state.copy(busy = false)
                    refresh()
                    bootloaderVerificationState = bootloaderVerificationState.copy(
                        working = false,
                        recovery = EmergencyRecoveryState(stage = EmergencyRecoveryStage.CONFIRM),
                    )
                }
            } catch (failure: Exception) {
                Log.w("BackupModel", "Could not download or validate emergency recovery data", failure)
                mainHandler.post {
                    state = state.copy(busy = false)
                    bootloaderVerificationState = bootloaderVerificationState.copy(
                        working = false,
                        recovery = EmergencyRecoveryState(
                            stage = EmergencyRecoveryStage.FAILURE,
                            message = "Failed to download recovery data from M-VAVE repo.",
                        ),
                    )
                }
            }
        }
    }

    private fun updateEmergencyRecovery(update: (EmergencyRecoveryState) -> EmergencyRecoveryState) {
        mainHandler.post {
            bootloaderVerificationState.recovery?.let { current ->
                bootloaderVerificationState = bootloaderVerificationState.copy(recovery = update(current))
            }
        }
    }

    /**
     * Starts only from the risk-confirmation stage and rechecks the same recovery session before I/O.
     * Uses foreground-transfer protection without publishing recovery status to the main information box.
     * Leaves the RAM loader running after either outcome; no automatic reboot is requested.
     */
    fun restoreEmergencyBootloader() {
        if (bootloaderVerificationState.recovery?.stage != EmergencyRecoveryStage.CONFIRM ||
            !bootloaderVerificationState.canRecover(BuildConfig.DEBUG) || !emergencyRecoveryConnected
        ) return
        val image = recoveryBootloader ?: return
        val deviceName = verifiedBootloaderDeviceName ?: return
        if (!batterySetupApproved { message -> failEmergencyRecoveryStart(message) }) return
        if (!beginTransfer(FlashOperation.RESTORE)) return
        cancelled.set(false)
        bootloaderVerificationState = bootloaderVerificationState.copy(
            working = true,
            recovery = EmergencyRecoveryState(
                stage = EmergencyRecoveryStage.FLASHING, phase = "Checking recovery connection", progress = null,
            ),
        )
        state = state.copy(busy = true)
        worker.execute {
            var mutationStarted = false
            var outcome = TransferOutcome.FAILURE
            try {
                val active = protocol ?: throw ProtocolException("The recovery connection is no longer available.")
                val checkConnection = {
                    val live = AndroidUsb.devices(manager).singleOrNull()
                    if (live?.deviceName != deviceName || activeDeviceName != deviceName ||
                        loaderDeviceName != deviceName || !manager.hasPermission(live)
                    ) throw ProtocolException("The FM-1 is no longer connected in recovery mode.")
                }
                val hash = BootloaderRecovery(active).run(
                    image,
                    forceRewrite = BuildConfig.DEBUG,
                    checkConnection = checkConnection,
                    saveOriginal = { original ->
                        val file = File.createTempFile("bootloader-before-recovery-", ".bin", application.filesDir)
                        file.outputStream().use { output ->
                            output.write(original)
                            output.fd.sync()
                        }
                        if (!file.readBytes().contentEquals(original)) {
                            throw IOException("Could not verify the saved original bootloader.")
                        }
                        Log.i("BackupModel", "Saved original bootloader to ${file.name}")
                    },
                    mutationStarting = { mutationStarted = true },
                    progress = { phase, bytes ->
                        updateEmergencyRecovery {
                            it.copy(phase = phase, progress = bytes.toFloat() / FlashRestore.PROTECTED_SIZE)
                        }
                    },
                )
                outcome = TransferOutcome.SUCCESS
                mainHandler.post {
                    state = state.copy(busy = false)
                    bootloaderVerificationState = bootloaderVerificationState.copy(
                        working = false, sha256 = hash, matchesStockV15 = true,
                        identity = null, matchesPublishedRelease = false,
                        recovery = EmergencyRecoveryState(
                            stage = EmergencyRecoveryStage.SUCCESS,
                            message = "Stock v15 bootloader restored and verified.\n\nFirmware, projects and settings outside the boot area were not changed. The FM-1 remains in recovery mode.",
                        ),
                    )
                }
            } catch (failure: Exception) {
                Log.e("BackupModel", "Emergency bootloader recovery failed", failure)
                // Never reset after a failed write; the RAM loader may be the only remaining access.
                val warning = if (mutationStarted) {
                    "The bootloader may be incomplete. Do not disconnect or restart the FM-1; additional hardware may be needed to recover it."
                } else {
                    "No bootloader erase or write was requested."
                }
                mainHandler.post {
                    state = state.copy(
                        busy = false, ready = state.ready && failure is RestoreVerificationException,
                    )
                    verifiedBootloaderDeviceName = null
                    bootloaderVerificationState = bootloaderVerificationState.copy(
                        working = false,
                        recovery = EmergencyRecoveryState(
                            stage = EmergencyRecoveryStage.FAILURE,
                            message = "Emergency recovery failed: ${failure.message ?: failure.javaClass.simpleName}. $warning",
                        ),
                    )
                }

            } finally {
                mainHandler.post {
                    recoveryBootloader = null
                    endTransfer(outcome)
                }
            }
        }
    }

    /** Refreshes candidates without guessing which device to use when several match. */
    fun refresh() {
        val setupComplete = application.getSharedPreferences("setup", Context.MODE_PRIVATE)
            .getBoolean("complete", false)
        val batteryExempt = application.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(application.packageName)
        if (!setupComplete || !batteryExempt) {
            if (!state.busy) {
                state = state.copy(
                    device = null, multipleDevices = false, deviceMode = DeviceMode.SEARCHING,
                    ready = false, discoveryActive = false, firmwareIdentity = null,
                    editorFirmwareLabel = null,
                    deviceClassification = DeviceClassification(),
                    manualFamilySelection = null,
                )
            }
            updateDiscoveryTimer(false)
            return
        }
        val devices = AndroidUsb.devices(manager)
        val device = devices.singleOrNull()
        val midiDevices = midiManager?.let(AndroidFm1Midi::devices).orEmpty()
        val mode = discoverDeviceMode(midiDevices.size, devices.size)
        val retainFirmware = (state.findingBootloader && mode != DeviceMode.MULTIPLE) ||
                (device?.deviceName == firmwareDeviceName && mode == DeviceMode.DOWNLOAD) ||
                (mode == DeviceMode.MIDI && midiDevices.singleOrNull()?.id == firmwareMidiId)
        val staleConnectionStatus = mode == DeviceMode.SEARCHING && !state.busy && !state.findingBootloader &&
            isConnectionStatus(state.status.heading)
        if (mode != DeviceMode.DOWNLOAD || device?.deviceName != loaderDeviceName) loaderDeviceName = null
        state = state.copy(
            device = device,
            multipleDevices = mode == DeviceMode.MULTIPLE,
            deviceMode = mode,
            discoveryActive = true,
            firmwareIdentity = if (retainFirmware) state.firmwareIdentity else null,
            editorFirmwareLabel = if (retainFirmware) state.editorFirmwareLabel else null,
            deviceClassification = if (retainFirmware) state.deviceClassification else DeviceClassification(),
            manualFamilySelection = if (retainFirmware) state.manualFamilySelection else null,
            ready = state.ready && mode == DeviceMode.DOWNLOAD && device?.deviceName == activeDeviceName,
            // A removed FM-1 must fall through to the no-connection state, not stale text.
            status = if (staleConnectionStatus) StatusMessage() else state.status,
        ).withCurrentBackupHash()
        if (!state.ready) verifiedBootloaderDeviceName = null
        updateDiscoveryTimer(mode == DeviceMode.SEARCHING)
        if (mode != DeviceMode.MIDI) {
            queriedMidiId = null
        } else if (!state.busy) {
            // Probe each newly seen MIDI device once, including one already attached at launch.
            midiDevices.singleOrNull()?.takeIf { it.id != queriedMidiId }?.let { readDiscoveredIdentity(it.id) }
        }
    }

    /** Keeps a verified connection; otherwise clears stale feedback and rediscovers without rebooting. */
    fun refreshDevice() {
        if (state.busy) return
        refresh()
        if (state.ready) return
        firmwareDeviceName = null
        firmwareMidiId = null
        pendingPermission = null
        cancelled.set(false)
        queriedMidiId = null
        mainHandler.removeCallbacks(discoveryTimeout)
        discoveryTimerPending = false
        state = state.copy(
            discoveryTimedOut = false,
            autoConnectPending = false,
            ready = false,
            firmwareIdentity = null,
            editorFirmwareLabel = null,
            deviceClassification = DeviceClassification(),
            manualFamilySelection = null,
            status = StatusMessage(),
            hash = "",
            progress = 0f,
            findingBootloader = false,
            restoreRetryAvailable = false
        )
        retryRestoreAttempt = null
        worker.execute {
            try {
                closeSession()
            } catch (failure: IOException) {
                Log.w("BackupModel", "Could not close previous USB session during refresh", failure)
            }
        }
        refresh()
    }

    /** Requests an application reboot, closes USB, and lets normal discovery pick up MIDI again. */
    fun disconnect() {
        if (state.busy || !state.ready) return
        val deviceName = state.connectedDeviceDisplayName.takeUnless { it == "???" } ?: "Device"
        firmwareDeviceName = null
        firmwareMidiId = null
        queriedMidiId = null
        pendingPermission = null
        retryRestoreAttempt = null
        mainHandler.removeCallbacks(discoveryTimeout)
        discoveryTimerPending = false
        cancelled.set(false)
        state = state.returnToMidi()
        worker.execute {
            var result = statusMessage("Waiting for MIDI connection", "")
            try {
                val active = protocol ?: throw IOException("The USB session is no longer available")
                try {
                    active.runApp()
                } catch (failure: IOException) {
                    // Like restore, a reset may drop USB before replying; discovery observes the result.
                    Log.i("BackupModel", "$deviceName reboot reply not received", failure)
                } finally {
                    closeSession()
                }
                loaderDeviceName = null
            } catch (failure: Exception) {
                Log.w("BackupModel", "Could not return $deviceName to MIDI mode", failure)
                result = statusMessage(
                    "Return to MIDI mode failed", failure.message ?: "Could not restart $deviceName",
                )
            }
            mainHandler.post {
                state = state.copy(busy = false, status = result)
                refresh()
            }
        }
    }

    private fun readDiscoveredIdentity(midiId: Int) {
        queriedMidiId = midiId
        state = state.copy(
            busy = true,
            readingFirmwareIdentity = true,
            status = StatusMessage(),
        )
        worker.execute {
            try {
                closeSession()
                val identity = AndroidFm1Midi.queryIdentity(application, midiId, ::checkCancelled)
                mainHandler.post {
                    refresh()
                    val stillConnected = state.discoveryActive && state.deviceMode == DeviceMode.MIDI &&
                            midiManager?.let(AndroidFm1Midi::devices)?.singleOrNull()?.id == midiId
                    firmwareMidiId = midiId.takeIf { stillConnected }
                    val familyName = DeviceFamily.fromMidiIdentity(identity.mUpgradeIdentity)
                        .takeIf { it != DeviceFamily.UNKNOWN }?.displayName ?: "???"
                    val status = when {
                        !stillConnected -> StatusMessage()
                        identity.mUpgradeIdentity == null -> statusMessage(
                            "Firmware identity unavailable",
                            "Connecting will automatically restart the device into recovery mode",
                        )

                        else -> statusMessage(
                            "Ready to connect to $familyName",
                            "The $familyName will automatically restart into recovery mode",
                        )
                    }
                    state = state.copy(
                        busy = false,
                        readingFirmwareIdentity = false,
                        firmwareIdentity = identity.mUpgradeIdentity.takeIf { stillConnected },
                        editorFirmwareLabel = identity.editorLabel.takeIf { stillConnected },
                        status = status,
                    )
                }
            } catch (failure: Exception) {
                Log.w("BackupModel", "Refresh identity query failed", failure)
                post {
                    it.copy(
                        busy = false,
                        readingFirmwareIdentity = false,
                        firmwareIdentity = null,
                        editorFirmwareLabel = null,
                        status = statusMessage("Firmware query failed", failure.message ?: "Unknown MIDI device error"),
                    )
                }
            }
        }
    }

    fun dismissTransferResult() {
        if (state.hash.isNotEmpty()) {
            state = state.copy(hash = "")
        }
    }

    /** Records the exact device requested by the activity before the system permission dialog opens. */
    fun awaitingPermission(device: UsbDevice) {
        pendingPermission = device.deviceName
        state = state.copy(
            busy = true,
            autoConnectPending = false,
            ready = false,
            status = statusMessage("Waiting for USB permission", "Approve the Android USB request"),
        )
    }

    /** Accepts only the current permission response, then rechecks the system permission itself. */
    fun permissionResult(device: UsbDevice?, granted: Boolean) {
        if (device == null || pendingPermission == null || device.deviceName != pendingPermission) return
        pendingPermission = null
        state = state.copy(busy = false)
        if (granted && manager.hasPermission(device)) {
            connect()
        } else {
            state = state.copy(
                status = statusMessage(
                    "USB permission denied",
                    "Tap Connect to ${state.connectedDeviceDisplayName} to request it again",
                ),
            )
        }
    }

    /** Invalidates idle sessions; active operations handle their expected USB reboot themselves. */
    fun detached(device: UsbDevice?) {
        val permissionDetached = device != null && device.deviceName == pendingPermission
        val supportedDeviceDetached = device != null &&
                (device.deviceName == state.device?.deviceName ||
                        device.deviceName == activeDeviceName ||
                        AndroidFm1Midi.isSupportedDevice(device))
        if (permissionDetached) {
            pendingPermission = null
            state = state.copy(busy = false)
        }
        if (!state.busy && (permissionDetached || supportedDeviceDetached)) {
            mainHandler.removeCallbacks(discoveryTimeout)
            discoveryTimerPending = false
            state = state.rediscoverAfterDisconnect()
        }
        refresh()
        // During loader startup detach is expected; transfers/reconnect detect actual failure.
    }

    /** Cancellation is cooperative so no command is abandoned halfway through a USB transfer. */
    private fun checkCancelled() {
        if (cancelled.get()) throw CancellationException("Cancelled")
    }

    /** Applies updates to the latest main-thread snapshot, not a stale worker-thread copy. */
    private fun post(update: (BackupState) -> BackupState) {
        mainHandler.post {
            state = update(state).withCurrentBackupHash()
        }
    }

    private fun postV15Recovery(update: (V15RecoveryState) -> V15RecoveryState) {
        mainHandler.post { v15RecoveryState = update(v15RecoveryState) }
    }

    fun showV15RecoveryDialog() {
        if (!state.canOfferFm1RecoverySnapshot || state.busy || state.libraryLoading || v15RecoveryState.working) return
        v15RecoveryState = V15RecoveryState(showDialog = true)
    }

    fun dismissV15RecoveryDialog() {
        if (!v15RecoveryState.working) v15RecoveryState = V15RecoveryState()
    }

    fun createV15RecoveryBackup() {
        if (!state.canOfferFm1RecoverySnapshot || !v15RecoveryState.showDialog || v15RecoveryState.working || state.busy ||
            state.libraryLoading
        ) return
        v15RecoveryState = v15RecoveryState.copy(
            working = true,
            progress = 0,
            phase = "Downloading official V15 firmware",
            message = "",
            completed = false,
        )
        worker.execute {
            var pending: File? = null
            try {
                var lastProgress = -1
                val packageBytes = downloadV15Package { downloaded ->
                    val progress = (downloaded * 100 / V15RecoveryImage.PACKAGE_SIZE).toInt()
                    if (progress != lastProgress) {
                        lastProgress = progress
                        postV15Recovery { it.copy(progress = progress) }
                    }
                }
                postV15Recovery {
                    it.copy(progress = null, phase = "Verifying package and building recovery snapshot")
                }
                val image = V15RecoveryImage.build(packageBytes)
                postV15Recovery { it.copy(phase = "Verifying and saving recovery backup") }

                val file = backupStore.createPending()
                pending = file
                BufferedOutputStream(file.outputStream(), 65536).use { it.write(image) }
                val entry = backupStore.publishWithMetadata(
                    file,
                    V15RecoveryImage.IMAGE_SHA256,
                    V15_RECOVERY_BACKUP_NAME,
                    v15RecoveryBackupMetadata(),
                    ::checkCancelled,
                )
                pending = null
                post { current ->
                    current.copy(
                        backups = (current.backups + entry)
                            .sortedWith(compareByDescending<BackupEntry> { it.createdAt }.thenByDescending { it.id }),
                    )
                }
                postV15Recovery {
                    it.copy(
                        working = false,
                        progress = 100,
                        phase = "",
                        message = "FM-1_015 RECOVERY was added to your backup library.",
                        completed = true,
                    )
                }
            } catch (failure: Exception) {
                val deleted = try {
                    pending?.let(backupStore::discard) ?: true
                } catch (cleanupFailure: Exception) {
                    Log.w("BackupModel", "Could not remove incomplete V15 recovery backup", cleanupFailure)
                    false
                }
                val cleanupMessage = if (deleted) "" else " An incomplete private file could not be removed."
                Log.w("BackupModel", "Could not create V15 recovery backup", failure)
                postV15Recovery {
                    it.copy(
                        working = false,
                        progress = null,
                        phase = "",
                        message = "Could not create recovery backup: ${failure.message ?: failure.javaClass.simpleName}.$cleanupMessage",
                        completed = false,
                    )
                }
            }
        }
    }

    private fun downloadV15Package(onProgress: (Long) -> Unit): ByteArray {
        val connection = URL(V15RecoveryImage.SOURCE_URL)
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        try {
            val response = connection.responseCode
            if (response != HttpURLConnection.HTTP_OK) {
                throw IOException("Firmware download failed with HTTP $response.")
            }
            if (connection.url.protocol != "https" ||
                connection.url.host != "raw.githubusercontent.com"
            ) {
                throw IOException("Firmware download redirected to an unexpected host.")
            }
            val declaredLength = connection.contentLengthLong
            if (declaredLength > 0 &&
                declaredLength != V15RecoveryImage.PACKAGE_SIZE.toLong()
            ) {
                throw IOException("Downloaded package size does not match the expected V15 package.")
            }
            val output = ByteArrayOutputStream(V15RecoveryImage.PACKAGE_SIZE)
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > V15RecoveryImage.PACKAGE_SIZE) {
                        throw IOException("Downloaded package exceeds the expected V15 size.")
                    }
                    output.write(buffer, 0, count)
                    onProgress(total.toLong())
                }
                if (total != V15RecoveryImage.PACKAGE_SIZE) {
                    throw IOException("Firmware download was incomplete.")
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun finishTransfer(
        outcome: TransferOutcome,
        update: (BackupState) -> BackupState,
        refreshDiscovery: Boolean = false,
    ) {
        mainHandler.post {
            try {
                state = update(state).copy(transfer = null, progressKnown = false)
                if (refreshDiscovery) refresh()
            } finally {
                endTransfer(outcome)
            }
        }
    }

    /** Startup failures stay in the modal, including errors reported synchronously by TransferService. */
    private fun failEmergencyRecoveryStart(message: String) {
        bootloaderVerificationState = bootloaderVerificationState.copy(
            recovery = EmergencyRecoveryState(
                stage = EmergencyRecoveryStage.FAILURE,
                message = "$message No flash changes requested.",
            ),
        )
    }

    fun transferStartFailed(message: String) {
        if (bootloaderVerificationState.recovery?.stage == EmergencyRecoveryStage.CONFIRM) {
            failEmergencyRecoveryStart(message)
            return
        }
        state = state.copy(status = statusMessage("Transfer could not start", "$message No flash changes requested"))
    }

    private fun progressReporter(phases: List<ProgressPhase>, maximumFraction: Float = 1f) =
        ProgressReporter(SystemClock::elapsedRealtime, phases, maximumFraction) { status, fraction ->
            post { currentState -> currentState.copy(status = status, progress = fraction, progressKnown = true) }
        }

    private fun backupProgressReporter(deviceName: String) = progressReporter(
        listOf(
            ProgressPhase(
                "Reading flash", FlashRestore.FLASH_SIZE.toLong(),
                statusMessage("Backing up $deviceName", "Reading flash"), weight = 49.5,
            ),
            ProgressPhase(
                "Verifying: reading flash again", FlashRestore.FLASH_SIZE.toLong(),
                statusMessage("Backing up $deviceName", "Validating flash"), weight = 49.5,
            ),
            ProgressPhase(
                "Verifying saved backup", FlashRestore.FLASH_SIZE.toLong(),
                statusMessage("Backing up $deviceName", "Checking the saved copy"), weight = 1.0,
            ),
        ),
        maximumFraction = 0.999f,
    )

    private fun restoreProgressReporter(deviceName: String) = progressReporter(
        listOf(
            ProgressPhase(
                "Staging restore image", FlashRestore.FLASH_SIZE.toLong(),
                statusMessage("Preparing restore", "Copying the backup into private storage")
            ),
            ProgressPhase(
                "Verifying staged restore", FlashRestore.FLASH_SIZE.toLong(),
                statusMessage("Preparing restore", "Checking the private backup copy")
            ),
            ProgressPhase(
                "Writing to flash", FlashRestore.WRITABLE_SIZE.toLong(),
                statusMessage("Restoring $deviceName", "Writing application flash")
            ),
            ProgressPhase(
                "Verifying: reading back written data", FlashRestore.WRITABLE_SIZE.toLong(),
                statusMessage("Restoring $deviceName", "Validating flash")
            ),
        )
    )

    private fun batterySetupApproved(
        onFailure: (String) -> Unit = {
            state = state.copy(
                status = statusMessage("Backup and restore are blocked", it),
            )
        },
    ): Boolean {
        if (application.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(application.packageName)
        ) return true
        onFailure("Allow background operation in Settings")
        return false
    }

    /** Worker-only cleanup, with references invalidated even if closing the connection fails. */
    private fun closeSession() {
        try {
            transport?.close()
        } finally {
            transport = null
            protocol = null
            flash = null
            activeDeviceName = null
        }
    }

    /** Opens a worker-owned session; callers must still identify the chip before issuing loader commands. */
    private fun open(device: UsbDevice): Fm1Protocol {
        checkCancelled()
        val botTransport = BotTransport(AndroidUsb.open(manager, device))
        transport = botTransport
        activeDeviceName = device.deviceName
        return Fm1Protocol(botTransport).also { openedProtocol -> protocol = openedProtocol }
    }

    /** Waits for loader re-enumeration; fresh permission requires explicit user interaction, not a fallback. */
    private fun reconnect(): Pair<Fm1Protocol, Fm1Identity> {
        val deadline = SystemClock.elapsedRealtime() + 15000
        var lastError: IOException? = null
        var lastObservation: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            checkCancelled()
            val devices = AndroidUsb.devices(manager)
            val device = devices.singleOrNull()
            val observation = devices.joinToString { candidate ->
                "%04X:%04X %s permission=%s".format(
                    candidate.vendorId, candidate.productId, candidate.deviceName,
                    manager.hasPermission(candidate),
                )
            }.ifEmpty { "No recovery-mode USB candidates" }
            if (observation != lastObservation) {
                traceConnection("Reconnect: $observation")
                lastObservation = observation
            }
            if (device != null) {
                if (!manager.hasPermission(device)) {
                    throw ProtocolException("Loader reconnected: Refresh and Connect to grant USB permission again")
                }
                try {
                    val loaderProtocol = open(device)
                    val identity = loaderProtocol.identify()
                    traceConnection("Reconnect inquiry: ${identity.product}, revision=${identity.revision}")
                    val info = loaderProtocol.probeLoader()
                    traceConnection("Loader capability verified: NOR=%06X; 256-byte sample read".format(info.id))
                    return loaderProtocol to identity
                } catch (failure: IOException) {
                    if (failure.message != lastError?.message) {
                        traceConnection("Reconnect failed: ${failure.javaClass.simpleName}: ${failure.message}")
                    }
                    // Re-enumeration can expose the old ROM interface or disconnect during inquiry.
                    // Retry only session establishment; never replay a loader upload or flash read.
                    lastError = failure
                    closeSession()
                }
            }
            Thread.sleep(300)
        }
        throw ProtocolException("Loader did not reconnect: ${lastError?.message ?: "Device missing"}")
    }

    /** Sends the FM1-only SysEx once, retaining MIDI handles until USB reboot is observed or waiting ends. */
    fun enterBootloader() {
        if (state.busy || state.ready || state.multipleDevices) return
        val deviceName = state.connectedDeviceDisplayName
        val knownFirmwareIdentity = state.firmwareIdentity
        val knownEditorFirmwareLabel = state.editorFirmwareLabel
        cancelled.set(false)
        state = state.copy(
            busy = true, ready = false, findingBootloader = true,
            hash = "", progress = 0f,
            status = statusMessage("Switching $deviceName to bootloader", "Preparing the recovery connection")
        )
        firmwareDeviceName = null
        worker.execute {
            var sysExSent = false
            var firmwareIdentity: String? = knownFirmwareIdentity
            var editorFirmwareLabel: String? = knownEditorFirmwareLabel
            try {
                closeSession()
                val previousDevices = AndroidUsb.devices(manager).map { device -> device.deviceName }.toSet()
                val midi = AndroidFm1Midi.sendBootloader(application, ::checkCancelled) { identity ->
                    if (identity.mUpgradeIdentity != null) {
                        firmwareIdentity = identity.mUpgradeIdentity
                    }
                    if (identity.editorLabel != null) editorFirmwareLabel = identity.editorLabel
                    post {
                        it.copy(
                            firmwareIdentity = firmwareIdentity,
                            editorFirmwareLabel = editorFirmwareLabel,
                        )
                    }
                }
                val bootloaderDevice = midi.use {
                    sysExSent = true
                    post { currentState ->
                        currentState.copy(
                            status = statusMessage("Waiting for $deviceName", "Switching to bootloader mode")
                        )
                    }
                    waitForBootloader(previousDevices)
                }
                mainHandler.post {
                    firmwareDeviceName = bootloaderDevice
                    refresh()
                    val autoConnect = state.device?.deviceName == bootloaderDevice && !state.multipleDevices
                    state = state.copy(
                        busy = autoConnect, findingBootloader = false,
                        autoConnectPending = autoConnect,
                        firmwareIdentity = firmwareIdentity.takeIf { state.device?.deviceName == bootloaderDevice },
                        editorFirmwareLabel = editorFirmwareLabel.takeIf {
                            state.device?.deviceName == bootloaderDevice
                        },
                        status = statusMessage("Connecting to $deviceName", "Checking recovery mode and flash")
                    )
                }
            } catch (failure: Exception) {
                // UI boundary: report failures rather than silently trying another MIDI device.
                mainHandler.post {
                    refresh()
                    val message = when (failure) {
                        is CancellationException -> if (sysExSent) {
                            statusMessage(
                                "Stopped waiting", "The SysEx was already sent; the device may still reboot. Refresh"
                            )
                        } else {
                            statusMessage("Bootloader request cancelled", "No bootloader request was sent")
                        }

                        else -> statusMessage("Bootloader request failed", failure.message ?: "Unknown MIDI device error")
                    }
                    state = state.copy(busy = false, ready = false, findingBootloader = false, status = message)
                }
            }
        }
    }

    /** Requires an observed disconnect or a new USB path, rather than accepting a stale ROM interface. */
    private fun waitForBootloader(previousDeviceNames: Set<String>): String {
        val deadline = SystemClock.elapsedRealtime() + 15000
        var sawDisconnect = previousDeviceNames.isEmpty()
        while (SystemClock.elapsedRealtime() < deadline) {
            checkCancelled()
            val devices = AndroidUsb.devices(manager)
            if (devices.size > 1) {
                throw ProtocolException("Multiple USB candidates found. Connect only the FM-1.")
            }
            if (devices.isEmpty()) sawDisconnect = true
            val device = devices.singleOrNull()
            if (device != null && (sawDisconnect || device.deviceName !in previousDeviceNames)) return device.deviceName
            Thread.sleep(200)
        }
        throw ProtocolException("Bootloader USB interface did not appear. Check the cable and Refresh.")
    }

    /** Identifies WL82, starts the fixed loader if required, and probes NOR before enabling backup. */
    fun connect() {
        if ((state.busy && !state.autoConnectPending) || state.ready) return
        val device = state.device ?: return
        verifiedBootloaderDeviceName = null
        val firmwareIdentity = state.firmwareIdentity
        val editorFirmwareLabel = state.editorFirmwareLabel
        val requestedDeviceName = state.connectedDeviceDisplayName
        synchronized(connectionTrace) { connectionTrace.clear() }
        traceConnection(
            "Cached MIDI evidence: M-UPGRADE=${firmwareIdentity ?: "Unknown"}; " +
                    "editor=${editorFirmwareLabel ?: "Unknown"}"
        )
        traceConnection(
            "Connecting USB %04X:%04X %s permission=%s".format(
                device.vendorId, device.productId, device.deviceName, manager.hasPermission(device),
            )
        )
        cancelled.set(false)
        state = state.copy(
            busy = true, autoConnectPending = false, ready = false, hash = "", progress = 0f,
            deviceClassification = DeviceClassification(),
            manualFamilySelection = null,
            status = statusMessage("Connecting to $requestedDeviceName", "Checking recovery mode and flash")
        )
        worker.execute {
            try {
                closeSession()
                var deviceProtocol = open(device)
                val identity = deviceProtocol.identify()
                traceConnection("Initial inquiry: ${identity.product}, revision=${identity.revision}")
                var needsUpload = false
                if (identity.needsLoader) {
                    // Only trust a loader this app started on this exact USB device. Probing an
                    // unknown ROM with loader commands can desynchronise the BOT session.
                    val loaderAlreadyRunning = loaderDeviceName == device.deviceName && try {
                        deviceProtocol.probeLoader()
                        true
                    } catch (failure: Exception) {
                        traceConnection("Known loader did not answer: ${failure.message}")
                        false
                    }
                    if (loaderAlreadyRunning) {
                        traceConnection("Loader already running; skipping upload")
                    } else {
                        loaderDeviceName = null
                        // A failed probe can leave the BOT session unusable, so start fresh.
                        closeSession()
                        deviceProtocol = open(device)
                    }
                    needsUpload = !loaderAlreadyRunning
                }
                if (needsUpload) {
                    val loaderBytes = application.assets.open(Fm1Protocol.LOADER_ASSET)
                        .use { loaderStream -> loaderStream.readBytes() }
                    traceConnection("Uploading ${loaderBytes.size} bytes to RAM 0x01C02000 (512-byte blocks)")
                    deviceProtocol.uploadLoader(loaderBytes, ::checkCancelled)
                    traceConnection("RAM upload acknowledged; starting loader with argument 0x0001")
                    checkCancelled()
                    try {
                        deviceProtocol.startLoader()
                        traceConnection("Loader start reply acknowledged")
                    } catch (failure: IOException) {
                        // The jump can remove USB before its status arrives. Success is established
                        // only by a fresh WL82 inquiry and probe below, never by this exception.
                        Log.d("BackupModel", "Loader jump interrupted by USB re-enumeration", failure)
                        traceConnection("Loader start reply failed: ${failure.javaClass.simpleName}: ${failure.message}")
                    }
                    closeSession()
                    Thread.sleep(500)
                    val (loaderProtocol, loaderIdentity) = reconnect()
                    traceConnection("Running loader identified: ${loaderIdentity.product}")
                    deviceProtocol = loaderProtocol
                }
                val flashInfo = deviceProtocol.flashInfo()
                traceConnection("Flash probe: type=%02X JEDEC=%06X".format(flashInfo.type, flashInfo.id))
                flashInfo.requireFm1Nor()
                flash = flashInfo
                val sample = deviceProtocol.readFlash(0, 256)
                if (sample.size != 256) throw ProtocolException("Incomplete 256-byte flash sample")
                traceConnection("256-byte flash sample read succeeded")
                val spiSetting = deviceProtocol.readBootSpiSetting()
                traceConnection("Boot SPI setting: ${spiSetting ?: "Unknown"}")
                val smkTrimMarkerPresent = deviceProtocol.hasSmkTrimMarker()
                traceConnection("SMK trim marker at 0x0C1224: $smkTrimMarkerPresent")
                val classification = classifyDeviceFamily(
                    firmwareIdentity, spiSetting, flashInfo.id, smkTrimMarkerPresent,
                )
                val resolvedDeviceName = classification.family.takeIf {
                    it != DeviceFamily.UNKNOWN && classification.origin != FamilyEvidenceOrigin.CONFLICT
                }?.displayName ?: "???"
                traceConnection(
                    "Family classification: ${classification.family.displayName}; " +
                            "origin=${classification.origin}; confidence=${classification.confidence}; " +
                            classification.explanation
                )
                checkCancelled()
                post { currentState ->
                    val currentDevice = AndroidUsb.devices(manager).singleOrNull()
                    val retainedIdentity = firmwareIdentity.takeIf { currentDevice?.deviceName == activeDeviceName }
                    val retainedEditorLabel = editorFirmwareLabel.takeIf {
                        currentDevice?.deviceName == activeDeviceName
                    }
                    val retainedClassification = classification.takeIf {
                        currentDevice?.deviceName == activeDeviceName
                    } ?: DeviceClassification()
                    firmwareDeviceName = activeDeviceName.takeIf {
                        currentDevice?.deviceName == activeDeviceName
                    }
                    loaderDeviceName = activeDeviceName
                    currentState.copy(
                        busy = false, ready = true,
                        firmwareIdentity = retainedIdentity,
                        editorFirmwareLabel = retainedEditorLabel,
                        deviceClassification = retainedClassification,
                        status = statusMessage(
                            "$resolvedDeviceName successfully connected",
                            "Ready to backup or restore full flash",
                        )
                    )
                }
            } catch (failure: Exception) {
                traceConnection("Connection failed: ${failure.javaClass.simpleName}: ${failure.message}")
                // Operation boundary: all failures invalidate readiness; none trigger an alternate loader.
                closeSession()
                post { currentState ->
                    currentState.copy(
                        busy = false, ready = false,
                        status = statusMessage("Connection failed", failure.message ?: "Unknown $requestedDeviceName error")
                    )
                }
            }
        }
    }

    /** Saves a full 1 MiB image privately, publishing only after device and saved-file verification. */
    fun backup() {
        if (!batterySetupApproved()) return
        if (state.busy || !state.ready || state.libraryLoading) return
        if (!state.canBackUpWithFamily) {
            state = state.copy(
                status = statusMessage("Backup blocked: device family unknown", "Select a device family in recovery mode"),
            )
            return
        }
        val size = FlashRestore.FLASH_SIZE.toLong()
        if (!beginTransfer(FlashOperation.BACKUP)) return
        val deviceName = state.connectedDeviceDisplayName
        val mUpgradeIdentity = state.firmwareIdentity
        val primaryName = primaryBackupName(state.editorFirmwareLabel, mUpgradeIdentity)
        val metadata = BackupMetadata(
            classification = state.deviceClassification.copy(rawMidiIdentity = mUpgradeIdentity),
            editorFirmwareLabel = state.editorFirmwareLabel,
            manualFamilySelection = state.manualFamilySelection,
        )
        cancelled.set(false)
        state = state.copy(
            busy = true, transfer = FlashOperation.BACKUP,
            progressKnown = false, hash = "", hashLabel = "SHA-256",
            status = statusMessage("Starting backup", "Reading and verifying the full flash"), progress = 0f
        )
        worker.execute {
            var pending: File? = null
            var outcome = TransferOutcome.FAILURE
            var result: (BackupState) -> BackupState = { it }
            try {
                val deviceProtocol = protocol ?: throw ProtocolException("Connect first")
                val flashInfo = flash ?: throw ProtocolException("Identify flash first")
                require(flashInfo.isNor && flashInfo.suggestedSize == size)
                val file = backupStore.createPending()
                pending = file
                val progress = backupProgressReporter(deviceName)
                val hash = BufferedOutputStream(file.outputStream(), 65536).use { output ->
                    FlashBackup(deviceProtocol::readFlash).run(size, output, ::checkCancelled) { phase, bytes ->
                        progress.report(phase, bytes, size)
                    }
                }
                val entry = backupStore.publishWithMetadata(
                    file, hash, primaryName, metadata, ::checkCancelled,
                    progress = { bytes, total -> progress.report("Verifying saved backup", bytes, total) },
                )
                pending = null
                if (!BackupMilestonePrompt.recordSuccessfulBackup(application.getSharedPreferences(
                        "setup",
                        Context.MODE_PRIVATE,
                    ))
                ) {
                    Log.w("BackupModel", "Could not persist successful backup milestone")
                }
                outcome = TransferOutcome.SUCCESS
                result = { currentState ->
                    currentState.copy(
                        busy = false, backups = (currentState.backups + entry)
                            .sortedWith(compareByDescending<BackupEntry> { it.createdAt }.thenByDescending { it.id }),
                        status = statusMessage(
                            "Backup saved", "$deviceName flash was read twice and verified against the saved copy"
                        ),
                        hash = hash, progress = 1f
                    )
                }
            } catch (failure: Exception) {
                outcome = if (failure is CancellationException) TransferOutcome.CANCELLED else TransferOutcome.FAILURE
                try {
                    closeSession()
                } catch (closeFailure: Exception) {
                    Log.w("BackupModel", "Could not close failed backup session", closeFailure)
                }
                val deleted = try {
                    pending?.let(backupStore::discard) ?: true
                } catch (cleanupFailure: Exception) {
                    Log.w("BackupModel", "Could not remove incomplete private backup", cleanupFailure)
                    false
                }
                val cleanupMessage = if (deleted) "No incomplete backup was added to the library."
                else "Incomplete private file could not be deleted; it is hidden from the library."
                result = { currentState ->
                    currentState.copy(
                        busy = false, ready = false, hash = "", progress = 0f,
                        status = if (failure is CancellationException) {
                            statusMessage("Backup cancelled", cleanupMessage)
                        } else {
                            statusMessage(
                                "Backup failed",
                                "${failure.message ?: "Unknown $deviceName error"}. $cleanupMessage"
                            )
                        }
                    )
                }
            } finally {
                finishTransfer(outcome, result)
            }
        }
    }

    fun showFirmwareImportDialog() {
        if (state.busy || state.libraryLoading || v15RecoveryState.working) return
        firmwareImportState = FirmwareImportState(showDialog = true)
    }

    fun selectImportFile(uri: Uri) {
        if (state.busy || state.libraryLoading) return
        cancelled.set(false)
        state = state.copy(busy = true, selectedImport = null)
        firmwareImportState = FirmwareImportState(showDialog = true, working = true, message = "Verifying import")
        worker.execute {
            selectedImportBytes = null
            try {
                val resolver = application.contentResolver
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) throw ProtocolException("Could not read import filename")
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } ?: throw ProtocolException("Could not read import filename")
                if (name.endsWith(".zip", ignoreCase = true)) {
                    val entries = resolver.openInputStream(uri)?.use { input ->
                        backupStore.importZip(input, ::checkCancelled) { count ->
                            mainHandler.post {
                                firmwareImportState = firmwareImportState.copy(message = "Verified $count backups")
                            }
                        }
                    } ?: throw ProtocolException("Could not open import source")
                    mainHandler.post {
                        finishFirmwareImport(
                            message = if (entries.isEmpty()) "No backups imported. Matching backups are already in the library."
                                else "Imported ${entries.size} verified ${if (entries.size == 1) "backup" else "backups"}.",
                            entries = entries,
                        )
                    }
                    return@execute
                }
                RestoreImage.requireBinaryName(name)
                val output = ByteArrayOutputStream(FlashRestore.FLASH_SIZE)
                val hash = resolver.openInputStream(uri)?.use { input ->
                    RestoreImage.stage(input, output, ::checkCancelled)
                } ?: throw ProtocolException("Could not open import source")
                checkCancelled()
                selectedImportBytes = output.toByteArray()
                mainHandler.post {
                    if (!skipDuplicateBinaryImport(hash)) {
                        state = state.copy(busy = false, selectedImport = SelectedImport(name, hash))
                        firmwareImportState = firmwareImportState.copy(working = false, cancelling = false, message = "")
                    }
                }
            } catch (failure: Exception) {
                selectedImportBytes = null
                mainHandler.post {
                    finishFirmwareImport(
                        if (failure is CancellationException) "Import cancelled. No backups were added."
                        else "Import file rejected: ${failure.message ?: "Choose a backup ZIP or raw BIN"}",
                    )
                }
            }
        }
    }

    fun cancelImport() {
        if (firmwareImportState.working) {
            firmwareImportState = firmwareImportState.copy(cancelling = true)
            cancelled.set(true)
            return
        }
        selectedImportBytes = null
        state = state.copy(selectedImport = null)
        firmwareImportState = FirmwareImportState()
    }

    fun saveImportedBackup(name: String) {
        val selected = state.selectedImport ?: return
        if (state.busy || state.libraryLoading) return
        if (skipDuplicateBinaryImport(selected.sha256)) return
        val identity = name.trim()
        if (identity.isEmpty() || identity.length > BackupStore.MAX_NAME_LENGTH ||
            identity.any { it.code !in 32..126 }
        ) {
            firmwareImportState = firmwareImportState.copy(
                message = "File name required: use up to ${BackupStore.MAX_NAME_LENGTH} printable ASCII characters",
            )
            return
        }
        cancelled.set(false)
        state = state.copy(busy = true)
        firmwareImportState = firmwareImportState.copy(
            working = true, cancelling = false, message = "Saving imported backup",
        )
        worker.execute {
            var pending: File? = null
            try {
                val image = selectedImportBytes
                    ?: throw ProtocolException("Validated import is no longer available; select the file again")
                val file = backupStore.createPending()
                pending = file
                BufferedOutputStream(file.outputStream(), 65536).use { it.write(image) }
                val published = backupStore.publish(file, selected.sha256, identity, ::checkCancelled)
                val entry = try {
                    backupStore.classifyImportedBackup(published).also { checkCancelled() }
                } catch (failure: Exception) {
                    backupStore.delete(published.id)
                    throw failure
                }
                pending = null
                selectedImportBytes = null
                mainHandler.post {
                    finishFirmwareImport("Backup imported. SHA-256 verified.", listOf(entry))
                }
            } catch (failure: Exception) {
                val deleted = try {
                    pending?.let(backupStore::discard) ?: true
                } catch (cleanupFailure: Exception) {
                    Log.w("BackupModel", "Could not remove incomplete imported backup", cleanupFailure)
                    false
                }
                val cleanupMessage = if (deleted) "" else " An incomplete private file could not be removed."
                mainHandler.post {
                    finishFirmwareImport(
                        if (failure is CancellationException) "Import cancelled. No backup was added.$cleanupMessage"
                        else "Import failed: ${failure.message ?: "Could not save the verified backup"}.$cleanupMessage",
                    )
                }
            }
        }
    }

    private fun skipDuplicateBinaryImport(sha256: String): Boolean {
        if (state.backups.none { it.sha256 == sha256 }) return false
        finishFirmwareImport("Not imported. A backup with this SHA-256 is already in the library.")
        return true
    }

    private fun finishFirmwareImport(message: String, entries: List<BackupEntry> = emptyList()) {
        selectedImportBytes = null
        state = state.copy(
            busy = false,
            selectedImport = null,
            backups = if (entries.isEmpty()) state.backups else (state.backups + entries)
                .sortedWith(compareByDescending<BackupEntry> { it.createdAt }.thenByDescending { it.id }),
        )
        firmwareImportState = firmwareImportState.copy(
            working = false, cancelling = false, completed = true, message = message,
        )
    }

    fun selectFamilyManually(family: DeviceFamily) {
        if (state.busy || !state.ready || state.deviceMode != DeviceMode.DOWNLOAD ||
            family == DeviceFamily.UNKNOWN
        ) return
        state = state.withManualFamilySelection(family)
    }

    fun restoreSavedBackup(id: String) {
        if (!batterySetupApproved()) return
        val entry = state.backups.firstOrNull { it.id == id } ?: return
        restoreSource(openSource = { backupStore.open(id) }, sourceFamily = entry.taggedFamily)
    }

    fun retryRestore() {
        if (state.busy || !state.restoreRetryAvailable) return
        val attempt = retryRestoreAttempt ?: return
        restoreSource(
            attempt.source,
            attempt.sourceFamily,
            retrying = true,
            expectedTargetFamily = attempt.targetFamily,
            expectedTargetDeviceName = attempt.targetDeviceName,
        )
    }

    private fun restoreSource(
        openSource: () -> InputStream,
        sourceFamily: DeviceFamily,
        retrying: Boolean = false,
        expectedTargetFamily: DeviceFamily? = null,
        expectedTargetDeviceName: String? = null,
    ) {
        if (!batterySetupApproved()) return
        if (state.busy || (!state.ready && !retrying)) return
        val targetFamily = expectedTargetFamily ?: state.effectiveDeviceFamily
        val targetDeviceName = expectedTargetDeviceName ?: activeDeviceName
        if (state.deviceClassification.origin == FamilyEvidenceOrigin.CONFLICT) {
            state = state.copy(
                status = statusMessage(
                    "Restore blocked: conflicting device evidence",
                    state.deviceClassification.explanation,
                ),
            )
            return
        }
        val compatibility = compatibility(sourceFamily, targetFamily)
        val deviceName = state.connectedDeviceDisplayName
        if (compatibility != BackupCompatibility.COMPATIBLE || targetDeviceName == null) {
            state = state.copy(
                status = statusMessage(
                    "Restore blocked",
                    when (compatibility) {
                        BackupCompatibility.INCOMPATIBLE ->
                            "Backup family ${sourceFamily.metadataName} does not match ${targetFamily.metadataName}"
                        BackupCompatibility.UNKNOWN -> "Backup or connected device family is unknown"
                        BackupCompatibility.COMPATIBLE -> "Connected USB session is unavailable"
                    },
                ),
            )
            return
        }
        if (!beginTransfer(FlashOperation.RESTORE)) return
        if (!retrying) {
            retryRestoreAttempt = RestoreAttempt(openSource, sourceFamily, targetFamily, targetDeviceName)
        }
        firmwareDeviceName = null
        firmwareMidiId = null
        queriedMidiId = null
        cancelled.set(false)
        state = state.copy(
            busy = true, transfer = FlashOperation.RESTORE,
            progressKnown = false, hash = "", progress = 0f, firmwareIdentity = null,
            editorFirmwareLabel = null,
            restoreRetryAvailable = false,
            status = statusMessage("Preparing restore", "Checking the backup. No flash changes have been made")
        )
        worker.execute {
            var stagedFile: File? = null
            var mutationStarted = false
            var restoreSucceeded = false
            var outcome = TransferOutcome.FAILURE
            var result: (BackupState) -> BackupState = { it }
            try {
                val deviceProtocol = protocol ?: throw ProtocolException("Connect first")
                val progress = restoreProgressReporter(deviceName)
                // createTempFile uses this app's private cache, never the document provider.
                val privateFile = File.createTempFile("fm1-restore-", ".bin", application.cacheDir)
                stagedFile = privateFile
                val sourceHash = openSource().use { source ->
                    privateFile.outputStream().buffered().use { destination ->
                        RestoreImage.stage(source, destination, ::checkCancelled) { bytes, total ->
                            progress.report("Staging restore image", bytes, total)
                        }
                    }
                }
                val snapshot = privateFile.inputStream().buffered().use { source ->
                    RestoreImage.snapshot(source, sourceHash, ::checkCancelled) { bytes, total ->
                        progress.report("Verifying staged restore", bytes, total)
                    }
                }
                checkCancelled()
                // Recheck the captured pair and USB path immediately before FlashRestore can write.
                if (activeDeviceName != targetDeviceName ||
                    AndroidUsb.devices(manager).singleOrNull()?.deviceName != targetDeviceName
                ) {
                    throw ProtocolException("Connected USB session changed; no flash changes were made")
                }
                if (compatibility(sourceFamily, targetFamily) != BackupCompatibility.COMPATIBLE) {
                    throw ProtocolException("Backup and connected device families no longer match")
                }
                FlashRestore(deviceProtocol).run(
                    snapshot, FlashRestore.FLASH_SIZE.toLong(), ::checkCancelled,
                    mutationStarting = { mutationStarted = true },
                    progress = progress::report,
                )
                // Reset only after verification; the device usually disconnects without replying.
                try {
                    deviceProtocol.runApp()
                } catch (failure: Exception) {
                    Log.i("BackupModel", "$deviceName reset reply not received after restore", failure)
                }
                try {
                    closeSession()
                } catch (closeFailure: Exception) {
                    Log.w("BackupModel", "Could not close session after reboot", closeFailure)
                }
                result = { currentState ->
                    retryRestoreAttempt = null
                    currentState.copy(
                        busy = false, ready = false, progress = 1f, hash = "",
                        restoreRetryAvailable = false,
                        status = statusMessage(
                            "Restore successful",
                            "Rebooting into restored firmware",
                        )
                    )
                }
                restoreSucceeded = true
                outcome = TransferOutcome.SUCCESS
            } catch (failure: Exception) {
                outcome = if (failure is CancellationException) TransferOutcome.CANCELLED else TransferOutcome.FAILURE
                val retryableVerificationFailure = mutationStarted && failure is RestoreVerificationException
                if (!retryableVerificationFailure) {
                    try {
                        closeSession()
                    } catch (closeFailure: Exception) {
                        // Cleanup must not hide the original failure or its partial-restore warning.
                        Log.w("BackupModel", "Could not close failed restore session", closeFailure)
                    }
                }
                val restoreMessage = if (retryableVerificationFailure) {
                    statusMessage(
                        "Restore verification failed",
                        "The $deviceName remains in recovery mode. Retry to rewrite and verify the application flash",
                    )
                } else if (mutationStarted) {
                    val heading =
                        if (failure is CancellationException) "Restore cancelled" else "Restore may be incomplete"
                    val cause = if (failure is CancellationException) "Restore stopped while writing flash"
                    else failure.message ?: "The $deviceName reported an error while writing flash"
                    statusMessage(
                        heading,
                        "$cause. Flash changes may be incomplete. The protected bootloader was not overwritten"
                    )
                } else {
                    val heading = if (failure is CancellationException) "Restore cancelled" else "Restore failed"
                    val cause = if (failure is CancellationException) "No flash changes were made"
                    else failure.message ?: "The $deviceName could not complete the restore"
                    statusMessage(
                        heading,
                        "$cause. The $deviceName was not changed. Check the backup and connection, then try again"
                    )
                }
                result = { currentState ->
                    currentState.copy(
                        busy = false, ready = false, hash = "", progress = 0f,
                        restoreRetryAvailable = retryableVerificationFailure,
                        status = restoreMessage
                    )
                }
            } finally {
                val deleted = try {
                    stagedFile?.let { file -> !file.exists() || file.delete() } ?: true
                } catch (failure: SecurityException) {
                    Log.w("BackupModel", "Private staging deletion denied", failure)
                    false
                }
                if (!deleted) {
                    Log.w("BackupModel", "Could not delete private staged restore image")
                    val completedResult = result
                    result = { currentState ->
                        val completedState = completedResult(currentState)
                        completedState.copy(
                            status = statusMessage(
                                completedState.status.heading,
                                "${completedState.status.secondary} Private restore copy remains in app cache. Clear cache to remove it",
                            )
                        )
                    }
                }
                finishTransfer(outcome, result, refreshDiscovery = restoreSucceeded)
            }
        }
    }

    fun deleteBackup(id: String) {
        if (state.busy || state.libraryLoading || state.backups.none { it.id == id }) return
        state = state.copy(busy = true)
        worker.execute {
            try {
                backupStore.delete(id)
                post {
                    val pinned = it.pinnedBackupIds - id
                    savePinnedBackups(pinned)
                    it.copy(
                        busy = false, backups = it.backups.filterNot { entry -> entry.id == id },
                        pinnedBackupIds = pinned,
                        status = statusMessage("Backup deleted from this app", "Exported copies are unaffected")
                    )
                }
            } catch (failure: Exception) {
                post {
                    it.copy(
                        busy = false,
                        status = statusMessage("Delete failed", failure.message ?: "Could not delete the backup")
                    )
                }
            }
        }
    }

    /** Renames only the saved entry's ID label; blank names are rejected. */
    fun renameBackup(id: String, name: String) {
        if (state.busy || state.libraryLoading || state.backups.none { it.id == id }) return
        state = state.copy(busy = true)
        worker.execute {
            try {
                val renamed = backupStore.rename(id, name)
                post { current ->
                    val pinned = if (id in current.pinnedBackupIds) {
                        buildSet {
                            addAll(current.pinnedBackupIds)
                            remove(id)
                            add(renamed.id)
                        }
                    } else current.pinnedBackupIds
                    if (pinned != current.pinnedBackupIds) savePinnedBackups(pinned)
                    current.copy(
                        busy = false,
                        backups = current.backups.map { if (it.id == id) renamed else it },
                        pinnedBackupIds = pinned,
                        status = statusMessage("Backup renamed", "The backup contents and hash are unchanged")
                    )
                }
            } catch (failure: Exception) {
                post {
                    it.copy(
                        busy = false,
                        status = statusMessage("Rename failed", failure.message ?: "Could not rename the backup")
                    )
                }
            }
        }
    }

    fun updateBackupMetadata(id: String, metadata: BackupMetadata) {
        if (state.busy || state.libraryLoading || state.backups.none { it.id == id }) return
        state = state.copy(busy = true)
        worker.execute {
            try {
                val updated = backupStore.updateMetadata(id, metadata)
                post { current ->
                    current.copy(
                        busy = false,
                        backups = current.backups.map { if (it.id == id) updated else it },
                        status = statusMessage("Backup tags updated", "The backup image and SHA-256 are unchanged"),
                    )
                }
            } catch (failure: Exception) {
                post {
                    it.copy(
                        busy = false,
                        status = statusMessage("Tag update failed", failure.message ?: "Could not update backup tags"),
                    )
                }
            }
        }
    }

    fun togglePinnedBackup(id: String) {
        if (state.busy || state.backups.none { it.id == id }) return
        val pinned = if (id in state.pinnedBackupIds) state.pinnedBackupIds - id
        else state.pinnedBackupIds + id
        savePinnedBackups(pinned)
        state = state.copy(pinnedBackupIds = pinned)
    }

    private fun savePinnedBackups(ids: Set<String>) {
        backupPreferences.edit { putStringSet(PINNED_BACKUP_IDS, ids) }
    }

    fun showFirmwareExportDialog(id: String? = null) {
        if (state.busy || state.libraryLoading || v15RecoveryState.working || state.backups.isEmpty()) return
        val entries = if (id == null) BackupStore.uniqueExports(state.backups)
            else state.backups.filter { it.id == id }
        if (entries.isEmpty()) return
        firmwareExportState = FirmwareExportState(
            showDialog = true,
            entries = entries,
            singleBackup = id != null,
        )
    }

    fun dismissFirmwareExportDialog() {
        if (!firmwareExportState.working) firmwareExportState = FirmwareExportState()
    }

    fun cancelFirmwareExport() {
        if (!firmwareExportState.working) return
        firmwareExportState = firmwareExportState.copy(cancelling = true)
        cancelled.set(true)
    }

    fun exportAllFirmware(uri: Uri) {
        if (!firmwareExportState.showDialog || firmwareExportState.working || firmwareExportState.completed) return
        val entries = firmwareExportState.entries
        if (state.busy || state.libraryLoading || v15RecoveryState.working || entries.isEmpty()) return
        cancelled.set(false)
        state = state.copy(busy = true)
        val singleBackup = firmwareExportState.singleBackup
        firmwareExportState = firmwareExportState.copy(working = true, progress = 0, message = "")
        worker.execute {
            val message = try {
                val destination = application.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("Could not open export destination")
                var lastProgress = -1
                val reportProgress: (Long, Long) -> Unit = { bytes, total ->
                    val progress = (bytes * 100 / total).toInt()
                    if (progress != lastProgress) {
                        lastProgress = progress
                        mainHandler.post {
                            firmwareExportState = firmwareExportState.copy(progress = progress)
                        }
                    }
                }
                if (singleBackup) {
                    backupStore.exportBackupZip(entries.single(), destination, ::checkCancelled, reportProgress)
                } else {
                    backupStore.exportZip(entries, destination, ::checkCancelled, reportProgress)
                }
                "Exported ${entries.size} ${if (entries.size == 1) "backup" else "backups"} to ZIP."
            } catch (failure: Exception) {
                val deleted = deleteIncompleteDocument(uri)
                val heading = if (failure is CancellationException) "Export cancelled."
                else "Export failed: ${failure.message ?: "Could not save ZIP"}."
                "$heading " + if (deleted) "The incomplete ZIP was removed."
                else "Delete the incomplete ZIP manually."
            }
            mainHandler.post {
                state = state.copy(busy = false)
                firmwareExportState = firmwareExportState.copy(
                    working = false, cancelling = false, completed = true, message = message,
                )
            }
        }
    }

    fun exportBackup(id: String, uri: Uri) {
        if (state.busy || state.libraryLoading || v15RecoveryState.working) return
        if (state.backups.none { it.id == id }) return
        showFirmwareExportDialog(id)
        exportAllFirmware(uri)
    }

    /**
     * Document providers may not support deletion or may revoke access after a failed write.
     * These expected cleanup failures require a manual-deletion warning, never a success result.
     */
    private fun deleteIncompleteDocument(uri: Uri): Boolean {
        return try {
            DocumentsContract.deleteDocument(application.contentResolver, uri)
        } catch (failure: IOException) {
            Log.w("BackupModel", "Could not delete incomplete backup", failure)
            false
        } catch (failure: SecurityException) {
            Log.w("BackupModel", "Document provider denied incomplete backup deletion", failure)
            false
        } catch (failure: UnsupportedOperationException) {
            Log.w("BackupModel", "Document provider does not support deletion", failure)
            false
        } catch (failure: IllegalArgumentException) {
            Log.w("BackupModel", "Document provider no longer recognizes the destination", failure)
            false
        } catch (failure: RuntimeException) {
            Log.w("BackupModel", "Document provider failed while deleting incomplete backup", failure)
            false
        }
    }

    /** Stops permission waits immediately and asks the worker to stop at its next safe boundary. */
    fun cancel() {
        if (pendingPermission != null) {
            pendingPermission = null
            state = state.copy(
                busy = false, ready = false,
                status = statusMessage("USB permission request cancelled", "No USB connection was opened")
            )
        }
        cancelled.set(true)
    }

    /** Enqueues cleanup behind any in-flight command before shutting down the worker. */
    fun shutdown() {
        mainHandler.removeCallbacks(keepAlive)
        mainHandler.removeCallbacks(discoveryTimeout)
        midiManager?.unregisterDeviceCallback(midiCallback)
        cancelled.set(true)
        recoveryDownloader.shutdownNow()
        worker.execute { closeSession() }
        worker.shutdown()
    }

    private companion object {
        const val PINNED_BACKUP_IDS = "pinned_backup_ids"
        const val LEGACY_BACKUP_UPGRADE_KEY = "legacy_backup_family_upgrade_v1"
        const val KEEPALIVE_INTERVAL_MS = 1000L
        const val DISCOVERY_TIMEOUT_MS = 3000L
    }
}
