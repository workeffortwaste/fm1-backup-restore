package com.workeffortwaste.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Locks down the FM1-only MIDI policy without depending on Android device discovery. */
class Fm1BootloaderMidiTest {
    @Test
    fun recognizesNormalModeUsbIdOnlyWithMidiInterface() {
        assertTrue(Fm1BootloaderMidi.matchesUsb(0x4c4a, 0xc755, true))
        assertTrue(Fm1BootloaderMidi.matchesUsb(0x1209, 0x0001, true))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x1209, 0x0001, false))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x1209, 0x0003, true))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x4c4a, 0xc755, false))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x4c4a, 0x0001, true))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x1209, 0xc755, true))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x4d4a, 0x4155, true))
    }

    /** Hardware expects these six bytes exactly; no channel framing or trailing message is allowed. */
    @Test
    fun sendsExactUserProvidedSysExOnce() {
        val messages = mutableListOf<ByteArray>()
        Fm1BootloaderMidi.send { message -> messages += message }
        assertEquals(1, messages.size)
        assertArrayEquals(
            byteArrayOf(0xf0.toByte(), 0x22, 0x24, 0x35, 0x7d, 0xf7.toByte()),
            messages.single()
        )
    }

    /** A receiver must not mutate the next caller's SysEx buffer. */
    @Test
    fun createsFreshMessageForEachRequest() {
        Fm1BootloaderMidi.send { message -> message.fill(0) }
        Fm1BootloaderMidi.send { message -> assertEquals(0xf0, message.first().toInt() and 0xff) }
    }

    /** A failed reboot request is ambiguous and must not be retried. */
    @Test
    fun propagatesSendFailureWithoutRetryingOrBroadcasting() {
        var sendCount = 0
        assertThrows(IOException::class.java) {
            Fm1BootloaderMidi.send {
                sendCount++
                throw IOException("Disconnected")
            }
        }
        assertEquals(1, sendCount)
    }
}
