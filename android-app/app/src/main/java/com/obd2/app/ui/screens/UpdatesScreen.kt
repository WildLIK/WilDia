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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2.app.core.updates.UpdateTrack
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.TextDim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UpdatesScreen(vm: ObdViewModel) {
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()
    val trackStates by vm.dbTrackStates.collectAsStateWithLifecycle()
    val history by vm.dbUpdateHistory.collectAsStateWithLifecycle()
    val message by vm.updateCheckMessage.collectAsStateWithLifecycle()
    val df = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(title = "Обновление диагностических баз") {
            Text(
                "Базы DTC, автомобилей и функций обновляются отдельно от APK и хранятся изолированно от пользовательской истории Room.",
                style = MaterialTheme.typography.bodySmall,
                color = TextDim,
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { vm.checkDatabaseUpdates(userInitiated = true) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Проверить обновления сейчас (GitHub Releases)")
            }
            message?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Green)
            }
        }

        Panel(title = "Установленные версии баз") {
            UpdateTrack.entries.forEach { track ->
                val st = trackStates.firstOrNull { it.track == track.name }
                Text(track.titleRu, style = MaterialTheme.typography.titleSmall)
                KeyValue("Текущая версия", st?.currentVersion ?: "встроенная")
                KeyValue("Статус", st?.lastStatusRu ?: "Встроенная версия")
                if (st?.canRollback == true) {
                    OutlinedButton(onClick = { vm.rollbackDatabaseTrack(track) }) {
                        Text("Откатить версию", color = Amber)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        Panel(title = "Параметры загрузки") {
            ToggleRow("Проверять обновления автоматически", settings.autoCheckUpdates) { v ->
                vm.updateSettings { it.setAutoCheckUpdates(v) }
            }
            ToggleRow("Обновлять базы автоматически", settings.autoUpdateDbs) { v ->
                vm.updateSettings { it.setAutoUpdateDbs(v) }
            }
            ToggleRow("Загружать только через Wi-Fi", settings.wifiOnlyUpdates) { v ->
                vm.updateSettings { it.setWifiOnlyUpdates(v) }
            }
        }

        Panel(title = "История обновлений (${history.size})") {
            if (history.isEmpty()) {
                Text("Обновления ещё не применялись.", style = MaterialTheme.typography.bodySmall, color = TextDim)
            } else {
                history.take(15).forEach { h ->
                    Text("${df.format(Date(h.timestampMs))} · ${h.track} · ${h.action}", style = MaterialTheme.typography.bodySmall)
                    Text("  ${h.fromVersion} → ${h.toVersion}: ${h.details}", style = MaterialTheme.typography.bodySmall, color = TextDim)
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
