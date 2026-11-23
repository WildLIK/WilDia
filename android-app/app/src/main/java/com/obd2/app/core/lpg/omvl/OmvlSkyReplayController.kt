package com.obd2.app.core.lpg.omvl

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Manages offline replay of the sanitized OMVL SAVER SKY 32 capture.
 * Completely isolated from Elm327Client, Room OBD2 sessions, and live Bluetooth.
 */
class OmvlSkyReplayController(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    data class ReplayUiState(
        val loaded: Boolean = false,
        val playing: Boolean = false,
        val controllerFamily: OmvlControllerFamily = OmvlControllerFamily.SKY_32_STD,
        val warningBannerRu: String = "Воспроизведение сохранённой записи. Нет подключения к автомобилю",
        val currentIndex: Int = 0,
        val totalTelemetryFrames: Int = 0,
        val currentTelemetry: OmvlTelemetrySnapshot? = null,
        val historicalDtcs: List<OmvlDtcEntry> = emptyList(),
        val rpmSeries: List<Double> = emptyList(),
        val petrolInjSeries: List<Double> = emptyList(),
        val gasInjSeries: List<Double> = emptyList(),
        val errorMessage: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val transport = OfflineReplayOmvlTransport()
    private val reassembler = OmvlStreamReassembler(expectedPrefix = OmvlSkyCodec.PREFIX_RESPONSE)

    private val _uiState = MutableStateFlow(ReplayUiState())
    val uiState: StateFlow<ReplayUiState> = _uiState

    private var telemetryFixtures: List<OmvlReplayFixtureFrame> = emptyList()
    private var table0x32Dtcs: List<OmvlDtcEntry> = emptyList()
    private var events0x35Dtcs: List<OmvlDtcEntry> = emptyList()
    private var playJob: Job? = null

    init {
        scope.launch {
            transport.incomingBytes.collect { chunk ->
                val frames = reassembler.feed(chunk)
                for (fr in frames) {
                    when (fr.opcode) {
                        0x32 -> table0x32Dtcs = OmvlSkyCodec.decodeDtcTable0x32(fr)
                        0x35 -> events0x35Dtcs = OmvlSkyCodec.decodeDtcEvents0x35(fr)
                        0x09 -> {
                            val idx = _uiState.value.currentIndex
                            val offMs = telemetryFixtures.getOrNull(idx)?.offsetMs ?: 0L
                            OmvlSkyCodec.decodeTelemetry0x09(fr, offMs)?.let { snap ->
                                val rpm = snap.rpm.value?.toDouble()
                                val pet = snap.petrolInjAvgMs.value
                                val gas = snap.gasInjAvgMs.value
                                _uiState.value = _uiState.value.copy(
                                    currentTelemetry = snap,
                                    rpmSeries = if (rpm != null) (_uiState.value.rpmSeries + rpm).takeLast(60) else _uiState.value.rpmSeries,
                                    petrolInjSeries = if (pet != null) (_uiState.value.petrolInjSeries + pet).takeLast(60) else _uiState.value.petrolInjSeries,
                                    gasInjSeries = if (gas != null) (_uiState.value.gasInjSeries + gas).takeLast(60) else _uiState.value.gasInjSeries,
                                )
                            }
                        }
                    }
                }
                if (table0x32Dtcs.isNotEmpty() || events0x35Dtcs.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        historicalDtcs = OmvlSkyCodec.mergeHistoricalDtcs(table0x32Dtcs, events0x35Dtcs),
                    )
                }
            }
        }
    }

    suspend fun ensureLoaded() {
        if (_uiState.value.loaded) return
        try {
            val rawJson = context.assets.open("omvl_sky32_replay.json").bufferedReader().use { it.readText() }
            val fixture = json.decodeFromString(OmvlReplayFixture.serializer(), rawJson)
            transport.connect()
            reassembler.reset()
            table0x32Dtcs = emptyList()
            events0x35Dtcs = emptyList()

            val dtcFrames = fixture.frames.filter { it.opcode == 0x32 || it.opcode == 0x35 }
            telemetryFixtures = fixture.frames.filter { it.opcode == 0x09 }
            for (df in dtcFrames) {
                transport.emitHexFrameFragmented(df.hex)
            }
            _uiState.value = _uiState.value.copy(
                loaded = true,
                totalTelemetryFrames = telemetryFixtures.size,
                currentIndex = 0,
                errorMessage = null,
            )
            if (telemetryFixtures.isNotEmpty()) {
                emitTelemetryAt(0)
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(errorMessage = "Ошибка загрузки записи ГБО: ${e.message}")
        }
    }

    private suspend fun emitTelemetryAt(index: Int) {
        val item = telemetryFixtures.getOrNull(index) ?: return
        _uiState.value = _uiState.value.copy(currentIndex = index)
        transport.emitHexFrameFragmented(item.hex, splitAt = 100)
    }

    fun stepNext() {
        scope.launch {
            ensureLoaded()
            if (telemetryFixtures.isEmpty()) return@launch
            val next = (_uiState.value.currentIndex + 1) % telemetryFixtures.size
            emitTelemetryAt(next)
        }
    }

    fun resetReplay() {
        pause()
        scope.launch {
            ensureLoaded()
            _uiState.value = _uiState.value.copy(
                rpmSeries = emptyList(),
                petrolInjSeries = emptyList(),
                gasInjSeries = emptyList(),
            )
            if (telemetryFixtures.isNotEmpty()) {
                emitTelemetryAt(0)
            }
        }
    }

    fun togglePlay() {
        if (_uiState.value.playing) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        if (playJob?.isActive == true) return
        _uiState.value = _uiState.value.copy(playing = true)
        playJob = scope.launch {
            ensureLoaded()
            while (isActive && _uiState.value.playing) {
                delay(450L)
                if (!_uiState.value.playing || telemetryFixtures.isEmpty()) break
                val next = (_uiState.value.currentIndex + 1) % telemetryFixtures.size
                emitTelemetryAt(next)
            }
        }
    }

    fun pause() {
        playJob?.cancel()
        playJob = null
        _uiState.value = _uiState.value.copy(playing = false)
    }
}
