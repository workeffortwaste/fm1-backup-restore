package com.workeffortwaste.protocol

internal data class MidiIdentityRoute(val inputPort: Int?, val identity: String? = null)

/** Supported-device MIDI command policy; Android discovery and handle ownership live in AndroidFm1Midi. */
object Fm1BootloaderMidi {
    const val SUPPORTED_USB_IDS = "4C4A:C755, 1209:0001, 3654:7756, 4D4A:4155"

    /** Observed normal-mode candidates, not proof of hardware identity; require MIDI streaming. */
    fun matchesUsb(vendorId: Int, productId: Int, hasMidiStreaming: Boolean): Boolean =
        hasMidiStreaming && (
                (vendorId == 0x4c4a && productId == 0xc755) ||
                        (vendorId == 0x1209 && productId == 0x0001) ||
                        (vendorId == 0x3654 && productId == 0x7756) ||
                        (vendorId == 0x4d4a && productId == 0x4155)
                )

    internal fun resolveIdentityRoute(
        inputPorts: List<Int>,
        probe: (Int) -> String?,
    ): MidiIdentityRoute {
        // A single send port is already unambiguous, including on firmware without an identity reply.
        if (inputPorts.size <= 1) return MidiIdentityRoute(inputPorts.firstOrNull())

        val replies = inputPorts.mapNotNull { port -> probe(port)?.let { port to it } }
        return MidiIdentityRoute(
            inputPort = replies.singleOrNull()?.first,
            identity = replies.map { it.second }.distinct().singleOrNull(),
        )
    }

    /** Sends one fresh SysEx message; a failed send is never retried or broadcast to another device. */
    fun send(receiver: (ByteArray) -> Unit) {
        receiver(byteArrayOf(0xf0.toByte(), 0x22, 0x24, 0x35, 0x7d, 0xf7.toByte()))
    }
}
