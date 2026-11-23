package com.obd2.app.core.lpg.omvl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Isolated Bluetooth Classic SPP session manager for future OMVL SAVER SKY read-only diagnostics.
 *
 * - Completely separate from Elm327Client and ObdClient (never sends ATZ/ATI/ATE0/0100).
 * - Supports adapter selection/re-selection, connect timeout, read timeout, cancel, and disconnect.
 * - Live TX is HARD-LOCKED OFF (`liveTransmissionAllowed = false`) until explicit owner approval.
 */
class OmvlSppSessionManager(
    private val connector: OmvlSppConnector = DisabledOmvlSppConnector,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    interface OmvlSppSession {
        val incomingChunks: Flow<ByteArray>
        suspend fun close()
    }

    interface OmvlSppConnector {
        suspend fun openPassiveSession(target: OmvlSppAdapterTarget): OmvlSppSession
    }

    sealed interface SendGuardResult {
        data class Blocked(val reason: String) : SendGuardResult
    }

    val liveTransmissionAllowed: Boolean = false

    private val _selectedAdapter = MutableStateFlow<OmvlSppAdapterTarget?>(null)
    val selectedAdapter: StateFlow<OmvlSppAdapterTarget?> = _selectedAdapter

    private val _state = MutableStateFlow<OmvlSppConnectionState>(OmvlSppConnectionState.Idle)
    val state: StateFlow<OmvlSppConnectionState> = _state

    private val _rxFrames = MutableStateFlow<List<OmvlFrame>>(emptyList())
    val rxFrames: StateFlow<List<OmvlFrame>> = _rxFrames

    private val reassembler = OmvlStreamReassembler(expectedPrefix = OmvlSkyCodec.PREFIX_RESPONSE)
    private var connectJob: Job? = null
    private var rxJob: Job? = null
    private var activeSession: OmvlSppSession? = null

    fun selectAdapter(mac: String, name: String) {
        val cleanMac = mac.trim().uppercase()
        if (cleanMac.isEmpty()) return
        val target = OmvlSppAdapterTarget(cleanMac, name.ifBlank { cleanMac })
        _selectedAdapter.value = target
        if (_state.value !is OmvlSppConnectionState.ConnectedPassiveRxOnly &&
            _state.value !is OmvlSppConnectionState.Connecting
        ) {
            _state.value = OmvlSppConnectionState.AdapterSelected(target)
        }
    }

    fun clearSelectedAdapter() {
        cancelConnect()
        _selectedAdapter.value = null
        _state.value = OmvlSppConnectionState.Idle
    }

    fun attemptSendBlocked(opcode: Int): SendGuardResult.Blocked =
        SendGuardResult.Blocked(
            "Передача команды 0x%02X в ЭБУ ГБО заблокирована до отдельного разрешения владельца.".format(opcode and 0xFF),
        )

    fun connectPassive(connectTimeoutMs: Long = 8_000L, readTimeoutMs: Long = 5_000L) {
        val target = _selectedAdapter.value ?: run {
            _state.value = OmvlSppConnectionState.Failed(null, "Адаптер Bluetooth Classic SPP не выбран")
            return
        }
        connectJob?.cancel()
        rxJob?.cancel()
        reassembler.reset()
        _rxFrames.value = emptyList()
        _state.value = OmvlSppConnectionState.Connecting(target)

        connectJob = scope.launch {
            try {
                val session = withTimeout(connectTimeoutMs) {
                    connector.openPassiveSession(target)
                }
                activeSession = session
                _state.value = OmvlSppConnectionState.ConnectedPassiveRxOnly(target, rxFrameCount = 0)
                startPassiveRxLoop(target, session, readTimeoutMs)
            } catch (_: TimeoutCancellationException) {
                _state.value = OmvlSppConnectionState.TimedOut(
                    target,
                    "Таймаут подключения SPP (${connectTimeoutMs} мс)",
                )
            } catch (_: CancellationException) {
                _state.value = OmvlSppConnectionState.Cancelled(target)
            } catch (e: Exception) {
                _state.value = OmvlSppConnectionState.Failed(
                    target,
                    e.message ?: "Ошибка подключения SPP",
                )
            }
        }
    }

    private fun startPassiveRxLoop(target: OmvlSppAdapterTarget, session: OmvlSppSession, readTimeoutMs: Long) {
        rxJob?.cancel()
        rxJob = scope.launch {
            try {
                withTimeout(readTimeoutMs) {
                    session.incomingChunks.collect { chunk ->
                        if (!isActive) return@collect
                        val decoded = reassembler.feed(chunk)
                        if (decoded.isNotEmpty()) {
                            _rxFrames.value = (_rxFrames.value + decoded).takeLast(120)
                            _state.value = OmvlSppConnectionState.ConnectedPassiveRxOnly(
                                target = target,
                                rxFrameCount = _rxFrames.value.size,
                            )
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                runCatching { session.close() }
                activeSession = null
                _state.value = OmvlSppConnectionState.TimedOut(
                    target,
                    "Таймаут ожидания данных SPP (${readTimeoutMs} мс)",
                )
            } catch (_: CancellationException) {
                runCatching { session.close() }
                activeSession = null
            } catch (e: Exception) {
                runCatching { session.close() }
                activeSession = null
                _state.value = OmvlSppConnectionState.Failed(
                    target,
                    e.message ?: "Потеряна связь SPP",
                )
            }
        }
    }

    fun cancelConnect() {
        val target = _selectedAdapter.value
        val wasConnecting = _state.value is OmvlSppConnectionState.Connecting
        connectJob?.cancel()
        connectJob = null
        if (wasConnecting) {
            _state.value = OmvlSppConnectionState.Cancelled(target)
        }
    }

    fun disconnect(reason: String = "Отключено пользователем") {
        val target = _selectedAdapter.value
        connectJob?.cancel()
        connectJob = null
        rxJob?.cancel()
        rxJob = null
        val s = activeSession
        activeSession = null
        if (s != null) {
            scope.launch { runCatching { s.close() } }
        }
        _state.value = OmvlSppConnectionState.Disconnected(target, reason)
    }

    object DisabledOmvlSppConnector : OmvlSppConnector {
        override suspend fun openPassiveSession(target: OmvlSppAdapterTarget): OmvlSppSession {
            throw UnsupportedOperationException(
                "Живое подключение Bluetooth Classic SPP к ${target.name} (${target.mac}) отключено на данном этапе.",
            )
        }
    }
}
