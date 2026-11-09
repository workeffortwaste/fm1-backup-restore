package com.workeffortwaste.protocol

/**
 * Extracts the stored M-UPGRADE identity from a full flash image's application area.
 *
 * Layout verified against the official FM-1 V15 package: the boot directory lists
 * app_dir_head, whose area reuses the UFW stream cipher keyed per 32-byte block relative
 * to the area start. The app.bin payload is CRC-validated before it is searched, and the
 * identity must be the payload's only recognised zero-terminated candidate string.
 */
internal object FlashAppIdentity {
    private const val ENTRY_SIZE = 32
    private const val TABLE_START = 0x20
    private const val TABLE_END = 0xA0
    private const val BOOT_KEY = 0xFFFF
    private const val AREA_KEY = 0x980F
    private const val APP_DIRECTORY = "app_dir_head"
    private const val AREA_HEAD = "app_area_head"
    private const val APP_FILE = "app.bin"
    private const val MAX_APP_ENTRIES = 16

    /** Matches the 27-byte identity field of the M-UPGRADE MIDI reply. */
    private const val MAX_IDENTITY_LENGTH = 27
    private const val MIN_IDENTITY_LENGTH = 3

    fun extract(image: ByteArray, isRecognized: (String) -> Boolean): String? {
        val application = readValidatedAppBin(image) ?: return null
        var identity: String? = null
        var index = 0
        while (index < application.size) {
            if ((application[index].toInt() and 0xFF) !in 32..126) {
                index++
                continue
            }
            var end = index
            while (end < application.size && (application[end].toInt() and 0xFF) in 32..126) end++
            if (end < application.size && application[end] == 0.toByte() &&
                end - index in MIN_IDENTITY_LENGTH..MAX_IDENTITY_LENGTH
            ) {
                val candidate = String(application, index, end - index, Charsets.US_ASCII)
                if (isRecognized(candidate)) {
                    // Two different recognised identities leave the stored identity ambiguous.
                    if (identity != null && identity != candidate) return null
                    identity = candidate
                }
            }
            index = end + 1
        }
        return identity
    }

    private fun readValidatedAppBin(image: ByteArray): ByteArray? {
        if (image.size < TABLE_END) return null
        val header = decode(image.copyOfRange(0, ENTRY_SIZE), BOOT_KEY)
        if (Fm1Protocol.crc16(header.copyOfRange(2, ENTRY_SIZE)) != uint16(header, 0)) return null

        var areaStart: Long? = null
        for (offset in TABLE_START until TABLE_END step ENTRY_SIZE) {
            val entry = decode(image.copyOfRange(offset, offset + ENTRY_SIZE), BOOT_KEY)
            if (Fm1Protocol.crc16(entry.copyOfRange(2, ENTRY_SIZE)) != uint16(entry, 0)) break
            if (name(entry) != APP_DIRECTORY) continue
            if (areaStart != null) return null
            areaStart = uint32(entry, 4)
        }
        val base = (areaStart ?: return null).toInt()
        if (base < TABLE_END || base.toLong() != areaStart || base > image.size - 2 * ENTRY_SIZE) return null

        val head = decodeArea(image, base, 0, ENTRY_SIZE)
        if (Fm1Protocol.crc16(head.copyOfRange(2, ENTRY_SIZE)) != uint16(head, 0)) return null
        if (name(head) != AREA_HEAD) return null

        for (index in 1..MAX_APP_ENTRIES) {
            val relative = index * ENTRY_SIZE
            if (base + relative + ENTRY_SIZE > image.size) return null
            val entry = decodeArea(image, base, relative, ENTRY_SIZE)
            if (Fm1Protocol.crc16(entry.copyOfRange(2, ENTRY_SIZE)) != uint16(entry, 0)) return null
            if (name(entry) != APP_FILE) continue
            val address = uint32(entry, 4)
            val size = uint32(entry, 8)
            if (address < 0 || size <= 0 || address + size > image.size - base) return null
            val payload = decodeArea(image, base, address.toInt(), size.toInt())
            if (Fm1Protocol.crc16(payload) != uint16(entry, 2)) return null
            return payload
        }
        return null
    }

    /** Decrypts an area region whose per-32-byte-block key is derived from its area-relative offset. */
    private fun decodeArea(image: ByteArray, base: Int, relative: Int, length: Int): ByteArray {
        val output = ByteArray(length)
        var position = 0
        while (position < length) {
            val chunk = minOf(ENTRY_SIZE, length - position)
            val start = base + relative + position
            decode(image.copyOfRange(start, start + chunk), AREA_KEY xor ((relative + position) shr 2))
                .copyInto(output, position)
            position += chunk
        }
        return output
    }

    private fun decode(bytes: ByteArray, key: Int): ByteArray {
        var state = key and 0xFFFF
        return ByteArray(bytes.size) { index ->
            val value = bytes[index].toInt() xor (state and 0xFF)
            state = ((state shl 1) xor (if (state and 0x8000 != 0) 0x1021 else 0)) and 0xFFFF
            value.toByte()
        }
    }

    private fun name(entry: ByteArray): String? {
        val field = entry.copyOfRange(16, ENTRY_SIZE)
        val end = field.indexOf(0)
        if (end <= 0 || field.take(end).any { (it.toInt() and 0xFF) !in 32..126 }) return null
        return String(field, 0, end, Charsets.US_ASCII)
    }

    private fun uint16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}
