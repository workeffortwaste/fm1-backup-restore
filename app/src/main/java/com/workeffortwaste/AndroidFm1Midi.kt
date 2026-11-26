package com.workeffortwaste

import android.content.Context
import android.hardware.usb.UsbDevice
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.util.Log
import com.workeffortwaste.protocol.Fm1BootloaderMidi
import com.workeffortwaste.protocol.FirmwareIdentity
import com.workeffortwaste.protocol.MidiIdentityEvidence
import com.workeffortwaste.protocol.ProtocolException
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Resolves a USB MIDI command route and owns its asynchronous Android handle lifecycle. */
object AndroidFm1Midi {
    @Suppress("DEPRECATION")
    private fun availableDevices(manager: MidiManager): List<MidiDeviceInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            manager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        } else {
            manager.devices.toList()
        }

    @Suppress("DEPRECATION")
    fun diagnostics(manager: MidiManager): String {
        val devices = availableDevices(manager).filter { it.type == MidiDeviceInfo.TYPE_USB }
        if (devices.isEmpty()) return "Android MIDI: no supported USB MIDI devices found."
        val matchedIds = devices(manager).map { it.id }.toSet()
        return devices.joinToString("\n") { info ->
            val usb = info.properties.getParcelable<UsbDevice>(MidiDeviceInfo.PROPERTY_USB_DEVICE)
            val id = usb?.let { "%04X:%04X".format(it.vendorId, it.productId) }
                ?: "USB descriptor unavailable"
            "Android MIDI #${info.id}: $id; " +
                    "${info.inputPortCount} input, ${info.outputPortCount} output; " +
                    if (info.id in matchedIds) "ID match" else "not matched"
        }
    }

    /**
     * Uses the pre-API-33 discovery/parcelable APIs for Android 8+ compatibility.
    * Matches supported normal-mode USB IDs and MIDI interfaces, never product or port names.
     */
    @Suppress("DEPRECATION")
    fun devices(manager: MidiManager): List<MidiDeviceInfo> =
        availableDevices(manager).filter { info ->
            if (info.type != MidiDeviceInfo.TYPE_USB) return@filter false
            val properties = info.properties
            val usbDevice = properties.getParcelable<UsbDevice>(MidiDeviceInfo.PROPERTY_USB_DEVICE)
            usbDevice?.let(::isSupportedDevice) ?: false
        }

    fun isSupportedDevice(device: UsbDevice): Boolean = Fm1BootloaderMidi.matchesUsb(
        device.vendorId,
        device.productId,
        (0 until device.interfaceCount).any { index ->
            val usbInterface = device.getInterface(index)
            usbInterface.interfaceClass == 1 && usbInterface.interfaceSubclass == 3
        },
    )

    /**
     * Called only on the model worker, since opening MIDI polls a main-thread callback.
     * The caller must retain and close the returned handle after reboot detection or failure:
    * Android's input port sends *to* the device, and closing it immediately can discard queued SysEx.
     */
    fun sendBootloader(
        context: Context,
        checkCancelled: () -> Unit,
        onIdentity: (MidiIdentityEvidence) -> Unit = {},
    ): Closeable = openMidiSession(context, checkCancelled, onIdentity, reboot = true)

    fun queryIdentity(context: Context, deviceId: Int, checkCancelled: () -> Unit): MidiIdentityEvidence {
        var identity = MidiIdentityEvidence()
        openMidiSession(
            context, checkCancelled, { identity = it }, reboot = false,
            expectedDeviceId = deviceId
        ).use { }
        return identity
    }

    private fun openMidiSession(
        context: Context,
        checkCancelled: () -> Unit,
        onIdentity: (MidiIdentityEvidence) -> Unit,
        reboot: Boolean,
        expectedDeviceId: Int? = null,
    ): Closeable {
        checkCancelled()
        val manager = context.getSystemService(MidiManager::class.java)
            ?: throw ProtocolException("Android MIDI is unavailable on this phone")
        val devices = devices(manager)
        if (devices.isEmpty()) {
            throw ProtocolException(
                "No supported USB MIDI interface found. Connect a supported device in normal MIDI mode and tap again."
            )
        }
        val deviceInfo = devices.singleOrNull()
            ?: throw ProtocolException("More than one supported USB MIDI interface found. Connect only one device.")
        if (expectedDeviceId != null && deviceInfo.id != expectedDeviceId) {
            throw ProtocolException("Supported MIDI device changed during discovery. Refresh again.")
        }
        val inputPorts = deviceInfo.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
            .map { it.portNumber }
        val outputPorts = deviceInfo.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
            .map { it.portNumber }

        val midiDevice = openDevice(manager, deviceInfo, checkCancelled)
        try {
            checkCancelled()
            val route = Fm1BootloaderMidi.resolveIdentityRoute(inputPorts) { number ->
                checkCancelled()
                midiDevice.openInputPort(number)?.use { candidate ->
                    readIdentity(midiDevice, outputPorts, candidate, checkCancelled,
                        FirmwareIdentity.request(), FirmwareIdentity::parse)
                }
            }
            val inputPort = route.inputPort
            if (inputPort == null) {
                onIdentity(MidiIdentityEvidence(mUpgradeIdentity = route.identity))
                if (reboot) throw ProtocolException("Could not verify a unique MIDI command port. Restart was withheld.")
                return midiDevice
            }
            val port = midiDevice.openInputPort(inputPort)
                ?: throw ProtocolException(
                    "Supported MIDI port is busy or disconnected. The device might be connected in another app or browser."
                )
            try {
                checkCancelled()
                @Suppress("DEPRECATION")
                val usb = deviceInfo.properties.getParcelable<UsbDevice>(MidiDeviceInfo.PROPERTY_USB_DEVICE)
                val identity = FirmwareIdentity.query(
                    includeLegacyEditor = usb?.vendorId == 0x1209 && usb.productId == 1,
                    knownMUpgradeIdentity = route.identity.takeUnless { reboot },
                    probeBuildCode = { mUpgradeIdentity ->
                        // Only Onyx-family Pocket AMP firmware answers; skip its timeout elsewhere.
                        mUpgradeIdentity == null ||
                                DeviceFamily.fromMidiIdentity(mUpgradeIdentity) == DeviceFamily.POCKET_AMP
                    },
                ) { request, parse ->
                    readIdentity(midiDevice, outputPorts, port, checkCancelled, request, parse)
                }
                onIdentity(identity.copy(mUpgradeIdentity = identity.mUpgradeIdentity ?: route.identity))
                checkCancelled()
                if (reboot) {
                    if (route.identity != null && identity.mUpgradeIdentity != route.identity) {
                        throw ProtocolException("The MIDI command port could not be reverified. Restart was withheld.")
                    }
                    Fm1BootloaderMidi.send { bytes -> port.send(bytes, 0, bytes.size) }
                }
            } catch (failure: Exception) {
                // Cleanup boundary only: send and cancellation errors must propagate without retry.
                closeAfterFailure(port, failure)
                throw failure
            }
            // send() queues data: keep the hardware port alive until reboot is observed.
            // MIDI flush() discards queued messages; it is not a delivery barrier.
            val closed = AtomicBoolean()
            return Closeable {
                if (!closed.compareAndSet(false, true)) return@Closeable
                try {
                    closeAfterDisconnect(port)
                } catch (failure: Exception) {
                    closeAfterFailure(midiDevice, failure)
                    throw failure
                }
                closeAfterDisconnect(midiDevice)
            }
        } catch (failure: Exception) {
            closeAfterFailure(midiDevice, failure)
            throw failure
        }
    }

    private fun readIdentity(
        device: MidiDevice,
        outputPorts: List<Int>,
        input: MidiInputPort,
        checkCancelled: () -> Unit,
        request: ByteArray,
        parse: (ByteArray) -> String?,
    ): String? {
        val response = CompletableFuture<String>()
        val outputs = mutableListOf<MidiOutputPort>()
        try {
            for (number in outputPorts) {
                checkCancelled()
                val output = device.openOutputPort(number) ?: continue
                outputs += output
                output.connect(identityReceiver(parse, response))
            }
            if (outputs.isEmpty()) return null
            input.send(request, 0, request.size)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline) {
                checkCancelled()
                try {
                    return response.get(100, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // Keep cancellation responsive while waiting for a reply.
                }
            }
            return null
        } finally {
            outputs.forEach(::closeAfterDisconnect)
        }
    }

    // Each output has its own frame buffer because MIDI callbacks can arrive concurrently.
    private fun identityReceiver(
        parse: (ByteArray) -> String?,
        response: CompletableFuture<String>,
    ): MidiReceiver = object : MidiReceiver() {
        private val frame = ArrayList<Byte>(600)

        @Synchronized
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (index in offset until offset + count) {
                val byte = data[index]
                val value = byte.toInt() and 0xff
                if (value >= 0xf8) continue
                if (value == 0xf0) frame.clear()
                if (value == 0xf0 || frame.isNotEmpty()) {
                    frame.add(byte)
                    if (value == 0xf7) {
                        parse(frame.toByteArray())?.let(response::complete)
                        frame.clear()
                    } else if (frame.size >= 600) frame.clear()
                }
            }
        }
    }

    /** Owns the asynchronous open until a checked handle is handed to the sender. */
    private fun openDevice(
        manager: MidiManager,
        deviceInfo: MidiDeviceInfo,
        checkCancelled: () -> Unit,
    ): MidiDevice {
        val openedDevice = CompletableFuture<MidiDevice?>()
        try {
            manager.openDevice(deviceInfo, { device ->
                // Completion loses to cancellation/timeout, so late handles belong to this callback.
                if (!openedDevice.complete(device)) closeAfterDisconnect(device)
            }, Handler(Looper.getMainLooper()))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                checkCancelled()
                if (System.nanoTime() >= deadline) {
                    throw ProtocolException("Timed out opening MIDI device; no SysEx was sent")
                }
                val device = try {
                    openedDevice.get(100, TimeUnit.MILLISECONDS)
                        ?: throw ProtocolException("Could not open MIDI device; no SysEx was sent")
                } catch (_: TimeoutException) {
                    // Poll so cancellation does not have to wait for the overall deadline.
                    continue
                }
                checkCancelled()
                return device
            }
        } catch (failure: Exception) {
            // Also covers cancellation after completion but before the worker consumes the handle.
            openedDevice.complete(null)
            openedDevice.getNow(null)?.let { device -> closeAfterFailure(device, failure) }
            throw failure
        }
    }

    private fun closeAfterFailure(handle: Closeable, failure: Exception) {
        try {
            closeAfterDisconnect(handle)
        } catch (closeFailure: Exception) {
            failure.addSuppressed(closeFailure)
        }
    }

    /** Reboot intentionally removes MIDI hardware; only its expected I/O close failures are tolerated. */
    private fun closeAfterDisconnect(handle: Closeable?) {
        try {
            handle?.close()
        } catch (failure: IOException) {
            Log.w("AndroidFm1Midi", "MIDI handle close failed after disconnect", failure)
        }
    }
}
