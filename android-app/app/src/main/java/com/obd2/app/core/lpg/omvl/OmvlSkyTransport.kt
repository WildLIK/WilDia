package com.obd2.app.core.lpg.omvl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Strictly read-only transport abstraction for future OMVL SAVER SKY SPP/replay modules.
 * Architecturally omits any `write` / `sendArbitraryCommand` API so no write/config commands
 * can ever be issued from the UI or replay layer.
 */
interface OmvlSkyTransport {
    sealed interface State {
        data object Disconnected : State
        data object Connecting : State
        data class Connected(val details: String) : State
        data class Failed(val reason: String) : State
    }

    val state: StateFlow<State>
    val incomingBytes: Flow<ByteArray>
    val isLiveTransmissionEnabled: Boolean

    suspend fun connect()
    suspend fun disconnect()
    fun cancel()
}

/**
 * Offline replay transport that emits sanitized recorded RFCOMM chunks (including 100+26
 * fragmentation simulation) into [OmvlStreamReassembler]. Zero Bluetooth or COM activity.
 */
class OfflineReplayOmvlTransport : OmvlSkyTransport {
    private val _state = MutableStateFlow<OmvlSkyTransport.State>(OmvlSkyTransport.State.Disconnected)
    override val state: StateFlow<OmvlSkyTransport.State> = _state

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingBytes: Flow<ByteArray> = _incoming

    override val isLiveTransmissionEnabled: Boolean = false

    override suspend fun connect() {
        _state.value = OmvlSkyTransport.State.Connected("Офлайн-воспроизведение записи SKY 32")
    }

    suspend fun emitHexFrameFragmented(hex: String, splitAt: Int = 100) {
        val clean = hex.replace(" ", "").trim()
        if (clean.length % 2 != 0 || clean.isEmpty()) return
        val bytes = ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        if (bytes.size > splitAt) {
            _incoming.emit(bytes.copyOfRange(0, splitAt))
            _incoming.emit(bytes.copyOfRange(splitAt, bytes.size))
        } else {
            _incoming.emit(bytes)
        }
    }

    override suspend fun disconnect() {
        _state.value = OmvlSkyTransport.State.Disconnected
    }

    override fun cancel() {
        _state.value = OmvlSkyTransport.State.Disconnected
    }
}

/**
 * Prepared placeholder for future Bluetooth Classic SPP (GBO-3, UUID 00001101) connection.
 * Live transmission and automatic polling are explicitly disabled at this stage.
 */
class FutureBluetoothSppOmvlTransportStub : OmvlSkyTransport {
    private val _state = MutableStateFlow<OmvlSkyTransport.State>(OmvlSkyTransport.State.Disconnected)
    override val state: StateFlow<OmvlSkyTransport.State> = _state

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    override val incomingBytes: Flow<ByteArray> = _incoming

    override val isLiveTransmissionEnabled: Boolean = false

    override suspend fun connect() {
        _state.value = OmvlSkyTransport.State.Failed(
            "Живое подключение к ГБО отключено на этапе офлайн-декодера (ожидает отдельного согласования).",
        )
    }

    override suspend fun disconnect() {
        _state.value = OmvlSkyTransport.State.Disconnected
    }

    override fun cancel() {
        _state.value = OmvlSkyTransport.State.Disconnected
    }
}
