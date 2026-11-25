package com.obd2.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2.app.ui.catalog.PidCatalog
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.Red
import com.obd2.app.ui.theme.TextDim

enum class PidStatus { SUPPORTED, UNSUPPORTED, UNKNOWN }
private enum class PidFilter(val label: String) { AVAILABLE("Доступные"), ALL("Все"), FAVORITES("Избранное") }

/**
 * «Параметры автомобиля» — driver-oriented catalog of the standard PID list:
 * semantic categories, search, availability filters, plain-language
 * explanations and expandable technical details. Requests are sent ONLY when
 * the driver taps a parameter; nothing is polled automatically, unknown PIDs
 * are never requested.
 */
@Composable
fun AllPidsScreen(vm: ObdViewModel) {
    val registry by vm.pidRegistry.collectAsStateWithLifecycle()
    val support by vm.support.collectAsStateWithLifecycle()
    val repoSupported by vm.supportedPids.collectAsStateWithLifecycle()
    val rawRead by vm.rawRead.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()

    val supportedSet: Set<Int>? = support?.supportedPids?.toSet() ?: repoSupported

    var category by remember { mutableStateOf(PidCatalog.CAT_ENGINE) }
    var filter by remember { mutableStateOf(PidFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var expandedPid by remember { mutableStateOf<String?>(null) }

    val connected = connection is com.obd2.app.core.repo.DiagnosticRepository.ConnectionState.Connected
    val favorites = settings.pidFavorites

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.discoverSupport() }, enabled = connected) {
                Text("Проверить поддержку")
            }
            Text(
                when {
                    supportedSet == null -> "поддержка ещё не проверялась"
                    else -> "автомобиль поддерживает ${supportedSet.size} параметров"
                },
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!connected) {
            Text(
                "Статусы появятся после подключения адаптера (или демо-режима). Чтение параметра — по нажатию на него.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Поиск параметра") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PidFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label) },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PidCatalog.CATEGORIES.forEach { cat ->
                FilterChip(
                    selected = category == cat,
                    onClick = { category = cat },
                    label = { Text(cat) },
                )
            }
        }

        val mode09 = remember(registry) { registry.filter { it.mode == 9 } }
        val filtered = remember(registry, category, filter, supportedSet, favorites, query) {
            val q = query.trim()
            registry.asSequence()
                .filter { it.mode == 1 && PidCatalog.categoryFor(it) == category }
                .filter { def ->
                    when (filter) {
                        PidFilter.AVAILABLE -> supportedSet?.contains(def.pid) == true
                        PidFilter.FAVORITES -> favorites.contains(PidCatalog.hex(def))
                        PidFilter.ALL -> true
                    }
                }
                .filter { def ->
                    if (q.isEmpty()) true
                    else (def.nameRu ?: def.nameEn).contains(q, ignoreCase = true) ||
                        def.nameEn.contains(q, ignoreCase = true) ||
                        "%02X".format(def.pid).contains(q, ignoreCase = true)
                }
                .toList()
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        when {
                            filter == PidFilter.AVAILABLE && supportedSet == null ->
                                "Сначала проверьте поддержку: ЭБУ сам сообщит, какие параметры доступны."
                            filter == PidFilter.FAVORITES && favorites.isEmpty() ->
                                "В избранном пока пусто — добавляйте параметры звёздочкой."
                            else -> "В этой категории ничего не найдено."
                        },
                        color = TextDim,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            items(filtered, key = { "${it.mode}-${it.pid}" }) { def ->
                val status = when {
                    supportedSet == null -> PidStatus.UNKNOWN
                    supportedSet.contains(def.pid) -> PidStatus.SUPPORTED
                    else -> PidStatus.UNSUPPORTED
                }
                ParamRow(
                    def = def,
                    status = status,
                    favorite = favorites.contains(PidCatalog.hex(def)),
                    expanded = expandedPid == "${def.mode}-${def.pid}",
                    onToggleExpand = {
                        expandedPid =
                            if (expandedPid == "${def.mode}-${def.pid}") null
                            else "${def.mode}-${def.pid}"
                    },
                    onRead = { vm.manualRead("01 %02X".format(def.pid)) },
                    onToggleFavorite = { vm.togglePidFavorite(PidCatalog.hex(def), !favorites.contains(PidCatalog.hex(def))) },
                    lastRaw = rawRead?.takeIf { it.ok && it.command == "01 %02X".format(def.pid) }?.lines,
                )
            }
            // Mode 09 identification — technical group at the bottom of «Прочее»
            if (category == PidCatalog.CAT_OTHER && query.isBlank() && filter == PidFilter.ALL) {
                item {
                    Text(
                        "Идентификация (Mode 09) — VIN, калибровки, имя ЭБУ:",
                        color = TextDim,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(mode09, key = { "m9-${it.pid}" }) { def ->
                    PidRowSimple(def) { vm.manualRead("09 %02X".format(def.pid)) }
                }
            }
        }

        rawRead?.let { row ->
            Text(
                "Ответ на ${row.command}: ${row.lines.take(120)} (${row.durationMs} мс)",
                color = if (row.ok) Green else Red,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ParamRow(
    def: com.obd2.app.core.obd.PidDefinition,
    status: PidStatus,
    favorite: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onRead: () -> Unit,
    onToggleFavorite: () -> Unit,
    lastRaw: String?,
) {
    Card(
        onClick = onRead,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    PidCatalog.title(def),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(
                    when (status) {
                        PidStatus.SUPPORTED -> "поддерживается"
                        PidStatus.UNSUPPORTED -> "не поддерживается"
                        PidStatus.UNKNOWN -> "не проверено"
                    },
                    when (status) {
                        PidStatus.SUPPORTED -> Green
                        PidStatus.UNSUPPORTED -> Red
                        PidStatus.UNKNOWN -> TextDim
                    },
                )
            }
            Text(
                PidCatalog.explanation(def),
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextButton(onClick = onToggleExpand) {
                    Text(if (expanded) "Скрыть подробности" else "Технические подробности")
                }
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = onToggleFavorite) {
                    Text(if (favorite) "★" else "☆", color = Amber)
                }
            }
            if (expanded) {
                Text(
                    PidCatalog.technicalDetails(def, lastRaw),
                    color = TextDim,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        }
    }
}

/** Compact row for Mode 09 technical items (hidden behind «Прочее»). */
@Composable
private fun PidRowSimple(def: com.obd2.app.core.obd.PidDefinition, onRead: () -> Unit) {
    Card(
        onClick = onRead,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                def.displayName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text("09 %02X".format(def.pid), color = TextDim, style = MaterialTheme.typography.labelSmall)
        }
    }
}
