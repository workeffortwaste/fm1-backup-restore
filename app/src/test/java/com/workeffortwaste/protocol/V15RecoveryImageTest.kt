package com.workeffortwaste.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V15RecoveryImageTest {
    @Test
    fun assemblesTheStockV15PartitionsAtTheirFlashOffsets() {
        val flash = ByteArray(0x93000) { (it % 251).toByte() }
        val usr = ByteArray(0x11E0A) { ((it + 31) % 251).toByte() }

        val image = V15RecoveryImage.assembleImage(flash, usr)

        assertEquals(0x100000, image.size)
        assertArrayEquals(flash, image.copyOfRange(0, flash.size))
        assertTrue(image.copyOfRange(0x93000, 0xEA000).all { it == 0xff.toByte() })
        assertArrayEquals(usr, image.copyOfRange(0xEA000, 0xEA000 + usr.size))
        assertTrue(image.copyOfRange(0xEA000 + usr.size, image.size).all { it == 0xff.toByte() })
    }

    @Test
    fun rejectsIncorrectPartitionSizes() {
        assertThrows(IllegalArgumentException::class.java) {
            V15RecoveryImage.assembleImage(ByteArray(0x93001), ByteArray(0x11E0A))
        }
        assertThrows(IllegalArgumentException::class.java) {
            V15RecoveryImage.assembleImage(ByteArray(0x93000), ByteArray(0x11E09))
        }
    }

    @Test
    fun rejectsUnverifiedPackagesBeforeParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            V15RecoveryImage.build(ByteArray(V15RecoveryImage.PACKAGE_SIZE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            V15RecoveryImage.build(ByteArray(V15RecoveryImage.PACKAGE_SIZE - 1))
        }
    }
}
