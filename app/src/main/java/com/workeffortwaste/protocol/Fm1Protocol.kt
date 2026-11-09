package com.workeffortwaste.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val SMK_TRIM_MARKER_ADDRESS = 0xC1224L

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
            throw ProtocolException("Supported devices require plausible SPI NOR JEDEC metadata for exactly 1 MiB")
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

data class BootDeviceConfig(val spiSetting: String? = null, val resetSetting: String? = null)

/** Reads device records from the bounded, CRC-validated config blob in the boot area. */
internal object BootSpiConfig {
    private const val TABLE_START = 0x20
    private const val TABLE_END = 0xa0
    private const val ENTRY_SIZE = 32
    private const val CONFIG_PREFIX_SIZE = 34
    private const val MAX_CONFIG_SIZE = 4096
    private const val ENCRYPTION_KEY = 0xffff
    private const val CONFIG_FILE_NAME = "isd_config.ini"
    private val settingValue = Regex("[0-9]+_[0-9]+_[0-9]+_[0-9]+")

    fun extractSetting(bootArea: ByteArray): String? = extract(bootArea)?.spiSetting

    fun extract(bootArea: ByteArray): BootDeviceConfig? {
        val windowSize = minOf(bootArea.size, FlashRestore.PROTECTED_SIZE)
        if (windowSize < TABLE_END) return null

        val header = decrypt(bootArea.copyOfRange(0, ENTRY_SIZE))
        if (Fm1Protocol.crc16(header.copyOfRange(2, ENTRY_SIZE)) != littleEndian16(header, 0)) return null

        var configBlob: ByteArray? = null
        for (offset in TABLE_START until TABLE_END step ENTRY_SIZE) {
            val entry = decrypt(bootArea.copyOfRange(offset, offset + ENTRY_SIZE))
            if (Fm1Protocol.crc16(entry.copyOfRange(2, ENTRY_SIZE)) != littleEndian16(entry, 0)) break
            if (asciiCString(entry, 16, 16) != CONFIG_FILE_NAME) continue
            if (configBlob != null) return null

            val address = littleEndian32(entry, 4)
            val size = littleEndian32(entry, 8)
            if (address < TABLE_END || size !in (CONFIG_PREFIX_SIZE + 1).toLong()..MAX_CONFIG_SIZE.toLong() ||
                address > windowSize.toLong() - size
            ) return null

            val blob = bootArea.copyOfRange(address.toInt(), (address + size).toInt())
            if (Fm1Protocol.crc16(blob) != littleEndian16(entry, 2)) return null
            configBlob = blob
        }
        return configBlob?.let(::parseConfig)
    }

    private fun parseConfig(blob: ByteArray): BootDeviceConfig? {
        var offset = CONFIG_PREFIX_SIZE
        var spi: String? = null
        var reset: String? = null
        while (offset < blob.size) {
            val valueLength = blob[offset].toInt() and 0xff
            if (valueLength == 0 || valueLength == 0xff) break

            val nameStart = offset + 1
            val nameEnd = (nameStart until blob.size).firstOrNull { blob[it] == 0.toByte() } ?: return null
            if (blob.copyOfRange(nameStart, nameEnd).any { (it.toInt() and 0xff) !in 32..126 }) return null
            val name = String(blob, nameStart, nameEnd - nameStart, Charsets.US_ASCII)
            val valueStart = nameEnd + 1
            if (valueLength > blob.size - valueStart) return null
            if (name == "SPI") {
                if (spi != null) return null
                val value = String(blob, valueStart, valueLength, Charsets.US_ASCII)
                if (!settingValue.matches(value)) return null
                spi = value
            }
            if (name == "RESET") {
                if (reset != null) return null
                val bytes = blob.copyOfRange(valueStart, valueStart + valueLength)
                if (bytes.any { (it.toInt() and 0xff) !in 32..126 }) return null
                reset = String(bytes, Charsets.US_ASCII)
            }
            offset = valueStart + valueLength
        }
        return BootDeviceConfig(spi, reset)
    }

    private fun decrypt(bytes: ByteArray): ByteArray {
        var key = ENCRYPTION_KEY
        return ByteArray(bytes.size) { index ->
            val value = bytes[index].toInt() xor (key and 0xff)
            key = ((key shl 1) xor (if (key and 0x8000 != 0) 0x1021 else 0)) and 0xffff
            value.toByte()
        }
    }

    private fun littleEndian16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndian32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
                ((bytes[offset + 1].toLong() and 0xff) shl 8) or
                ((bytes[offset + 2].toLong() and 0xff) shl 16) or
                ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun asciiCString(bytes: ByteArray, offset: Int, length: Int): String? {
        val end = (offset until offset + length).firstOrNull { bytes[it] == 0.toByte() } ?: return null
        if (bytes.copyOfRange(offset, end).any { (it.toInt() and 0xff) !in 32..126 }) return null
        return String(bytes, offset, end - offset, Charsets.US_ASCII)
    }
}

/** FM1/WL82 commands; normal restore and emergency boot repair have separate fixed write boundaries. */
class Fm1Protocol(private val transport: ScsiTransport) {
    /** Rejects other chip families and unsupported download modes before loader upload is allowed. */
    fun identify(): Fm1Identity {
        val data = transport.execute(byteArrayOf(0x12, 0, 0, 0, 36, 0), 36)
        fun inquiryField(start: Int, end: Int) = data.copyOfRange(start, end)
            .toString(Charsets.US_ASCII).trim()

        val family = inquiryField(8, 16)
        val product = inquiryField(16, 32)
        if (!family.equals("WL82", ignoreCase = true)) {
            throw ProtocolException("Supported devices must report WL82; found $family")
        }
        if (!product.startsWith("UBOOT") &&
            !product.startsWith("UDISK") && !product.startsWith("DEVICE")
        ) {
            throw ProtocolException("Device is not in a supported recovery mode: $product")
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

    fun readBootSpiSetting(): String? = readBootDeviceConfig()?.spiSetting

    /** Reads SPI and RESET together without a second protected-area transfer. */
    fun readBootDeviceConfig(): BootDeviceConfig? {
        val bootArea = ByteArray(FlashRestore.PROTECTED_SIZE)
        var offset = 0
        while (offset < bootArea.size) {
            val length = minOf(256, bootArea.size - offset)
            val block = readFlash(offset.toLong(), length)
            if (block.size != length) throw ProtocolException("Incomplete boot-area read at 0x%04X".format(offset))
            block.copyInto(bootArea, offset)
            offset += length
        }
        return BootSpiConfig.extract(bootArea)
    }

    /** Reads the one documented SMK marker; absence is not evidence that the unit is not SMK-37. */
    fun hasSmkTrimMarker(): Boolean {
        val marker = readFlash(SMK_TRIM_MARKER_ADDRESS, 8)
        if (marker.size != 8) throw ProtocolException("Incomplete SMK trim-marker read")
        return marker.contentEquals(byteArrayOf(0x74, 0x72, 0x69, 0x6d, 0x64, 0x61, 0x74, 0))
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
        eraseAt(address)
    }

    /** Emergency-only sector erase; cannot cross into application flash or relax normal restore limits. */
    internal fun eraseBootloaderSector(address: Long) {
        require(address in 0L..(FlashRestore.PROTECTED_SIZE - FlashRestore.SECTOR_SIZE).toLong())
        require(address % FlashRestore.SECTOR_SIZE == 0L)
        eraseAt(address)
    }

    private fun eraseAt(address: Long) {
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
        programAt(address, bytes)
    }

    /** Emergency-only programming; both boot-area and page boundaries remain mandatory. */
    internal fun writeBootloaderPage(address: Long, bytes: ByteArray) {
        require(bytes.size in 1..FlashRestore.PAGE_SIZE)
        require(address in 0L..(FlashRestore.PROTECTED_SIZE - bytes.size).toLong())
        require(address % FlashRestore.PAGE_SIZE + bytes.size <= FlashRestore.PAGE_SIZE)
        programAt(address, bytes)
    }

    private fun programAt(address: Long, bytes: ByteArray) {
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
