package com.workeffortwaste

import android.content.Context
import android.hardware.usb.UsbDevice
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.util.Log
import com.workeffortwaste.protocol.Fm1BootloaderMidi
import com.workeffortwaste.protocol.FirmwareIdentity
import com.workeffortwaste.protocol.ProtocolException
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Resolves a single FM1 USB MIDI input and owns its asynchronous Android handle lifecycle. */
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
        if (devices.isEmpty()) return "Android MIDI: no FM-1 MIDI interfaces."
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
     * Matches only the normal-mode USB ID and MIDI interface, never product or port names.
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
     * Android's input port sends *to* the FM1, and closing it immediately can discard queued SysEx.
     */
    fun sendBootloader(
        context: Context,
        checkCancelled: () -> Unit,
        onFirmwareIdentity: (String?) -> Unit = {},
    ): Closeable = openMidiSession(context, checkCancelled, onFirmwareIdentity, reboot = true)

    fun queryIdentity(context: Context, deviceId: Int, checkCancelled: () -> Unit): String? {
        var identity: String? = null
        openMidiSession(
            context, checkCancelled, { identity = it }, reboot = false,
            expectedDeviceId = deviceId
        ).use { }
        return identity
    }

    private fun openMidiSession(
        context: Context,
        checkCancelled: () -> Unit,
        onFirmwareIdentity: (String?) -> Unit,
        reboot: Boolean,
        expectedDeviceId: Int? = null,
    ): Closeable {
        checkCancelled()
        val manager = context.getSystemService(MidiManager::class.java)
            ?: throw ProtocolException("Android MIDI is unavailable on this phone")
        val devices = devices(manager)
        if (devices.isEmpty()) {
            throw ProtocolException("No FM-1 USB MIDI interface found. Connect the FM-1 in normal MIDI mode and tap again.")
        }
        val deviceInfo = devices.singleOrNull()
            ?: throw ProtocolException("More than one FM-1 MIDI interface found. Connect only one FM-1.")
        if (expectedDeviceId != null && deviceInfo.id != expectedDeviceId) {
            throw ProtocolException("FM-1 MIDI connection changed during discovery. Tap Refresh again.")
        }
        val inputPort = deviceInfo.ports.singleOrNull { port -> port.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
            ?: throw ProtocolException("Expected one FM-1 MIDI input port; no SysEx was sent")

        val midiDevice = openDevice(manager, deviceInfo, checkCancelled)
        try {
            checkCancelled()
            val port = midiDevice.openInputPort(inputPort.portNumber)
                ?: throw ProtocolException("FM-1 MIDI port is busy or disconnected; no SysEx was sent")
            try {
                checkCancelled()
                val send: (ByteArray) -> Unit = { bytes ->
                    port.send(bytes, 0, bytes.size)
                }

                @Suppress("DEPRECATION")
                val usb = deviceInfo.properties.getParcelable<UsbDevice>(MidiDeviceInfo.PROPERTY_USB_DEVICE)
                val editorIdentity = if (usb?.vendorId == 0x1209 && usb.productId == 1) {
                    readIdentity(
                        midiDevice, deviceInfo, send, checkCancelled,
                        FirmwareIdentity.editorRequest(), FirmwareIdentity::parseEditor
                    )
                } else null
                val identity = editorIdentity ?: readIdentity(
                    midiDevice, deviceInfo, send, checkCancelled,
                    FirmwareIdentity.request(), FirmwareIdentity::parse
                )
                onFirmwareIdentity(identity)
                checkCancelled()
                if (reboot) Fm1BootloaderMidi.send { bytes -> port.send(bytes, 0, bytes.size) }
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
        info: MidiDeviceInfo,
        send: (ByteArray) -> Unit,
        checkCancelled: () -> Unit,
        request: ByteArray,
        parse: (ByteArray) -> String?,
    ): String? {
        val outputInfo = info.ports.singleOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
        if (outputInfo == null) {
            Log.w("AndroidFm1Midi", "Firmware identity unavailable: expected one MIDI output")
            return null
        }
        val output = device.openOutputPort(outputInfo.portNumber)
        if (output == null) {
            Log.w("AndroidFm1Midi", "Firmware identity unavailable: MIDI output busy or disconnected")
            return null
        }
        output.use {
            val response = CompletableFuture<String>()
            val receiver = object : MidiReceiver() {
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
            output.connect(receiver)
            try {
                send(request)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (System.nanoTime() < deadline) {
                    checkCancelled()
                    try {
                        return response.get(100, TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        // Keep cancellation responsive while waiting for a reply.
                    }
                }
                Log.w("AndroidFm1Midi", "Firmware identity unavailable: no valid reply within two seconds")
                return null
            } finally {
                output.disconnect(receiver)
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
                    throw ProtocolException("Timed out opening FM-1 MIDI; no SysEx was sent")
                }
                val device = try {
                    openedDevice.get(100, TimeUnit.MILLISECONDS)
                        ?: throw ProtocolException("Could not open FM-1 MIDI; no SysEx was sent")
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
