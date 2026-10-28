package com.workeffortwaste.protocol

import org.junit.Assert.*
import org.junit.Test

/** Covers read-only identity requests and strict parsing of FM-1 MIDI replies and captures. */
class FirmwareIdentityTest {
    private fun editorReply(label: String): ByteArray =
        byteArrayOf(0xf0.toByte(), 0x7d, 0x46, 0x4c, 1) +
                label.toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 15, 123, 66, 32, 0xf7.toByte())

    @Test
    fun editorQueryIsReadOnlyInfoCommand() {
        assertArrayEquals(
            byteArrayOf(0xf0.toByte(), 0x7d, 0x46, 0x4c, 1, 0xf7.toByte()),
            FirmwareIdentity.editorRequest()
        )
    }

    @Test
    fun editorInfoReportsFirmwareAndVersion() {
        for (label in listOf("JIANT 0.9.12", "FELUCCA v1.4.1")) {
            assertEquals(label, FirmwareIdentity.parseEditor(editorReply(label)))
        }
        assertNull(FirmwareIdentity.parseEditor(reply("FM-1_900")))
    }

    @Test
    fun rejectsMalformedOrUnrelatedEditorReplies() {
        val valid = editorReply("JIANT 0.9.12")
        for (invalid in listOf(
            valid.copyOf().apply { this[4] = 2 },
            valid.copyOf().apply { this[lastIndex] = 0 },
            valid.copyOf().apply { this[6] = 0x80.toByte() },
            editorReply(""), editorReply("OTHER 1.0"), editorReply("JIANT " + "x".repeat(20)),
            editorReply("JIANT \u0001"), byteArrayOf(),
        )) {
            assertNull(FirmwareIdentity.parseEditor(invalid))
        }
    }

    @Test
    fun parsesPhysicalJiantDeviceCapture() {
        val captured = ("F0 00 32 45 58 01 00 00 23 4D 5A 44 79 15 07 0C 18 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 40 05 F7")
            .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("FM-1_900", FirmwareIdentity.parse(captured))
    }

    private fun reply(name: String, header: Int = 0x11, length: Int = 27): ByteArray {
        val block = ByteArray(34)
        block[1] = 0x59
        block[2] = header.toByte()
        block[3] = length.toByte()
        name.toByteArray(Charsets.US_ASCII).copyInto(block, 6)
        block[33] = 0x7f
        val encoded = mutableListOf(0xf0.toByte())
        for (offset in 0 until 273 step 7) {
            var value = 0
            for (bit in 0..6) {
                val source = offset + bit
                if (source < 272 && (block[source / 8].toInt() and (1 shl (source % 8))) != 0) {
                    value = value or (1 shl bit)
                }
            }
            encoded += value.toByte()
        }
        encoded += 0xf7.toByte()
        return encoded.toByteArray()
    }

    @Test
    fun requestIsReadOnlyIdentityCommand() {
        assertEquals(
            "f0003245000000407ff7",
            FirmwareIdentity.request().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    @Test
    fun readsRawIdentityWithoutGuessingFirmwareFamily() {
        for (name in listOf("FM-1_015", "FM-1_089", "FM-1_900", "FM1_OFFICIAL_OPEN")) {
            assertEquals(name, FirmwareIdentity.parse(reply(name)))
        }
    }

    @Test
    fun rejectsWrongFramingHeaderLengthAndNonPrintableName() {
        val valid = reply("FM-1_015")
        for (message in listOf(
            valid.dropLast(1).toByteArray(),
            valid.copyOf().apply { this[1] = 0x80.toByte() },
            valid.copyOf().apply { this[0] = 0 },
            reply("FM-1_015", header = 0x30), reply("FM-1_015", length = 26),
            reply(""), reply("FM\u0001")
        )) {
            assertNull(FirmwareIdentity.parse(message))
        }
    }

    @Test
    fun rejectsNonzeroDataAfterNameTerminator() {
        assertNull(FirmwareIdentity.parse(reply("FM-1\u0000other")))
    }
}
