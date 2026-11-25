package com.obd2.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Accent
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.TextDim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DtcOnlineSearchScreen(vm: ObdViewModel) {
    val ctx = LocalContext.current
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()
    val cards by vm.dtcSearchCards.collectAsStateWithLifecycle()
    val running by vm.dtcSearchRunning.collectAsStateWithLifecycle()
    val notice by vm.dtcSearchNotice.collectAsStateWithLifecycle()
    val readDtcs = vm.currentReadDtcRequests()
    val df = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(title = "Найти причины и решения в интернете") {
            Text(
                "Поиск выполняется только по кодам ошибок, реально считанным с автомобиля. VIN и личные данные не отправляются без согласия.",
                style = MaterialTheme.typography.bodySmall,
                color = TextDim,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Искать дополнительные сведения в интернете",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = settings.onlineDtcSearchEnabled,
                    onCheckedChange = { v -> vm.updateSettings { it.setOnlineDtcSearchEnabled(v) } },
                )
            }
            Spacer(Modifier.height(6.dp))
            if (readDtcs.isEmpty()) {
                Text(
                    "В текущем или открытом сеансе нет считанных ошибок (DTC). Сначала выполните чтение ошибок.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                )
            } else {
                Button(
                    onClick = { vm.searchAllSessionDtcsOnline() },
                    enabled = settings.onlineDtcSearchEnabled && !running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (running) "Выполняется поиск…" else "Найти причины для всех ошибок (${readDtcs.size})")
                }
                Spacer(Modifier.height(6.dp))
                readDtcs.forEach { (code, mode, legacy) ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val modeLabel = when (mode) {
                            3 -> "Сохр."
                            7 -> "Ожид."
                            10 -> "Пост."
                            else -> "Mode $mode"
                        }
                        Text(
                            "$code ($modeLabel)" + if (legacy) " · старая версия" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = {
                                vm.searchDtcOnline(code, mode, readDtcs.map { it.first }, legacy)
                            },
                            enabled = settings.onlineDtcSearchEnabled && !running,
                        ) { Text("Найти") }
                    }
                }
            }
            if (running) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            notice?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Amber)
            }
        }

        cards.forEach { card ->
            Panel(title = card.title) {
                Text(
                    "Источник данных: ${card.origin.labelRu} · " +
                        if (card.isStandardCode) "Стандартный код OBD-II" else "Фирменный код производителя",
                    color = Accent,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (card.isLegacyUnverified) {
                    Text(
                        "Внимание: ошибка относится к старому неподтверждённому сеансу.",
                        color = Amber,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                card.offlineNotice?.let {
                    Text(it, color = Amber, style = MaterialTheme.typography.bodySmall)
                }
                Text("Описание ошибки", style = MaterialTheme.typography.titleSmall, color = Green)
                Text(card.standardMeaning, style = MaterialTheme.typography.bodyMedium)
                card.manufacturerInfo?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("Данные производителя: $it", style = MaterialTheme.typography.bodySmall, color = TextDim)
                }
                card.contradictionNote?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Amber)
                }

                Spacer(Modifier.height(6.dp))
                Text("Возможные причины", style = MaterialTheme.typography.titleSmall, color = Accent)
                card.possibleCauses.forEach { cause ->
                    Text("• ${cause.text} [${cause.status.labelRu}]", style = MaterialTheme.typography.bodySmall)
                }

                if (card.recommendedChecks.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Что проверить", style = MaterialTheme.typography.titleSmall, color = Green)
                    card.recommendedChecks.forEach { chk ->
                        Text("• $chk", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (card.repairExperience.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Опыт ремонта (неподтверждённые обсуждения)", style = MaterialTheme.typography.titleSmall, color = Amber)
                    card.repairExperience.forEach { exp ->
                        Text("• ${exp.title}: ${exp.url}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (card.sources.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Источники", style = MaterialTheme.typography.titleSmall)
                    card.sources.forEach { src ->
                        Text("• [${src.tier.labelRu}] ${src.title} — ${src.url}", style = MaterialTheme.typography.bodySmall, color = TextDim)
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "Обновлено: ${df.format(Date(card.fetchedAtMs))}" + if (card.fromCache) " (из локального кэша)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDim,
                )
                Spacer(Modifier.height(6.dp))
                val links = card.browserLinks.ifEmpty {
                    listOf(com.obd2.app.core.search.DtcSearchSource("Открыть поисковый запрос в браузере", card.fallbackSearchUrl))
                }
                links.forEach { link ->
                    OutlinedButton(
                        onClick = {
                            val i = Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
                            ctx.startActivity(i)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(link.title)
                    }
                }
            }
        }
    }
}
