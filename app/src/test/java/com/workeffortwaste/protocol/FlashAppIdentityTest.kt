package com.workeffortwaste.protocol

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies CRC-gated identity extraction from the encrypted application area of a flash image. */
class FlashAppIdentityTest {
    private val recognize: (String) -> Boolean =
        { it.startsWith("FM-1") || it.startsWith("BlackBox") }

    @Test fun extractsTheOnlyRecognizedIdentityFromValidatedApplication() {
        val image = image(payload("  32 Voice Save To ...  ", "FM-1_015"))
        assertEquals("FM-1_015", FlashAppIdentity.extract(image, recognize))
    }

    @Test fun repeatedIdentityIsNotAmbiguousButDifferentIdentitiesAre() {
        assertEquals(
            "BlackBox_021",
            FlashAppIdentity.extract(image(payload("BlackBox_021", "BlackBox_021")), recognize),
        )
        assertNull(FlashAppIdentity.extract(image(payload("FM-1_015", "BlackBox_021")), recognize))
    }

    @Test fun identityMustBeZeroTerminatedAndAtMostTheMidiFieldLength() {
        val overlong = "FM-1 padded with far more than its twenty-seven characters"
        assertNull(FlashAppIdentity.extract(image(payload(overlong)), recognize))
        val unterminated = ByteArrayOutputStream().apply {
            write(0)
            write("FM-1_015".toByteArray(Charsets.US_ASCII))
            write(0x90)
        }.toByteArray()
        assertNull(FlashAppIdentity.extract(image(unterminated), recognize))
        assertNull(FlashAppIdentity.extract(image(payload("Unrelated label")), recognize))
    }

    @Test fun corruptionOrMissingRecordsNeverProduceAnIdentity() {
        val payload = payload("FM-1_015")
        val corruptPayload = image(payload).also { it[BASE + 0x40] = (it[BASE + 0x40] + 1).toByte() }
        assertNull(FlashAppIdentity.extract(corruptPayload, recognize))
        val corruptEntry = image(payload).also { it[BASE + 0x20] = (it[BASE + 0x20] + 1).toByte() }
        assertNull(FlashAppIdentity.extract(corruptEntry, recognize))
        assertNull(FlashAppIdentity.extract(image(payload, appFileName = "cfg_tool.bin"), recognize))
        assertNull(FlashAppIdentity.extract(image(payload, areaHeadName = "other_area_head"), recognize))
        assertNull(FlashAppIdentity.extract(image(payload, bootEntryName = "other_dir_head"), recognize))
        assertNull(FlashAppIdentity.extract(image(payload, payloadSize = 0x200000), recognize))
        assertNull(FlashAppIdentity.extract(ByteArray(64), recognize))
    }

    private fun payload(vararg strings: String): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(1, 2, 3))
        for (value in strings) {
            write(value.toByteArray(Charsets.US_ASCII))
            write(0)
            write(0x90)
        }
        write(ByteArray(5))
    }.toByteArray()

    private fun image(
        payload: ByteArray,
        bootEntryName: String = "app_dir_head",
        areaHeadName: String = "app_area_head",
        appFileName: String = "app.bin",
        payloadSize: Int = payload.size,
    ): ByteArray {
        val image = ByteArray(0x8000) { 0xff.toByte() }
        val header = ByteArray(32)
        write16(header, 0, Fm1Protocol.crc16(header.copyOfRange(2, header.size)))
        encode(header, 0xffff).copyInto(image)
        val bootEntry = ByteArray(32)
        write32(bootEntry, 4, BASE)
        write32(bootEntry, 8, -1)
        bootEntryName.toByteArray(Charsets.US_ASCII).copyInto(bootEntry, 16)
        write16(bootEntry, 0, Fm1Protocol.crc16(bootEntry.copyOfRange(2, bootEntry.size)))
        encode(bootEntry, 0xffff).copyInto(image, 0x20)

        fun appRecord(relative: Int, name: String, address: Int, length: Int, dataCrc: Int) {
            val record = ByteArray(32)
            write16(record, 2, dataCrc)
            write32(record, 4, address)
            write32(record, 8, length)
            name.toByteArray(Charsets.US_ASCII).copyInto(record, 16)
            write16(record, 0, Fm1Protocol.crc16(record.copyOfRange(2, record.size)))
            encode(record, 0x980F xor (relative shr 2)).copyInto(image, BASE + relative)
        }
        appRecord(0, areaHeadName, 0x120, 0x40 + payload.size, 0xff83)
        appRecord(0x20, appFileName, 0x40, payloadSize, Fm1Protocol.crc16(payload))
        for (offset in payload.indices step 32) {
            val chunk = payload.copyOfRange(offset, minOf(offset + 32, payload.size))
            encode(chunk, 0x980F xor ((0x40 + offset) shr 2)).copyInto(image, BASE + 0x40 + offset)
        }
        return image
    }

    private fun encode(bytes: ByteArray, key: Int): ByteArray {
        var state = key and 0xffff
        return ByteArray(bytes.size) { index ->
            val value = bytes[index].toInt() xor (state and 0xff)
            state = ((state shl 1) xor (if (state and 0x8000 != 0) 0x1021 else 0)) and 0xffff
            value.toByte()
        }
    }

    private fun write16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun write32(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private companion object {
        const val BASE = FlashRestore.PROTECTED_SIZE
    }
}
