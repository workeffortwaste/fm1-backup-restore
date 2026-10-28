package com.workeffortwaste.protocol

import java.io.OutputStream
import java.security.MessageDigest

/**
 * Streams a full flash range without retaining it in memory, then independently rereads the device.
 * @param read Exact block reader; failures must propagate rather than return padded or cached data.
 */
class FlashBackup(private val read: (Long, Int) -> ByteArray) {
    /**
     * Writes exactly [size] bytes and returns their SHA-256 only if both device reads agree.
     * The caller owns [output] and must discard the destination on any failure, including close failure.
     * [checkCancelled] runs between blocks; [progress] reports bytes within the current pass.
     * Verification checks the device, not the saved document provider's contents.
     */
    fun run(
        size: Long,
        output: OutputStream,
        checkCancelled: () -> Unit,
        progress: (phase: String, bytes: Long) -> Unit,
    ): String {
        require(size in 1..0x100000000L)
        val backupHash = readAndHash(size, output, checkCancelled) { bytes ->
            progress("Reading FM-1 flash", bytes)
        }
        val verificationHash = readAndHash(size, output = null, checkCancelled) { bytes ->
            progress("Verifying: reading FM-1 flash again", bytes)
        }
        checkCancelled()
        if (!MessageDigest.isEqual(backupHash, verificationHash)) {
                throw ProtocolException("Verification failed: two complete FM-1 reads differ")
        }
        return backupHash.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    /** Reads one complete pass; null [output] explicitly selects verification without saving bytes again. */
    private fun readAndHash(
        size: Long,
        output: OutputStream?,
        checkCancelled: () -> Unit,
        progress: (Long) -> Unit,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        var address = 0L
        while (address < size) {
            checkCancelled()
            val length = minOf(256L, size - address).toInt()
            val block = read(address, length)
            if (block.size != length) throw ProtocolException("Incomplete flash read at $address")
            output?.write(block)
            digest.update(block)
            address += block.size
            progress(address)
        }
        output?.flush()
        return digest.digest()
    }
}
