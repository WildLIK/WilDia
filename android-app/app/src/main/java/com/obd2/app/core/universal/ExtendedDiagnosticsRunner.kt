package com.obd2.app.core.universal

import com.obd2.app.core.obd.StandardIdentifiers
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.repo.VehicleProfile
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Orchestrates Mode 2: Extended Diagnostics ("Расширенная диагностика"):
 * 1. Vehicle Identification (Mode 09 + catalog, never defaults to Škoda)
 * 2. Adapter capability verification
 * 3. Protocol verification
 * 4. Documented ECU Discovery (no blind CAN brute-force)
 * 5. Capability evaluation (strictly read-only confirmed functions for normal users)
 */
class ExtendedDiagnosticsRunner(
    private val repo: DiagnosticRepository,
    private val catalogManager: CompatibilityCatalogManager,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun run(
        onProgress: (step: String) -> Unit = {},
    ): ExtendedDiagnosticsReport {
        val now = System.currentTimeMillis()
        val warnings = ArrayList<String>()
        val catalog = catalogManager.load()
        val manualProfile: VehicleProfile? = runCatching {
            val profiles = repo.settings.profilesFlow.first()
            val id = repo.settings.activeProfileIdFlow.first()
            profiles.firstOrNull { it.id == id }
        }.getOrNull()

        val pipeline = repo.pipelineStatus.value
        val elm = repo.elm
        val obd = repo.obd
        val adapterCaps = AdapterCapabilityProbe.probe(
            adapterName = pipeline.adapterName,
            transportType = pipeline.transportType,
            demoMode = repo.isDemo,
            elmInfo = elm?.info?.value,
            ecuResponding = pipeline.ecuResponding,
        )

        if (obd == null || !repo.isConnectedNow) {
            val emptyVehicle = VehicleIdentifier.identify(null, null, manualProfile, catalog)
            return ExtendedDiagnosticsReport(
                vehicle = emptyVehicle,
                adapterCapabilities = adapterCaps,
                activeModuleName = ManufacturerModuleRegistry.forManufacturer(emptyVehicle.manufacturer)?.manufacturerName
                    ?: UniversalObd2Module.manufacturerName,
                discoveredEcus = emptyList(),
                evaluatedCapabilities = emptyList(),
                catalogVersion = catalog.catalogVersion,
                incomplete = true,
                warnings = listOf("Нет подключения к адаптеру или ЭБУ."),
                timestampMs = now,
            )
        }

        // 1. Identify vehicle (Mode 09)
        onProgress("1/5: Идентификация автомобиля (Mode 09)…")
        val identifiers: StandardIdentifiers = obd.readStandardIdentifiers(timeoutMs = 2500)
        val vehicle = VehicleIdentifier.identify(
            identifiers = identifiers,
            protocolText = elm?.info?.value?.protocol,
            manualProfile = manualProfile,
            catalog = catalog,
        )

        // 2. Check adapter & active module
        onProgress("2/5: Проверка возможностей адаптера и протоколов…")
        val brandModule = ManufacturerModuleRegistry.forManufacturer(vehicle.manufacturer)
        val activeModule = brandModule ?: UniversalObd2Module
        if (brandModule != null) {
            warnings += brandModule.adapterLimitationsRu
        }

        // 3. ECU Discovery (documented targets only)
        onProgress("3/5: Поиск электронных блоков (ECU Discovery)…")
        val targets = (UniversalObd2Module.documentedEcus + (brandModule?.documentedEcus ?: emptyList()))
            .distinctBy { it.rxAddress }
        val ecus = EcuDiscovery.discover(
            elm = elm,
            adapterCaps = adapterCaps,
            documentedTargets = targets,
            calIds = identifiers.calIds,
            ecuName = identifiers.ecuName,
            onProgress = onProgress,
        )

        // 4. Probe standard OBD feature confirmation (read-only)
        onProgress("4/5: Проверка подтверждённых возможностей…")
        val supPids = repo.supportedPids.value ?: obd.discoverSupportedPids().supportedPids
        val hasMode09 = identifiers.vin != null || identifiers.calIds.isNotEmpty() || identifiers.ecuName != null || identifiers.supportedInfoTypes.isNotEmpty()
        val hasFreezeFrame = repo.quickDiagResult.value?.freezeFrame?.let {
            it.triggerDtc != null || it.items.isNotEmpty()
        } ?: false
        val hasMode06 = repo.quickDiagResult.value?.mode06?.supportedMids?.isNotEmpty() ?: false

        // 5. Evaluate capabilities
        onProgress("5/5: Формирование списка доступных функций…")
        val specs = ManufacturerModuleRegistry.combinedSpecsFor(vehicle.manufacturer)
        val evaluated = CapabilityEvaluator.evaluateAll(
            specs = specs,
            vehicle = vehicle,
            adapterCaps = adapterCaps,
            ecus = ecus,
            supportedObdPids = supPids,
            hasFreezeFrameSupport = hasFreezeFrame,
            hasMode06Support = hasMode06,
            hasMode09Support = hasMode09,
        )

        val sid = repo.currentSessionIdFlow.value
        if (sid > 0) {
            val ecusJson = runCatching { json.encodeToString(ecus) }.getOrNull()
            repo.saveExtendedSessionArtifacts(
                sessionId = sid,
                freezeFrameJson = null,
                readinessJson = null,
                discoveredEcusJson = ecusJson,
            )
        }

        return ExtendedDiagnosticsReport(
            vehicle = vehicle,
            adapterCapabilities = adapterCaps,
            activeModuleName = activeModule.manufacturerName,
            discoveredEcus = ecus,
            evaluatedCapabilities = evaluated,
            catalogVersion = catalog.catalogVersion,
            incomplete = !repo.isConnectedNow,
            warnings = (warnings + adapterCaps.limitations).distinct(),
            timestampMs = now,
        )
    }
}
