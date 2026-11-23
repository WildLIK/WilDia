package com.obd2.app.core.diag

/**
 * Cause analysis from recorded events (§12). Every conclusion distinguishes a
 * CONFIRMED fact (recorded in events) from a HYPOTHESIS (likely explanation).
 */
class DiagAnalyzer {

    fun warningsFromPhone(phone: PhoneInfo.PhoneFacts): List<String> = buildList {
        if (!phone.appForeground) add("Приложение в фоне: Android может ограничивать BLE-операции")
        if (phone.powerSaving["globalPowerSaveMode"] == "true") {
            add("Включён системный режим энергосбережения — BLE может работать нестабильно")
        }
        if (phone.bleCapabilities["offloadedFiltering"] == false) {
            add("Фильтрация скана не offload на контроллер — возможны пропуски рекламы")
        }
    }

    fun analyze(session: DiagSession, stages: List<DiagStageState>): List<Conclusion> {
        val out = ArrayList<Conclusion>()
        val confirmed: (String) -> Unit = { out.add(Conclusion(true, it)) }
        val hypothesis: (String) -> Unit = { out.add(Conclusion(false, it)) }

        val permStage = stages.firstOrNull { it.id == "perm" }
        if (permStage?.state == StageState.FAIL) {
            confirmed("Bluetooth-разрешения отсутствуют: ${permStage.resultText}")
        }

        val scanStage = stages.firstOrNull { it.id == "scan" }
        val summaries = session.scanSummaries()
        when {
            scanStage?.state == StageState.FAIL && summaries.isEmpty() ->
                confirmed("BLE-устройства не обнаружены за ${session.scanInfo.scanSeconds} с")
            session.scanInfo.vlinkSeen ->
                confirmed("IOS-Vlink/Android-Vlink виден в эфире (${summaries.filter { it.name.uppercase().contains("VLINK") }.joinToString { "${it.name}: ${it.count} обн., RSSI ${it.rssiMin}..${it.rssiMax}" }})")
            scanStage != null && summaries.isNotEmpty() ->
                confirmed("Скан работает (${summaries.size} устройств), но Vlink не обнаружен")
        }
        session.scanInfo.failureCode?.let {
            hypothesis("Сканирование завершилось ошибкой стека (${it}) — возможна перегрузка BLE-стека или другой активный скан")
        }

        val connectStage = stages.firstOrNull { it.id == "connect" }
        val s = session.stats
        when {
            s.failures > 0 -> {
                val lastErr = session.gattEvents.lastOrNull { it.severity == DiagSeverity.ERROR }
                confirmed(
                    "GATT-подключение завершилось ошибкой " +
                        "(попыток ${s.attempts}, неуспешных ${s.failures})" +
                        (lastErr?.let { ": ${it.op} ${it.resultCode ?: ""} ${it.details ?: ""}" } ?: "")
                )
            }
            s.successes > 0 -> confirmed(
                "GATT-подключение установлено: MTU=${s.negotiatedMtu ?: "недоступно"}, " +
                    "время подключения ${s.minAvgMax(s.connectDurationsMs)}, RSSI=${s.rssi ?: "недоступно"}"
            )
        }
        if (connectStage?.state == StageState.SKIP && connectStage.resultText.contains("переиспользуется")) {
            confirmed("Использовано существующее подключение приложения (параллельные соединения не создавались)")
        }

        val cccd = session.gattEvents.lastOrNull { it.op.startsWith("gatt.cccd") }
        when {
            cccd?.op == "gatt.cccdRejected" -> confirmed("CCCD не принят адаптером (${cccd.resultCode}) — уведомления не работают")
            cccd?.op == "gatt.cccdSubmitFail" -> confirmed("CCCD-запись не отправлена: ${cccd.details}")
            cccd?.op == "gatt.cccdSubscribed" -> confirmed("Подписка на уведомления активна (CCCD записан, ${cccd.durationMs} мс)")
        }

        val charPickFail = session.gattEvents.lastOrNull { it.op == "gatt.charsPicked" } == null &&
            session.gattEvents.any { it.op == "gatt.onServicesDiscovered" && it.resultCode == "GATT_SUCCESS" }
        if (charPickFail && s.successes == 0) {
            confirmed("Сервисы обнаружены, но подходящие характеристики записи/уведомлений не найдены")
        }

        val classic = session.classicSummaries()
        if (classic.isNotEmpty()) {
            val vlinkClassic = classic.filter { it.name.uppercase().contains("VLINK") || it.name.uppercase().contains("VGATE") }
            if (vlinkClassic.isNotEmpty()) {
                confirmed("Bluetooth Classic обнаружил: ${vlinkClassic.joinToString { "${it.name} (${it.sppStatus})" }}")
            } else {
                confirmed("Bluetooth Classic обнаружил ${classic.size} устройств")
            }
        }
        session.gattEvents.lastOrNull { it.op == "classic.sdp" }?.let { sdp ->
            confirmed("Проверка SDP Classic: ${sdp.resultCode} (${sdp.details ?: ""})")
        }
        session.gattEvents.lastOrNull { it.op == "classic.connected" }?.let { cc ->
            confirmed("RFCOMM/SPP подключён: ${cc.details ?: cc.resultCode}")
        }

        val elmCmds = session.elmCommands
        when {
            elmCmds.isEmpty() && s.successes > 0 && stages.any { it.id == "elm" } ->
                confirmed("BLE подключён, но команды ELM327 не отправлялись (этап прерван)")
            elmCmds.isNotEmpty() && elmCmds.all { it.errorKind?.contains("таймаут") == true } ->
                confirmed("BLE подключён, но ELM327 не отвечает (все команды — таймаут, признак '>' не получен)")
            elmCmds.isNotEmpty() && elmCmds.any { it.resultCode == "OK" || it.resultCode == "ADAPTER_OK_ONLY" } -> {
                confirmed("ELM327 отвечает (первый ответ адаптера: ${session.stats.timeToFirstAdapterResponseMs ?: "недоступно"} мс)")
                val unable = elmCmds.any { it.resultCode == "ADAPTER_UNABLE_TO_CONNECT" }
                if (unable) confirmed("ELM327 отвечает, но автомобильный ЭБУ недоступен (UNABLE TO CONNECT) — зажигание/протокол/машина")
                val info = session.journal.allInMemory().lastOrNull { it.op == "session.start" }
                if (info != null) { /* placeholder for future protocol summary */ }
                session.elmCommands.firstOrNull { it.cmd == "ATDP" }?.let { dp ->
                    if (dp.resultCode == "OK") confirmed("Протокол автомобиля определён (ответ ATDP записан)")
                }
                val obd = session.obdResults
                if (obd.isNotEmpty()) {
                    val notSupported = obd.count { it.validity == ObdValidity.NOT_SUPPORTED }
                    val ok = obd.count { it.validity == ObdValidity.OK }
                    if (notSupported > 0) confirmed("Часть PID не поддерживается ЭБУ ($notSupported из ${obd.size})")
                    if (ok > 0) confirmed("Параметры Mode 01 успешно читаются ($ok из ${obd.size})")
                }
            }
        }

        if (out.isEmpty()) {
            hypothesis("Причин не выявлено: выполненных этапов недостаточно для вывода — запустите сценарий D")
        }
        return out
    }
}
