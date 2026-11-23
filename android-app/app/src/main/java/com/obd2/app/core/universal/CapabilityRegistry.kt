package com.obd2.app.core.universal

enum class OperationType(val labelRu: String) {
    READ("Чтение"),
    SERVICE("Сервисная операция"),
    MODIFY("Изменяющая операция"),
}

enum class RiskLevel(val labelRu: String) {
    NONE("Безопасно (только чтение)"),
    LOW("Низкий риск"),
    MEDIUM("Средний риск"),
    HIGH("Высокий риск (изменение конфигурации)"),
}

enum class VerificationMethod(val labelRu: String) {
    OBD_SUPPORT_BITMAP("Маска поддерживаемых PID/MID OBD-II"),
    ECU_RESPONDED("Подтверждён ответ ЭБУ на адресный запрос"),
    ADAPTER_PROBE("Проверка возможностей адаптера и шины"),
    CATALOG_AND_VEHICLE_MATCH("Сопоставление каталога и идентификаторов авто"),
    DISABLED_SAFETY_LOCK("Отключено до отдельной проверки на автомобиле"),
}

enum class CapabilityStatus(val labelRu: String) {
    CONFIRMED_SUPPORTED("Подтверждено и доступно"),
    CONFIRMED_UNSUPPORTED("Не поддерживается автомобилем"),
    UNKNOWN("Не подтверждено"),
    ADAPTER_UNSUPPORTED("Не поддерживается адаптером"),
    REQUIRES_VALIDATION("Отключено (требует валидации)"),
}

/**
 * Structured specification for every diagnostic, service, or coding capability.
 * All 13 required fields are explicitly modeled.
 */
data class DiagnosticFunctionSpec(
    val functionId: String,
    val titleRu: String,
    val descriptionRu: String,
    val manufacturer: String,                 // "Universal" | "VAG" | "Ford" | "Nissan / Datsun" | "Haval / Great Wall"
    val compatibleModels: List<String>,       // e.g. ["Любой OBD-II"] or ["Octavia A5 (1Z)"]
    val supportedEcuIds: List<String>,        // e.g. ["7E8", "7E0", "714"]
    val requiredProtocol: DiagnosticProtocolKind,
    val adapterRequirements: String,
    val preconditions: List<String>,
    val operationType: OperationType,
    val riskLevel: RiskLevel,
    val verificationMethod: VerificationMethod,
    val confidenceHint: String,
    val groupRu: String = operationType.labelRu,
)

data class EvaluatedCapability(
    val spec: DiagnosticFunctionSpec,
    val status: CapabilityStatus,
    val reasonRu: String,
    val confidenceScore: Int,                 // 0..100
    val executableInUi: Boolean,
    val visibleToNormalUser: Boolean,
    val riskWarningRu: String? = null,
)

/**
 * Evaluates a list of [DiagnosticFunctionSpec] against actual vehicle, adapter,
 * protocol, and ECU discovery facts.
 *
 * Rules:
 * - Catalog presence alone NEVER yields CONFIRMED_SUPPORTED.
 * - Normal users see ONLY confirmed read-only operations (`CONFIRMED_SUPPORTED` & `READ`).
 * - Service and modifying operations remain disabled (`REQUIRES_VALIDATION`) with risk warnings.
 */
object CapabilityEvaluator {

    fun evaluateAll(
        specs: List<DiagnosticFunctionSpec>,
        vehicle: VehicleIdentityResult,
        adapterCaps: AdapterCapabilities,
        ecus: List<DiscoveredEcu>,
        supportedObdPids: Set<Int>,
        hasFreezeFrameSupport: Boolean,
        hasMode06Support: Boolean,
        hasMode09Support: Boolean,
    ): List<EvaluatedCapability> = specs.map { spec ->
        evaluateOne(
            spec = spec,
            vehicle = vehicle,
            adapterCaps = adapterCaps,
            ecus = ecus,
            supportedObdPids = supportedObdPids,
            hasFreezeFrameSupport = hasFreezeFrameSupport,
            hasMode06Support = hasMode06Support,
            hasMode09Support = hasMode09Support,
        )
    }

    fun evaluateOne(
        spec: DiagnosticFunctionSpec,
        vehicle: VehicleIdentityResult,
        adapterCaps: AdapterCapabilities,
        ecus: List<DiscoveredEcu>,
        supportedObdPids: Set<Int>,
        hasFreezeFrameSupport: Boolean,
        hasMode06Support: Boolean,
        hasMode09Support: Boolean,
    ): EvaluatedCapability {
        val riskWarning = if (spec.operationType != OperationType.READ) {
            "Внимание (${spec.riskLevel.labelRu}): некорректное применение может изменить настройки блока или сбросить калибровки. Операция отключена до стендовой проверки."
        } else null

        // 1. Service & Modifying operations are strictly disabled in v0.5.0
        if (spec.operationType == OperationType.SERVICE || spec.operationType == OperationType.MODIFY) {
            val status = if (!adapterCaps.supportsProtocol(spec.requiredProtocol)) {
                CapabilityStatus.ADAPTER_UNSUPPORTED
            } else {
                CapabilityStatus.REQUIRES_VALIDATION
            }
            return EvaluatedCapability(
                spec = spec,
                status = status,
                reasonRu = "Техническая спецификация: выполнение отключено (только безопасное чтение).",
                confidenceScore = 15,
                executableInUi = false,
                visibleToNormalUser = false,
                riskWarningRu = riskWarning,
            )
        }

        // 2. Check adapter & protocol compatibility
        if (!adapterCaps.supportsProtocol(spec.requiredProtocol)) {
            return EvaluatedCapability(
                spec = spec,
                status = CapabilityStatus.ADAPTER_UNSUPPORTED,
                reasonRu = "Текущий адаптер/шина не подтверждает протокол ${spec.requiredProtocol.labelRu}.",
                confidenceScore = 0,
                executableInUi = false,
                visibleToNormalUser = false,
                riskWarningRu = riskWarning,
            )
        }

        // 3. Check manufacturer match if spec is brand-specific
        if (spec.manufacturer != "Universal") {
            val vehicleMaker = vehicle.manufacturer
            if (vehicleMaker == null || !vehicleMaker.equals(spec.manufacturer, ignoreCase = true)) {
                return EvaluatedCapability(
                    spec = spec,
                    status = CapabilityStatus.UNKNOWN,
                    reasonRu = "Автомобиль не подтверждён как ${spec.manufacturer}.",
                    confidenceScore = 10,
                    executableInUi = false,
                    visibleToNormalUser = false,
                    riskWarningRu = riskWarning,
                )
            }
        }

        // 4. Verify via real ECU / OBD evidence (never from catalog presence alone!)
        return when (spec.functionId) {
            "obd_dtc_read" -> confirmedIf(
                condition = adapterCaps.supportsStandardObd2,
                spec = spec,
                okReason = "ЭБУ отвечает по стандартному OBD-II (Mode 03/07/0A).",
                failStatus = CapabilityStatus.ADAPTER_UNSUPPORTED,
                failReason = "ЭБУ не отвечает на стандартный OBD-II.",
            )
            "obd_live_pids" -> confirmedIf(
                condition = adapterCaps.supportsStandardObd2 && supportedObdPids.isNotEmpty(),
                spec = spec,
                okReason = "Подтверждено поддержкой ${supportedObdPids.size} PID (Mode 01).",
                failStatus = CapabilityStatus.CONFIRMED_UNSUPPORTED,
                failReason = "ЭБУ не вернул поддерживаемые PID Mode 01.",
            )
            "obd_im_readiness" -> confirmedIf(
                condition = adapterCaps.supportsStandardObd2 && (0x01 in supportedObdPids || supportedObdPids.isNotEmpty()),
                spec = spec,
                okReason = "Доступно чтение мониторов готовности I/M Readiness (01 01).",
                failStatus = CapabilityStatus.CONFIRMED_UNSUPPORTED,
                failReason = "Мониторы I/M Readiness недоступны.",
            )
            "obd_freeze_frame" -> confirmedIf(
                condition = hasFreezeFrameSupport,
                spec = spec,
                okReason = "ЭБУ поддерживает чтение стоп-кадра Mode 02.",
                failStatus = CapabilityStatus.UNKNOWN,
                failReason = "Наличие стоп-кадра не подтверждено ЭБУ (или ошибок нет).",
            )
            "obd_mode06" -> confirmedIf(
                condition = hasMode06Support,
                spec = spec,
                okReason = "ЭБУ вернул поддерживаемые мониторы Mode 06.",
                failStatus = CapabilityStatus.CONFIRMED_UNSUPPORTED,
                failReason = "Mode 06 не поддерживается или не подтверждён ЭБУ.",
            )
            "obd_mode09_ident" -> confirmedIf(
                condition = hasMode09Support,
                spec = spec,
                okReason = "ЭБУ отвечает на идентификацию Mode 09 (VIN/CalID/ECU).",
                failStatus = CapabilityStatus.CONFIRMED_UNSUPPORTED,
                failReason = "Mode 09 не вернул идентификаторы на этом ЭБУ.",
            )
            else -> {
                // Brand-specific read capabilities require BOTH matching detected ECU AND responding status
                val respondingEcu = ecus.firstOrNull { ecu ->
                    ecu.status == EcuStatus.DETECTED_RESPONDING &&
                        (spec.supportedEcuIds.isEmpty() || ecu.address in spec.supportedEcuIds)
                }
                if (respondingEcu != null) {
                    EvaluatedCapability(
                        spec = spec,
                        status = CapabilityStatus.CONFIRMED_SUPPORTED,
                        reasonRu = "Подтверждено ответом ЭБУ ${respondingEcu.address} (${respondingEcu.nameRu}).",
                        confidenceScore = 85,
                        executableInUi = true,
                        visibleToNormalUser = true,
                        riskWarningRu = riskWarning,
                    )
                } else {
                    val restricted = ecus.any { it.status == EcuStatus.DETECTED_RESTRICTED && it.address in spec.supportedEcuIds }
                    EvaluatedCapability(
                        spec = spec,
                        status = if (restricted) CapabilityStatus.REQUIRES_VALIDATION else CapabilityStatus.UNKNOWN,
                        reasonRu = if (restricted) {
                            "Блок обнаружен с ограниченным доступом — функция требует проверки."
                        } else {
                            "Требуемый ЭБУ (${spec.supportedEcuIds.joinToString()}) не обнаружен на этом автомобиле."
                        },
                        confidenceScore = if (restricted) 40 else 0,
                        executableInUi = false,
                        visibleToNormalUser = false,
                        riskWarningRu = riskWarning,
                    )
                }
            }
        }
    }

    private fun confirmedIf(
        condition: Boolean,
        spec: DiagnosticFunctionSpec,
        okReason: String,
        failStatus: CapabilityStatus,
        failReason: String,
    ): EvaluatedCapability = if (condition) {
        EvaluatedCapability(
            spec = spec,
            status = CapabilityStatus.CONFIRMED_SUPPORTED,
            reasonRu = okReason,
            confidenceScore = 100,
            executableInUi = true,
            visibleToNormalUser = true,
        )
    } else {
        EvaluatedCapability(
            spec = spec,
            status = failStatus,
            reasonRu = failReason,
            confidenceScore = 0,
            executableInUi = false,
            visibleToNormalUser = false,
        )
    }
}
