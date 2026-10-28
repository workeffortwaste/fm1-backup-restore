package com.workeffortwaste.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Validated WL82 inquiry fields.
 * @property product Download-mode product string, not proof of the physical FM1 model.
 * @property revision Device-reported firmware revision.
 */
data class Fm1Identity(val product: String, val revision: String) {
    /** Initial loader-upload hint only; a running RAM loader may retain this inquiry string. */
    val needsLoader get() = product == "UBOOT1.00"
}

/**
 * Loader-reported flash metadata.
 * @property type JieLi target type; only known SPI NOR targets are accepted.
 * @property id Unsigned JEDEC identifier returned in a little-endian 32-bit field.
 */
data class FlashInfo(val type: Int, val id: Long) {
    /** The two SPI NOR target codes supported by the fixed WL82 loader. */
    val isNor get() = type == 0x03 || type == 0x16
    fun requireFm1Nor() {
        val manufacturer = (id shr 16).toInt() and 0xff
        val memoryType = (id shr 8).toInt() and 0xff
        if (!isNor || id !in 1..0xffffff ||
            manufacturer in listOf(0, 0xff) || memoryType in listOf(0, 0xff) ||
            suggestedSize != FlashRestore.FLASH_SIZE.toLong()
        ) {
            throw ProtocolException("FM-1 requires plausible SPI NOR JEDEC metadata for exactly 1 MiB")
        }
    }

    /** A plausible JEDEC density hint, never a substitute for physical capacity confirmation. */
    val suggestedSize: Long?
        get() {
            if (!isNor || id !in 1..0xffffff) return null
            val densityCode = id.toInt() and 0xff
            return if (densityCode in 16..28) 1L shl densityCode else null
        }
}

/** FM1/WL82 commands with an independent, fixed protected boundary on every flash mutation. */
class Fm1Protocol(private val transport: ScsiTransport) {
    /** Rejects other chip families and unsupported download modes before loader upload is allowed. */
    fun identify(): Fm1Identity {
        val data = transport.execute(byteArrayOf(0x12, 0, 0, 0, 36, 0), 36)
        fun inquiryField(start: Int, end: Int) = data.copyOfRange(start, end)
            .toString(Charsets.US_ASCII).trim()

        val family = inquiryField(8, 16)
        val product = inquiryField(16, 32)
        if (!family.equals("WL82", ignoreCase = true)) {
            throw ProtocolException("Connect an M-VAVE FM-1 (AC791N/WL82); found $family")
        }
        if (!product.startsWith("UBOOT") &&
            !product.startsWith("UDISK") && !product.startsWith("DEVICE")
        ) {
            throw ProtocolException("FM-1 is not in a supported recovery mode: $product")
        }
        return Fm1Identity(product, inquiryField(32, 36))
    }

    /** JieLi uses a two-byte command followed by arguments and mandatory 0xff padding. */
    private fun commandDescriptor(command: Int, arguments: ByteArray): ByteArray {
        require(arguments.size <= 14)
        return ByteArray(16) { 0xff.toByte() }.also { descriptor ->
            descriptor[0] = (command shr 8).toByte()
            descriptor[1] = command.toByte()
            arguments.copyInto(descriptor, 2)
        }
    }

    /** Validates the echoed command before returning the fourteen-byte response payload. */
    private fun response(command: Int, arguments: ByteArray = byteArrayOf()): ByteArray {
        val data = transport.execute(commandDescriptor(command, arguments), 16)
        if ((data[0].toInt() and 0xff) != command shr 8 ||
            (data[1].toInt() and 0xff) != (command and 0xff)
        ) {
            throw ProtocolException("Unexpected JieLi response to %04X".format(command))
        }
        return data.copyOfRange(2, 16)
    }

    /** Reads target type and JEDEC ID without assuming that the density code is trustworthy. */
    fun flashInfo(): FlashInfo {
        val data = response(0xfc0a)
        val targetType = data[0].toInt() and 0xff
        val jedecId = ByteBuffer.wrap(data, 2, 4).order(ByteOrder.LITTLE_ENDIAN)
            .int.toLong() and 0xffffffffL
        return FlashInfo(targetType, jedecId)
    }

    /** Read-only loader capability check; inquiry text alone does not establish readiness. */
    fun probeLoader(): FlashInfo {
        val info = flashInfo()
        info.requireFm1Nor()
        val sample = readFlash(0, 256)
        if (sample.size != 256) throw ProtocolException("Running loader returned an incomplete flash sample")
        return info
    }

    /** Reads one 1–256-byte block within the unsigned 32-bit flash address space. */
    fun readFlash(address: Long, length: Int): ByteArray {
        require(length in 1..256)
        // Subtract the bounded length first so even Long.MAX_VALUE cannot overflow this check.
        require(address in 0..(0x100000000L - length))
        return transport.execute(commandDescriptor(0xfd05, addressLength(address, length)), length)
    }

    /**
     * Erases exactly one 4 KiB sector, never the bootloader or any byte beyond the 1 MiB chip.
     * No chip/block erase alternative is exposed. The echoed reply and BOT status must both pass.
     */
    fun eraseSector(address: Long) {
        require(
            address in FlashRestore.PROTECTED_SIZE.toLong()..
                    (FlashRestore.FLASH_SIZE - FlashRestore.SECTOR_SIZE).toLong()
        )
        require(address % FlashRestore.SECTOR_SIZE == 0L)
        response(0xfb01, ByteBuffer.allocate(4).putInt(address.toInt()).array())
    }

    /**
     * Programs at most one page without implicit erase. Subtraction before range checking prevents
     * overflow even for Long.MAX_VALUE. These checks are mandatory even outside the restore workflow.
     */
    fun writeFlash(address: Long, bytes: ByteArray) {
        require(bytes.size in 1..FlashRestore.PAGE_SIZE)
        require(
            address in FlashRestore.PROTECTED_SIZE.toLong()..
                    (FlashRestore.FLASH_SIZE - bytes.size).toLong()
        )
        require(address % FlashRestore.PAGE_SIZE + bytes.size <= FlashRestore.PAGE_SIZE)
        val crc = crc16(bytes)
        val arguments = addressLength(address, bytes.size) +
                byteArrayOf(0, crc.toByte(), (crc shr 8).toByte())
        transport.execute(commandDescriptor(0xfb04, arguments), output = bytes)
    }

    /** Uploads the already-encoded bundled loader in CRC-protected, cancellable RAM blocks. */
    fun uploadLoader(bytes: ByteArray, checkCancelled: () -> Unit) {
        require(bytes.isNotEmpty() && bytes.size <= 1024 * 1024)
        val blockSize = 512
        var offset = 0
        while (offset < bytes.size) {
            checkCancelled()
            // WL82 ROM expects MengLi data and its bundled loader is already MengLi encoded.
            val block = bytes.copyOfRange(offset, minOf(offset + blockSize, bytes.size))
            val crc = crc16(block)
            val arguments = addressLength(LOADER_ADDRESS + offset, block.size) +
                    byteArrayOf(0, crc.toByte(), (crc shr 8).toByte())
            transport.execute(commandDescriptor(0xfb06, arguments), output = block)
            offset += block.size
        }
    }

    /** Jumps to the fixed WL82 RAM address with the SPI NOR argument; may disconnect before replying. */
    fun startLoader() {
        response(
            0xfb08, ByteBuffer.allocate(6).putInt(LOADER_ADDRESS.toInt())
                .putShort(0x0001).array()
        )
    }

    /** Leaves the loader and resets into the application (RUN_APP 1); the device may drop before replying. */
    fun runApp() {
        response(0xfc0c, byteArrayOf(0, 0, 0, 1))
    }

    companion object {
        /** The only loader packaged by the Android build. */
        const val LOADER_ASSET = "loaderblobs/usb/wl82loader.bin"

        /** Fixed WL82 entry point, intentionally not configurable or derived from other chip profiles. */
        private const val LOADER_ADDRESS = 0x1c02000L

        /** Addresses and lengths inside JieLi commands are big-endian, unlike BOT wrappers. */
        private fun addressLength(address: Long, length: Int): ByteArray =
            ByteBuffer.allocate(6).putInt(address.toInt()).putShort(length.toShort()).array()

        /** CRC-16/XMODEM (polynomial 0x1021), required by the WL82 loader upload protocol. */
        fun crc16(bytes: ByteArray, initial: Int = 0): Int {
            var crc = initial
            for (byte in bytes) {
                crc = crc xor ((byte.toInt() and 0xff) shl 8)
                repeat(8) {
                    val polynomial = if (crc and 0x8000 != 0) 0x1021 else 0
                    crc = ((crc shl 1) xor polynomial) and 0xffff
                }
            }
            return crc
        }
    }
}
