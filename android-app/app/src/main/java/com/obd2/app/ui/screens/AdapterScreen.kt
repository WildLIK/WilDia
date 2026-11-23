package com.obd2.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.obd2.app.core.transport.TransportState
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.TextDim
import kotlinx.coroutines.launch

@Composable
fun AdapterScreen(vm: ObdViewModel) {
    val uiScope = rememberCoroutineScope()
    val elmInfo by vm.elmInfo.collectAsState()
    val stats by vm.elmStats.collectAsState()
    val connection by vm.connection.collectAsState()
    var dbMeta by remember { mutableStateOf<com.obd2.app.core.db.ObdLookupDb.DbMeta?>(null) }
    var integrity by remember { mutableStateOf<String?>(null) }
    var showFullLog by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        dbMeta = vm.repo.lookupDb.meta()
    }

    val gatt = (vm.repo.transport as? com.obd2.app.core.ble.BleTransport)?.gattInfo
    val logLines = vm.container.log.snapshot().takeLast(if (showFullLog) 600 else 80)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(title = "ELM327") {
            KeyValue("Идентификатор", elmInfo?.identity ?: "—")
            KeyValue("Баннер ATZ", elmInfo?.resetBanner ?: "—")
            KeyValue("Протокол", elmInfo?.protocol ?: "—")
            KeyValue("Номер протокола", elmInfo?.protocolNumber?.toString() ?: "—")
            KeyValue("Напряжение", elmInfo?.voltage?.let { "%.1f В".format(it) } ?: "—")
            KeyValue("Echo/Headers", "${elmInfo?.echo}/${elmInfo?.headers}")
        }

        Panel(title = "Статистика запросов") {
            stats?.let { s ->
                KeyValue("Отправлено", s.sent.toString())
                KeyValue("Успешно", s.ok.toString())
                KeyValue("Таймауты", s.timeouts.toString())
                KeyValue("Ошибки адаптера", s.adapterErrors.toString())
                KeyValue("Время ответа, среднее", "${s.avgMs} мс")
                KeyValue("мин/макс", "${if (s.minMs == Long.MAX_VALUE) 0 else s.minMs} / ${s.maxMs} мс")
            } ?: Text("Нет данных — выполните опрос или полную проверку", color = TextDim)
        }

        Panel(title = "Состояние транспорта") {
            val t = vm.repo.transport
            KeyValue("Тип", t?.let { it::class.simpleName } ?: "—")
            KeyValue("Состояние", t?.state?.value?.toString() ?: "—")
            (t as? com.obd2.app.core.ble.BleTransport)?.let { ble ->
                KeyValue("MTU", ble.mtu.toString())
                ble.lastError?.let { KeyValue("Последняя ошибка", it) }
            }
        }

        Panel(title = "GATT-сервисы и характеристики") {
            if (gatt == null) {
                Text(
                    if (connection is com.obd2.app.core.repo.DiagnosticRepository.ConnectionState.Connected)
                        "Демо-режим: GATT недоступен"
                    else "Требуется BLE-подключение",
                    color = TextDim,
                )
            } else {
                Text("MTU: ${gatt.mtu}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Выбраны: write=${gatt.selectedWriteUuid}, notify=${gatt.selectedNotifyUuid}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Green,
                )
                Spacer(Modifier.height(8.dp))
                gatt.services.forEach { svc ->
                    Text(svc.uuid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    svc.characteristics.forEach { ch ->
                        Text(
                            "  ${ch.uuid}  ${com.obd2.app.core.ble.GattPropertyNames.decode(ch.properties)}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextDim,
                        )
                    }
                }
            }
        }

        Panel(title = "Журнал обмена (последние записи)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showFullLog = !showFullLog }) {
                    Text(if (showFullLog) "Короткий журнал" else "Показать больше")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                logLines.joinToString("\n") { e ->
                    when (e.dir) {
                        com.obd2.app.core.log.RawLog.Dir.TX -> ">> ${e.text}"
                        com.obd2.app.core.log.RawLog.Dir.RX -> "<< ${e.text} (${e.durationMs ?: 0} мс)"
                        com.obd2.app.core.log.RawLog.Dir.INFO -> "-- ${e.text}"
                        com.obd2.app.core.log.RawLog.Dir.ERROR -> "!! ${e.text}"
                    }
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Panel(title = "Офлайн-база данных") {
            dbMeta?.let { m ->
                KeyValue("Версия", m.version)
                KeyValue("Дата сборки", m.builtAt)
                KeyValue("PID", m.pids.toString())
                KeyValue("DTC всего", m.dtcTotal.toString())
                KeyValue("из них общие", m.dtcGeneric.toString())
                KeyValue("производители", m.dtcManufacturer.toString())
                KeyValue("марок", m.makers.toString())
                KeyValue("RU-переводов", m.ruTranslations.toString())
                Text("Источники: ${m.sources}", color = TextDim, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    uiScope.launch { integrity = vm.repo.lookupDb.integrityCheck() }
                }) { Text("Проверить целостность") }
            }
            integrity?.let {
                Text(
                    "PRAGMA integrity_check: $it",
                    color = if (it == "ok") Green else MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
