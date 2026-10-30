package com.workeffortwaste.protocol

import java.security.MessageDigest

/**
 * Fixed FM1 layout only: the first 16 KiB is preserved and ignored; the full writable range is verified.
 * Hardware validation is still required. Exceptions stop immediately; mutation is never retried here.
 */
class FlashRestore(private val protocol: Fm1Protocol) {
    /**
     * Rechecks live identity/JEDEC metadata before mutation. Source bootloader bytes are ignored.
     * [image] is copied once so caller changes cannot affect the expected bytes during programming.
     * Writable sectors are read and compared before mutation; matching sectors are skipped.
     * [mutationStarting] runs immediately before the first erase, allowing conservative failure UI
     * even when an erase times out and the device's actual state is unknown.
     * [progress] supplies bytes and total within the current writable phase.
     * Returns SHA-256 of the verified writable range only, not of the protected part of the source.
     */
    fun run(
        image: ByteArray,
        confirmedCapacity: Long,
        checkCancelled: () -> Unit,
        mutationStarting: () -> Unit,
        progress: (phase: String, bytes: Long, total: Long) -> Unit,
    ): String {
        require(confirmedCapacity == FLASH_SIZE.toLong()) { "FM-1 restore requires exactly 1 MiB" }
        require(image.size == FLASH_SIZE) { "Restore image must be exactly 1 MiB" }
        val expected = image.copyOf()
        checkCancelled()
        protocol.identify()
        checkCancelled()
        val info = protocol.flashInfo()
        // A plausible 24-bit JEDEC ID must have real manufacturer/type bytes and density 0x14.
        // Unknown or conflicting density is a rejection, not permission to trust the file's length.
        info.requireFm1Nor()
        var mutationStarted = false
        for (sector in PROTECTED_SIZE until FLASH_SIZE step SECTOR_SIZE) {
            checkCancelled()
            var matches = true
            for (address in sector until sector + SECTOR_SIZE step PAGE_SIZE) {
                checkCancelled()
                val actual = readExact(address)
                if (!actual.contentEquals(expected.copyOfRange(address, address + PAGE_SIZE))) {
                    matches = false
                }
            }
            if (!matches) {
                if (!mutationStarted) {
                    mutationStarting()
                    mutationStarted = true
                }
                protocol.eraseSector(sector.toLong())
                for (address in sector until sector + SECTOR_SIZE step PAGE_SIZE) {
                    checkCancelled()
                    protocol.writeFlash(address.toLong(), expected.copyOfRange(address, address + PAGE_SIZE))
                }
            }
            progress(
                "Writing to FM-1 flash",
                (sector + SECTOR_SIZE - PROTECTED_SIZE).toLong(),
                WRITABLE_SIZE.toLong(),
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for (address in PROTECTED_SIZE until FLASH_SIZE step PAGE_SIZE) {
            checkCancelled()
            val actual = readExact(address)
            if (!actual.contentEquals(expected.copyOfRange(address, address + PAGE_SIZE))) {
                throw ProtocolException("Restore readback mismatch at 0x%06X".format(address))
            }
            digest.update(actual)
            progress(
                "Verifying: reading back written data",
                (address + PAGE_SIZE - PROTECTED_SIZE).toLong(),
                WRITABLE_SIZE.toLong(),
            )
        }
        checkCancelled()
        return RestoreImage.hex(digest.digest())
    }

    /** A short simulated or device read is never padded into a plausible backup or baseline. */
    private fun readExact(address: Int): ByteArray {
        val bytes = protocol.readFlash(address.toLong(), PAGE_SIZE)
        if (bytes.size != PAGE_SIZE) throw ProtocolException("Incomplete flash read at $address")
        return bytes
    }

    companion object {
        const val FLASH_SIZE = 0x100000
        const val PROTECTED_SIZE = 0x4000
        const val SECTOR_SIZE = 4096
        const val PAGE_SIZE = 256
        const val WRITABLE_SIZE = FLASH_SIZE - PROTECTED_SIZE
    }
}
