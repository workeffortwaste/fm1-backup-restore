package com.workeffortwaste.protocol

data class MidiIdentityEvidence(
    val mUpgradeIdentity: String? = null,
    val editorLabel: String? = null,
)

/** Read-only firmware labels, kept separate from the M-UPGRADE device-family hint. */
object FirmwareIdentity {
    fun query(
        includeLegacyEditor: Boolean,
        probeBuildCode: (mUpgradeIdentity: String?) -> Boolean = { true },
        read: (ByteArray, (ByteArray) -> String?) -> String?,
    ): MidiIdentityEvidence {
        // Independent family evidence first: it also gates the slower, firmware-specific label probes.
        val mUpgradeIdentity = read(request(), ::parse)
        val editorLabel = (if (probeBuildCode(mUpgradeIdentity)) read(buildCodeRequest(), ::parseBuildCode) else null)
            ?: if (includeLegacyEditor) read(editorRequest(), ::parseEditor) else null
        return MidiIdentityEvidence(mUpgradeIdentity, editorLabel)
    }

    /** Onyx names command 81, kind 255: a build-code read, not an update or configuration write. */
    fun buildCodeRequest(): ByteArray = pack(
        byteArrayOf(0, 0x59, 0x51, 2, 0, 0, 0xff.toByte(), 0, 0xad.toByte())
    )

    fun parseBuildCode(message: ByteArray): String? {
        if (message.size !in 17..62) return null
        val block = unpack(message) ?: return null
        if (!message.contentEquals(pack(block)) ||
            !block.copyOfRange(0, 3).contentEquals(byteArrayOf(0, 0x59, 0x61))
        ) return null
        val length = (block[3].toInt() and 255) or
                ((block[4].toInt() and 255) shl 8) or ((block[5].toInt() and 255) shl 16)
        if (length !in 6..45 || block.size != length + 7) return null
        val checksum = block.slice(2 until block.lastIndex).sumOf { it.toInt() and 255 }.inv() and 255
        if ((block.last().toInt() and 255) != checksum ||
            block[6] != 0xff.toByte() || block[7] != 0.toByte() || block[8] != 0.toByte()
        ) return null
        val nameLength = block[9].toInt() and 255
        if (nameLength !in 1..40 || length != 5 + nameLength) return null
        // Byte 10 is the firmware's pack number, not a model or a fixed Onyx version.
        val name = block.copyOfRange(11, block.lastIndex)
        if (name.any { it.toInt() !in 32..126 }) return null
        return name.toString(Charsets.US_ASCII).takeIf { it.isNotBlank() }
    }

    fun editorRequest(): ByteArray = byteArrayOf(0xf0.toByte(), 0x7d, 0x46, 0x4c, 0x01, 0xf7.toByte())

    /**
     * Editor INFO begins with a zero-terminated ASCII firmware/version label (at most 24 bytes).
     * Felucca derivatives rename the label (FELUCCA, JIANT, X0X, SLOOP, ...), so only the protocol
     * structure gates the reply, never a fixed firmware name.
     */
    fun parseEditor(message: ByteArray): String? {
        if (message.size !in 8..600 ||
            !message.take(5).toByteArray().contentEquals(byteArrayOf(0xf0.toByte(), 0x7d, 0x46, 0x4c, 1)) ||
            message.last() != 0xf7.toByte() ||
            message.slice(1 until message.lastIndex).any { (it.toInt() and 255) > 127 }
        ) return null
        val end = (5 until minOf(30, message.lastIndex)).firstOrNull { message[it] == 0.toByte() } ?: return null
        val name = message.copyOfRange(5, end)
        if (name.isEmpty() || name.any { it.toInt() !in 32..126 }) return null
        return name.toString(Charsets.US_ASCII).takeIf { it.isNotBlank() }
    }

    fun request(): ByteArray = byteArrayOf(
        0xf0.toByte(), 0x00, 0x32, 0x45, 0x00, 0x00, 0x00, 0x40, 0x7f, 0xf7.toByte(),
    )

    fun parse(message: ByteArray): String? {
        if (message.size != 41) return null
        val block = unpack(message) ?: return null
        if (!block.copyOfRange(0, 6).contentEquals(byteArrayOf(0, 0x59, 0x11, 27, 0, 0))) return null
        val name = block.copyOfRange(6, 33)
        val end = name.indexOf(0).let { if (it < 0) name.size else it }
        if (end == 0 || name.take(end).any { it.toInt() !in 32..126 } ||
            name.drop(end).any { it != 0.toByte() }) return null
        // Some replacement firmwares retain the stock checksum after changing the name.
        return String(name, 0, end, Charsets.US_ASCII)
    }

    private fun unpack(message: ByteArray): ByteArray? {
        if (message.size < 2 || message.first() != 0xf0.toByte() || message.last() != 0xf7.toByte()) return null
        val block = ByteArray((message.size - 2) * 7 / 8)
        var bits = 0
        var pending = 0
        var position = 0
        for (index in 1 until message.lastIndex) {
            val value = message[index].toInt() and 0xff
            if (value > 127) return null
            pending = pending or (value shl bits)
            bits += 7
            if (bits >= 8) {
                block[position++] = pending.toByte()
                pending = pending ushr 8
                bits -= 8
            }
        }
        return block
    }

    private fun pack(block: ByteArray): ByteArray {
        val message = ByteArray(2 + (block.size * 8 + 6) / 7)
        message[0] = 0xf0.toByte()
        message[message.lastIndex] = 0xf7.toByte()
        var bits = 0
        var pending = 0
        var position = 1
        for (byte in block) {
            pending = pending or ((byte.toInt() and 255) shl bits)
            bits += 8
            while (bits >= 7) {
                message[position++] = (pending and 127).toByte()
                pending = pending ushr 7
                bits -= 7
            }
        }
        if (bits > 0) message[position] = pending.toByte()
        return message
    }
}
