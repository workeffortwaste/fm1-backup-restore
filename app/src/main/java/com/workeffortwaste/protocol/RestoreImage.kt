package com.workeffortwaste.protocol

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Bounded document staging, separate from USB so every file check precedes the first erase. */
object RestoreImage {
    fun requireBinaryName(name: String) {
        if (!name.endsWith(".bin", ignoreCase = true)) {
            throw ProtocolException("Choose a raw .bin backup file")
        }
    }

    /**
     * Copies at most 1 MiB plus one sentinel byte into caller-owned private storage.
     * Document-provider size metadata is not trusted. Short, oversized and unreadable inputs fail.
     */
    fun stage(input: InputStream, output: OutputStream, checkCancelled: () -> Unit): String =
        stage(input, output, checkCancelled) { _, _ -> }

    fun stage(
        input: InputStream,
        output: OutputStream,
        checkCancelled: () -> Unit,
        progress: (bytes: Long, total: Long) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var total = 0
        while (total <= FlashRestore.FLASH_SIZE) {
            checkCancelled()
            val count = input.read(buffer, 0, minOf(buffer.size, FlashRestore.FLASH_SIZE + 1 - total))
            if (count < 0) break
            if (count == 0) throw ProtocolException("Restore source made no read progress")
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            total += count
            progress(total.toLong(), FlashRestore.FLASH_SIZE.toLong())
        }
        if (total != FlashRestore.FLASH_SIZE) {
            throw ProtocolException("Restore requires a raw binary of exactly 1048576 bytes (1 MiB)")
        }
        output.flush()
        checkCancelled()
        return hex(digest.digest())
    }

    /**
     * Reads the staged file once into a bounded snapshot and proves it still matches the staged hash.
     * All subsequent device comparisons and writes use this snapshot, not the URI or file.
     */
    fun snapshot(input: InputStream, stagedHash: String, checkCancelled: () -> Unit): ByteArray =
        snapshot(input, stagedHash, checkCancelled) { _, _ -> }

    fun snapshot(
        input: InputStream,
        stagedHash: String,
        checkCancelled: () -> Unit,
        progress: (bytes: Long, total: Long) -> Unit,
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream(FlashRestore.FLASH_SIZE)
        val hash = stage(input, output, checkCancelled, progress)
        if (hash != stagedHash) throw ProtocolException("Staged restore image changed before restore")
        return output.toByteArray()
    }

    /** Shared lowercase formatting for source and writable-range digests. */
    internal fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
