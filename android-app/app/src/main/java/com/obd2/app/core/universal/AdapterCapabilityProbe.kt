package com.obd2.app.core.universal

import com.obd2.app.core.bt.BtTransportType
import com.obd2.app.core.elm327.Elm327Client

enum class AdapterTransportKind {
    BLE,
    CLASSIC_SPP,
    WIFI_TCP,
    USB_SERIAL,
    DEMO,
    UNKNOWN,
}

enum class DiagnosticProtocolKind(val labelRu: String) {
    STANDARD_OBD2("Стандартный OBD-II (ISO 15031 / J1979)"),
    CAN_ISO_TP("ISO 15765-4 CAN / ISO-TP"),
    UDS_ISO14229("UDS (ISO 14229 over CAN, read-only)"),
    KWP2000("KWP2000 (ISO 14230)"),
    J1850_PWM("SAE J1850 PWM"),
    VAG_TP20("VAG TP 2.0 (CAN)"),
}

data class AdapterCapabilities(
    val adapterName: String,
    val transportKind: AdapterTransportKind,
    val elmIdentity: String?,
    val protocolText: String?,
    val protocolNumber: Int?,
    val isCanProtocol: Boolean,
    val supportsStandardObd2: Boolean,
    val supportsCustomCanHeaders: Boolean,
    val supportsUdsReadOnly: Boolean,
    val supportsKwp2000: Boolean,
    val supportsVagTp20: Boolean,
    val supportsKLinePhysical: Boolean,
    val supportsSecureAccess: Boolean,
    val limitations: List<String>,
) {
    fun supportsProtocol(proto: DiagnosticProtocolKind): Boolean = when (proto) {
        DiagnosticProtocolKind.STANDARD_OBD2 -> supportsStandardObd2
        DiagnosticProtocolKind.CAN_ISO_TP -> isCanProtocol && supportsStandardObd2
        DiagnosticProtocolKind.UDS_ISO14229 -> supportsUdsReadOnly
        DiagnosticProtocolKind.KWP2000 -> supportsKwp2000
        DiagnosticProtocolKind.J1850_PWM -> (protocolText?.contains("J1850", ignoreCase = true) == true) || protocolNumber == 1 || protocolNumber == 2
        DiagnosticProtocolKind.VAG_TP20 -> supportsVagTp20
    }
}

/**
 * Evaluates actual adapter + protocol capabilities without assuming ELM327 can do
 * arbitrary CAN addressing, TP 2.0, K-Line, or protected ECU access.
 */
object AdapterCapabilityProbe {

    fun probe(
        adapterName: String?,
        transportType: BtTransportType?,
        demoMode: Boolean,
        elmInfo: Elm327Client.Elm327Info?,
        ecuResponding: Boolean,
    ): AdapterCapabilities {
        val kind = when {
            demoMode -> AdapterTransportKind.DEMO
            transportType == BtTransportType.BLE -> AdapterTransportKind.BLE
            transportType == BtTransportType.CLASSIC_SPP -> AdapterTransportKind.CLASSIC_SPP
            transportType == BtTransportType.WIFI_TCP -> AdapterTransportKind.WIFI_TCP
            transportType == BtTransportType.USB_SERIAL -> AdapterTransportKind.USB_SERIAL
            else -> AdapterTransportKind.UNKNOWN
        }
        val ident = elmInfo?.identity ?: elmInfo?.resetBanner
        val proto = elmInfo?.protocol
        val pNum = elmInfo?.protocolNumber
        val isCan = elmInfo?.isCanProtocol == true || (pNum != null && pNum in 6..9)
        val isKwp = (proto?.contains("14230", ignoreCase = true) == true) ||
            (proto?.contains("KWP", ignoreCase = true) == true) ||
            (pNum in listOf(4, 5))

        val elmDetected = !ident.isNullOrBlank() || demoMode
        val factualHeaders = demoMode || (elmInfo?.supportsHeaders ?: elmDetected)
        val factualCanAddressing = elmInfo?.supportsCanAddressing == true || demoMode

        val supportsStdObd = elmDetected && ecuResponding
        val supportsHeaders = elmDetected && isCan && factualHeaders
        // Read-only UDS over ISO-TP CAN is possible only when CAN is active and AT headers/addressing actually succeed.
        val supportsUdsRead = supportsHeaders && isCan && (factualCanAddressing || factualHeaders)
        // Generic ELM327 / clone / Wi-Fi / USB adapters do NOT guarantee VAG TP 2.0 channel framing without field verification
        val supportsTp20 = false
        val supportsKLine = isKwp && elmDetected

        val limits = ArrayList<String>()
        if (!elmDetected) {
            limits += "Адаптер ELM327 не инициализирован."
        }
        if (!ecuResponding) {
            limits += "ЭБУ автомобиля не отвечает на стандартные OBD-II запросы."
        }
        if (elmDetected && isCan && !factualHeaders) {
            limits += "Адаптер не подтвердил поддержку ATH1/ATH0 (ограниченный клон ELM327 — разделение ЭБУ и UDS могут быть недоступны)."
        }
        elmInfo?.capabilityNotes?.forEach { limits += it }
        if (kind == AdapterTransportKind.CLASSIC_SPP) {
            limits += "Bluetooth Classic SPP: совместимость зависит от профиля адаптера и магнитолы."
        }
        if (kind == AdapterTransportKind.WIFI_TCP) {
            limits += "Wi-Fi TCP: убедитесь, что телефон/магнитола подключены к Wi-Fi точке адаптера."
        }
        if (kind == AdapterTransportKind.USB_SERIAL) {
            limits += "USB Serial (OTG): поддерживаются только стандартные ELM327 UART-адаптеры (FTDI/CH340/CP210x/PL2303/CDC)."
        }
        if (!isCan) {
            limits += "Активный протокол не является CAN ISO 15765-4 — UDS и адресный CAN-опрос блоков недоступны."
        }
        limits += "VAG TP 2.0 не подтверждён на текущем ELM327-адаптере (требуется проверка на автомобиле)."
        limits += "Защищённый доступ (SecurityAccess / кодирование / адаптации) отключён."

        return AdapterCapabilities(
            adapterName = adapterName ?: "Неизвестный адаптер",
            transportKind = kind,
            elmIdentity = ident,
            protocolText = proto,
            protocolNumber = pNum,
            isCanProtocol = isCan,
            supportsStandardObd2 = supportsStdObd,
            supportsCustomCanHeaders = supportsHeaders,
            supportsUdsReadOnly = supportsUdsRead,
            supportsKwp2000 = supportsKLine,
            supportsVagTp20 = supportsTp20,
            supportsKLinePhysical = supportsKLine,
            supportsSecureAccess = false,
            limitations = limits.distinct(),
        )
    }
}
