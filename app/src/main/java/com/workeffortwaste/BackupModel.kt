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
import com.workeffortwaste.protocol.Fm1Protocol
import com.workeffortwaste.protocol.Fm1Identity
import com.workeffortwaste.protocol.FlashBackup
import com.workeffortwaste.protocol.FlashInfo
import com.workeffortwaste.protocol.FlashRestore
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
    SEARCHING("Discovering FM-1"),
    MIDI("FM-1 \u00b7 MIDI mode"),
    DOWNLOAD("FM-1 \u00b7 Recovery mode"),
    MULTIPLE("Multiple FM-1 connections \u00b7 Connect only one FM-1"),
}

internal fun discoverDeviceMode(midiDevices: Int, downloadDevices: Int): DeviceMode = when {
    midiDevices + downloadDevices > 1 -> DeviceMode.MULTIPLE
    downloadDevices == 1 -> DeviceMode.DOWNLOAD
    midiDevices == 1 -> DeviceMode.MIDI
    else -> DeviceMode.SEARCHING
}

enum class FlashOperation(val title: String, val label: String) {
    BACKUP("Backing up FM-1", "Backup"),
    RESTORE("Restoring FM-1", "Restore"),
}

enum class TransferOutcome {
    SUCCESS,
    FAILURE,
    CANCELLED,
}

data class SelectedImport(val sourceName: String, val sha256: String)

data class FirmwareExportState(
    val showDialog: Boolean = false,
    val entries: List<BackupEntry> = emptyList(),
    val working: Boolean = false,
    val cancelling: Boolean = false,
    val progress: Int = 0,
    val message: String = "",
    val completed: Boolean = false,
)

data class V15RecoveryState(
    val showDialog: Boolean = false,
    val working: Boolean = false,
    val progress: Int? = null,
    val phase: String = "",
    val message: String = "",
    val completed: Boolean = false,
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
    statusMessage("No FM-1 connected", "Refresh or connect FM-1 via USB")
} else {
    statusMessage("Discovering FM-1", "Searching for USB connection")
}

private val CONNECTION_STATUS_PREFIXES = listOf(
    "FM-1 successfully connected", "Reported firmware", "Firmware identity unavailable",
    "Waiting for FM-1", "Connecting to FM-1", "Ready to connect to FM-1", "FM-1 disconnected",
    "Waiting for USB permission", "USB permission denied", "USB permission request cancelled",
    "Connection failed", "Firmware query failed", "Bootloader request failed",
    "Bootloader request cancelled", "Stopped waiting",
)

internal fun BackupState.afterDiscoveryTimeout(): BackupState {
    if (deviceMode != DeviceMode.SEARCHING || findingBootloader || busy) return this
    return copy(
        discoveryTimedOut = true,
        status = if (CONNECTION_STATUS_PREFIXES.any { status.heading.startsWith(it) }) {
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
    discoverySuppressedByUser = false,
    ready = false,
    discoveryTimedOut = false,
    autoConnectPending = false,
    firmwareIdentity = null,
    status = StatusMessage(),
    hash = "",
)

internal fun BackupState.disconnectByUser(): BackupState = copy(
    device = null,
    multipleDevices = false,
    deviceMode = DeviceMode.SEARCHING,
    discoveryActive = false,
    discoverySuppressedByUser = true,
    discoveryTimedOut = false,
    findingBootloader = false,
    autoConnectPending = false,
    ready = false,
    readingFirmwareIdentity = false,
    status = statusMessage("FM-1 disconnected", "Tap Refresh to reconnect"),
    firmwareIdentity = null,
    progress = 0f,
    transfer = null,
    progressKnown = false,
    restoreRetryAvailable = false,
    hash = "",
)

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
    val discoverySuppressedByUser: Boolean = false,
    val discoveryTimedOut: Boolean = false,
    val findingBootloader: Boolean = false,
    /** Set once after Connect to Device finds the bootloader USB interface; the UI then connects. */
    val autoConnectPending: Boolean = false,
    val busy: Boolean = false,
    val readingFirmwareIdentity: Boolean = false,
    val ready: Boolean = false,
    val status: StatusMessage = StatusMessage(),
    val firmwareIdentity: String? = null,
    val connectionDiagnostics: String = "",
    val backups: List<BackupEntry> = emptyList(),
    val pinnedBackupIds: Set<String> = emptySet(),
    val libraryLoading: Boolean = true,
    val libraryError: String = "",
    val progress: Float = 0f,
    val transfer: FlashOperation? = null,
    val progressKnown: Boolean = false,
    val selectedImport: SelectedImport? = null,
    val restoreRetryAvailable: Boolean = false,
    val hash: String = "",
    val hashLabel: String = "SHA-256",
) {
    val showTransferProgress: Boolean get() = busy && transfer != null
    val pinnedBackups: List<BackupEntry> get() = backups.filter { it.id in pinnedBackupIds }
    val unpinnedBackups: List<BackupEntry> get() = backups.filterNot { it.id in pinnedBackupIds }

    val deviceStatus: String
        get() = when {
            deviceMode == DeviceMode.MULTIPLE -> deviceMode.label
            !firmwareIdentity.isNullOrBlank() &&
                    (findingBootloader || deviceMode == DeviceMode.MIDI || deviceMode == DeviceMode.DOWNLOAD) -> {
                val detail = if (deviceMode == DeviceMode.DOWNLOAD) "Recovery mode" else "MIDI mode"
                "FM-1 \u00b7 $firmwareIdentity \u00b7 $detail"
            }
            findingBootloader && deviceMode != DeviceMode.DOWNLOAD -> DeviceMode.MIDI.label
            ready && deviceMode == DeviceMode.DOWNLOAD -> "FM-1 \u00b7 Recovery mode"
            else -> deviceMode.label
        }
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

    /** Delivers worker results to Compose and serializes them with activity/broadcast callbacks. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Serializes MIDI, loader startup, transfers, and cleanup without concurrent USB commands. */
    private val worker = Executors.newSingleThreadExecutor()

    /** Main-thread cancellation flag checked by the worker between bounded I/O commands. */
    private val cancelled = AtomicBoolean()
    private var userDisconnectedDeviceName: String? = null

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
    private var retryRestoreSource: (() -> InputStream)? = null
    private var firmwareDeviceName: String? = null
    private var firmwareMidiId: Int? = null
    private var queriedMidiId: Int? = null

    /** USB path of a loader this app started and verified; cleared whenever that device goes away. */
    @Volatile
    private var loaderDeviceName: String? = null
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
            if (state.ready && !state.busy) {
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
                            if (current.busy || current.discoverySuppressedByUser) current else current.copy(
                                ready = false,
                                status = statusMessage("Connection failed", "FM-1 stopped responding. Tap Refresh"),
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
        state = state.copy(
            pinnedBackupIds = backupPreferences.getStringSet(PINNED_BACKUP_IDS, emptySet()).orEmpty().toSet()
        )
        // The transport-specific callback overload requires API 33.
        @Suppress("DEPRECATION")
        midiManager?.registerDeviceCallback(midiCallback, mainHandler)
        refresh()
        mainHandler.postDelayed(keepAlive, KEEPALIVE_INTERVAL_MS)
        worker.execute {
            try {
                val entries = backupStore.list()
                post {
                    val pinned = it.pinnedBackupIds intersect entries.mapTo(mutableSetOf()) { entry -> entry.id }
                    if (pinned != it.pinnedBackupIds) savePinnedBackups(pinned)
                    it.copy(backups = entries, pinnedBackupIds = pinned, libraryLoading = false)
                }
            } catch (failure: Exception) {
                post { it.copy(libraryLoading = false, libraryError = "Could not load backups: ${failure.message}") }
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
        }.ifEmpty { "USB host: no FM-1 found." }
        val midi = midiManager?.let(AndroidFm1Midi::diagnostics) ?: "Android MIDI service unavailable."
        return "Supported MIDI IDs: ${com.workeffortwaste.protocol.Fm1BootloaderMidi.SUPPORTED_USB_IDS}" +
                "; interface 1/3\n\n$usb\n\n$midi\n\nConnection trace:\n" +
                state.connectionDiagnostics.ifEmpty { "No connection attempt recorded." }
    }

    /** Refreshes candidates without guessing which device to use when several match. */
    fun refresh() {
        if (state.discoverySuppressedByUser) return
        val setupComplete = application.getSharedPreferences("setup", Context.MODE_PRIVATE)
            .getBoolean("complete", false)
        val batteryExempt = application.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(application.packageName)
        if (!setupComplete || !batteryExempt) {
            if (!state.busy) {
                state = state.copy(
                    device = null, multipleDevices = false, deviceMode = DeviceMode.SEARCHING,
                    ready = false, discoveryActive = false, firmwareIdentity = null,
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
                CONNECTION_STATUS_PREFIXES.any { state.status.heading.startsWith(it) }
        if (mode != DeviceMode.DOWNLOAD || device?.deviceName != loaderDeviceName) loaderDeviceName = null
        state = state.copy(
            device = device,
            multipleDevices = mode == DeviceMode.MULTIPLE,
            deviceMode = mode,
            discoveryActive = true,
            firmwareIdentity = if (retainFirmware) state.firmwareIdentity else null,
            ready = state.ready && mode == DeviceMode.DOWNLOAD && device?.deviceName == activeDeviceName,
            // A removed FM-1 must fall through to the no-connection state, not stale text.
            status = if (staleConnectionStatus) StatusMessage() else state.status,
        ).withCurrentBackupHash()
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
        state = state.copy(discoverySuppressedByUser = false)
        userDisconnectedDeviceName = null
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
            status = StatusMessage(),
            hash = "",
            progress = 0f,
            findingBootloader = false,
            restoreRetryAvailable = false
        )
        retryRestoreSource = null
        worker.execute {
            try {
                closeSession()
            } catch (failure: IOException) {
                Log.w("BackupModel", "Could not close previous USB session during refresh", failure)
            }
        }
        refresh()
    }

    /** Closes the idle session and waits for an explicit Refresh before discovering the device again. */
    fun disconnect() {
        if (state.busy || !state.ready) return
        userDisconnectedDeviceName = state.device?.deviceName ?: activeDeviceName
        firmwareDeviceName = null
        firmwareMidiId = null
        queriedMidiId = null
        pendingPermission = null
        retryRestoreSource = null
        mainHandler.removeCallbacks(discoveryTimeout)
        discoveryTimerPending = false
        state = state.disconnectByUser()
        worker.execute {
            try {
                closeSession()
            } catch (failure: IOException) {
                Log.w("BackupModel", "Could not close USB session after disconnect", failure)
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
                    val status = when {
                        !stillConnected -> StatusMessage()
                        identity == null -> statusMessage(
                            "Firmware identity unavailable",
                            "Connecting will automatically restart the FM-1 into recovery mode",
                        )

                        else -> statusMessage(
                            "Ready to connect to FM-1",
                            "The FM-1 will automatically restart into recovery mode",
                        )
                    }
                    state = state.copy(
                        busy = false,
                        readingFirmwareIdentity = false,
                        firmwareIdentity = identity.takeIf { stillConnected },
                        status = status,
                    )
                }
            } catch (failure: Exception) {
                Log.w("BackupModel", "Refresh identity query failed", failure)
                post {
                    it.copy(
                        busy = false,
                        readingFirmwareIdentity = false,
                        status = statusMessage("Firmware query failed", failure.message ?: "Unknown FM-1 error"),
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
                status = statusMessage("USB permission denied", "Tap Connect to FM-1 to request it again"),
            )
        }
    }

    /** Invalidates idle sessions; active transfers handle the expected loader reboot themselves. */
    fun detached(device: UsbDevice?) {
        val permissionDetached = device != null && device.deviceName == pendingPermission
        val userDisconnectedDeviceDetached = device != null &&
                (device.deviceName == userDisconnectedDeviceName || AndroidFm1Midi.isSupportedDevice(device))
        val supportedDeviceDetached = device != null &&
                (device.deviceName == state.device?.deviceName ||
                        device.deviceName == activeDeviceName ||
                        device.deviceName == userDisconnectedDeviceName ||
                        AndroidFm1Midi.isSupportedDevice(device))
        if (userDisconnectedDeviceDetached) {
            userDisconnectedDeviceName = null
            state = state.copy(discoverySuppressedByUser = false)
        }
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
        if (state.busy || state.libraryLoading || v15RecoveryState.working) return
        v15RecoveryState = V15RecoveryState(showDialog = true)
    }

    fun dismissV15RecoveryDialog() {
        if (!v15RecoveryState.working) v15RecoveryState = V15RecoveryState()
    }

    fun createV15RecoveryBackup() {
        if (!v15RecoveryState.showDialog || v15RecoveryState.working || state.busy ||
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
                val entry = backupStore.publish(
                    file,
                    V15RecoveryImage.IMAGE_SHA256,
                    V15_RECOVERY_BACKUP_NAME,
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
                        message = "FM-1_015 RECOVERY was added to your backup library. It has not been restored.",
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

    fun transferStartFailed(message: String) {
        state = state.copy(status = statusMessage("Transfer could not start", "$message No flash changes requested"))
    }

    private fun progressReporter(phases: List<ProgressPhase>, maximumFraction: Float = 1f) =
        ProgressReporter(SystemClock::elapsedRealtime, phases, maximumFraction) { status, fraction ->
            post { currentState -> currentState.copy(status = status, progress = fraction, progressKnown = true) }
        }

    private fun backupProgressReporter() = progressReporter(
        listOf(
            ProgressPhase(
                "Reading FM-1 flash", FlashRestore.FLASH_SIZE.toLong(),
                statusMessage("Backing up FM-1", "Reading flash"), weight = 49.5,
            ),
            ProgressPhase(
                "Verifying: reading FM-1 flash again", FlashRestore.FLASH_SIZE.toLong(),
                statusMessage("Backing up FM-1", "Validating flash"), weight = 49.5,
            ),
            ProgressPhase(
                "Verifying saved backup", FlashRestore.FLASH_SIZE.toLong(),
                statusMessage("Backing up FM-1", "Checking the saved copy"), weight = 1.0,
            ),
        ),
        maximumFraction = 0.999f,
    )

    private fun restoreProgressReporter() = progressReporter(
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
                "Writing to FM-1 flash", FlashRestore.WRITABLE_SIZE.toLong(),
                statusMessage("Restoring FM-1", "Writing application flash")
            ),
            ProgressPhase(
                "Verifying: reading back written data", FlashRestore.WRITABLE_SIZE.toLong(),
                statusMessage("Restoring FM-1", "Validating flash")
            ),
        )
    )

    private fun batterySetupApproved(): Boolean {
        if (application.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(application.packageName)
        ) return true
        state = state.copy(
            status = statusMessage(
                "Backup and restore are blocked", "Allow background operation in Settings"
            )
        )
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
                    throw ProtocolException("Loader reconnected: tap Refresh and Connect to grant USB permission again")
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
        throw ProtocolException("Loader did not reconnect: ${lastError?.message ?: "FM-1 missing"}")
    }

    /** Sends the FM1-only SysEx once, retaining MIDI handles until USB reboot is observed or waiting ends. */
    fun enterBootloader() {
        if (state.busy || state.ready || state.multipleDevices) return
        val knownFirmwareIdentity = state.firmwareIdentity
        cancelled.set(false)
        state = state.copy(
            busy = true, ready = false, findingBootloader = true,
            hash = "", progress = 0f,
            status = statusMessage("Switching FM-1 to bootloader", "Preparing the recovery connection")
        )
        firmwareDeviceName = null
        worker.execute {
            var sysExSent = false
            var firmwareIdentity: String? = knownFirmwareIdentity
            try {
                closeSession()
                val previousDevices = AndroidUsb.devices(manager).map { device -> device.deviceName }.toSet()
                val midi = AndroidFm1Midi.sendBootloader(application, ::checkCancelled) { identity ->
                    if (identity != null) {
                        firmwareIdentity = identity
                        post { it.copy(firmwareIdentity = identity) }
                    }
                }
                val bootloaderDevice = midi.use {
                    sysExSent = true
                    post { currentState ->
                        currentState.copy(
                            status = statusMessage("Waiting for FM-1", "Switching to bootloader mode")
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
                        status = statusMessage("Connecting to FM-1", "Checking recovery mode and flash")
                    )
                }
            } catch (failure: Exception) {
                // UI boundary: report failures rather than silently trying another MIDI device.
                mainHandler.post {
                    refresh()
                    val message = when (failure) {
                        is CancellationException -> if (sysExSent) {
                            statusMessage(
                                "Stopped waiting", "The SysEx was already sent; the FM-1 may still reboot. Tap Refresh"
                            )
                        } else {
                            statusMessage("Bootloader request cancelled", "No bootloader request was sent")
                        }

                        else -> statusMessage("Bootloader request failed", failure.message ?: "Unknown FM-1 error")
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
        throw ProtocolException("Bootloader USB interface did not appear. Check the cable and tap Refresh.")
    }

    /** Identifies WL82, starts the fixed loader if required, and probes NOR before enabling backup. */
    fun connect() {
        if ((state.busy && !state.autoConnectPending) || state.ready) return
        val device = state.device ?: return
        val firmwareIdentity = state.firmwareIdentity
        synchronized(connectionTrace) { connectionTrace.clear() }
        traceConnection(
            "Connecting USB %04X:%04X %s permission=%s".format(
                device.vendorId, device.productId, device.deviceName, manager.hasPermission(device),
            )
        )
        cancelled.set(false)
        state = state.copy(
            busy = true, autoConnectPending = false, ready = false, hash = "", progress = 0f,
            status = statusMessage("Connecting to FM-1", "Checking recovery mode and flash")
        )
        worker.execute {
            try {
                closeSession()
                var deviceProtocol = open(device)
                val identity = deviceProtocol.identify()
                traceConnection("Initial inquiry: ${identity.product}, revision=${identity.revision}")
                var needsUpload = false
                if (identity.needsLoader) {
                    // Only trust a loader this app started on this exact FM-1. Probing an
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
                checkCancelled()
                post { currentState ->
                    val currentDevice = AndroidUsb.devices(manager).singleOrNull()
                    val retainedIdentity = firmwareIdentity.takeIf { currentDevice?.deviceName == activeDeviceName }
                    firmwareDeviceName = activeDeviceName.takeIf { retainedIdentity != null }
                    loaderDeviceName = activeDeviceName
                    currentState.copy(
                        busy = false, ready = true,
                        firmwareIdentity = retainedIdentity,
                        status = statusMessage("FM-1 successfully connected", "Ready to backup or restore full flash")
                    )
                }
            } catch (failure: Exception) {
                traceConnection("Connection failed: ${failure.javaClass.simpleName}: ${failure.message}")
                // Operation boundary: all failures invalidate readiness; none trigger an alternate loader.
                closeSession()
                post { currentState ->
                    currentState.copy(
                        busy = false, ready = false,
                        status = statusMessage("Connection failed", failure.message ?: "Unknown FM-1 error")
                    )
                }
            }
        }
    }

    /** Saves a full 1 MiB image privately, publishing only after device and saved-file verification. */
    fun backup() {
        if (!batterySetupApproved()) return
        if (state.busy || !state.ready || state.libraryLoading) return
        val size = FlashRestore.FLASH_SIZE.toLong()
        if (!beginTransfer(FlashOperation.BACKUP)) return
        val firmwareIdentity = state.firmwareIdentity
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
                val progress = backupProgressReporter()
                val hash = BufferedOutputStream(file.outputStream(), 65536).use { output ->
                    FlashBackup(deviceProtocol::readFlash).run(size, output, ::checkCancelled) { phase, bytes ->
                        progress.report(phase, bytes, size)
                    }
                }
                val entry = backupStore.publish(
                    file, hash, firmwareIdentity, ::checkCancelled,
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
                            "Backup saved", "FM-1 flash was read twice and verified against the saved copy"
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
                                "${failure.message ?: "Unknown FM-1 error"}. $cleanupMessage"
                            )
                        }
                    )
                }
            } finally {
                finishTransfer(outcome, result)
            }
        }
    }

    /** Validates an external image before asking for the name of its private library copy. */
    fun selectImportFile(uri: Uri) {
        if (state.busy || state.libraryLoading) return
        cancelled.set(false)
        state = state.copy(busy = true, selectedImport = null)
        worker.execute {
            selectedImportBytes = null
            try {
                val resolver = application.contentResolver
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) throw ProtocolException("Could not read import filename")
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } ?: throw ProtocolException("Could not read import filename")
                RestoreImage.requireBinaryName(name)
                val output = ByteArrayOutputStream(FlashRestore.FLASH_SIZE)
                val hash = resolver.openInputStream(uri)?.use { input ->
                    RestoreImage.stage(input, output, ::checkCancelled)
                } ?: throw ProtocolException("Could not open import source")
                selectedImportBytes = output.toByteArray()
                post { it.copy(busy = false, selectedImport = SelectedImport(name, hash)) }
            } catch (failure: Exception) {
                selectedImportBytes = null
                post {
                    it.copy(
                        busy = false, selectedImport = null,
                        status = if (failure is CancellationException) {
                            statusMessage("Import validation cancelled", "No backup was added")
                        } else {
                            statusMessage("Import file rejected", failure.message ?: "Choose a raw .bin backup file")
                        }
                    )
                }
            }
        }
    }

    fun cancelImport() {
        if (state.busy || state.selectedImport == null) return
        selectedImportBytes = null
        state = state.copy(selectedImport = null)
    }

    fun saveImportedBackup(name: String) {
        val selected = state.selectedImport ?: return
        if (state.busy || state.libraryLoading) return
        val identity = name.trim()
        if (identity.isEmpty() || identity.length > BackupStore.MAX_NAME_LENGTH ||
            identity.any { it.code !in 32..126 }
        ) {
            state = state.copy(
                status = statusMessage("File name required", "Enter a printable ASCII name of up to ${BackupStore.MAX_NAME_LENGTH} characters")
            )
            return
        }
        state = state.copy(
            busy = true,
            status = statusMessage("Saving imported backup", "Adding the verified file to this app's backup library"),
        )
        worker.execute {
            var pending: File? = null
            try {
                val image = selectedImportBytes
                    ?: throw ProtocolException("Validated import is no longer available; select the file again")
                val file = backupStore.createPending()
                pending = file
                BufferedOutputStream(file.outputStream(), 65536).use { it.write(image) }
                val entry = backupStore.publish(file, selected.sha256, identity)
                pending = null
                selectedImportBytes = null
                post { current ->
                    current.copy(
                        busy = false,
                        selectedImport = null,
                        backups = (current.backups + entry)
                            .sortedWith(compareByDescending<BackupEntry> { it.createdAt }.thenByDescending { it.id }),
                        status = statusMessage("Backup imported", "The verified file was added to this app's backup library"),
                    )
                }
            } catch (failure: Exception) {
                val deleted = try {
                    pending?.let(backupStore::discard) ?: true
                } catch (cleanupFailure: Exception) {
                    Log.w("BackupModel", "Could not remove incomplete imported backup", cleanupFailure)
                    false
                }
                val cleanupMessage = if (deleted) "" else " An incomplete private file could not be removed."
                post {
                    it.copy(
                        busy = false,
                        status = statusMessage(
                            "Import failed",
                            "${failure.message ?: "Could not save the verified backup"}.$cleanupMessage",
                        ),
                    )
                }
            }
        }
    }

    fun restoreSavedBackup(id: String) {
        if (!batterySetupApproved()) return
        if (state.backups.none { it.id == id }) return
        restoreSource(openSource = { backupStore.open(id) })
    }

    fun retryRestore() {
        if (state.busy || !state.restoreRetryAvailable) return
        val source = retryRestoreSource ?: return
        restoreSource(source, retrying = true)
    }

    private fun restoreSource(openSource: () -> InputStream, retrying: Boolean = false) {
        if (!batterySetupApproved()) return
        if (state.busy || (!state.ready && !retrying)) return
        if (!beginTransfer(FlashOperation.RESTORE)) return
        if (!retrying) retryRestoreSource = openSource
        firmwareDeviceName = null
        firmwareMidiId = null
        queriedMidiId = null
        cancelled.set(false)
        state = state.copy(
            busy = true, transfer = FlashOperation.RESTORE,
            progressKnown = false, hash = "", progress = 0f, firmwareIdentity = null,
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
                val progress = restoreProgressReporter()
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
                FlashRestore(deviceProtocol).run(
                    snapshot, FlashRestore.FLASH_SIZE.toLong(), ::checkCancelled,
                    mutationStarting = { mutationStarted = true },
                    progress = progress::report,
                )
                // Reset only after verification; the device usually disconnects without replying.
                try {
                    deviceProtocol.runApp()
                } catch (failure: Exception) {
                    Log.i("BackupModel", "FM-1 reset reply not received", failure)
                }
                try {
                    closeSession()
                } catch (closeFailure: Exception) {
                    Log.w("BackupModel", "Could not close session after reboot", closeFailure)
                }
                result = { currentState ->
                    retryRestoreSource = null
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
                        "The FM-1 remains in bootloader mode. Retry to rewrite and verify the application flash",
                    )
                } else if (mutationStarted) {
                    val heading =
                        if (failure is CancellationException) "Restore cancelled" else "Restore may be incomplete"
                    val cause = if (failure is CancellationException) "Restore stopped while writing flash"
                    else failure.message ?: "The FM-1 reported an error while writing flash"
                    statusMessage(
                        heading,
                        "$cause. Flash changes may be incomplete. The protected bootloader was not overwritten"
                    )
                } else {
                    val heading = if (failure is CancellationException) "Restore cancelled" else "Restore failed"
                    val cause = if (failure is CancellationException) "No flash changes were made"
                    else failure.message ?: "The FM-1 could not complete the restore"
                    statusMessage(
                        heading,
                        "$cause. The FM-1 was not changed. Check the backup and connection, then try again"
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
                    val pinned = if (id in current.pinnedBackupIds) current.pinnedBackupIds - id + renamed.id
                    else current.pinnedBackupIds
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

    fun showFirmwareExportDialog() {
        if (state.busy || state.libraryLoading || v15RecoveryState.working || state.backups.isEmpty()) return
        firmwareExportState = FirmwareExportState(
            showDialog = true,
            entries = BackupStore.uniqueExports(state.backups),
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
        cancelled.set(false)
        state = state.copy(busy = true, hash = "")
        firmwareExportState = firmwareExportState.copy(working = true, message = "")
        worker.execute {
            val message = try {
                val destination = application.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("Could not open export destination")
                var lastProgress = -1
                backupStore.exportZip(entries, destination, ::checkCancelled) { bytes, total ->
                    val progress = (bytes * 100 / total).toInt()
                    if (progress != lastProgress) {
                        lastProgress = progress
                        mainHandler.post {
                            firmwareExportState = firmwareExportState.copy(progress = progress)
                        }
                    }
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
        if (state.busy) return
        if (state.backups.none { it.id == id }) return
        cancelled.set(false)
        state = state.copy(
            busy = true, hash = "", progress = 0f,
            hashLabel = "SHA-256",
            status = statusMessage("Exporting backup", "Saving a verified copy to the selected location")
        )
        worker.execute {
            try {
                backupStore.open(id).use { source ->
                    val destination = application.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Could not open export destination")
                    destination.buffered().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            checkCancelled()
                            val count = source.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                post {
                    it.copy(
                        busy = false, hash = "",
                        status = statusMessage("Backup exported successfully", "Saved to the selected location")
                    )
                }
            } catch (failure: Exception) {
                val deleted = deleteIncompleteDocument(uri)
                post {
                    it.copy(
                        busy = false, status = statusMessage(
                            "Export failed",
                            "${failure.message ?: "The selected location could not save the backup"}. " +
                                    if (deleted) "The incomplete copy was removed; the private backup is unchanged"
                                    else "Delete the incomplete copy manually; the private backup is unchanged",
                        )
                    )
                }
            }
        }
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
        worker.execute { closeSession() }
        worker.shutdown()
    }

    private companion object {
        const val V15_RECOVERY_BACKUP_NAME = "FM-1_015 RECOVERY"
        const val PINNED_BACKUP_IDS = "pinned_backup_ids"
        const val KEEPALIVE_INTERVAL_MS = 1000L
        const val DISCOVERY_TIMEOUT_MS = 3000L
    }
}
