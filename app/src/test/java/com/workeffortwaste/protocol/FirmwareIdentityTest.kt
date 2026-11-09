package com.workeffortwaste.protocol

import org.junit.Assert.*
import org.junit.Test

/** Covers read-only identity requests and strict parsing of supported-device MIDI replies/captures. */
class FirmwareIdentityTest {
    private fun pack(block: ByteArray): ByteArray {
        val encoded = mutableListOf(0xf0.toByte())
        for (offset in 0 until block.size * 8 step 7) {
            var value = 0
            for (bit in 0..6) {
                val source = offset + bit
                if (source < block.size * 8 &&
                    (block[source / 8].toInt() and (1 shl (source % 8))) != 0
                ) value = value or (1 shl bit)
            }
            encoded += value.toByte()
        }
        encoded += 0xf7.toByte()
        return encoded.toByteArray()
    }

    private fun buildReply(
        label: String,
        packNumber: Int = 194,
        change: (ByteArray) -> Unit = {},
    ): ByteArray {
        val payload = byteArrayOf(0xff.toByte(), 0, 0, label.length.toByte(), packNumber.toByte()) +
                label.toByteArray(Charsets.US_ASCII)
        val block = byteArrayOf(0, 0x59, 0x61, payload.size.toByte(), 0, 0) + payload + byteArrayOf(0)
        change(block)
        block[block.lastIndex] = block.slice(2 until block.lastIndex)
            .sumOf { it.toInt() and 255 }.inv().toByte()
        return pack(block)
    }

    @Test
    fun buildQueryMatchesOnyxReadOnlyNamesRequest() {
        assertArrayEquals(
            pack(byteArrayOf(0, 0x59, 0x51, 2, 0, 0, 0xff.toByte(), 0, 0xad.toByte())),
            FirmwareIdentity.buildCodeRequest(),
        )
    }

    @Test
    fun readsBuildLabelsWithoutPinningFirmwareNameModelOrPackNumber() {
        for (label in listOf("Pocket_AMP_Onyx_1.01-2611051015", "Fork 2.0", "x", "x".repeat(40))) {
            for (packNumber in listOf(0, 194, 255)) {
                assertEquals(label, FirmwareIdentity.parseBuildCode(buildReply(label, packNumber)))
            }
        }
    }

    @Test
    fun rejectsMalformedBuildRepliesAndOrdinaryNamePages() {
        val valid = buildReply("Pocket_AMP_Onyx_1.01-2611051015")
        for (invalid in listOf(
            byteArrayOf(), valid.dropLast(1).toByteArray(),
            valid.copyOf().apply { this[0] = 0 },
            valid.copyOf().apply { this[1] = 0x80.toByte() },
            valid.copyOf().apply { this[lastIndex - 2] = (this[lastIndex - 2].toInt() xor 1).toByte() },
            valid.copyOf().apply { this[lastIndex - 1] = (this[lastIndex - 1].toInt() or 0x40).toByte() },
            buildReply(""), buildReply(" "), buildReply("x".repeat(41)), buildReply("Bad\u0001"),
            buildReply("Onyx") { it[1] = 0x58 },
            buildReply("Onyx") { it[2] = 0x60 },
            buildReply("Onyx") { it[3] = 8 },
            buildReply("Onyx") { it[4] = 1 },
            buildReply("Onyx") { it[5] = 1 },
            buildReply("Onyx") { it[6] = 0 },
            buildReply("Onyx") { it[7] = 1 },
            buildReply("Onyx") { it[8] = 1 },
            buildReply("Onyx") { it[9] = 5 },
            editorReply("JIANT 0.9.12"), reply("Pocket AMP_105"),
        )) {
            assertNull(FirmwareIdentity.parseBuildCode(invalid))
        }
    }

    @Test
    fun pocketAmpIdentityTriggersBuildCodeProbeAndItsLabelWins() {
        val requests = mutableListOf<List<Byte>>()
        val label = "Pocket_AMP_Onyx_1.01-2611051015"
        val evidence = FirmwareIdentity.query(
            includeLegacyEditor = true,
            probeBuildCode = { it == null || it.startsWith("Pocket AMP") },
        ) { request, parse ->
            requests += request.toList()
            parse(if (requests.size == 1) reply("Pocket AMP_105") else buildReply(label))
        }
        assertEquals(
            listOf(FirmwareIdentity.request().toList(), FirmwareIdentity.buildCodeRequest().toList()),
            requests,
        )
        assertEquals(MidiIdentityEvidence("Pocket AMP_105", label), evidence)
    }

    @Test
    fun nonPocketAmpIdentitySkipsBuildCodeAndFallsBackToLegacyEditor() {
        for (includeLegacyEditor in listOf(false, true)) {
            for (legacyLabel in listOf(null, "JIANT 0.9.12")) {
                val requests = mutableListOf<List<Byte>>()
                val evidence = FirmwareIdentity.query(
                    includeLegacyEditor,
                    probeBuildCode = { it == null || it.startsWith("Pocket AMP") },
                ) { request, parse ->
                    requests += request.toList()
                    when {
                        request.contentEquals(FirmwareIdentity.editorRequest()) -> legacyLabel?.let { parse(editorReply(it)) }
                        else -> parse(reply("FM-1_900"))
                    }
                }
                val expected = mutableListOf(FirmwareIdentity.request().toList())
                if (includeLegacyEditor) expected += FirmwareIdentity.editorRequest().toList()
                assertEquals(expected, requests)
                assertEquals(MidiIdentityEvidence("FM-1_900", legacyLabel.takeIf { includeLegacyEditor }), evidence)
            }
        }
    }

    @Test
    fun absentRepliesStayUnknownAndTransportFailuresPropagate() {
        assertEquals(MidiIdentityEvidence(), FirmwareIdentity.query(true) { _, _ -> null })
        val failure = IllegalStateException("disconnected")
        try {
            FirmwareIdentity.query(true) { _, _ -> throw failure }
            fail("Expected transport failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
    }

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
    fun editorInfoAcceptsAnyFeluccaProtocolFirmwareLabel() {
        for (label in listOf("JIANT 0.9.12", "FELUCCA v1.4.1", "X0X 1.2.3", "SLOOP v2.8", "Fork")) {
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
            editorReply(""), editorReply("   "), editorReply("JIANT " + "x".repeat(20)),
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
