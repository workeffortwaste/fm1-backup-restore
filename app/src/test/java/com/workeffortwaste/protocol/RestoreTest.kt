package com.workeffortwaste.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises the actual public protocol against a strict 1 MiB NOR model, not mocked restore logic. */
class RestoreTest {
    @Test
    fun externalRestoreRequiresBinaryFilename() {
        for (name in listOf("backup.bin", "backup.BIN", "FM-1 backup.Bin")) {
            RestoreImage.requireBinaryName(name)
        }
        for (name in listOf("backup", "backup.zip", "backup.bin.zip", "backup.bin ")) {
            assertThrows(ProtocolException::class.java) { RestoreImage.requireBinaryName(name) }
        }
    }

    private val image get() = ByteArray(FlashRestore.FLASH_SIZE) { ((it * 17) xor (it shr 8)).toByte() }

    private class Command(val descriptor: ByteArray, val output: ByteArray) {
        val code get() = ((descriptor[0].toInt() and 0xff) shl 8) or (descriptor[1].toInt() and 0xff)
        val address get() = ByteBuffer.wrap(descriptor, 2, 4).int
        val isMutation get() = code == 0xfb01 || code == 0xfb04
    }

    /** Enforces real NOR programming (only 1 to 0) so missing erases cannot pass the happy path. */
    private class MemoryScsi(source: ByteArray) : ScsiTransport {
        val memory = source.copyOf().also { it.fill(0, FlashRestore.PROTECTED_SIZE) }
        val commands = mutableListOf<Command>()
        var family = "WL82"
        var product = "UDISK"
        var flashType = 3
        var jedec = 0xef4014L
        var before: (Command) -> Unit = {}
        var after: (Command) -> Unit = {}

        override fun execute(cdb: ByteArray, inputLength: Int, output: ByteArray): ByteArray {
            val command = Command(cdb.copyOf(), output.copyOf())
            commands += command
            before(command)
            val result = when {
                cdb[0] == 0x12.toByte() -> ByteArray(36) { 32 }.also {
                    family.toByteArray().copyInto(it, 8)
                    product.toByteArray().copyInto(it, 16)
                    "1.00".toByteArray().copyInto(it, 32)
                }

                command.code == 0xfc0a -> ByteArray(16).also {
                    cdb.copyInto(it, endIndex = 2)
                    it[2] = flashType.toByte()
                    ByteBuffer.wrap(it, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(jedec.toInt())
                }

                command.code == 0xfd05 -> memory.copyOfRange(command.address, command.address + inputLength)
                command.code == 0xfb01 -> {
                    assertEquals(16, inputLength)
                    assertTrue(output.isEmpty())
                    memory.fill(-1, command.address, command.address + FlashRestore.SECTOR_SIZE)
                    ByteArray(16).also { cdb.copyInto(it, endIndex = 2) }
                }

                command.code == 0xfb04 -> {
                    assertEquals(0, inputLength)
                    assertEquals(output.size, ByteBuffer.wrap(cdb, 6, 2).short.toInt())
                    assertEquals(0, cdb[8].toInt())
                    assertEquals(
                        Fm1Protocol.crc16(output),
                        (cdb[9].toInt() and 0xff) or ((cdb[10].toInt() and 0xff) shl 8)
                    )
                    output.forEachIndexed { offset, byte ->
                        val address = command.address + offset
                        memory[address] = (memory[address].toInt() and byte.toInt()).toByte()
                    }
                    byteArrayOf()
                }

                else -> throw AssertionError("Unexpected command 0x%04X".format(command.code))
            }
            after(command)
            return result
        }

        val mutations get() = commands.filter { it.isMutation }
    }

    private fun restore(
        device: MemoryScsi,
        source: ByteArray = image,
        capacity: Long = FlashRestore.FLASH_SIZE.toLong(),
        cancelled: () -> Unit = {},
        mutationStarting: () -> Unit = {},
        progress: (String, Long, Long) -> Unit = { _, _, _ -> },
    ) = FlashRestore(Fm1Protocol(device)).run(source, capacity, cancelled, mutationStarting, progress)

    @Test
    fun restoresEveryWritableByteAndPreservesEntireProtectedBaseline() {
        val source = image
        val device = MemoryScsi(source)
        val baseline = device.memory.copyOfRange(0, FlashRestore.PROTECTED_SIZE)
        var started = false
        val hash = restore(device, source, mutationStarting = { started = true })
        assertTrue(started)
        assertArrayEquals(
            source.copyOfRange(FlashRestore.PROTECTED_SIZE, FlashRestore.FLASH_SIZE),
            device.memory.copyOfRange(FlashRestore.PROTECTED_SIZE, FlashRestore.FLASH_SIZE),
        )
        assertArrayEquals(baseline, device.memory.copyOfRange(0, FlashRestore.PROTECTED_SIZE))
        assertEquals(
            RestoreImage.hex(
                MessageDigest.getInstance("SHA-256").digest(
                    source.copyOfRange(FlashRestore.PROTECTED_SIZE, FlashRestore.FLASH_SIZE)
                )
            ), hash
        )
        val erases = device.mutations.filter { it.code == 0xfb01 }
        val writes = device.mutations.filter { it.code == 0xfb04 }
        assertEquals(252, erases.size)
        assertEquals(4032, writes.size)
        assertTrue(device.mutations.all {
            it.address in 0x4000 until 0x100000
        })
        assertEquals(0xff000, erases.last().address)
        assertEquals(0xfff00, writes.last().address)
        assertEquals(source.last(), device.memory[0xfffff])
        device.mutations.chunked(17).forEach { sector ->
            assertEquals(0xfb01, sector.first().code)
            assertEquals(0, sector.first().address % 4096)
            sector.drop(1).forEachIndexed { index, page ->
                assertEquals(0xfb04, page.code)
                assertEquals(sector.first().address + index * 256, page.address)
            }
        }
        val reads = device.commands.filter { it.code == 0xfd05 }
        assertEquals(4032, reads.size)
        assertEquals(0x4000, reads.first().address)
        assertEquals(0xfff00, reads.last().address)
    }

    @Test
    fun differentSourceBootloaderBytesAreIgnoredAndRemainUntouched() {
        for (offset in listOf(0, 0x2345, 0x3fff)) {
            val source = image
            val device = MemoryScsi(source)
            val baseline = device.memory.copyOfRange(0, FlashRestore.PROTECTED_SIZE)
            source[offset] = (source[offset].toInt() xor 1).toByte()
            restore(device, source)
            assertArrayEquals(baseline, device.memory.copyOfRange(0, FlashRestore.PROTECTED_SIZE))
            assertTrue(device.mutations.all { it.address >= FlashRestore.PROTECTED_SIZE })
        }
    }

    @Test
    fun progressReportsExplicitTotalsAndCompletesEveryPhase() {
        val phases = linkedMapOf(
            "Writing to FM-1 flash" to FlashRestore.WRITABLE_SIZE.toLong(),
            "Verifying: reading back written data" to FlashRestore.WRITABLE_SIZE.toLong(),
        )
        val lastBytes = linkedMapOf<String, Long>()
        restore(MemoryScsi(image), progress = { phase, bytes, total ->
            assertEquals(phases.getValue(phase), total)
            assertEquals((lastBytes[phase] ?: 0L) + FlashRestore.PAGE_SIZE, bytes)
            lastBytes[phase] = bytes
        })
        assertEquals(phases.keys.toList(), lastBytes.keys.toList())
        assertEquals(phases, lastBytes)
    }

    @Test
    fun invalidImageLengthOrUnconfirmedCapacityPreventsEveryCommand() {
        for (size in listOf(0, 0xfffff, 0x100001, 0x104000)) {
            val device = MemoryScsi(image)
            assertThrows(IllegalArgumentException::class.java) { restore(device, ByteArray(size)) }
            assertTrue(device.commands.isEmpty())
        }
        for (capacity in listOf(0L, 0x104000L, 0xfffffL, Long.MAX_VALUE)) {
            val device = MemoryScsi(image)
            assertThrows(IllegalArgumentException::class.java) { restore(device, capacity = capacity) }
            assertTrue(device.commands.isEmpty())
        }
    }

    @Test
    fun incompatibleLiveIdentityAndFlashMetadataNeverMutate() {
        val invalidTargets: List<(MemoryScsi) -> Unit> = listOf(
            { it.family = "BR25" },
            { it.product = "OTHER" },
            { it.flashType = 4 },
            { it.jedec = 0xef4015 },
            { it.jedec = 0xef4013 },
            { it.jedec = 0 },
            { it.jedec = 0xff4014 },
            { it.jedec = 0x004014 },
            { it.jedec = 0xef0014 },
            { it.jedec = 0xefff14 },
            { it.jedec = 0x1ef4014 },
        )
        invalidTargets.forEach { configure ->
            val device = MemoryScsi(image)
            configure(device)
            assertThrows(ProtocolException::class.java) { restore(device) }
            assertTrue(device.mutations.isEmpty())
        }
    }

    @Test
    fun bothSupportedNorTypesCanRestore() {
        val device = MemoryScsi(image)
        device.flashType = 0x16
        restore(device)
        assertArrayEquals(image, device.memory)
    }

    @Test
    fun runningLoaderCanRetainRomInquiryName() {
        val device = MemoryScsi(image)
        device.product = "UBOOT1.00"
        assertEquals(FlashInfo(3, 0xef4014), Fm1Protocol(device).probeLoader())
        assertTrue(device.mutations.isEmpty())
        restore(device)
        assertArrayEquals(image, device.memory)
    }

    @Test
    fun romWithoutLoaderCommandsCannotProbeOrRestore() {
        val device = MemoryScsi(image)
        device.product = "UBOOT1.00"
        device.before = { command ->
            if (command.code == 0xfc0a) throw ProtocolException("Loader command unsupported")
        }
        assertThrows(ProtocolException::class.java) { Fm1Protocol(device).probeLoader() }
        assertThrows(ProtocolException::class.java) { restore(device) }
        assertTrue(device.mutations.isEmpty())
    }

    @Test
    fun loaderProbeRejectsInvalidMetadataAndShortReadsWithoutMutation() {
        for (id in listOf(0L, 0xff4014, 0xef0014, 0xef4015)) {
            val device = MemoryScsi(image)
            device.jedec = id
            assertThrows(ProtocolException::class.java) { Fm1Protocol(device).probeLoader() }
            assertTrue(device.commands.none { it.code == 0xfd05 || it.isMutation })
        }
        val device = MemoryScsi(image)
        val transport = object : ScsiTransport {
            override fun execute(cdb: ByteArray, inputLength: Int, output: ByteArray): ByteArray {
                val data = device.execute(cdb, inputLength, output)
                return if (cdb[0] == 0xfd.toByte()) data.copyOf(255) else data
            }
        }
        assertThrows(ProtocolException::class.java) { Fm1Protocol(transport).probeLoader() }
        assertTrue(device.mutations.isEmpty())
    }

    @Test
    fun eraseOrWriteFailureStopsImmediatelyWithNoRetryOrFurtherRead() {
        for (code in listOf(0xfb01, 0xfb04)) {
            val device = MemoryScsi(image)
            device.before = { if (it.code == code) throw IOException("Disconnected or timed out") }
            assertThrows(IOException::class.java) { restore(device) }
            assertEquals(code, device.commands.last().code)
            assertEquals(1, device.commands.count { it.code == code })
            assertEquals(if (code == 0xfb01) 1 else 2, device.mutations.size)
        }
    }

    @Test
    fun wrongEraseEchoStopsBeforeAnyPageWrite() {
        val delegate = MemoryScsi(image)
        val transport = object : ScsiTransport {
            override fun execute(cdb: ByteArray, inputLength: Int, output: ByteArray): ByteArray {
                val response = delegate.execute(cdb, inputLength, output)
                return if (cdb[0] == 0xfb.toByte() && cdb[1] == 1.toByte()) ByteArray(16) else response
            }
        }
        assertThrows(ProtocolException::class.java) {
            FlashRestore(Fm1Protocol(transport)).run(image, 0x100000, {}, {}, { _, _, _ -> })
        }
        assertEquals(1, delegate.mutations.size)
    }

    @Test
    fun restoreDoesNotReadProtectedBootloaderBytes() {
        val delegate = MemoryScsi(image)
        restore(delegate)
        assertTrue(delegate.commands.filter { it.code == 0xfd05 }
            .all { it.address >= FlashRestore.PROTECTED_SIZE })
    }

    @Test
    fun verificationReadFailureIsNotRetried() {
        val device = MemoryScsi(image)
        device.before = {
            if (it.code == 0xfd05 && it.address == 0x4000) throw IOException("Readback disconnected")
        }
        assertThrows(IOException::class.java) { restore(device) }
        assertEquals(0xfd05, device.commands.last().code)
        assertEquals(0x4000, device.commands.last().address)
        assertEquals(1, device.commands.count { it.code == 0xfd05 && it.address == 0x4000 })
    }

    @Test
    fun mismatchOnLastWritableByteFailsVerification() {
        val device = MemoryScsi(image)
        var corruptOnce = true
        device.after = {
            if (corruptOnce && it.code == 0xfb04 && it.address == 0xfff00) {
                device.memory[0xfffff] = (device.memory[0xfffff].toInt() xor 1).toByte()
                corruptOnce = false
            }
        }
        val failure = assertThrows(ProtocolException::class.java) { restore(device) }
        assertTrue(failure.message!!.contains("readback mismatch"))
        assertEquals(0xfff00, device.commands.last().address)
        restore(device)
        assertArrayEquals(
            image.copyOfRange(FlashRestore.PROTECTED_SIZE, FlashRestore.FLASH_SIZE),
            device.memory.copyOfRange(FlashRestore.PROTECTED_SIZE, FlashRestore.FLASH_SIZE),
        )
    }

    @Test
    fun externalProtectedMutationIsIgnoredByRestoreVerification() {
        val device = MemoryScsi(image)
        device.after = {
            if (it.code == 0xfb01 && it.address == 0x4000) {
                device.memory[0x3fff] = (device.memory[0x3fff].toInt() xor 1).toByte()
            }
        }
        restore(device)
        assertEquals((image[0x3fff].toInt() xor 1).toByte(), device.memory[0x3fff])
        assertTrue(device.mutations.all { it.address in 0x4000 until 0x100000 })
    }

    @Test
    fun cancellationBeforeFirstEraseMakesNoChanges() {
        val device = MemoryScsi(image)
        var signalled = false
        assertThrows(CancellationException::class.java) {
            restore(device, cancelled = {
                // Cancel after live metadata validation, immediately before mutation.
                if (device.commands.lastOrNull()?.code == 0xfc0a) {
                    throw CancellationException()
                }
            }, mutationStarting = { signalled = true })
        }
        assertFalse(signalled)
        assertTrue(device.mutations.isEmpty())
    }

    @Test
    fun cancellationAfterEraseStopsBeforeWriteAndPreservesBootloader() {
        val source = image
        val device = MemoryScsi(source)
        var signalled = false
        assertThrows(CancellationException::class.java) {
            restore(device, source, cancelled = {
                if (device.mutations.isNotEmpty()) throw CancellationException()
            }, mutationStarting = { signalled = true })
        }
        assertTrue(signalled)
        assertEquals(1, device.mutations.size)
        assertEquals(0xfb01, device.commands.last().code)
        assertArrayEquals(source.copyOfRange(0, 0x4000), device.memory.copyOfRange(0, 0x4000))
    }

    @Test
    fun cancellationAfterFirstPageDoesNotWriteNextPageOrRetryErase() {
        val device = MemoryScsi(image)
        assertThrows(CancellationException::class.java) {
            restore(device, cancelled = {
                if (device.commands.lastOrNull()?.code == 0xfb04) throw CancellationException()
            })
        }
        assertEquals(listOf(0xfb01, 0xfb04), device.mutations.map { it.code })
        assertEquals(0x4000, device.commands.last().address)
    }

    @Test
    fun cancellationFromAnotherThreadFinishesInFlightMutationBeforeStopping() {
        for (code in listOf(0xfb01, 0xfb04)) {
            val source = image
            val device = MemoryScsi(source)
            val enteredCommand = CountDownLatch(1)
            val returnCommand = CountDownLatch(1)
            val cancelled = AtomicBoolean()
            val completedCommand = AtomicBoolean()
            device.before = {
                if (it.code == code) {
                    enteredCommand.countDown()
                    check(returnCommand.await(5, TimeUnit.SECONDS))
                }
            }
            device.after = { if (it.code == code) completedCommand.set(true) }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<String> {
                    restore(device, source, cancelled = {
                        if (cancelled.get()) throw CancellationException()
                    })
                }
                assertTrue(enteredCommand.await(5, TimeUnit.SECONDS))
                cancelled.set(true)
                assertFalse(completedCommand.get())
                returnCommand.countDown()
                val failure = assertThrows(ExecutionException::class.java) {
                    result.get(5, TimeUnit.SECONDS)
                }
                assertTrue(failure.cause is CancellationException)
                assertTrue(completedCommand.get())
                assertEquals(code, device.commands.last().code)
                assertEquals(1, device.commands.count { it.code == code })
                assertTrue(device.mutations.all { it.address >= 0x4000 })
                assertArrayEquals(source.copyOfRange(0, 0x4000), device.memory.copyOfRange(0, 0x4000))
            } finally {
                returnCommand.countDown()
                worker.shutdown()
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun cancellationAtStartAndDuringVerificationNeverReturnsSuccess() {
        val device = MemoryScsi(image)
        assertThrows(CancellationException::class.java) {
            restore(device, cancelled = { throw CancellationException() })
        }
        assertTrue(device.commands.isEmpty())
        assertThrows(CancellationException::class.java) {
            restore(device, cancelled = {
                if (device.commands.lastOrNull()?.let {
                        it.code == 0xfd05 && it.address == 0x4000
                    } == true) throw CancellationException()
            })
        }
        assertEquals(0x4000, device.commands.last().address)
    }

    @Test
    fun callerImageMutationDuringRestoreCannotChangeSnapshot() {
        val source = image
        val original = source.copyOf()
        val device = MemoryScsi(source)
        restore(device, source, mutationStarting = { source.fill(0) })
        assertArrayEquals(original, device.memory)
    }

    @Test
    fun unsafePublicMutationCommandsAreRejectedWithoutTransportExecution() {
        val device = MemoryScsi(image)
        val protocol = Fm1Protocol(device)
        for (address in listOf(
            -1L, 0L, 0x3000L, 0x3fffL, 0x4001L, 0xff001L,
            0x100000L, 0x104000L, Long.MAX_VALUE, Long.MIN_VALUE
        )) {
            assertThrows(IllegalArgumentException::class.java) { protocol.eraseSector(address) }
        }
        for (address in listOf(-1L, 0L, 0x3fffL, 0x100000L, 0x104000L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { protocol.writeFlash(address, byteArrayOf(1)) }
        }
        for (size in listOf(0, 257, 4096)) {
            assertThrows(IllegalArgumentException::class.java) { protocol.writeFlash(0x4000, ByteArray(size)) }
        }
        for ((address, size) in listOf(0x40ffL to 2, 0x4001L to 256, 0xfffffL to 2)) {
            assertThrows(IllegalArgumentException::class.java) { protocol.writeFlash(address, ByteArray(size)) }
        }
        assertTrue(device.commands.isEmpty())
    }

    @Test
    fun exactEraseWriteEncodingIncludesCrcLittleEndianAndNoAutoerase() {
        val device = MemoryScsi(image)
        val protocol = Fm1Protocol(device)
        protocol.eraseSector(0x4000)
        protocol.writeFlash(0x4000, byteArrayOf(1))
        assertArrayEquals(
            byteArrayOf(0xfb.toByte(), 1, 0, 0, 0x40, 0) + ByteArray(10) { -1 },
            device.commands[0].descriptor,
        )
        assertArrayEquals(
            byteArrayOf(0xfb.toByte(), 4, 0, 0, 0x40, 0, 0, 1, 0, 0x21, 0x10) + ByteArray(5) { -1 },
            device.commands[1].descriptor,
        )
        assertArrayEquals(byteArrayOf(1), device.commands[1].output)
        protocol.eraseSector(0xff000)
        protocol.writeFlash(0xfffff, byteArrayOf(1))
        assertEquals(0xfffff, device.commands.last().address)
        // A partial page need not start at its boundary, but it must end within that same page.
        protocol.writeFlash(0x4101, ByteArray(255))
        assertEquals(0x4101, device.commands.last().address)
    }

    @Test
    fun stagesExactLengthAndHashesThenChecksStableSnapshot() {
        val source = image
        val output = ByteArrayOutputStream()
        val hash = RestoreImage.stage(ByteArrayInputStream(source), output) {}
        assertArrayEquals(source, output.toByteArray())
        assertEquals(RestoreImage.hex(MessageDigest.getInstance("SHA-256").digest(source)), hash)
        assertArrayEquals(source, RestoreImage.snapshot(ByteArrayInputStream(output.toByteArray()), hash) {})
        source[0x5000] = (source[0x5000].toInt() xor 1).toByte()
        assertThrows(ProtocolException::class.java) {
            RestoreImage.snapshot(ByteArrayInputStream(source), hash) {}
        }
    }

    @Test
    fun stagingRejectsShortAndOversizeInputsWithBoundedReadingAndWriting() {
        for (size in listOf(0, 0x4000, 0xfffff, 0x100001, 0x104000, 0x200000)) {
            val input = ByteArrayInputStream(ByteArray(size))
            val output = ByteArrayOutputStream()
            assertThrows(ProtocolException::class.java) { RestoreImage.stage(input, output) {} }
            assertTrue(output.size() <= FlashRestore.FLASH_SIZE + 1)
            assertEquals(minOf(size, FlashRestore.FLASH_SIZE + 1), size - input.available())
        }
    }

    @Test
    fun stagingCancellationReadFailureAndStalledProviderFailBeforeUsbIsInvolved() {
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            RestoreImage.stage(ByteArrayInputStream(image), output) { throw CancellationException() }
        }
        assertEquals(0, output.size())
        val failure = object : InputStream() {
            override fun read(): Int = throw IOException("Provider lost")
        }
        assertThrows(IOException::class.java) { RestoreImage.stage(failure, output) {} }
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertThrows(ProtocolException::class.java) { RestoreImage.stage(stalled, output) {} }
    }
}
