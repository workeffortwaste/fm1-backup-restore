package com.workeffortwaste.protocol

/** Read-only M-UPGRADE identity request and its LSB-first, seven-bit packed reply. */
object FirmwareIdentity {
    fun editorRequest(): ByteArray = byteArrayOf(0xf0.toByte(), 0x7d, 0x46, 0x4c, 0x01, 0xf7.toByte())

    /** Editor INFO begins with a zero-terminated ASCII firmware/version label (at most 24 bytes). */
    fun parseEditor(message: ByteArray): String? {
        if (message.size !in 8..600 ||
            !message.take(5).toByteArray().contentEquals(byteArrayOf(0xf0.toByte(), 0x7d, 0x46, 0x4c, 1)) ||
            message.last() != 0xf7.toByte() ||
            message.slice(1 until message.lastIndex).any { (it.toInt() and 255) > 127 }
        ) return null
        val end = (5 until minOf(30, message.lastIndex)).firstOrNull { message[it] == 0.toByte() } ?: return null
        val name = message.copyOfRange(5, end)
        if (name.isEmpty() || name.any { it.toInt() !in 32..126 }) return null
        val label = name.toString(Charsets.US_ASCII)
        return label.takeIf { it.startsWith("JIANT ") || it.startsWith("FELUCCA ") }
    }

    fun request(): ByteArray = byteArrayOf(
        0xf0.toByte(), 0x00, 0x32, 0x45, 0x00, 0x00, 0x00, 0x40, 0x7f, 0xf7.toByte(),
    )

    fun parse(message: ByteArray): String? {
        if (message.size != 41 || message.first() != 0xf0.toByte() || message.last() != 0xf7.toByte()) return null
        val block = ByteArray(34)
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
        if (!block.copyOfRange(0, 6).contentEquals(byteArrayOf(0, 0x59, 0x11, 27, 0, 0))) return null
        val name = block.copyOfRange(6, 33)
        val end = name.indexOf(0).let { if (it < 0) name.size else it }
        if (end == 0 || name.take(end).any { it.toInt() !in 32..126 } ||
            name.drop(end).any { it != 0.toByte() }) return null
        // Some replacement firmwares retain the stock checksum after changing the name.
        return String(name, 0, end, Charsets.US_ASCII)
    }
}
