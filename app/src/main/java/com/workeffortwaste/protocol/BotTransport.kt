package com.workeffortwaste.protocol

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Single-transfer USB operations. Counts must include every byte requested by the caller. */
interface BulkIo : Closeable {
    /** Sends one command wrapper or data payload; a short count is a failure, not a retry. */
    fun write(data: ByteArray): Int
    /** Receives one data payload or status wrapper into [data]. */
    fun read(data: ByteArray): Int
    /** Attempts BOT reset recovery after failure, without replaying the failed command. */
    fun reset()
}

/** Synchronous SCSI command boundary shared by the Android transport and protocol test doubles. */
interface ScsiTransport {
    /**
     * Executes a command descriptor block (CDB) with at most one data direction.
     * Returns exactly [inputLength] bytes or throws; [output] is empty for input commands.
     */
    fun execute(cdb: ByteArray, inputLength: Int = 0, output: ByteArray = byteArrayOf()): ByteArray
}

/** Invalid device response or failed I/O; never safe to treat as a successful partial backup. */
class ProtocolException(message: String) : IOException(message)

/**
 * USB Mass Storage Bulk-Only Transport (BOT), restricted to one LUN and exact transfers.
 * A failed transaction permanently invalidates this session because its device-side state is unknown.
 */
class BotTransport(private val io: BulkIo) : ScsiTransport, Closeable {
    /** Correlates each command wrapper with its status wrapper. */
    private var nextTag = 1
    /** Prevents continuation after either a transfer failure or an explicit close. */
    private var failed = false

    @Synchronized
    override fun execute(cdb: ByteArray, inputLength: Int, output: ByteArray): ByteArray {
        require(cdb.size in 1..16)
        require(inputLength in 0..16384 && output.size <= 16384)
        require(inputLength == 0 || output.isEmpty())
        check(!failed) { "USB session failed; reconnect before issuing another command" }
        val tag = nextTag
        nextTag = if (nextTag == -1) 1 else nextTag + 1
        val transferLength = if (output.isNotEmpty()) output.size else inputLength
        // BOT wrappers are little-endian, unlike JieLi command addresses inside the CDB.
        val commandWrapper = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x43425355).putInt(tag).putInt(transferLength)
            .put(if (inputLength > 0) 0x80.toByte() else 0)
            .put(0).put(cdb.size.toByte()).put(cdb).array()
        try {
            requireExactTransfer(io.write(commandWrapper), commandWrapper.size, "command write")
            val input = ByteArray(inputLength)
            if (output.isNotEmpty()) requireExactTransfer(io.write(output), output.size, "data write")
            if (input.isNotEmpty()) requireExactTransfer(io.read(input), input.size, "data read")
            val statusWrapper = ByteArray(13)
            requireExactTransfer(io.read(statusWrapper), statusWrapper.size, "status read")
            val status = ByteBuffer.wrap(statusWrapper).order(ByteOrder.LITTLE_ENDIAN)
            if (status.int != 0x53425355 || status.int != tag) {
                throw ProtocolException("Invalid BOT status signature or transaction tag")
            }
            val residue = status.int.toLong() and 0xffffffffL
            val result = status.get().toInt() and 0xff
            if (residue != 0L || result != 0) {
                throw ProtocolException("BOT command failed: status=$result, missing=$residue bytes")
            }
            return input
        } catch (failure: Exception) {
            failed = true
            // Never retry an ambiguous command or resume a partially written backup.
            // Recovery is cleanup only: preserve its diagnostic without hiding the original failure.
            try {
                io.reset()
            } catch (resetFailure: Exception) {
                failure.addSuppressed(resetFailure)
            }
            throw failure
        }
    }

    /** Rejects short packets, Android's negative timeout result, and oversized test-double replies. */
    private fun requireExactTransfer(actual: Int, expected: Int, phase: String) {
        if (actual != expected) {
            throw ProtocolException("USB $phase: $actual/$expected bytes (timeout, stall or disconnect)")
        }
    }

    @Synchronized
    override fun close() {
        failed = true
        io.close()
    }
}
