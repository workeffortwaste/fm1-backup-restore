package com.workeffortwaste

import com.workeffortwaste.protocol.FlashRestore
import com.workeffortwaste.protocol.RestoreImage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

data class BackupEntry(val id: String, val createdAt: Long, val sha256: String, val firmwareIdentity: String? = null)

/** Worker-only private library. Only closed, device-verified images are atomically published. */
class BackupStore(private val directory: File) {
    fun createPending(): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create backup library")
        return File.createTempFile("fm1-${System.currentTimeMillis()}-", ".partial", directory)
    }

    fun publish(
        pending: File,
        deviceHash: String,
        firmwareIdentity: String? = null,
        checkCancelled: () -> Unit = {},
    ): BackupEntry = publish(pending, deviceHash, firmwareIdentity, checkCancelled) { _, _ -> }

    fun publish(
        pending: File,
        deviceHash: String,
        firmwareIdentity: String?,
        checkCancelled: () -> Unit,
        progress: (bytes: Long, total: Long) -> Unit,
    ): BackupEntry {
        require(PENDING.matches(pending.name) && owned(pending.name) == pending.canonicalFile)
        require(HASH.matches(deviceHash)) { "Invalid FM-1 hash" }
        if (!verified(pending, deviceHash, checkCancelled, progress)) {
            throw IOException("Saved backup verification failed")
        }
        require(
            firmwareIdentity == null ||
                    (firmwareIdentity.length in 1..MAX_NAME_LENGTH && firmwareIdentity.all { it.code in 32..126 })
        ) {
            "Invalid firmware identity"
        }
        val firmwareSuffix =
            firmwareIdentity?.let { "-fw-${RestoreImage.hex(it.toByteArray(Charsets.US_ASCII))}" } ?: ""
        val id = "${pending.name.removeSuffix(".partial")}-$deviceHash$firmwareSuffix.bin"
        val destination = owned(id)
        checkCancelled()
        if (destination.exists() || !pending.renameTo(destination)) throw IOException("Cannot publish backup")
        return entry(id)
    }

    fun discard(pending: File): Boolean {
        require(PENDING.matches(pending.name) && owned(pending.name) == pending.canonicalFile)
        return !pending.exists() || pending.delete()
    }

    fun list(): List<BackupEntry> {
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles() ?: throw IOException("Cannot read backup library")
        return files.mapNotNull { file ->
            if (!SAVED.matches(file.name)) return@mapNotNull null
            val entry = entry(file.name)
            val valid = try {
                verified(owned(entry.id), entry.sha256)
            } catch (_: IOException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
            if (valid) entry else null
        }.sortedWith(compareByDescending<BackupEntry> { it.createdAt }.thenByDescending { it.id })
    }

    fun open(id: String): InputStream {
        val entry = entry(id)
        val file = owned(id)
        if (!verified(file, entry.sha256)) throw IOException("Saved backup is missing or damaged")
        return file.inputStream()
    }

    fun delete(id: String) {
        entry(id)
        val file = owned(id)
        if (!file.isFile || !file.delete()) throw IOException("Could not delete backup")
    }

    /**
     * Changes only the displayed firmware ID by atomically renaming the verified file; image bytes
     * and SHA-256 are untouched. A blank name is rejected. Never overwrites another file.
     */
    fun rename(id: String, newIdentity: String): BackupEntry {
        val current = entry(id)
        val name = newIdentity.trim()
        require(name.length in 1..MAX_NAME_LENGTH && name.all { it.code in 32..126 }) {
            "Name must be 1–$MAX_NAME_LENGTH printable ASCII characters"
        }
        if (name == current.firmwareIdentity) return current
        val source = owned(id)
        if (!verified(source, current.sha256)) throw IOException("Saved backup is missing or damaged")
        val suffix = "-fw-${RestoreImage.hex(name.toByteArray(Charsets.US_ASCII))}"
        val newId = "fm1-${current.createdAt}-${id.split('-')[2]}-${current.sha256}$suffix.bin"
        val destination = owned(newId)
        if (destination.exists()) throw IOException("Cannot rename backup: name already in use")
        // Files.move reports the real cause (for example an over-long filename), unlike File.renameTo.
        java.nio.file.Files.move(source.toPath(), destination.toPath())
        return entry(newId)
    }

    private fun entry(id: String): BackupEntry {
        val match = SAVED.matchEntire(id) ?: throw IllegalArgumentException("Invalid backup name")
        val encodedIdentity = match.groupValues[3]
        val firmwareIdentity = if (encodedIdentity.isEmpty()) null else {
            require(encodedIdentity.length % 2 == 0) { "Invalid firmware identity encoding" }
            val bytes = encodedIdentity.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            require(bytes.all { it.toInt() in 32..126 }) { "Invalid firmware identity" }
            String(bytes, Charsets.US_ASCII)
        }
        return BackupEntry(id, match.groupValues[1].toLong(), match.groupValues[2], firmwareIdentity)
    }

    private fun owned(name: String): File {
        require(name == File(name).name && '/' !in name && '\\' !in name) { "Invalid backup name" }
        val file = File(directory, name)
        require(file.canonicalFile == File(directory.canonicalFile, name)) { "Backup is outside library or is a link" }
        return file.canonicalFile
    }

    private fun verified(
        file: File,
        expected: String,
        checkCancelled: () -> Unit = {},
        progress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean {
        if (!file.isFile || file.length() != FlashRestore.FLASH_SIZE.toLong()) return false
        val expectedTotal = file.length()
        val digest = MessageDigest.getInstance("SHA-256")
        var processed = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                processed += count
                if (processed > FlashRestore.FLASH_SIZE) return false
                digest.update(buffer, 0, count)
                progress(processed, expectedTotal)
            }
        }
        return processed == FlashRestore.FLASH_SIZE.toLong() && RestoreImage.hex(digest.digest()) == expected
    }

    companion object {
        /** Hex-encoded in the filename; 72 characters fits the 255-byte limit beside a 19-digit counter. */
        const val MAX_NAME_LENGTH = 72
        fun exportFileName(entry: BackupEntry): String {
            val plainName = Normalizer.normalize(
                entry.firmwareIdentity ?: "Firmware unknown",
                Normalizer.Form.NFD,
            ).replace(Regex("\\p{M}+"), "")
            val slug = plainName.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifEmpty { "firmware-unknown" }
            return "$slug-${entry.sha256.lowercase(Locale.ROOT)}.bin"
        }

        private val HASH = Regex("[0-9a-f]{64}")
        private val PENDING = Regex("fm1-[0-9]{13}-[0-9]+\\.partial")
        private val SAVED = Regex(
            "fm1-([0-9]{13})-[0-9]+-([0-9a-f]{64})(?:-fw-((?:[0-9a-f]{2}){1,$MAX_NAME_LENGTH}))?\\.bin"
        )
    }
}
