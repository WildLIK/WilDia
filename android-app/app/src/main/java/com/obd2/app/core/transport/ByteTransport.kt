package com.obd2.app.core.transport

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Byte-level transport between the phone and the ELM327 adapter.
 *
 * Implementations: [com.obd2.app.core.ble.BleTransport] (real adapter) and
 * [com.obd2.app.core.transport.DemoElmTransport] (offline demo/simulator).
 * Keeping this pure-Kotlin allows the ELM327/OBD layers to be unit tested
 * without Android.
 */
interface ByteTransport {
    val name: String
    val state: StateFlow<TransportState>
    /** Chunks of bytes received from the adapter (may be split arbitrarily). */
    val incoming: SharedFlow<ByteArray>

    suspend fun connect()
    suspend fun disconnect()

    /** Writes a raw payload; implementations must queue while busy. */
    suspend fun write(data: ByteArray)

    /** True when the transport can currently carry commands. */
    fun isConnected(): Boolean

    /** UUID of the characteristic used by the most recent write (diagnostics). */
    val lastWriteUuid: String? get() = null
}

sealed interface TransportState {
    data object Disconnected : TransportState
    data object Scanning : TransportState
    data object Connecting : TransportState
    data class Connected(val details: String) : TransportState
    data class Reconnecting(val attempt: Int) : TransportState
    data class Failed(val reason: String) : TransportState
}
