package com.workeffortwaste

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.workeffortwaste.protocol.BulkIo
import com.workeffortwaste.protocol.ProtocolException

/** Android USB Host adapter; discovery narrows candidates but protocol inquiry validates WL82. */
object AndroidUsb {
    /** Returns deterministic JieLi candidates with exactly one usable Mass Storage BOT interface. */
    fun devices(manager: UsbManager): List<UsbDevice> = manager.deviceList.values.filter { device ->
        device.vendorId == 0x4c4a && massStorageInterfaces(device).size == 1
    }.sortedBy { device -> device.deviceName }

    /** Requires the SCSI transparent command set and BOT, not merely a matching USB vendor. */
    private fun massStorageInterfaces(device: UsbDevice): List<UsbInterface> =
        (0 until device.interfaceCount).map(device::getInterface).filter { usbInterface ->
            usbInterface.interfaceClass == 0x08 && usbInterface.interfaceSubclass == 0x06 &&
                usbInterface.interfaceProtocol == 0x50 && bulkEndpoints(usbInterface) != null
        }

    /** Input first, output second; ambiguous endpoint layouts are rejected instead of guessed. */
    private fun bulkEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val bulk = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
            .filter { endpoint -> endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val input = bulk.singleOrNull { endpoint -> endpoint.direction == UsbConstants.USB_DIR_IN } ?: return null
        val output = bulk.singleOrNull { endpoint -> endpoint.direction == UsbConstants.USB_DIR_OUT } ?: return null
        return input to output
    }

    /** Claims the sole BOT interface after permission; the returned handle owns its connection. */
    fun open(manager: UsbManager, device: UsbDevice): BulkIo {
        check(manager.hasPermission(device)) { "Grant USB permission, then connect again" }
        val usbInterface = massStorageInterfaces(device).singleOrNull()
            ?: throw ProtocolException("Expected exactly one Mass Storage BOT interface")
        val (input, output) = bulkEndpoints(usbInterface) ?: throw ProtocolException("Missing bulk endpoints")
        val connection = manager.openDevice(device) ?: throw ProtocolException("Could not open the FM-1 USB connection")
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            throw ProtocolException("Could not claim USB interface; disconnect other USB apps")
        }
        return ConnectionIo(connection, usbInterface, input, output)
    }

    /**
     * Owns one claimed interface. Transfer results are validated by BotTransport, never retried here.
     * @property connection Hardware handle closed even if releasing the interface fails.
     * @property usbInterface Claimed Mass Storage BOT interface.
     * @property input Device-to-host bulk endpoint for payloads and command status.
     * @property output Host-to-device bulk endpoint for command wrappers and loader blocks.
     */
    private class ConnectionIo(
        private val connection: UsbDeviceConnection,
        private val usbInterface: UsbInterface,
        private val input: UsbEndpoint,
        private val output: UsbEndpoint,
    ) : BulkIo {
        override fun write(data: ByteArray) =
            connection.bulkTransfer(output, data, data.size, 5000)

        override fun read(data: ByteArray) =
            connection.bulkTransfer(input, data, data.size, 5000)

        override fun reset() {
            // BOT reset recovery: class-specific reset, then clear both endpoint halts.
            // Return codes cannot salvage this session; BotTransport already marked it unusable.
            connection.controlTransfer(0x21, 0xff, 0, usbInterface.id, null, 0, 1000)
            connection.controlTransfer(0x02, 0x01, 0, input.address, null, 0, 1000)
            connection.controlTransfer(0x02, 0x01, 0, output.address, null, 0, 1000)
        }

        override fun close() {
            try {
                connection.releaseInterface(usbInterface)
            } finally {
                connection.close()
            }
        }
    }
}
