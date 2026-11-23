package com.obd2.app.core.universal

import com.obd2.app.core.log.RawLog
import com.obd2.app.core.obd.StandardIdentifiers
import com.obd2.app.core.repo.VehicleProfile

enum class VehicleIdentityStatus(val labelRu: String) {
    AUTO_FULL("Определён достоверно"),
    AUTO_PARTIAL("Определён частично"),
    MANUAL("Выбран вручную"),
    UNKNOWN("Автомобиль не определён"),
}

data class VehicleIdentityResult(
    val status: VehicleIdentityStatus,
    val manufacturer: String? = null,
    val brand: String? = null,
    val model: String? = null,
    val generationOrYears: String? = null,
    val engine: String? = null,
    val vin: String? = null,
    val vinMasked: String? = null,
    val ecuName: String? = null,
    val calIds: List<String> = emptyList(),
    val cvns: List<String> = emptyList(),
    val detectedProtocols: List<String> = emptyList(),
    val matchedCatalogId: String? = null,
    val needsManualConfirmation: Boolean = false,
    val summaryRu: String = "Автомобиль не определён",
)

/**
 * Universal vehicle identifier using Mode 09 + protocol + offline CompatibilityCatalog.
 *
 * Rules:
 * - Never falls back to a default Škoda profile.
 * - Never treats VIN alone as sufficient proof of exact trim/engine unless catalog matches CalID/ECU Name too.
 * - Never sends proprietary brand commands just to guess a model.
 */
object VehicleIdentifier {

    fun identify(
        identifiers: StandardIdentifiers?,
        protocolText: String?,
        manualProfile: VehicleProfile?,
        catalog: CompatibilityCatalogData,
    ): VehicleIdentityResult {
        val vin = identifiers?.vin?.trim()?.takeIf { it.length == 17 }
        val vinMasked = vin?.let { RawLog.maskVin(it) }
        val wmi = vin?.take(3)?.uppercase()
        val calIds = identifiers?.calIds.orEmpty()
        val ecuName = identifiers?.ecuName?.trim()?.takeIf { it.isNotBlank() }
        val protocols = listOfNotNull(protocolText?.takeIf { it.isNotBlank() })

        if (vin == null && calIds.isEmpty() && ecuName == null) {
            if (manualProfile != null) {
                val title = listOf(manualProfile.maker, manualProfile.model, manualProfile.year)
                    .filter { it.isNotBlank() }.joinToString(" ").ifBlank { manualProfile.name }
                return VehicleIdentityResult(
                    status = VehicleIdentityStatus.MANUAL,
                    manufacturer = manualProfile.maker.ifBlank { null },
                    brand = manualProfile.maker.ifBlank { null },
                    model = manualProfile.model.ifBlank { null },
                    generationOrYears = manualProfile.year.ifBlank { null },
                    engine = manualProfile.engine.ifBlank { null },
                    detectedProtocols = protocols,
                    needsManualConfirmation = false,
                    summaryRu = "Выбран вручную: $title",
                )
            }
            return VehicleIdentityResult(
                status = VehicleIdentityStatus.UNKNOWN,
                detectedProtocols = protocols,
                needsManualConfirmation = false,
                summaryRu = "Автомобиль не определён",
            )
        }

        // 1. Score catalog matches (require both WMI/brand AND CalID or ECU Name for AUTO_FULL)
        var bestEntry: CatalogVehicleEntry? = null
        var bestScore = 0
        var hasEcuOrCalMatch = false
        for (entry in catalog.vehicles) {
            var score = 0
            var ecuOrCal = false
            if (wmi != null && entry.wmiPrefixes.any { wmi.startsWith(it, ignoreCase = true) }) {
                score += 2
            }
            if (calIds.any { c -> entry.calIdPrefixes.any { p -> c.contains(p, ignoreCase = true) } }) {
                score += 3
                ecuOrCal = true
            }
            if (ecuName != null && entry.ecuNameKeywords.any { k -> ecuName.contains(k, ignoreCase = true) }) {
                score += 3
                ecuOrCal = true
            }
            if (score > bestScore) {
                bestScore = score
                bestEntry = entry
                hasEcuOrCalMatch = ecuOrCal
            }
        }

        val wmiHit = wmi?.let { catalog.wmiBrands[it] }
        val makerFromWmi = wmiHit?.getOrNull(0)
        val brandFromWmi = wmiHit?.getOrNull(1)

        if (bestEntry != null && hasEcuOrCalMatch && bestScore >= 3) {
            val fullModel = "${bestEntry.brand} ${bestEntry.model} (${bestEntry.years})"
            return VehicleIdentityResult(
                status = VehicleIdentityStatus.AUTO_FULL,
                manufacturer = bestEntry.manufacturer,
                brand = bestEntry.brand,
                model = bestEntry.model,
                generationOrYears = bestEntry.years,
                engine = bestEntry.engine,
                vin = vin,
                vinMasked = vinMasked,
                ecuName = ecuName,
                calIds = calIds,
                cvns = identifiers?.cvns.orEmpty(),
                detectedProtocols = protocols,
                matchedCatalogId = bestEntry.id,
                needsManualConfirmation = false,
                summaryRu = "$fullModel · ${bestEntry.engine}",
            )
        }

        // Partial identification: VIN/WMI or CalID/ECU Name only, NOT enough for exact trim/engine
        val partialBrand = brandFromWmi ?: bestEntry?.brand
        val partialMaker = makerFromWmi ?: bestEntry?.manufacturer
        val summary = buildString {
            if (partialBrand != null) append("$partialBrand · ")
            when {
                vinMasked != null -> append("VIN $vinMasked")
                ecuName != null -> append("ЭБУ $ecuName")
                calIds.isNotEmpty() -> append("CalID ${calIds.first()}")
                else -> append("Частичные данные ЭБУ")
            }
            append(" (требует уточнения модели)")
        }
        return VehicleIdentityResult(
            status = VehicleIdentityStatus.AUTO_PARTIAL,
            manufacturer = partialMaker,
            brand = partialBrand,
            model = null,
            generationOrYears = null,
            engine = null,
            vin = vin,
            vinMasked = vinMasked,
            ecuName = ecuName,
            calIds = calIds,
            cvns = identifiers?.cvns.orEmpty(),
            detectedProtocols = protocols,
            matchedCatalogId = null,
            needsManualConfirmation = true,
            summaryRu = summary,
        )
    }
}
