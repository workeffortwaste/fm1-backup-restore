package com.workeffortwaste

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.workeffortwaste.protocol.BootSpiConfig
import com.workeffortwaste.protocol.FlashAppIdentity
import com.workeffortwaste.protocol.FlashRestore
import com.workeffortwaste.protocol.Fm1Protocol
import com.workeffortwaste.protocol.RestoreImage
import com.workeffortwaste.protocol.SMK_TRIM_MARKER_ADDRESS
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private data class BackupArchiveMetadata(
    val identifier: String,
    val schema: Int,
    val sha256: String,
    val createdAt: Long,
    val firmwareIdentity: String?,
    val metadata: BackupMetadata?,
)

data class BackupMetadata(
    val classification: DeviceClassification = DeviceClassification(),
    val editorFirmwareLabel: String? = null,
    val manualFamilySelection: DeviceFamily? = null,
    val userFamily: DeviceFamily? = null,
    val userFirmwareLabel: String? = null,
)

data class BackupEntry(
    val id: String,
    val createdAt: Long,
    val sha256: String,
    val firmwareIdentity: String? = null,
    val metadata: BackupMetadata? = null,
) {
    val taggedFamily: DeviceFamily
        get() = metadata?.userFamily ?: metadata?.manualFamilySelection
            ?: metadata?.classification?.family ?: DeviceFamily.UNKNOWN
    val familyIsUserTagged: Boolean get() = metadata?.userFamily != null
    val familyIsManuallySelected: Boolean get() = metadata?.manualFamilySelection != null
}

internal val BackupEntry.needsLegacyFamilyUpgrade: Boolean
    get() = taggedFamily == DeviceFamily.UNKNOWN && metadata?.userFamily == null &&
            metadata?.manualFamilySelection == null &&
            metadata?.classification?.origin != FamilyEvidenceOrigin.CONFLICT

/** Shows an explicit custom tag, otherwise the captured M-UPGRADE identity. */
fun BackupEntry.identityLine(): String {
    val firmware = metadata?.userFirmwareLabel?.takeIf { it.isNotBlank() }
        ?: metadata?.classification?.rawMidiIdentity?.takeIf { it.isNotBlank() }
    return firmware.orEmpty()
}

/** Worker-only private library. Only complete, hash-verified images are atomically published. */
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
    ): BackupEntry = publishInternal(pending, deviceHash, firmwareIdentity, checkCancelled, { _, _ -> }, null)

    fun publishWithMetadata(
        pending: File,
        deviceHash: String,
        firmwareIdentity: String?,
        metadata: BackupMetadata,
        checkCancelled: () -> Unit = {},
        progress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): BackupEntry = publishInternal(pending, deviceHash, firmwareIdentity, checkCancelled, progress, metadata)

    private fun publishInternal(
        pending: File,
        deviceHash: String,
        firmwareIdentity: String?,
        checkCancelled: () -> Unit,
        progress: (bytes: Long, total: Long) -> Unit,
        metadata: BackupMetadata?,
        createdAt: Long? = null,
    ): BackupEntry {
        require(PENDING.matches(pending.name) && owned(pending.name) == pending.canonicalFile)
        require(HASH.matches(deviceHash)) { "Invalid backup hash" }
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
        val base = if (createdAt == null) pending.name.removeSuffix(".partial")
            else "fm1-$createdAt-${pending.name.split('-')[2].removeSuffix(".partial")}"
        val id = "$base-$deviceHash$firmwareSuffix.bin"
        val destination = owned(id)
        if (destination.exists()) throw IOException("Cannot publish backup")
        if (metadata != null) writeMetadata(id, metadata)
        try {
            checkCancelled()
            if (!pending.renameTo(destination)) throw IOException("Cannot publish backup")
        } catch (failure: Exception) {
            if (metadata != null) metadataFile(id).delete()
            throw failure
        }
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

    fun open(id: String, checkCancelled: () -> Unit = {}): InputStream {
        val entry = entry(id)
        val file = owned(id)
        if (!verified(file, entry.sha256, checkCancelled)) throw IOException("Saved backup is missing or damaged")
        return file.inputStream()
    }

    fun exportZip(
        entries: List<BackupEntry>,
        destination: OutputStream,
        checkCancelled: () -> Unit = {},
        progress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        ZipOutputStream(destination.buffered()).use { zip ->
            val unique = uniqueExports(entries)
            require(unique.isNotEmpty()) { "No backups to export" }
            val total = unique.size.toLong() * FlashRestore.FLASH_SIZE
            var processed = 0L
            for (entry in unique) {
                checkCancelled()
                val archive = ByteArrayOutputStream()
                exportBackupZip(entry, archive, checkCancelled) { bytes, _ ->
                    progress(processed + bytes, total)
                }
                zip.putNextEntry(ZipEntry(exportArchiveName(entry)))
                zip.write(archive.toByteArray())
                zip.closeEntry()
                processed += FlashRestore.FLASH_SIZE
            }
            checkCancelled()
        }
    }

    fun exportBackupZip(
        entry: BackupEntry,
        destination: OutputStream,
        checkCancelled: () -> Unit = {},
        progress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val saved = entry(entry.id)
        ZipOutputStream(destination.buffered()).use { zip ->
            val name = exportFileName(saved)
            zip.putNextEntry(ZipEntry(name))
            var processed = 0L
            open(saved.id, checkCancelled).use { source ->
                val buffer = ByteArray(65536)
                while (true) {
                    checkCancelled()
                    val count = source.read(buffer)
                    if (count < 0) break
                    zip.write(buffer, 0, count)
                    processed += count
                    progress(processed, FlashRestore.FLASH_SIZE.toLong())
                }
            }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(name.removeSuffix(".bin") + ".json"))
            val metadata = archiveMetadata(saved)
            zip.write(Gson().toJson(metadata).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            checkCancelled()
        }
    }

    /** Imports only identified JSON/BIN pairs; a failed batch rolls back its new library entries. */
    fun importZip(
        source: InputStream,
        checkCancelled: () -> Unit = {},
        progress: (verifiedBackups: Int) -> Unit = {},
    ): List<BackupEntry> {
        val pending = mutableListOf<Pair<File, BackupArchiveMetadata>>()
        val staged = mutableListOf<File>()
        val published = mutableListOf<BackupEntry>()
        var expanded = 0L
        var entryCount = 0
        fun readArchive(input: InputStream, depth: Int) {
            val files = linkedMapOf<String, File>()
            val names = mutableSetOf<String>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    checkCancelled()
                    val item = zip.nextEntry ?: break
                    require(++entryCount <= MAX_ARCHIVE_ENTRIES) { "Too many archive entries" }
                    val name = item.name
                    require(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name &&
                        name.split('/').none { it == ".." || it == "." }) { "Unsafe archive path" }
                    if (item.isDirectory) {
                        require(zip.read() == -1) { "Archive directory contains data" }
                        continue
                    }
                    require(names.add(name)) { "Duplicate archive entry" }
                    val file = createPending()
                    staged += file
                    val buffer = ByteArray(65536)
                    var size = 0L
                    file.outputStream().buffered().use { output ->
                        while (true) {
                            checkCancelled()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            expanded += count
                            size += count
                            require(size <= MAX_ARCHIVE_ENTRY_BYTES &&
                                expanded <= MAX_ARCHIVE_EXPANDED_BYTES) { "Archive is too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                    if (name.endsWith(".zip", true)) {
                        require(depth == 0) { "Archive nesting is too deep" }
                        readArchive(file.inputStream(), depth + 1)
                    } else {
                        if (name.endsWith(".json", true)) {
                            require(size <= MAX_ARCHIVE_JSON_BYTES) { "Backup JSON is too large" }
                        }
                        files[name] = file
                    }
                }
            }
            for ((name, file) in files) {
                checkCancelled()
                if (!name.endsWith(".json", true)) continue
                val metadata = readArchiveMetadata(file) ?: continue
                val image = files[name.dropLast(5) + ".bin"]
                    ?: throw IOException("Backup JSON has no matching BIN")
                require(image.length() == FlashRestore.FLASH_SIZE.toLong()) { "Invalid backup size" }
                require(verified(image, metadata.sha256, checkCancelled)) { "Backup SHA-256 does not match metadata" }
                pending += image to metadata
                progress(pending.size)
            }
        }
        try {
            readArchive(source, 0)
            require(pending.isNotEmpty()) { "No fm1-backup-restore JSON/BIN pairs found" }
            val known = list().map(::archiveMetadata).toMutableSet()
            for ((file, metadata) in pending) {
                checkCancelled()
                if (!known.add(metadata)) continue
                val entry = publishInternal(
                    pending = file,
                    deviceHash = metadata.sha256,
                    firmwareIdentity = metadata.firmwareIdentity,
                    checkCancelled = checkCancelled,
                    progress = { _, _ -> },
                    metadata = metadata.metadata,
                    createdAt = metadata.createdAt,
                )
                published += entry
            }
            checkCancelled()
            return published
        } catch (failure: Exception) {
            published.forEach { runCatching { delete(it.id) } }
            throw failure
        } finally {
            staged.forEach { discard(it) }
        }
    }

    fun classifyImportedBackup(entry: BackupEntry): BackupEntry {
        val metadata = entry.metadata ?: BackupMetadata()
        if (entry.taggedFamily != DeviceFamily.UNKNOWN ||
            metadata.userFamily != null || metadata.manualFamilySelection != null ||
            metadata.classification.origin == FamilyEvidenceOrigin.CONFLICT
        ) return entry
        val updated = metadata.copy(classification = readImageClassification(owned(entry.id), metadata))
        writeMetadata(entry.id, updated)
        return entry.copy(metadata = updated)
    }

    fun upgradeLegacyFamilies(
        entries: List<BackupEntry>,
        progress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): List<BackupEntry> = entries.mapIndexed { index, entry ->
        val upgraded = classifyImportedBackup(entry)
        progress(index + 1, entries.size)
        upgraded
    }

    private fun archiveMetadata(entry: BackupEntry): BackupArchiveMetadata = BackupArchiveMetadata(
        identifier = ARCHIVE_IDENTIFIER,
        schema = ARCHIVE_SCHEMA,
        sha256 = entry.sha256,
        createdAt = entry.createdAt,
        firmwareIdentity = entry.firmwareIdentity,
        metadata = entry.metadata,
    )

    /** Decodes fields explicitly so unknown enum values cannot silently become missing tags. */
    private fun readArchiveMetadata(file: File): BackupArchiveMetadata? {
        val parsed = try {
            JsonParser.parseString(file.readText(Charsets.UTF_8))
        } catch (_: JsonParseException) {
            return null
        }
        if (!parsed.isJsonObject) return null
        val json = parsed.asJsonObject
        val identifier = json.get("identifier") ?: return null
        if (!identifier.isJsonPrimitive || !identifier.asJsonPrimitive.isString ||
            identifier.asString != ARCHIVE_IDENTIFIER
        ) return null

        require(json.longValue("schema") == ARCHIVE_SCHEMA.toLong()) { "Unsupported backup metadata schema" }
        val hash = requireNotNull(json.stringValue("sha256")) { "Backup SHA-256 is missing" }
        require(HASH.matches(hash)) { "Invalid backup SHA-256" }
        val createdAt = requireNotNull(json.longValue("createdAt")) { "Backup date is missing" }
        require(createdAt in 1000000000000L..9999999999999L) { "Invalid backup date" }
        val metadata = json.objectValue("metadata")?.let { values ->
            val evidence = requireNotNull(values.objectValue("classification")) { "Backup classification is missing" }
            val rawMidiIdentity = evidence.stringValue("rawMidiIdentity")
            val spiSetting = evidence.stringValue("spiSetting")
            val resetSetting = evidence.stringValue("resetSetting")
            val jedecId = evidence.longValue("jedecId")
            val smkTrimMarkerPresent =
                requireNotNull(evidence.booleanValue("smkTrimMarkerPresent")) {
                    "Backup trim evidence is missing"
                }
            val classification = DeviceClassification(
                family = requireNotNull(
                    evidence.enumValue<DeviceFamily>("family"),
                ) { "Backup family is missing" },
                origin = requireNotNull(evidence.enumValue<FamilyEvidenceOrigin>("origin")) { "Backup evidence origin is missing" },
                confidence = requireNotNull(evidence.enumValue<FamilyEvidenceConfidence>("confidence")) { "Backup confidence is missing" },
                rawMidiIdentity = rawMidiIdentity,
                spiSetting = spiSetting,
                resetSetting = resetSetting,
                jedecId = jedecId,
                smkTrimMarkerPresent = smkTrimMarkerPresent,
                suggestedFamily = evidence.enumValue<DeviceFamily>("suggestedFamily"),
                explanation = requireNotNull(evidence.stringValue("explanation")) { "Backup explanation is missing" },
            )
            BackupMetadata(
                classification = classification,
                editorFirmwareLabel = values.stringValue("editorFirmwareLabel"),
                manualFamilySelection = values.enumValue<DeviceFamily>("manualFamilySelection"),
                userFamily = values.enumValue<DeviceFamily>("userFamily"),
                userFirmwareLabel = values.stringValue("userFirmwareLabel"),
            )
        }
        return BackupArchiveMetadata(
            identifier = ARCHIVE_IDENTIFIER,
            schema = ARCHIVE_SCHEMA,
            sha256 = hash,
            createdAt = createdAt,
            firmwareIdentity = json.stringValue("firmwareIdentity"),
            metadata = metadata,
        )
    }

    private fun JsonObject.stringValue(name: String): String? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid backup metadata: $name must be a string" }
        return value.asString
    }

    private fun JsonObject.longValue(name: String): Long? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Invalid backup metadata: $name must be an integer" }
        return requireNotNull(value.asString.toLongOrNull()) { "Invalid backup metadata: $name must be an integer" }
    }

    private fun JsonObject.booleanValue(name: String): Boolean? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "Invalid backup metadata: $name must be a boolean" }
        return value.asBoolean
    }

    private fun JsonObject.objectValue(name: String): JsonObject? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        require(value.isJsonObject) { "Invalid backup metadata: $name must be an object" }
        return value.asJsonObject
    }

    private inline fun <reified T : Enum<T>> JsonObject.enumValue(name: String): T? {
        val value = stringValue(name) ?: return null
        return requireNotNull(enumValues<T>().firstOrNull { it.name == value }) {
            "Unsupported backup metadata: $name=$value"
        }
    }

    fun delete(id: String) {
        entry(id)
        val file = owned(id)
        if (!file.isFile || !file.delete()) throw IOException("Could not delete backup")
        val metadata = metadataFile(id)
        if (metadata.exists() && !metadata.delete()) throw IOException("Could not delete backup metadata")
    }

    /** Saves user tags independently of the image, checksum, captured identity, and filename. */
    fun updateMetadata(id: String, metadata: BackupMetadata): BackupEntry {
        val current = entry(id)
        val file = owned(id)
        if (!verified(file, current.sha256)) throw IOException("Saved backup is missing or damaged")
        writeMetadata(id, metadata)
        return current.copy(metadata = metadata)
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
        val encodedIdentity = match.groupValues[4]
        val firmwareIdentity = if (encodedIdentity.isEmpty()) null else {
            require(encodedIdentity.length % 2 == 0) { "Invalid firmware identity encoding" }
            val bytes = encodedIdentity.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            require(bytes.all { it.toInt() in 32..126 }) { "Invalid firmware identity" }
            String(bytes, Charsets.US_ASCII)
        }
        return BackupEntry(
            id,
            match.groupValues[1].toLong(),
            match.groupValues[3],
            firmwareIdentity,
            readMetadata(id),
        )
    }

    /** Excludes the mutable firmware-name suffix so metadata remains attached after rename. */
    private fun stableKey(id: String): String {
        val match = SAVED.matchEntire(id) ?: throw IllegalArgumentException("Invalid backup name")
        return "fm1-${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}"
    }

    private fun metadataFile(id: String): File = owned("${stableKey(id)}.meta")

    private fun readImageClassification(file: File, metadata: BackupMetadata): DeviceClassification {
        val image = file.readBytes()
        val bootConfig = BootSpiConfig.extract(image)
        val markerStart = SMK_TRIM_MARKER_ADDRESS.toInt()
        val trimMarkerPresent = image.size >= markerStart + 8 &&
                image.copyOfRange(markerStart, markerStart + 8)
                    .contentEquals(byteArrayOf(0x74, 0x72, 0x69, 0x6d, 0x64, 0x61, 0x74, 0))
        val midiIdentity = metadata.classification.rawMidiIdentity
            ?: FlashAppIdentity.extract(image) { DeviceFamily.fromMidiIdentity(it) != DeviceFamily.UNKNOWN }
        return classifyImportedImage(
            midiIdentity,
            bootConfig?.spiSetting,
            trimMarkerPresent,
            bootConfig?.resetSetting,
        ).copy(jedecId = metadata.classification.jedecId)
    }

    /** Missing, malformed, or newer sidecars remain unknown rather than becoming inferred evidence. */
    private fun readMetadata(id: String): BackupMetadata? {
        val file = metadataFile(id)
        if (!file.isFile) return null
        return try {
            val values = Properties().apply { file.inputStream().use(::load) }
            if (values.getProperty("schema") != "1") return null
            val midiIdentity = values.getProperty("midiIdentity")
            val spiSetting = values.getProperty("spiSetting")
            val resetSetting = values.getProperty("resetSetting")
            val jedecId = values.getProperty("jedec")?.toLongOrNull(16)
            val trimMarker = values.getProperty("trimMarker") == "true"
            val classification = DeviceClassification(
                family = enumValue(values.getProperty("family")) ?: DeviceFamily.UNKNOWN,
                origin = enumValue(values.getProperty("origin")) ?: FamilyEvidenceOrigin.UNKNOWN,
                confidence = enumValue(values.getProperty("confidence")) ?: FamilyEvidenceConfidence.NONE,
                rawMidiIdentity = midiIdentity,
                spiSetting = spiSetting,
                resetSetting = resetSetting,
                jedecId = jedecId,
                smkTrimMarkerPresent = trimMarker,
                suggestedFamily = enumValue(values.getProperty("suggestedFamily")),
                explanation = values.getProperty("explanation") ?: "Device family is unknown",
            )
            BackupMetadata(
                classification = classification,
                editorFirmwareLabel = values.getProperty("editorFirmwareLabel"),
                manualFamilySelection = enumValue(values.getProperty("manualFamilySelection")),
                userFamily = enumValue(values.getProperty("userFamily")),
                userFirmwareLabel = values.getProperty("userFirmwareLabel"),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMetadata(id: String, metadata: BackupMetadata) {
        require(validMetadataText(metadata.classification.rawMidiIdentity, 256))
        require(validMetadataText(metadata.classification.spiSetting, 64))
        require(validMetadataText(metadata.classification.resetSetting, 64))
        require(validMetadataText(metadata.classification.explanation, 512))
        require(validMetadataText(metadata.editorFirmwareLabel, 256))
        require(validMetadataText(metadata.userFirmwareLabel, MAX_NAME_LENGTH))
        val values = Properties().apply {
            setProperty("schema", "1")
            setProperty("family", metadata.classification.family.name)
            setProperty("origin", metadata.classification.origin.name)
            setProperty("confidence", metadata.classification.confidence.name)
            setNullable("midiIdentity", metadata.classification.rawMidiIdentity)
            setNullable("spiSetting", metadata.classification.spiSetting)
            setNullable("resetSetting", metadata.classification.resetSetting)
            metadata.classification.jedecId?.let { setProperty("jedec", "%06X".format(it)) }
            setProperty("trimMarker", metadata.classification.smkTrimMarkerPresent.toString())
            metadata.classification.suggestedFamily?.let { setProperty("suggestedFamily", it.name) }
            setProperty("explanation", metadata.classification.explanation)
            setNullable("editorFirmwareLabel", metadata.editorFirmwareLabel)
            metadata.manualFamilySelection?.let { setProperty("manualFamilySelection", it.name) }
            metadata.userFamily?.let { setProperty("userFamily", it.name) }
            setNullable("userFirmwareLabel", metadata.userFirmwareLabel)
        }
        val destination = metadataFile(id)
        val pending = File(directory, ".${destination.name}-${System.nanoTime()}.partial")
        try {
            FileOutputStream(pending).use { values.store(it, null) }
            try {
                java.nio.file.Files.move(
                    pending.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            pending.delete()
        }
    }

    private fun validMetadataText(value: String?, maxLength: Int): Boolean =
        value == null || value.length <= maxLength && value.none { it.code < 32 || it.code == 127 }

    private fun Properties.setNullable(key: String, value: String?) {
        if (value != null) setProperty(key, value)
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?): T? =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

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
        private const val ARCHIVE_IDENTIFIER = "fm1-backup-restore"
        private const val ARCHIVE_SCHEMA = 1
        private const val MAX_ARCHIVE_ENTRIES = 1024
        private const val MAX_ARCHIVE_ENTRY_BYTES = 2L * FlashRestore.FLASH_SIZE
        private const val MAX_ARCHIVE_JSON_BYTES = 65536L
        private const val MAX_ARCHIVE_EXPANDED_BYTES = 256L * FlashRestore.FLASH_SIZE

        /** Hex-encoded in the filename; 72 characters fits the 255-byte limit beside a 19-digit counter. */
        const val MAX_NAME_LENGTH = 72
        fun uniqueExports(entries: List<BackupEntry>): List<BackupEntry> = entries.distinctBy { it.sha256 }

        fun exportArchiveName(entry: BackupEntry): String = exportFileName(entry).removeSuffix(".bin") + ".zip"

        fun exportFileName(entry: BackupEntry): String {
            val plainName = Normalizer.normalize(
                entry.firmwareIdentity.orEmpty(),
                Normalizer.Form.NFD,
            ).replace(Regex("\\p{M}+"), "")
            val slug = plainName.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            val prefix = slug.takeIf { it.isNotEmpty() }?.let { "$it-" }.orEmpty()
            return "$prefix${entry.sha256.lowercase(Locale.ROOT)}.bin"
        }

        private val HASH = Regex("[0-9a-f]{64}")
        private val PENDING = Regex("fm1-[0-9]{13}-[0-9]+\\.partial")
        private val SAVED = Regex(
            "fm1-([0-9]{13})-([0-9]+)-([0-9a-f]{64})(?:-fw-((?:[0-9a-f]{2}){1,$MAX_NAME_LENGTH}))?\\.bin"
        )
    }
}
