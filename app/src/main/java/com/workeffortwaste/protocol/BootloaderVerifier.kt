package com.workeffortwaste.protocol

import java.security.MessageDigest

internal data class BootloaderVerificationResult(
    val sha256: String,
    val matchesStockV15: Boolean,
    val matchesStockV16: Boolean = false,
    val identity: String? = null,
    val matchesPublishedRelease: Boolean = false,
) {
    val message: String
        get() = when {
            matchesStockV15 -> "Stock v15 bootloader SHA-256 matches the verified official image."
            matchesStockV16 -> "Stock v16 bootloader SHA-256 matches the verified official image."
            identity != null && matchesPublishedRelease ->
                "Identified as $identity. Reconstructed boot container SHA-256 matches the published release."
            identity != null ->
                "Identified as $identity, but reconstructed boot container SHA-256 does not match a published release."
            else -> "Unrecognized bootloader. It may be custom, corrupted, or another stock version."
        }
}

/** Reads the protected region and identifies stock v15/v16 or a published m-bootloader container. */
internal object BootloaderVerifier {
    const val STOCK_V15_SHA256 = "d9f43191777656de01c7bed4f9e9ba2e34e94832c5d2ed02c3fc7ab7a6d9bd67"
    const val STOCK_V16_SHA256 = "8f5228c2d6ea2068838ef4916f946bea877d14b07e6cfca49e41957f4c7b234f"
    private const val BOOT_AREA_SIZE = FlashRestore.PROTECTED_SIZE
    private const val BOOT_FILE_ADDRESS = 0xA0
    private const val MAX_BOOT_FILE_SIZE = 0x3830
    private const val BOOT_CONTAINER_HEADER_SIZE = 16
    private const val BANKCB_LOAD_ADDRESS = 0x01C02000L
    private const val ENC_KEY = 0xffff

    private val knownMbootloaderReleases = mapOf(
        "1.1-e9a25e1" to "ba1429f5f40f3804821fa5f4f81a8d892b9c56519af2c76b6c551d62026a4200",
        "1.2-22e2559" to "9e5ec0e39dc55c167f8e289cfdac612d7d340b5f46100e1be2540ef4d6ec2f13",
    )

    fun read(
        readFlash: (address: Long, length: Int) -> ByteArray,
        checkCancelled: () -> Unit = {},
        progress: (bytesRead: Int) -> Unit = {},
    ): BootloaderVerificationResult {
        val protectedRegion = ByteArray(BOOT_AREA_SIZE)
        for (address in 0 until BOOT_AREA_SIZE step FlashRestore.PAGE_SIZE) {
            checkCancelled()
            val length = minOf(FlashRestore.PAGE_SIZE, BOOT_AREA_SIZE - address)
            val page = readFlash(address.toLong(), length)
            if (page.size != length) throw ProtocolException("Incomplete bootloader read at 0x%06X".format(address))
            page.copyInto(protectedRegion, address)
            progress(address + length)
        }
        checkCancelled()
        return identify(protectedRegion)
    }

    fun identify(protectedRegion: ByteArray): BootloaderVerificationResult {
        require(protectedRegion.size == BOOT_AREA_SIZE) { "Expected the protected 16 KiB boot area" }
        val regionSha256 = sha256(protectedRegion)
        if (regionSha256 == STOCK_V15_SHA256) {
            return BootloaderVerificationResult(regionSha256, matchesStockV15 = true)
        }
        if (regionSha256 == STOCK_V16_SHA256) {
            return BootloaderVerificationResult(regionSha256, matchesStockV15 = false, matchesStockV16 = true)
        }
        val container = extractBootContainer(protectedRegion)
            ?: return BootloaderVerificationResult(regionSha256, matchesStockV15 = false)
        val identity = parseMbootloaderIdentity(container)
            ?: return BootloaderVerificationResult(regionSha256, matchesStockV15 = false)
        val containerSha256 = sha256(container)
        return BootloaderVerificationResult(
            sha256 = regionSha256,
            matchesStockV15 = false,
            identity = "m-bootloader $identity",
            matchesPublishedRelease = knownMbootloaderReleases[identity] == containerSha256,
        )
    }

    fun isKnownStockV15(sha256: String): Boolean = sha256.equals(STOCK_V15_SHA256, ignoreCase = true)

    fun isKnownStockV16(sha256: String): Boolean = sha256.equals(STOCK_V16_SHA256, ignoreCase = true)

    internal fun isKnownMbootloaderRelease(identity: String, containerSha256: String): Boolean =
        knownMbootloaderReleases[identity]?.equals(containerSha256, ignoreCase = true) == true

    private fun extractBootContainer(region: ByteArray): ByteArray? {
        val flashHeader = enc(region.copyOfRange(0, 32))
        if (crc16(flashHeader.copyOfRange(2, 32)) != uint16le(flashHeader, 0)) return null

        val bootEntry = enc(region.copyOfRange(0x20, 0x40))
        if (crc16(bootEntry.copyOfRange(2, 32)) != uint16le(bootEntry, 0)) return null
        if (asciiCString(bootEntry, 16) != "uboot.boot") return null
        val address = uint32le(bootEntry, 4)
        val length = uint32le(bootEntry, 8)
        if (address != BOOT_FILE_ADDRESS.toLong() || length !in 16..MAX_BOOT_FILE_SIZE.toLong()) return null
        if (length > region.size - address) return null

        val stored = region.copyOfRange(address.toInt(), address.toInt() + length.toInt())
        val container = enc(stored.copyOfRange(0, BOOT_CONTAINER_HEADER_SIZE)) +
            enc(stored.copyOfRange(BOOT_CONTAINER_HEADER_SIZE, stored.size))
        if (crc16(container) != uint16le(bootEntry, 2)) return null
        return container
    }

    private fun parseMbootloaderIdentity(container: ByteArray): String? {
        if (container.size < 16) return null
        if (uint16le(container, 0) != 1) return null
        val payloadLength = uint16le(container, 2)
        if (uint32le(container, 4) != BANKCB_LOAD_ADDRESS ||
            uint32le(container, 8) != BOOT_CONTAINER_HEADER_SIZE.toLong()
        ) return null
        if (payloadLength != container.size - BOOT_CONTAINER_HEADER_SIZE || payloadLength < 0x70) return null
        if (crc16(container.copyOfRange(0, 14)) != uint16le(container, 14)) return null
        if (crc16(container.copyOfRange(BOOT_CONTAINER_HEADER_SIZE, container.size)) != uint16le(container, 12)) {
            return null
        }

        val identity = container.copyOfRange(
            BOOT_CONTAINER_HEADER_SIZE + 0x40,
            BOOT_CONTAINER_HEADER_SIZE + 0x70,
        )
        if (!identity.copyOfRange(0, 8).contentEquals("AC79BLID".toByteArray(Charsets.US_ASCII))) return null
        if (!identity.copyOfRange(8, 16).contentEquals(byteArrayOf(102, 109, 49, 0, 0, 0, 0, 0))) return null
        return asciiCString(identity, 32)?.takeIf { it.isNotBlank() }
    }

    private fun enc(bytes: ByteArray): ByteArray {
        var key = ENC_KEY
        return ByteArray(bytes.size) { index ->
            val value = bytes[index].toInt() xor (key and 0xff)
            key = ((key shl 1) xor (if (key and 0x8000 != 0) 0x1021 else 0)) and 0xffff
            value.toByte()
        }
    }

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

    private fun uint16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun uint32le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
                ((bytes[offset + 1].toLong() and 0xff) shl 8) or
                ((bytes[offset + 2].toLong() and 0xff) shl 16) or
                ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun asciiCString(bytes: ByteArray, length: Int): String? {
        val field = bytes.copyOfRange(16, 16 + length)
        val end = field.indexOf(0)
        if (end < 0 || field.take(end).any { (it.toInt() and 0xff) !in 32..126 }) return null
        return String(field, 0, end, Charsets.US_ASCII)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}