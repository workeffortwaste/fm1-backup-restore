package com.workeffortwaste.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Locks down supported-device MIDI policy without depending on Android device discovery. */
class Fm1BootloaderMidiTest {
    @Test
    fun recognizesNormalModeUsbIdOnlyWithMidiInterface() {
        assertTrue(Fm1BootloaderMidi.matchesUsb(0x4c4a, 0xc755, true))
        assertTrue(Fm1BootloaderMidi.matchesUsb(0x1209, 0x0001, true))
        assertTrue(Fm1BootloaderMidi.matchesUsb(0x3654, 0x7756, true))
        assertTrue(Fm1BootloaderMidi.matchesUsb(0x4d4a, 0x4155, true))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x4d4a, 0x4155, false))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x3654, 0x7756, false))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x3654, 0x7757, true))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x4c4a, 0x7756, true))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x1209, 0x0001, false))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x1209, 0x0003, true))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x4c4a, 0xc755, false))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x4c4a, 0x0001, true))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x1209, 0xc755, true))
    }

    @Test
    fun recognizesStockSmk37ProUsbIdOnlyWithMidiStreaming() {
        assertTrue(Fm1BootloaderMidi.matchesUsb(0x4353, 0xb64b, true))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x4353, 0xb64b, false))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x4353, 0xb64c, true))
        assertFalse(Fm1BootloaderMidi.matchesUsb(0x4c4a, 0xb64b, true))
    }

    @Test
    fun selectsTheOnlyRespondingPortRegardlessOfItsIndex() {
        val probed = mutableListOf<Int>()
        val route = Fm1BootloaderMidi.resolveIdentityRoute(listOf(0, 1, 2)) { port ->
            probed += port
            "SMK-37 Pro_016".takeIf { port == 2 }
        }

        assertEquals(listOf(0, 1, 2), probed)
        assertEquals(MidiIdentityRoute(2, "SMK-37 Pro_016"), route)
    }

    @Test
    fun retainsAnAgreedIdentityWithoutChoosingBetweenRespondingPorts() {
        val route = Fm1BootloaderMidi.resolveIdentityRoute(listOf(0, 1, 2)) { port ->
            "SMK-37 Pro_016".takeIf { port != 1 }
        }

        assertEquals(MidiIdentityRoute(null, "SMK-37 Pro_016"), route)
    }

    @Test
    fun leavesTheRouteUnresolvedWhenRepliesConflictOrAreMissing() {
        val ports = listOf(0, 1, 2)
        val conflicting = Fm1BootloaderMidi.resolveIdentityRoute(ports) { "Keyboard_$it" }
        val absent = Fm1BootloaderMidi.resolveIdentityRoute(ports) { null }

        assertEquals(MidiIdentityRoute(null), conflicting)
        assertEquals(MidiIdentityRoute(null), absent)
    }

    @Test
    fun keepsSinglePortFirmwareCompatibleWithoutRequiringAnIdentityReply() {
        val route = Fm1BootloaderMidi.resolveIdentityRoute(listOf(3)) { error("Unexpected route probe") }

        assertEquals(MidiIdentityRoute(3), route)
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
