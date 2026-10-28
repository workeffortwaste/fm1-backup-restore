package com.workeffortwaste.protocol

import java.security.MessageDigest

/** Builds the tested stock V15 recovery layout from the official FWSC package. */
internal object V15RecoveryImage {
    const val PACKAGE_SIZE = 699_956
    const val PACKAGE_SHA256 = "db1642b2b6fa5c2cccb11ffd13878068bb28601678d3644049f99dc40e7edb8a"
    const val IMAGE_SHA256 = "74638523215f34243baea3ae4dbda181358e10e7563b23efbc94abcb0cbe5750"
    const val SOURCE_URL =
        "https://raw.githubusercontent.com/m-vave/fm1_open/4c7496daac04a95ff10d1afdcc8f9ead2dbabc41/sdk/firmware/FM-1_015.fwsc"

    private const val UFW_SIZE = 699_936
    private const val FLASH_SIZE = 0x93000
    private const val USR_SIZE = 0x11E0A
    private const val USR_FLASH_OFFSET = 0xEA000
    private const val IMAGE_SIZE = 0x100000
    private const val VOICE_SIZE = 16_384
    private const val BLOCK_SIZE = 32
    private const val ENTRY_SIZE = 80
    private const val ENTRY_COUNT = 8
    private const val VOICE_SHA256 = "ecad0b8620f9d784d46c8da1e3c5de02c40214649ed8dec218026431046aad25"

    fun build(packageBytes: ByteArray): ByteArray {
        require(packageBytes.size == PACKAGE_SIZE) { "Official V15 package size does not match." }
        require(sha256(packageBytes) == PACKAGE_SHA256) { "Downloaded package is not the verified official V15 package." }

        val ufw = unwrap(packageBytes)
        val payloads = extractEntries(ufw)
        val flash = payloads["flash.bin"] ?: error("V15 package is missing flash.bin.")
        val usr = payloads["USR"] ?: error("V15 package is missing USR.")
        require(flash.size == FLASH_SIZE) { "Unexpected V15 flash.bin size." }
        require(usr.size == USR_SIZE) { "Unexpected V15 USR size." }
        require(sha256(usr.copyOfRange(0, VOICE_SIZE)) == VOICE_SHA256) {
            "V15 factory voice data does not match."
        }

        val image = assembleImage(flash, usr)
        require(sha256(image) == IMAGE_SHA256) { "Constructed V15 image failed its SHA-256 check." }
        return image
    }

    internal fun assembleImage(flash: ByteArray, usr: ByteArray): ByteArray {
        require(flash.size == FLASH_SIZE) { "Unexpected V15 flash.bin size." }
        require(usr.size == USR_SIZE) { "Unexpected V15 USR size." }
        return ByteArray(IMAGE_SIZE) { 0xff.toByte() }.also { image ->
            flash.copyInto(image)
            usr.copyInto(image, destinationOffset = USR_FLASH_OFFSET)
        }
    }

    private fun unwrap(packageBytes: ByteArray): ByteArray {
        require(packageBytes.size == PACKAGE_SIZE) { "Unexpected FWSC package size." }
        val output = ByteArray(UFW_SIZE)
        var target = 0
        repeat(20) { block ->
            val source = block * 48
            packageBytes.copyInto(output, target, source, source + 47)
            target += 47
        }
        packageBytes.copyInto(output, target, 960, packageBytes.size)
        require(target + packageBytes.size - 960 == UFW_SIZE) { "Unexpected FWSC wrapper layout." }
        return output
    }

    private fun extractEntries(ufw: ByteArray): Map<String, ByteArray> {
        require(ufw.size == UFW_SIZE) { "Unexpected UFW stream size." }
        val header = transform(ufw.copyOfRange(0, 64))
        require(crc16(header.copyOfRange(2, header.size)) == littleEndian(header, 0, 2).toInt()) {
            "UFW header CRC does not match."
        }
        require(littleEndian(header, 4, 4) == ufw.size.toLong()) { "UFW length does not match." }
        val count = littleEndian(header, 8, 2).toInt()
        require(count == ENTRY_COUNT) { "Unexpected UFW directory entry count." }

        val tableEnd = 64 + ENTRY_SIZE * count
        val table = ufw.copyOfRange(64, tableEnd)
        require(crc16(table) == littleEndian(header, 2, 2).toInt()) { "UFW directory CRC does not match." }

        val entries = LinkedHashMap<String, ByteArray>(count)
        repeat(count) { index ->
            val start = index * ENTRY_SIZE
            val entry = transform(table.copyOfRange(start, start + ENTRY_SIZE))
            val expectedCrc = littleEndian(entry, 4, 2).toInt()
            val offset = littleEndian(entry, 8, 4)
            val logicalSize = littleEndian(entry, 12, 4)
            val storedSize = littleEndian(entry, 16, 4)
            val encrypted = littleEndian(entry, 24, 4) != 0L
            val rawName = entry.copyOfRange(64, 80)
            val end = rawName.indexOf(0).let { if (it < 0) rawName.size else it }
            require(end > 0 && rawName.take(end).all { it.toInt() in 32..126 }) {
                "UFW directory contains an invalid entry name."
            }
            val name = String(rawName, 0, end, Charsets.US_ASCII)
            require(name !in entries) { "UFW directory contains a duplicate entry." }
            require(logicalSize <= storedSize && offset >= tableEnd && offset + storedSize <= ufw.size) {
                "UFW entry bounds are invalid: $name."
            }

            var data = ufw.copyOfRange(offset.toInt(), (offset + storedSize).toInt())
            if (encrypted) {
                val decrypted = ByteArray(data.size)
                for (blockOffset in data.indices step BLOCK_SIZE) {
                    val endOffset = minOf(blockOffset + BLOCK_SIZE, data.size)
                    transform(
                        data.copyOfRange(blockOffset, endOffset),
                        0x980F xor ((offset + blockOffset) shr 2).toInt(),
                    ).copyInto(decrypted, blockOffset)
                }
                data = decrypted
            }
            val logicalData = data.copyOf(logicalSize.toInt())
            require(crc16(logicalData) == expectedCrc) { "UFW entry CRC does not match: $name." }
            entries[name] = logicalData
        }
        return entries
    }

    private fun transform(data: ByteArray, initialState: Int = 0xffff): ByteArray {
        var state = initialState
        return ByteArray(data.size) { index ->
            val value = (data[index].toInt() and 0xff) xor (state and 0xff)
            state = ((state shl 1) xor (if (state and 0x8000 != 0) 0x1021 else 0)) and 0xffff
            value.toByte()
        }
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0
        data.forEach { byte ->
            crc = crc xor ((byte.toInt() and 0xff) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xffff
            }
        }
        return crc
    }

    private fun littleEndian(data: ByteArray, offset: Int, size: Int): Long {
        var value = 0L
        repeat(size) { index ->
            value = value or ((data[offset + index].toLong() and 0xff) shl (index * 8))
        }
        return value
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
