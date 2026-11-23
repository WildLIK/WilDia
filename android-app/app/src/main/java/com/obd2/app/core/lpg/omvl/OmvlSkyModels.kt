package com.obd2.app.core.lpg.omvl

import kotlinx.serialization.Serializable

/**
 * Supported / tracked OMVL SAVER SKY controller families.
 * SKY 64 is explicitly marked unverified until a dedicated passive capture is recorded.
 */
enum class OmvlControllerFamily(val labelRu: String, val verified: Boolean) {
    SKY_32_STD("OMVL SAVER SKY 32 STD (метаданные записи; пакет идентификации не захвачен)", verified = false),
    SKY_64_UNVERIFIED("OMVL SAVER SKY 64 (требуется отдельный захват)", verified = false),
    UNKNOWN("Неизвестный контроллер ГБО", verified = false),
}

data class OmvlSppAdapterTarget(
    val mac: String,
    val name: String,
)

sealed interface OmvlSppConnectionState {
    data object Idle : OmvlSppConnectionState
    data class AdapterSelected(val target: OmvlSppAdapterTarget) : OmvlSppConnectionState
    data class Connecting(val target: OmvlSppAdapterTarget) : OmvlSppConnectionState
    data class ConnectedPassiveRxOnly(val target: OmvlSppAdapterTarget, val rxFrameCount: Int = 0) : OmvlSppConnectionState
    data class TimedOut(val target: OmvlSppAdapterTarget, val reason: String) : OmvlSppConnectionState
    data class Cancelled(val target: OmvlSppAdapterTarget?) : OmvlSppConnectionState
    data class Disconnected(val target: OmvlSppAdapterTarget?, val reason: String) : OmvlSppConnectionState
    data class Failed(val target: OmvlSppAdapterTarget?, val reason: String) : OmvlSppConnectionState
}

enum class OmvlFieldConfidence(val labelRu: String) {
    CONFIRMED("Подтверждено"),
    PROBABLE("Предположительно"),
    UNDECODED("Не расшифровано"),
}

data class OmvlDecodedValue<T>(
    val value: T?,
    val unit: String?,
    val confidence: OmvlFieldConfidence,
    val offsetLabel: String? = null,
    val noteRu: String? = null,
)

data class OmvlFrame(
    val prefix: Int,
    val length: Int,
    val opcode: Int,
    val payload: ByteArray,
    val crcBe: Int,
    val raw: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is OmvlFrame && raw.contentEquals(other.raw)
    override fun hashCode(): Int = raw.contentHashCode()
}

sealed interface OmvlFrameDecodeResult {
    data class Valid(val frame: OmvlFrame) : OmvlFrameDecodeResult
    data class Invalid(val reason: String) : OmvlFrameDecodeResult
}

data class OmvlTelemetrySnapshot(
    val offsetMs: Long,
    val rpm: OmvlDecodedValue<Int>,
    val petrolInjCylMs: OmvlDecodedValue<List<Double>>,
    val petrolInjAvgMs: OmvlDecodedValue<Double>,
    val gasInjCylMs: OmvlDecodedValue<List<Double>>,
    val gasInjAvgMs: OmvlDecodedValue<Double>,
    val mapBar: OmvlDecodedValue<Double>,
    val gasPressureBar: OmvlDecodedValue<Double>,
    val supplyVoltageV: OmvlDecodedValue<Double>,
    val gasTempC: OmvlDecodedValue<Double> = OmvlDecodedValue(null, "°C", OmvlFieldConfidence.UNDECODED),
    val reducerTempC: OmvlDecodedValue<Double> = OmvlDecodedValue(null, "°C", OmvlFieldConfidence.UNDECODED),
    val fuelMode: OmvlDecodedValue<String> = OmvlDecodedValue(null, null, OmvlFieldConfidence.UNDECODED),
    val injectorState: OmvlDecodedValue<String> = OmvlDecodedValue(null, null, OmvlFieldConfidence.UNDECODED),
)

/** Strictly E*** LPG fault code (never mixed with OBD2 P**** codes). */
data class OmvlDtcEntry(
    val code: String,
    val codeNumber: Int,
    val descriptionRu: String,
    val occurrenceCount: Int? = null,
    val statusByteHex: String? = null,
    val hasFreezeFrameRecord: Boolean = false,
    val isHistorical: Boolean = true,
)

@Serializable
data class OmvlReplayFixtureFrame(
    val offsetMs: Long = 0L,
    val opcode: Int,
    val hex: String,
)

@Serializable
data class OmvlReplayFixture(
    val source: String = "",
    val controllerLabel: String = "",
    val frames: List<OmvlReplayFixtureFrame> = emptyList(),
)
