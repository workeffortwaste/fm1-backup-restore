package com.workeffortwaste.protocol

import java.security.MessageDigest

/** Explicit stock-only repair; ordinary restore can never enter this write path. */
internal class BootloaderRecovery(private val protocol: Fm1Protocol) {
    /**
     * Copies and validates stock data before device access; touches only the first 16 KiB.
     * [saveOriginal] must persist and verify the original bytes before returning: failure prevents erase.
     * [mutationStarting] runs before the first erase, whose failure may still mean flash was changed.
     * [checkConnection] guards reads and mutations, not user cancellation; errors stop without retry/reset.
     * [forceRewrite] is supplied only by debug builds and programs every page, including matching/FF data.
     * Success requires exact full-region readback and the stock hash, even when all sectors were skipped.
     */
    fun run(
        image: ByteArray,
        checkConnection: () -> Unit,
        saveOriginal: (ByteArray) -> Unit,
        mutationStarting: () -> Unit,
        progress: (phase: String, bytes: Int) -> Unit,
        forceRewrite: Boolean = false,
    ): String {
        val expected = image.copyOf()
        requireStock(expected)
        checkConnection()
        protocol.identify()
        protocol.flashInfo().requireFm1Nor()
        val original = ByteArray(FlashRestore.PROTECTED_SIZE)
        for (address in original.indices step FlashRestore.PAGE_SIZE) {
            checkConnection()
            readExact(address).copyInto(original, address)
            progress("Saving existing bootloader", address + FlashRestore.PAGE_SIZE)
        }
        saveOriginal(original.copyOf())
        var mutationStarted = false
        for (sector in original.indices step FlashRestore.SECTOR_SIZE) {
            val end = sector + FlashRestore.SECTOR_SIZE
            if (forceRewrite || !(sector until end).all { original[it] == expected[it] }) {
                checkConnection()
                if (!mutationStarted) {
                    mutationStarting()
                    mutationStarted = true
                }
                protocol.eraseBootloaderSector(sector.toLong())
                for (address in sector until end step FlashRestore.PAGE_SIZE) {
                    val pageEnd = address + FlashRestore.PAGE_SIZE
                    if (!forceRewrite && (address until pageEnd).all { expected[it] == 0xff.toByte() }) continue
                    checkConnection()
                    protocol.writeBootloaderPage(address.toLong(), expected.copyOfRange(address, pageEnd))
                }
            }
            progress("Restoring stock bootloader", end)
        }
        val actual = ByteArray(expected.size)
        for (address in actual.indices step FlashRestore.PAGE_SIZE) {
            checkConnection()
            val page = readExact(address)
            if (!page.indices.all { page[it] == expected[address + it] }) {
                throw RestoreVerificationException("Bootloader readback mismatch at 0x%06X".format(address))
            }
            page.copyInto(actual, address)
            progress("Verifying restored bootloader", address + page.size)
        }
        checkConnection()
        return requireStock(actual)
    }

    private fun readExact(address: Int): ByteArray =
        protocol.readFlash(address.toLong(), FlashRestore.PAGE_SIZE).also {
            if (it.size != FlashRestore.PAGE_SIZE) {
                throw ProtocolException("Incomplete bootloader read at 0x%06X".format(address))
            }
        }

    companion object {
        /** Reuses full package/image validation, then independently checks the extracted boot-area hash. */
        fun fromPackage(bytes: ByteArray): ByteArray =
            V15RecoveryImage.build(bytes).copyOfRange(0, FlashRestore.PROTECTED_SIZE).also { requireStock(it) }

        /** Returns the computed digest only after checking exact length and the pinned stock hash. */
        fun requireStock(bytes: ByteArray): String {
            require(bytes.size == FlashRestore.PROTECTED_SIZE) { "Expected exactly 16 KiB of bootloader data." }
            val sha256 = RestoreImage.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
            require(BootloaderVerifier.isKnownStockV15(sha256)) {
                "Recovery bootloader does not match the verified stock V15 SHA-256."
            }
            return sha256
        }
    }
}
