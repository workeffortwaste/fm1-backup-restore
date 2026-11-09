package com.workeffortwaste.protocol

import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BootloaderVerifierTest {
    @Test
    fun readsTheProtectedRegionInExactPagesAndReportsItsSha256() {
        val image = ByteArray(FlashRestore.PROTECTED_SIZE) { (it % 251).toByte() }
        var nextAddress = 0L
        var progress = 0
        val result = BootloaderVerifier.read({ address, length ->
            assertEquals(nextAddress, address)
            assertEquals(FlashRestore.PAGE_SIZE, length)
            nextAddress += length
            image.copyOfRange(address.toInt(), address.toInt() + length)
        }, progress = { progress = it })
        val expected = MessageDigest.getInstance("SHA-256").digest(image)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        assertEquals(FlashRestore.PROTECTED_SIZE.toLong(), nextAddress)
        assertEquals(FlashRestore.PROTECTED_SIZE, progress)
        assertEquals(expected, result.sha256)
        assertFalse(result.matchesStockV15)
    }

    @Test
    fun acceptsOnlyThePinnedStockV15Fingerprint() {
        assertTrue(BootloaderVerifier.isKnownStockV15(BootloaderVerifier.STOCK_V15_SHA256))
        assertFalse(BootloaderVerifier.isKnownStockV15("0".repeat(64)))
    }

    @Test
    fun recognizesTheVerifiedStockV16BootAreaFingerprint() {
        assertTrue(
            BootloaderVerifier.isKnownStockV16(
                "8f5228c2d6ea2068838ef4916f946bea877d14b07e6cfca49e41957f4c7b234f",
            ),
        )
        assertFalse(BootloaderVerifier.isKnownStockV16("0".repeat(64)))
    }

    @Test
    fun identifiesMbootloaderVersionsFromAnEncryptedFlashBootArea() {
        for (version in listOf("1.1-e9a25e1", "1.2-22e2559")) {
            val result = BootloaderVerifier.identify(installedFm1BootArea(version))
            assertEquals("m-bootloader $version", result.identity)
            assertFalse(result.matchesStockV15)
            assertFalse(result.matchesPublishedRelease)
            assertTrue(result.message.contains("does not match a published release"))
        }
    }

    @Test
    fun verifiesPublishedArtifactDigestsAndRejectsAlteredArtifacts() {
        assertTrue(
            BootloaderVerifier.isKnownMbootloaderRelease(
                "1.1-e9a25e1",
                "ba1429f5f40f3804821fa5f4f81a8d892b9c56519af2c76b6c551d62026a4200",
            ),
        )
        assertTrue(
            BootloaderVerifier.isKnownMbootloaderRelease(
                "1.2-22e2559",
                "9e5ec0e39dc55c167f8e289cfdac612d7d340b5f46100e1be2540ef4d6ec2f13",
            ),
        )
        assertFalse(
            BootloaderVerifier.isKnownMbootloaderRelease(
                "1.2-22e2559",
                "0".repeat(64),
            ),
        )
    }

    @Test
    fun malformedOrDamagedBootAreaIsNotMisidentified() {
        val region = installedFm1BootArea("1.2-22e2559")
        region[0xa0 + 0x50] = 0
        val result = BootloaderVerifier.identify(region)
        assertEquals(null, result.identity)
        assertFalse(result.matchesStockV15)
        assertTrue(result.message.startsWith("Unrecognized bootloader"))
    }

    @Test
    fun rejectsShortReadsAndHonorsCancellation() {
        assertThrows(ProtocolException::class.java) {
            BootloaderVerifier.read({ _, _ -> ByteArray(FlashRestore.PAGE_SIZE - 1) })
        }
        assertThrows(CancellationException::class.java) {
            BootloaderVerifier.read({ _, _ -> error("must not read") }, { throw CancellationException() })
        }
    }

    private fun installedFm1BootArea(version: String): ByteArray {
        val payload = ByteArray(0x70)
        "AC79BLID".toByteArray(Charsets.US_ASCII).copyInto(payload, 0x40)
        byteArrayOf(102, 109, 49, 0, 0, 0, 0, 0).copyInto(payload, 0x48)
        version.toByteArray(Charsets.US_ASCII).copyInto(payload, 0x50)

        val container = ByteArray(16 + payload.size)
        put16(container, 0, 1)
        put16(container, 2, payload.size)
        put32(container, 4, 0x01C02000)
        put32(container, 8, 16)
        put16(container, 12, crc16(payload))
        put16(container, 14, crc16(container.copyOfRange(0, 14)))
        payload.copyInto(container, 16)

        val region = ByteArray(FlashRestore.PROTECTED_SIZE) { 0xff.toByte() }
        val flashHeader = ByteArray(32)
        put16(flashHeader, 0, crc16(flashHeader.copyOfRange(2, 32)))
        enc(flashHeader).copyInto(region)

        val entry = ByteArray(32)
        put16(entry, 2, crc16(container))
        put32(entry, 4, 0xa0)
        put32(entry, 8, container.size)
        "uboot.boot".toByteArray(Charsets.US_ASCII).copyInto(entry, 16)
        put16(entry, 0, crc16(entry.copyOfRange(2, 32)))
        enc(entry).copyInto(region, 0x20)

        enc(container.copyOfRange(0, 16)).copyInto(region, 0xa0)
        enc(container.copyOfRange(16, container.size)).copyInto(region, 0xb0)
        return region
    }

    private fun enc(bytes: ByteArray): ByteArray {
        var key = 0xffff
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

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
    }
}