package com.workeffortwaste.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Wire-format and backup safety regressions; these tests need neither Android nor USB hardware. */
class ProtocolTest {
    /** The APK asset must retain the exact WL82 loader bytes and exclude other chip loaders. */
    @Test
    fun bundlesOnlyTheOriginalWl82Loader() {
        val assets = File("src/main/assets")
        val loader = File(assets, Fm1Protocol.LOADER_ASSET)
        assertTrue("Missing asset: $loader", loader.isFile)
        val hash = MessageDigest.getInstance("SHA-256").digest(loader.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("d41da6126760c9d66660bcc0cac8d27d221806c5e369a8036921efe68dca5376", hash)
        assertEquals(
            setOf(loader, File(assets, "material-symbols-LICENSE.txt")),
            assets.walkTopDown().filter { it.isFile }.toSet(),
        )
    }

    /**
     * Captured command snapshot, copied so subsequent caller mutation cannot change assertions.
     * @property cdb Wire-level SCSI command descriptor.
     * @property inputLength Requested device-to-host payload length.
     * @property output Host-to-device payload, empty for reads.
     */
    private class Command(val cdb: ByteArray, val inputLength: Int, val output: ByteArray)

    /** Captures protocol commands, optionally substituting a device response for each command. */
    private class RecordingScsi : ScsiTransport {
        /** Ordered snapshots used for assertions about exact encoding and commands never sent. */
        val commands = mutableListOf<Command>()

        /** Null selects the default echoed response; custom replies model inquiry and malformed data. */
        var reply: ((Command) -> ByteArray)? = null
        override fun execute(cdb: ByteArray, inputLength: Int, output: ByteArray): ByteArray {
            val command = Command(cdb.copyOf(), inputLength, output.copyOf())
            commands += command
            return reply?.invoke(command) ?: ByteArray(inputLength).also { response ->
                if (response.size == 16) cdb.copyInto(response, endIndex = 2)
            }
        }
    }

    /** Scripted USB transfer counts and packets, including reset failures after an ambiguous command. */
    private class FakeBulk : BulkIo {
        /** Captures writes independently of whether the simulated transfer succeeds. */
        val writes = mutableListOf<ByteArray>()

        /** FIFO data/status packets; an empty queue simulates Android's negative timeout result. */
        val reads = ArrayDeque<ByteArray>()

        /** Overrides successful write length to simulate short transfers. */
        var writeResult: Int? = null

        /** Injected cleanup failure must not replace the original command failure. */
        var resetFailure: IOException? = null

        /** Records whether BOT recovery was attempted. */
        var reset = false

        /** Records ownership cleanup by BotTransport.close. */
        var closed = false
        override fun write(data: ByteArray): Int {
            writes += data.copyOf()
            return writeResult ?: data.size
        }

        override fun read(data: ByteArray): Int {
            if (reads.isEmpty()) return -1
            val packet = reads.removeFirst()
            packet.copyInto(data, endIndex = minOf(packet.size, data.size))
            return packet.size
        }

        override fun reset() {
            reset = true
            resetFailure?.let { failure -> throw failure }
        }

        override fun close() {
            closed = true
        }
    }

    /** Builds a little-endian BOT status wrapper, with individual fields overrideable for failures. */
    private fun status(tag: Int = 1, residue: Int = 0, result: Int = 0, signature: Int = 0x53425355) =
        ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(signature).putInt(tag).putInt(residue).put(result.toByte()).array()

    /** Fixed-width, space-padded standard SCSI inquiry fields used by WL82 ROM and its loader. */
    private fun inquiryReply(family: String = "WL82", product: String = "UBOOT1.00") =
        ByteArray(36) { 32 }.also { response ->
            family.toByteArray().copyInto(response, 8)
            product.toByteArray().copyInto(response, 16)
            "1.00".toByteArray().copyInto(response, 32)
        }

    /** Loader selection depends only on the known ROM mode, not generic chip profiles. */
    @Test
    fun identifiesFm1DownloadModesWithoutAChipProfile() {
        val scsi = RecordingScsi()
        val fm1 = Fm1Protocol(scsi)
        for (product in listOf("UBOOT1.00", "UBOOT2.00", "UDISK", "DEVICE")) {
            scsi.reply = { inquiryReply(product = product) }
            assertEquals(product == "UBOOT1.00", fm1.identify().needsLoader)
        }
    }

    /** No loader or write command may follow a rejected family or download mode. */
    @Test
    fun keepsIdentitySafetyCheckForNonFm1Devices() {
        val scsi = RecordingScsi()
        val fm1 = Fm1Protocol(scsi)
        for (family in listOf("br25", "wl80", "br17", "", "ac791n")) {
            scsi.reply = { inquiryReply(family) }
            assertThrows(ProtocolException::class.java) {
                fm1.identify()
            }
        }
        scsi.reply = { inquiryReply(product = "UNRELATED") }
        assertThrows(ProtocolException::class.java) {
            fm1.identify()
        }
        assertTrue(scsi.commands.all { command ->
            command.cdb[0] == 0x12.toByte() && command.output.isEmpty()
        })
    }

    /** Inquiry remains standard SCSI even though later commands use vendor-specific encoding. */
    @Test
    fun inquiryUsesStandardScsiAndNormalizesFamily() {
        val scsi = RecordingScsi()
        scsi.reply = {
            ByteArray(36) { 32 }.also { response ->
                "WL82    ".toByteArray().copyInto(response, 8)
                "UBOOT1.00       ".toByteArray().copyInto(response, 16)
                "1.00".toByteArray().copyInto(response, 32)
            }
        }
        assertEquals(Fm1Identity("UBOOT1.00", "1.00"), Fm1Protocol(scsi).identify())
        assertArrayEquals(byteArrayOf(0x12, 0, 0, 0, 36, 0), scsi.commands.single().cdb)
    }

    /** JieLi flash addresses are big-endian inside an otherwise little-endian BOT transport. */
    @Test
    fun flashReadUsesBigEndianAddressLengthAndFfPadding() {
        val scsi = RecordingScsi()
        Fm1Protocol(scsi).readFlash(0x12345678, 256)
        val command = scsi.commands.single()
        assertArrayEquals(
            byteArrayOf(0xfd.toByte(), 5, 0x12, 0x34, 0x56, 0x78, 1, 0) +
                ByteArray(8) { -1 }, command.cdb
        )
        assertEquals(256, command.inputLength)
        assertTrue(command.output.isEmpty())
    }

    /** Rejects invalid block sizes and unsigned-address overflow before any command is sent. */
    @Test
    fun rejectsUnsafeReadRanges() {
        val protocol = Fm1Protocol(RecordingScsi())
        for (length in listOf(0, 257, -1)) {
            assertThrows(IllegalArgumentException::class.java) { protocol.readFlash(0, length) }
        }
        assertThrows(IllegalArgumentException::class.java) { protocol.readFlash(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { protocol.readFlash(0xffffffffL, 2) }
        assertThrows(IllegalArgumentException::class.java) { protocol.readFlash(Long.MAX_VALUE, 1) }
    }

    /** The final unsigned 32-bit address is valid for exactly one byte, not a wrapped range. */
    @Test
    fun acceptsLastFlashByteWithoutWrappingAddress() {
        val scsi = RecordingScsi()
        Fm1Protocol(scsi).readFlash(0xffffffffL, 1)
        assertArrayEquals(
            byteArrayOf(0xfd.toByte(), 5, -1, -1, -1, -1, 0, 1) + ByteArray(8) { -1 },
            scsi.commands.single().cdb,
        )
    }

    /** Only known NOR target types and plausible density codes can produce a capacity suggestion. */
    @Test
    fun parsesOnlineFlashIdLittleEndian() {
        val scsi = RecordingScsi()
        scsi.reply = { byteArrayOf(0xfc.toByte(), 0x0a, 3, 0, 0x18, 0x40, 0xef.toByte(), 0) + ByteArray(8) }
        val info = Fm1Protocol(scsi).flashInfo()
        assertEquals(0xef4018L, info.id)
        assertTrue(info.isNor)
        assertEquals(16777216L, info.suggestedSize)
        assertNull(FlashInfo(5, 0xef4018).suggestedSize)
        assertNull(FlashInfo(3, 0).suggestedSize)
        assertFalse(FlashInfo(4, 0xef4018).isNor)
    }

    /** Correct transfer length alone is not evidence that the requested command succeeded. */
    @Test
    fun rejectsWrongJieLiResponseEcho() {
        val scsi = RecordingScsi()
        scsi.reply = { ByteArray(16) }
        assertThrows(ProtocolException::class.java) { Fm1Protocol(scsi).flashInfo() }
    }

    /** Locks down the fixed entry address, encoded payload, partial final block, and CRC byte order. */
    @Test
    fun wl82LoaderIsTransferredAlreadyEncodedWithCrcAnd512ByteBlocks() {
        val scsi = RecordingScsi()
        val protocol = Fm1Protocol(scsi)
        val bytes = ByteArray(513).also { loaderBytes -> loaderBytes[512] = 1 }
        protocol.uploadLoader(bytes) {}
        assertEquals(2, scsi.commands.size)
        val first = scsi.commands[0]
        assertArrayEquals(bytes.copyOfRange(0, 512), first.output)
        assertArrayEquals(
            byteArrayOf(0xfb.toByte(), 6, 1, 0xc0.toByte(), 0x20, 0, 2, 0, 0, 0, 0) +
                ByteArray(5) { -1 }, first.cdb
        )
        val second = scsi.commands[1]
        assertArrayEquals(byteArrayOf(1), second.output)
        assertArrayEquals(
            byteArrayOf(0xfb.toByte(), 6, 1, 0xc0.toByte(), 0x22, 0, 0, 1, 0, 0x21, 0x10) +
                ByteArray(5) { -1 }, second.cdb
        )
    }

    /** Standard check vector verifies the CRC used for WL82 loader uploads. */
    @Test
    fun crcMatchesDesktopCrc16Xmodem() {
        assertEquals(0x31c3, Fm1Protocol.crc16("123456789".toByteArray()))
    }

    /** The loader jump must never acquire a configurable chip address or alternate target argument. */
    @Test
    fun loaderJumpIsWl82OnlyAndTargetsNor() {
        val scsi = RecordingScsi()
        val protocol = Fm1Protocol(scsi)
        protocol.startLoader()
        assertArrayEquals(
            byteArrayOf(0xfb.toByte(), 8, 1, 0xc0.toByte(), 0x20, 0, 0, 1) +
                ByteArray(8) { -1 }, scsi.commands.single().cdb
        )
    }

    /** Cancellation is observed at a whole-block boundary, before the next RAM write. */
    @Test
    fun cancelledLoaderUploadDoesNotWriteAnotherBlock() {
        val scsi = RecordingScsi()
        assertThrows(CancellationException::class.java) {
            Fm1Protocol(scsi).uploadLoader(ByteArray(1024)) {
                if (scsi.commands.isNotEmpty()) throw CancellationException()
            }
        }
        assertEquals(1, scsi.commands.size)
    }

    /** Checks BOT wrapper fields and ownership cleanup on a complete read transaction. */
    @Test
    fun botReadWrapsCdbAndChecksStatus() {
        val bulk = FakeBulk()
        bulk.reads += byteArrayOf(1, 2, 3)
        bulk.reads += status()
        val bot = BotTransport(bulk)
        assertArrayEquals(byteArrayOf(1, 2, 3), bot.execute(byteArrayOf(0x12), 3))
        val commandWrapper = ByteBuffer.wrap(bulk.writes.single()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(31, commandWrapper.capacity())
        assertEquals(0x43425355, commandWrapper.int)
        assertEquals(1, commandWrapper.int)
        assertEquals(3, commandWrapper.int)
        assertEquals(0x80, commandWrapper.get().toInt() and 0xff)
        assertEquals(0, commandWrapper.get().toInt())
        assertEquals(1, commandWrapper.get().toInt())
        assertEquals(0x12, commandWrapper.get().toInt())
        assertFalse(bulk.reset)
        bot.close()
        assertTrue(bulk.closed)
    }

    /** A payload is a separate bulk write, and the following command receives a fresh transaction tag. */
    @Test
    fun botWritesPayloadAndIncrementsTag() {
        val bulk = FakeBulk()
        bulk.reads += status()
        bulk.reads += status(tag = 2)
        val bot = BotTransport(bulk)
        bot.execute(byteArrayOf(1), output = byteArrayOf(7, 8))
        bot.execute(byteArrayOf(2))
        assertEquals(3, bulk.writes.size)
        assertArrayEquals(byteArrayOf(7, 8), bulk.writes[1])
        val commandWrapper = ByteBuffer.wrap(bulk.writes[2]).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, commandWrapper.getInt(4))
        assertEquals(0, commandWrapper.get(12).toInt())
    }

    /** Every malformed or unsuccessful status requires an entirely new session, not a retry. */
    @Test
    fun botRejectsBadStatusAndPoisonsSession() {
        for (statusWrapper in listOf(
            status(tag = 2), status(signature = 0), status(residue = 1),
            status(result = 1), status(result = 2), ByteArray(12)
        )) {
            val bulk = FakeBulk()
            bulk.reads += statusWrapper
            val bot = BotTransport(bulk)
            assertThrows(ProtocolException::class.java) { bot.execute(byteArrayOf(1)) }
            assertTrue(bulk.reset)
            assertThrows(IllegalStateException::class.java) { bot.execute(byteArrayOf(1)) }
            assertEquals(1, bulk.writes.size)
        }
    }

    /** Short transfers cannot be combined with later packets because command state is ambiguous. */
    @Test
    fun botRejectsShortReadTimeoutAndShortWrite() {
        val short = FakeBulk()
        short.reads += byteArrayOf(1)
        assertThrows(ProtocolException::class.java) { BotTransport(short).execute(byteArrayOf(1), 2) }
        val timeout = FakeBulk()
        assertThrows(ProtocolException::class.java) { BotTransport(timeout).execute(byteArrayOf(1), 2) }
        val write = FakeBulk().also { bulk -> bulk.writeResult = 30 }
        assertThrows(ProtocolException::class.java) { BotTransport(write).execute(byteArrayOf(1)) }
    }

    /** Cleanup diagnostics remain attached while the original exact-transfer error stays primary. */
    @Test
    fun botPreservesCommandFailureWhenResetAlsoFails() {
        val resetFailure = IOException("Device disappeared during reset")
        val bulk = FakeBulk().also { connection -> connection.resetFailure = resetFailure }
        val transport = BotTransport(bulk)
        val commandFailure = assertThrows(ProtocolException::class.java) {
            transport.execute(byteArrayOf(1), 2)
        }
        assertTrue(commandFailure.message!!.contains("data read"))
        assertSame(resetFailure, commandFailure.suppressed.single())
        assertThrows(IllegalStateException::class.java) { transport.execute(byteArrayOf(1)) }
        assertEquals(1, bulk.writes.size)
    }

    /** BOT rejects invalid or bidirectional requests before writing any USB command wrapper. */
    @Test
    fun botRejectsInvalidRequestsBeforeSending() {
        val bulk = FakeBulk()
        val transport = BotTransport(bulk)
        assertThrows(IllegalArgumentException::class.java) { transport.execute(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { transport.execute(ByteArray(17)) }
        assertThrows(IllegalArgumentException::class.java) { transport.execute(byteArrayOf(1), -1) }
        assertThrows(IllegalArgumentException::class.java) { transport.execute(byteArrayOf(1), 16385) }
        assertThrows(IllegalArgumentException::class.java) {
            transport.execute(byteArrayOf(1), output = ByteArray(16385))
        }
        assertThrows(IllegalArgumentException::class.java) {
            transport.execute(byteArrayOf(1), 1, byteArrayOf(2))
        }
        assertTrue(bulk.writes.isEmpty())
        assertFalse(bulk.reset)
    }

    /** The partial final block is neither padded nor duplicated into the destination during verification. */
    @Test
    fun backupStreamsExactlyTheRequestedBytesAndVerifiesSecondRead() {
        val output = ByteArrayOutputStream()
        val addresses = mutableListOf<Long>()
        val data = ByteArray(513) { index -> index.toByte() }
        val phases = mutableListOf<String>()
        val hash = FlashBackup { address, length ->
            addresses += address
            data.copyOfRange(address.toInt(), address.toInt() + length)
        }.run(513, output, {}) { phase, _ -> phases += phase }
        assertEquals(listOf(0L, 256L, 512L, 0L, 256L, 512L), addresses)
        assertArrayEquals(data, output.toByteArray())
        assertEquals(
            MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }, hash
        )
        assertEquals(2, phases.distinct().size)
    }

    /** Device inconsistency and destination failures both prevent a success hash from being returned. */
    @Test
    fun backupRejectsChangingFlashShortReadAndOutputFailure() {
        var reads = 0
        assertThrows(ProtocolException::class.java) {
            FlashBackup { _, length -> ByteArray(length) { (reads++).toByte() } }
                .run(1, ByteArrayOutputStream(), {}) { _, _ -> }
        }
        assertThrows(ProtocolException::class.java) {
            FlashBackup { _, _ -> ByteArray(0) }
                .run(1, ByteArrayOutputStream(), {}) { _, _ -> }
        }
        assertThrows(IOException::class.java) {
            FlashBackup { _, length -> ByteArray(length) }.run(
                1,
                object : java.io.OutputStream() {
                    override fun write(value: Int) {
                        throw IOException("Disk full")
                    }
                }, {}) { _, _ -> }
        }
    }

    /** Cooperative cancellation must not cause even one extra device read. */
    @Test
    fun cancellationStopsBackupBeforeNextRead() {
        var reads = 0
        assertThrows(CancellationException::class.java) {
            FlashBackup { _, length -> reads++; ByteArray(length) }.run(
                513, ByteArrayOutputStream(),
                { if (reads == 1) throw CancellationException() }) { _, _ -> }
        }
        assertEquals(1, reads)
    }

    /** Invalid capacities must not create a partial device read or destination write. */
    @Test
    fun backupRejectsInvalidCapacityBeforeReading() {
        var readCount = 0
        val backup = FlashBackup { _, length ->
            readCount++
            ByteArray(length)
        }
        val output = ByteArrayOutputStream()
        for (size in listOf(0L, -1L, 0x100000001L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                backup.run(size, output, {}) { _, _ -> }
            }
        }
        assertEquals(0, readCount)
        assertEquals(0, output.size())
    }

    /** Flushing the saved pass must succeed before verification begins or any hash is returned. */
    @Test
    fun backupPropagatesFlushFailureBeforeVerification() {
        var readCount = 0
        val output = object : ByteArrayOutputStream() {
            override fun flush() {
                throw IOException("Document provider flush failed")
            }
        }
        assertThrows(IOException::class.java) {
            FlashBackup { _, length ->
                readCount++
                ByteArray(length)
            }.run(1, output, {}) { _, _ -> }
        }
        assertEquals(1, readCount)
    }

    /** Even a cancellation arriving after the last verification block must withhold success. */
    @Test
    fun cancellationAfterVerificationDoesNotReturnHash() {
        var readCount = 0
        assertThrows(CancellationException::class.java) {
            FlashBackup { _, length ->
                readCount++
                ByteArray(length)
            }.run(1, ByteArrayOutputStream(), {
                if (readCount == 2) throw CancellationException()
            }) { _, _ -> }
        }
        assertEquals(2, readCount)
    }
}
