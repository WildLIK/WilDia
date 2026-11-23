package com.obd2.app.core.universal

import com.obd2.app.core.elm327.Elm327Client
import com.obd2.app.core.elm327.ElmResponse
import kotlinx.serialization.Serializable

enum class EcuStatus(val labelRu: String) {
    DETECTED_RESPONDING("Обнаружен и отвечает"),
    DETECTED_RESTRICTED("Обнаружен, но доступ ограничен"),
    NOT_DETECTED("Не обнаружен"),
    ADAPTER_UNSUPPORTED("Не поддерживается адаптером"),
    NOT_CHECKED("Не проверялся"),
}

@Serializable
data class DiscoveredEcu(
    val address: String,          // e.g. "7E8" / "7E0" / "700" / "740"
    val nameRu: String,
    val protocol: String,
    val status: EcuStatus,
    val partNumber: String? = null,
    val swVersion: String? = null,
    val calId: String? = null,
    val supportsStandardObd: Boolean = false,
    val notes: String = "",
)

data class DocumentedEcuTarget(
    val txAddress: String,
    val rxAddress: String,
    val nameRu: String,
    val requiredProtocol: DiagnosticProtocolKind,
    val standardObdFallback: Boolean = false,
)

/**
 * Documented, non-destructive ECU Discovery.
 *
 * Rules:
 * - Never brute-forces arbitrary CAN addresses.
 * - Uses standard OBD-II functional header inspection (`ATH1` + `0100`, always restoring `ATH0`)
 *   and documented module targets only when the adapter supports the required protocol.
 * - Undetected ECUs are never presented as installed equipment.
 */
object EcuDiscovery {

    suspend fun discover(
        elm: Elm327Client?,
        adapterCaps: AdapterCapabilities,
        documentedTargets: List<DocumentedEcuTarget>,
        calIds: List<String> = emptyList(),
        ecuName: String? = null,
        onProgress: (String) -> Unit = {},
    ): List<DiscoveredEcu> {
        if (elm == null || !adapterCaps.supportsStandardObd2) {
            return documentedTargets.map { target ->
                DiscoveredEcu(
                    address = target.rxAddress,
                    nameRu = target.nameRu,
                    protocol = target.requiredProtocol.labelRu,
                    status = EcuStatus.ADAPTER_UNSUPPORTED,
                    notes = "Адаптер или ЭБУ не поддерживают опрос блоков.",
                )
            }
        }

        val discovered = LinkedHashMap<String, DiscoveredEcu>()

        // 1. Functional OBD-II CAN ECU discovery via ATH1 + 0100 (safe standard broadcast)
        if (adapterCaps.isCanProtocol) {
            onProgress("Поиск OBD-II ЭБУ (ATH1 + 0100)…")
            try {
                elm.command("ATH1", 1500)
                val resp = elm.command("0100", 3000)
                if (resp is ElmResponse.Ok) {
                    for (line in resp.lines) {
                        val header = line.trim().uppercase().split(' ', '\t').firstOrNull().orEmpty()
                        if ((header.length == 3 || header.length == 8) && header.all { it in '0'..'9' || it in 'A'..'F' }) {
                            val name = obdCanAddressNameRu(header, ecuName)
                            discovered[header] = DiscoveredEcu(
                                address = header,
                                nameRu = name,
                                protocol = DiagnosticProtocolKind.STANDARD_OBD2.labelRu,
                                status = EcuStatus.DETECTED_RESPONDING,
                                calId = calIds.firstOrNull(),
                                supportsStandardObd = true,
                                notes = "Ответил на функциональный запрос 0100",
                            )
                        }
                    }
                }
            } finally {
                runCatching { elm.command("ATH0", 1500) }
            }
        } else {
            // Non-CAN (KWP2000 / J1850): functional OBD-II responded during init
            discovered["OBD"] = DiscoveredEcu(
                address = "OBD",
                nameRu = ecuName ?: "Блок управления двигателем (OBD-II)",
                protocol = adapterCaps.protocolText ?: DiagnosticProtocolKind.STANDARD_OBD2.labelRu,
                status = EcuStatus.DETECTED_RESPONDING,
                calId = calIds.firstOrNull(),
                supportsStandardObd = true,
                notes = "Определён по стандартному OBD-II ответу",
            )
        }

        // 2. Documented ECU targets for the active manufacturer module
        for (target in documentedTargets) {
            if (discovered.containsKey(target.rxAddress)) continue
            if (!adapterCaps.supportsProtocol(target.requiredProtocol)) {
                discovered[target.rxAddress] = DiscoveredEcu(
                    address = target.rxAddress,
                    nameRu = target.nameRu,
                    protocol = target.requiredProtocol.labelRu,
                    status = EcuStatus.ADAPTER_UNSUPPORTED,
                    notes = "Протокол ${target.requiredProtocol.labelRu} не поддерживается текущим адаптером/шиной.",
                )
                continue
            }

            if (target.requiredProtocol == DiagnosticProtocolKind.UDS_ISO14229 && adapterCaps.supportsUdsReadOnly) {
                onProgress("Проверка блока ${target.nameRu} (${target.txAddress})…")
                var probeStatus = EcuStatus.NOT_DETECTED
                var partNumber: String? = null
                var notes = "Не ответил на безопасный UDS-запрос идентификации (22 F1 87)"
                try {
                    elm.command("ATSH ${target.txAddress}", 1500)
                    elm.command("ATCRA ${target.rxAddress}", 1500)
                    val r = elm.command("22F187", 2000)
                    if (r is ElmResponse.Ok) {
                        val joined = r.lines.joinToString(" ").uppercase()
                        when {
                            joined.contains("62 F1 87") -> {
                                probeStatus = EcuStatus.DETECTED_RESPONDING
                                partNumber = extractAsciiTail(r.lines)
                                notes = "Подтверждён по UDS DID F187"
                            }
                            joined.contains("7F 22 31") || joined.contains("7F 22 22") || joined.contains("7F 22 33") -> {
                                probeStatus = EcuStatus.DETECTED_RESTRICTED
                                notes = "Блок ответил NRC (ограниченный доступ или DID не поддержан)"
                            }
                        }
                    }
                } finally {
                    runCatching {
                        elm.command("ATCRA", 1000)
                        elm.command("ATSH 7DF", 1000)
                    }
                }
                discovered[target.rxAddress] = DiscoveredEcu(
                    address = target.rxAddress,
                    nameRu = target.nameRu,
                    protocol = target.requiredProtocol.labelRu,
                    status = probeStatus,
                    partNumber = partNumber,
                    supportsStandardObd = target.standardObdFallback,
                    notes = notes,
                )
            } else {
                discovered[target.rxAddress] = DiscoveredEcu(
                    address = target.rxAddress,
                    nameRu = target.nameRu,
                    protocol = target.requiredProtocol.labelRu,
                    status = EcuStatus.NOT_CHECKED,
                    notes = "Требуется отдельный фирменный протокол или полевая проверка",
                )
            }
        }

        return discovered.values.toList()
    }

    private fun obdCanAddressNameRu(rxHex: String, ecuName: String?): String = when (rxHex) {
        "7E8" -> if (!ecuName.isNullOrBlank()) "Двигатель ($ecuName · 7E8)" else "Двигатель / ECM (7E8)"
        "7E9" -> "Трансмиссия / TCM (7E9)"
        "7EA" -> "Дополнительный контроллер (7EA)"
        "7EB" -> "Контроллер гибридной/энергосистемы (7EB)"
        else -> "ЭБУ OBD-II ($rxHex)"
    }

    private fun extractAsciiTail(lines: List<String>): String? {
        val bytes = lines.flatMap { line ->
            line.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull(16) }
        }
        val idx = bytes.indexOfFirst { it == 0x62 }
        if (idx < 0 || idx + 3 >= bytes.size) return null
        val ascii = bytes.drop(idx + 3)
            .filter { it in 0x20..0x7E }
            .map { it.toChar() }
            .joinToString("")
            .trim()
        return ascii.takeIf { it.length >= 4 }
    }
}
