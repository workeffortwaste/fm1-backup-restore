package com.workeffortwaste.protocol

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BootloaderRecoveryTest {
    private class Nor : ScsiTransport {
        val memory = ByteArray(FlashRestore.FLASH_SIZE) { 0x5a }
        val mutations = mutableListOf<Pair<Int, Int>>()
        var afterWrite: (Int) -> Unit = {}
        var shortRead = false
        var jedec = 0xef4014

        override fun execute(cdb: ByteArray, inputLength: Int, output: ByteArray): ByteArray {
            if (cdb[0] == 0x12.toByte()) {
                return ByteArray(36) { 32 }.also {
                    "WL82".toByteArray().copyInto(it, 8)
                    "UDISK".toByteArray().copyInto(it, 16)
                }
            }
            val code = ((cdb[0].toInt() and 255) shl 8) or (cdb[1].toInt() and 255)
            val address = ByteBuffer.wrap(cdb, 2, 4).int
            fun reply() = ByteArray(16).also { cdb.copyInto(it, endIndex = 2) }
            return when (code) {
                0xfc0a -> reply().also {
                    it[2] = 3
                    ByteBuffer.wrap(it, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(jedec)
                }
                0xfd05 -> memory.copyOfRange(address, address + inputLength - if (shortRead) 1 else 0)
                0xfb01 -> {
                    mutations += code to address
                    memory.fill(-1, address, address + FlashRestore.SECTOR_SIZE)
                    reply()
                }
                0xfb04 -> {
                    assertEquals(Fm1Protocol.crc16(output), (cdb[9].toInt() and 255) or ((cdb[10].toInt() and 255) shl 8))
                    mutations += code to address
                    output.forEachIndexed { index, byte ->
                        memory[address + index] = (memory[address + index].toInt() and byte.toInt()).toByte()
                    }
                    afterWrite(address)
                    byteArrayOf()
                }
                else -> throw AssertionError("Unexpected command %04X".format(code))
            }
        }
    }

    private fun stock(): ByteArray {
        val path = System.getenv("FM1_V15_PACKAGE")
        assumeTrue("Set FM1_V15_PACKAGE to the official v15 .fwsc to run stock-data integration cases", path != null)
        return BootloaderRecovery.fromPackage(File(requireNotNull(path)).readBytes())
    }

    @Test
    fun rejectsNonStockOrIncorrectlySizedDataBeforeAnyDeviceCommand() {
        val rejecting = Fm1Protocol(object : ScsiTransport {
            override fun execute(cdb: ByteArray, inputLength: Int, output: ByteArray): ByteArray =
                throw AssertionError("Invalid source must not touch the device")
        })
        for (size in listOf(0, 0x3fff, 0x4000, 0x4001, 0x100000)) {
            assertThrows(IllegalArgumentException::class.java) {
                BootloaderRecovery(rejecting).run(ByteArray(size), {}, {}, {}, { _, _ -> })
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BootloaderRecovery.fromPackage(ByteArray(V15RecoveryImage.PACKAGE_SIZE))
        }
    }

    @Test
    fun emergencyCommandsCannotEscapeBootAreaAndNormalCommandsStillCannotEnterIt() {
        val device = Nor()
        val protocol = Fm1Protocol(device)
        for (address in listOf(-1L, 1L, 0x4000L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { protocol.eraseBootloaderSector(address) }
        }
        for (address in listOf(-1L, 0x3f01L, 0x4000L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                protocol.writeBootloaderPage(address, ByteArray(256))
            }
        }
        for (size in listOf(0, 257)) {
            assertThrows(IllegalArgumentException::class.java) { protocol.writeBootloaderPage(0, ByteArray(size)) }
        }
        assertThrows(IllegalArgumentException::class.java) { protocol.eraseSector(0) }
        assertThrows(IllegalArgumentException::class.java) { protocol.writeFlash(0, ByteArray(256)) }
        assertTrue(device.mutations.isEmpty())
        protocol.eraseBootloaderSector(0)
        protocol.eraseBootloaderSector(0x3000)
        protocol.writeBootloaderPage(0, ByteArray(256))
        protocol.writeBootloaderPage(0x3f00, ByteArray(256))
        assertEquals(listOf(0, 0x3000, 0, 0x3f00), device.mutations.map { it.second })
    }

    @Test
    fun restoresAndVerifiesOnlyBootAreaAfterSavingOriginal() {
        val expected = stock()
        val device = Nor()
        val outside = device.memory.copyOfRange(0x4000, device.memory.size)
        val original = device.memory.copyOfRange(0, 0x4000)
        var saved = false
        var started = false
        val phases = mutableListOf<Pair<String, Int>>()
        val hash = BootloaderRecovery(Fm1Protocol(device)).run(
            expected, {},
            saveOriginal = {
                assertTrue(device.mutations.isEmpty())
                assertArrayEquals(original, it)
                saved = true
                it.fill(0) // The persistence callback cannot alter the comparison baseline.
            },
            mutationStarting = {
                assertTrue(saved)
                started = true
            },
            progress = { phase, bytes -> phases += phase to bytes },
        )
        assertTrue(started)
        assertEquals(BootloaderVerifier.STOCK_V15_SHA256, hash)
        assertArrayEquals(expected, device.memory.copyOfRange(0, 0x4000))
        assertArrayEquals(outside, device.memory.copyOfRange(0x4000, device.memory.size))
        assertEquals(listOf(0, 0x1000, 0x2000, 0x3000), device.mutations.filter { it.first == 0xfb01 }.map { it.second })
        assertTrue(device.mutations.all { it.second in 0 until 0x4000 })
        assertEquals("Verifying restored bootloader" to 0x4000, phases.last())
    }

    @Test
    fun matchingBootloaderIsVerifiedWithoutMutation() {
        val expected = stock()
        val device = Nor()
        expected.copyInto(device.memory)
        BootloaderRecovery(Fm1Protocol(device)).run(
            expected, {}, {}, { throw AssertionError("Already stock") }, { _, _ -> },
        )
        assertTrue(device.mutations.isEmpty())
    }

    @Test
    fun forcedRecoveryErasesAllFourSectorsAndProgramsAll64PagesEvenWhenMatching() {
        val expected = stock()
        val device = Nor()
        expected.copyInto(device.memory)
        val outside = device.memory.copyOfRange(0x4000, device.memory.size)
        var started = false
        BootloaderRecovery(Fm1Protocol(device)).run(
            expected, {}, {}, { started = true }, { _, _ -> }, forceRewrite = true,
        )
        assertTrue(started)
        assertEquals((0 until 0x4000 step 0x1000).toList(),
            device.mutations.filter { it.first == 0xfb01 }.map { it.second })
        assertEquals((0 until 0x4000 step 256).toList(),
            device.mutations.filter { it.first == 0xfb04 }.map { it.second })
        assertArrayEquals(expected, device.memory.copyOfRange(0, 0x4000))
        assertArrayEquals(outside, device.memory.copyOfRange(0x4000, device.memory.size))
    }

    @Test
    fun persistenceFailurePreventsEveryMutation() {
        val expected = stock()
        val device = Nor()
        assertThrows(IOException::class.java) {
            BootloaderRecovery(Fm1Protocol(device)).run(
                expected, {}, { throw IOException("Storage unavailable") }, {}, { _, _ -> },
            )
        }
        assertTrue(device.mutations.isEmpty())
    }

    @Test
    fun lostRecoveryConnectionBeforeErasePreventsEveryMutation() {
        val expected = stock()
        val device = Nor()
        var connected = true
        assertThrows(IOException::class.java) {
            BootloaderRecovery(Fm1Protocol(device)).run(
                expected,
                { if (!connected) throw IOException("Disconnected") },
                { connected = false }, {}, { _, _ -> },
            )
        }
        assertTrue(device.mutations.isEmpty())
    }

    @Test
    fun readbackCorruptionIsNotReportedAsSuccess() {
        val expected = stock()
        val device = Nor()
        device.afterWrite = { device.memory[0] = (expected[0].toInt() xor 1).toByte() }
        assertThrows(RestoreVerificationException::class.java) {
            BootloaderRecovery(Fm1Protocol(device)).run(expected, {}, {}, {}, { _, _ -> })
        }
    }

    @Test
    fun sourceChangesAfterValidationCannotChangeWhatIsProgrammed() {
        val source = stock()
        val expected = source.copyOf()
        val device = Nor()
        BootloaderRecovery(Fm1Protocol(device)).run(
            source, {}, { source.fill(0) }, {}, { _, _ -> },
        )
        assertArrayEquals(expected, device.memory.copyOfRange(0, 0x4000))
    }

    @Test
    fun connectionLossDuringProgrammingStopsWithoutRetryingOrTouchingOtherSectors() {
        val expected = stock()
        val device = Nor()
        var connected = true
        device.afterWrite = { connected = false }
        assertThrows(IOException::class.java) {
            BootloaderRecovery(Fm1Protocol(device)).run(
                expected, { if (!connected) throw IOException("Disconnected") }, {}, {}, { _, _ -> },
            )
        }
        assertEquals(listOf(0xfb01 to 0, 0xfb04 to 0), device.mutations)
    }

    @Test
    fun shortBaselineReadAndWrongFlashCapacityNeverErase() {
        val expected = stock()
        for (shortRead in listOf(false, true)) {
            val device = Nor().also {
                it.shortRead = shortRead
                if (!shortRead) it.jedec = 0xef4015
            }
            assertThrows(ProtocolException::class.java) {
                BootloaderRecovery(Fm1Protocol(device)).run(expected, {}, {}, {}, { _, _ -> })
            }
            assertTrue(device.mutations.isEmpty())
        }
    }
}
