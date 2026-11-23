package com.obd2.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavHostController
import com.obd2.app.Screen
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.obd2.app.core.db.SessionEntity
import com.obd2.app.core.db.SessionStatus
import com.obd2.app.core.db.VehicleIdentitySource
import com.obd2.app.core.obd.DtcModes
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.Primary
import com.obd2.app.ui.theme.Red
import com.obd2.app.ui.theme.TextDim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DtcCategoryTab(val labelRu: String) {
    ALL("Все"),
    STORED("Сохранённые (03)"),
    PENDING("Ожидающие (07)"),
    PERMANENT("Постоянные (0A)"),
}

@Composable
fun DiagnosticsScreen(vm: ObdViewModel, nav: NavHostController? = null) {
    val connection by vm.connection.collectAsState()
    val pipeline by vm.pipelineStatus.collectAsState()
    val currentDtcState by vm.currentDtcState.collectAsState()
    val dtcScanRuns by vm.dtcScanRuns.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val reading by vm.readingDtcs.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val currentSessionId by vm.currentSessionIdFlow.collectAsState()
    val openedSaved by vm.openedSavedSession.collectAsState()
    val lastCrash by vm.lastCrash.collectAsState()

    var includeIdentifiers by remember { mutableStateOf(false) }
    var exportFeedback by remember { mutableStateOf<String?>(null) }
    var exportIsError by remember { mutableStateOf(false) }
    var showDemoInHistory by remember { mutableStateOf(false) }
    var sessionPendingDelete by remember { mutableStateOf<SessionEntity?>(null) }

    val connected = connection is DiagnosticRepository.ConnectionState.Connected && pipeline.ecuResponding

    sessionPendingDelete?.let { target ->
        val df = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        AlertDialog(
            onDismissRequest = { sessionPendingDelete = null },
            title = { Text("Удалить сеанс диагностики?") },
            text = {
                Text(
                    "Сеанс от ${df.format(Date(target.startedAtMs))} (${target.displayVehicleTitle()}, " +
                        "ошибок: ${target.totalDtcCount}) будет удалён без возможности восстановления.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteSavedSession(target.id)
                        sessionPendingDelete = null
                    },
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { sessionPendingDelete = null }) { Text("Отмена") }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        lastCrash?.let { crash ->
            CrashReportCard(crash, onClear = { vm.acknowledgeCrash() })
        }

        // =====================================================================
        // 1. ТЕКУЩАЯ ДИАГНОСТИКА
        // =====================================================================
        Panel(title = "Чтение кодов ошибок") {
            Text(
                "Текущая диагностика (только чтение: Mode 03 сохранённые, Mode 07 ожидающие, Mode 0A постоянные). " +
                    "Результаты каждого режима автоматически сохраняются сразу после чтения.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    exportFeedback = null
                    vm.readDtcs()
                },
                enabled = (connection is DiagnosticRepository.ConnectionState.Connected) && !reading,
            ) {
                if (reading) {
                    CircularProgressIndicator(Modifier.height(18.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                }
                Text(if (reading) "Чтение…" else "Прочитать ошибки")
            }
            if (connection !is DiagnosticRepository.ConnectionState.Connected) {
                Spacer(Modifier.height(6.dp))
                Text("Сначала подключитесь к адаптеру (или используйте демо-режим).", color = TextDim)
            } else if (!pipeline.ecuResponding) {
                Spacer(Modifier.height(6.dp))
                Text(
                    pipeline.lastNote ?: "Адаптер подключён, но ЭБУ не отвечает. Проверьте зажигание.",
                    color = Amber,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Panel(title = "Текущая диагностика") {
            when (val st = currentDtcState) {
                is DiagnosticRepository.CurrentDtcState.NotReadYet -> {
                    StatusChip("ДАННЫЕ ЕЩЁ НЕ СЧИТАНЫ", TextDim)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "В текущем подключении чтение ошибок ещё не выполнялось. Старые результаты из прошлых сеансов не подмешиваются.",
                        color = TextDim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is DiagnosticRepository.CurrentDtcState.Reading -> {
                    StatusChip("ЧТЕНИЕ ОШИБОК…", Amber)
                    Spacer(Modifier.height(6.dp))
                    Text(st.stageLabelRu, color = Amber, style = MaterialTheme.typography.bodyMedium)
                    st.partial?.let { partial ->
                        Spacer(Modifier.height(8.dp))
                        DtcResultsBlock(
                            stored = partial.stored,
                            pending = partial.pending,
                            permanent = partial.permanent,
                            errors = partial.errors,
                            durationMs = partial.durationMs,
                        )
                    }
                }

                is DiagnosticRepository.CurrentDtcState.Incomplete -> {
                    val chipText = if (st.partial?.status == DiagnosticRepository.DtcScanStatus.DTC_READ_FAILED) {
                        "ОШИБКА ЧТЕНИЯ / НЕТ ОТВЕТА ЭБУ"
                    } else {
                        "ПРОВЕРКА НЕ ЗАВЕРШЕНА"
                    }
                    StatusChip(chipText, Red)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Чтение не удалось: ${st.reason}. Отсутствие ответа ЭБУ или таймаут не означают, что ошибок нет.",
                        color = Red,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    st.partial?.let { partial ->
                        Spacer(Modifier.height(8.dp))
                        DtcResultsBlock(
                            stored = partial.stored,
                            pending = partial.pending,
                            permanent = partial.permanent,
                            errors = partial.errors,
                            durationMs = partial.durationMs,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (nav != null && vm.currentReadDtcRequests().isNotEmpty()) {
                            OutlinedButton(
                                onClick = { nav.navigate(Screen.DTC_SEARCH.route) { launchSingleTop = true } },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Поиск причин в интернете") }
                            Spacer(Modifier.height(6.dp))
                        }
                        ExportActionsBar(
                            sessionId = partial.sessionId.takeIf { it > 0 },
                            includeIdentifiers = includeIdentifiers,
                            onToggleIdentifiers = { includeIdentifiers = it },
                            vm = vm,
                            onFeedback = { msg, err -> exportFeedback = msg; exportIsError = err },
                        )
                    }
                }

                is DiagnosticRepository.CurrentDtcState.Completed -> {
                    val res = st.result
                    val total = res.stored.size + res.pending.size + res.permanent.size
                    if (total == 0) {
                        StatusChip("ОШИБОК НЕ ОБНАРУЖЕНО (ПОДТВЕРЖДЕНО ЭБУ)", Green)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "ЭБУ ответил на диагностический запрос; кодов неисправностей (Mode 03/07/0A) не зафиксировано.",
                            color = Green,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        StatusChip("ОБНАРУЖЕНО ОШИБОК: $total", Red)
                    }
                    Spacer(Modifier.height(8.dp))
                    DtcResultsBlock(
                        stored = res.stored,
                        pending = res.pending,
                        permanent = res.permanent,
                        errors = res.errors,
                        durationMs = res.durationMs,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (nav != null && total > 0) {
                        OutlinedButton(
                            onClick = { nav.navigate(Screen.DTC_SEARCH.route) { launchSingleTop = true } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Поиск причин в интернете") }
                        Spacer(Modifier.height(6.dp))
                    }
                    ExportActionsBar(
                        sessionId = res.sessionId.takeIf { it > 0 },
                        includeIdentifiers = includeIdentifiers,
                        onToggleIdentifiers = { includeIdentifiers = it },
                        vm = vm,
                        onFeedback = { msg, err -> exportFeedback = msg; exportIsError = err },
                    )
                }
            }

            exportFeedback?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(
                    msg,
                    color = if (exportIsError) Red else Green,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (dtcScanRuns.isNotEmpty()) {
            Panel(title = "Журнал прогонов Mode 03/07/0A (${dtcScanRuns.size})") {
                dtcScanRuns.take(8).forEach { run ->
                    val tsStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(run.timestampMs))
                    val allCodes = (run.storedCodes + run.pendingCodes + run.permanentCodes).distinct()
                    val codeText = if (allCodes.isEmpty()) "кодов нет" else allCodes.joinToString(", ")
                    val color = when (run.status) {
                        DiagnosticRepository.DtcScanStatus.DTC_FOUND -> Red
                        DiagnosticRepository.DtcScanStatus.DTC_NONE_CONFIRMED -> Green
                        DiagnosticRepository.DtcScanStatus.DTC_READ_FAILED -> Amber
                        DiagnosticRepository.DtcScanStatus.DTC_NOT_SCANNED -> TextDim
                    }
                    Text(
                        "$tsStr — ${run.status.labelRu}: $codeText",
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        val txt = vm.exportDtcAuditLogSafe(includeSensitive = includeIdentifiers)
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(txt))
                        exportFeedback = "Журнал команд Mode 03/07/0A скопирован в буфер обмена"
                        exportIsError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Скопировать журнал команд")
                }
            }
        }

        // =====================================================================
        // Открытый сохранённый сеанс (просмотр без подключения к автомобилю)
        // =====================================================================
        openedSaved?.let { details ->
            SavedSessionDetailPanel(
                details = details,
                includeIdentifiers = includeIdentifiers,
                onToggleIdentifiers = { includeIdentifiers = it },
                vm = vm,
                onClose = { vm.closeSavedSession() },
                onFeedback = { msg, err -> exportFeedback = msg; exportIsError = err },
            )
        }

        // =====================================================================
        // 2. ПОСЛЕДНИЙ СОХРАНЁННЫЙ СЕАНС (только реальные сеансы, без демо)
        // =====================================================================
        val latestSaved = sessions.firstOrNull { !it.demoMode }
        Panel(title = "Последний сохранённый сеанс") {
            if (latestSaved == null) {
                Text(
                    "Сохранённых сеансов пока нет. Результаты появятся сразу после первого подключения или чтения ошибок.",
                    color = TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val df = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
                KeyValue("Дата и время", df.format(Date(latestSaved.startedAtMs)))
                KeyValue("Автомобиль", latestSaved.displayVehicleTitle())
                KeyValue(
                    "Источник авто",
                    VehicleIdentitySource.labelRu(latestSaved.vehicleIdentitySource),
                )
                KeyValue(
                    "Количество ошибок",
                    "сохранённых ${latestSaved.storedCount}, ожидающих ${latestSaved.pendingCount}, постоянных ${latestSaved.permanentCount}",
                )
                KeyValue(
                    "Статус проверки",
                    SessionStatus.labelRu(latestSaved.status, latestSaved.dtcReadCompleted),
                )
                latestSaved.endReason?.takeIf { it.isNotBlank() }?.let {
                    KeyValue("Примечание", it)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.openSavedSession(latestSaved.id) }) {
                        Text("Открыть")
                    }
                    OutlinedButton(
                        onClick = {
                            vm.exportSessionReport(latestSaved.id, "PDF", includeIdentifiers, share = false) { res ->
                                res.fold(
                                    onSuccess = { exportFeedback = "PDF сохранён: ${it.absolutePath}"; exportIsError = false },
                                    onFailure = { exportFeedback = "Ошибка PDF: ${it.message}"; exportIsError = true },
                                )
                            }
                        },
                    ) { Text("Экспорт PDF") }
                    OutlinedButton(
                        onClick = {
                            vm.exportSessionReport(latestSaved.id, "PDF", includeIdentifiers, share = true) { res ->
                                res.fold(
                                    onSuccess = { exportFeedback = "Отправка отчёта: ${it.name}"; exportIsError = false },
                                    onFailure = { exportFeedback = "Ошибка отправки: ${it.message}"; exportIsError = true },
                                )
                            }
                        },
                    ) { Text("Поделиться") }
                }
            }
        }

        // =====================================================================
        // 3. ИСТОРИЯ ДИАГНОСТИКИ (реальные сеансы отделены от DEMO)
        // =====================================================================
        val realSessions = sessions.filter { !it.demoMode }
        val filteredSessions = if (showDemoInHistory) sessions else realSessions
        Panel(title = "История диагностики (${realSessions.size})") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !showDemoInHistory,
                    onClick = { showDemoInHistory = false },
                    label = { Text("Реальные сеансы") },
                )
                FilterChip(
                    selected = showDemoInHistory,
                    onClick = { showDemoInHistory = true },
                    label = { Text("Включая демо") },
                )
            }
            if (filteredSessions.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Список сеансов пуст.", color = TextDim)
            } else {
                val df = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    filteredSessions.take(30).forEach { s ->
                        SessionHistoryRow(
                            s = s,
                            dateText = df.format(Date(s.startedAtMs)),
                            isCurrent = s.id == currentSessionId,
                            onOpen = { vm.openSavedSession(s.id) },
                            onExportPdf = {
                                vm.exportSessionReport(s.id, "PDF", includeIdentifiers, share = false) { res ->
                                    res.fold(
                                        onSuccess = { exportFeedback = "PDF сохранён: ${it.absolutePath}"; exportIsError = false },
                                        onFailure = { exportFeedback = "Ошибка PDF: ${it.message}"; exportIsError = true },
                                    )
                                }
                            },
                            onExportZip = {
                                vm.exportSessionReport(s.id, "ZIP", includeIdentifiers, share = false) { res ->
                                    res.fold(
                                        onSuccess = { exportFeedback = "ZIP сохранён: ${it.absolutePath}"; exportIsError = false },
                                        onFailure = { exportFeedback = "Ошибка ZIP: ${it.message}"; exportIsError = true },
                                    )
                                }
                            },
                            onShare = {
                                vm.exportSessionReport(s.id, "PDF", includeIdentifiers, share = true) { res ->
                                    res.fold(
                                        onSuccess = { exportFeedback = "Отправка отчёта: ${it.name}"; exportIsError = false },
                                        onFailure = { exportFeedback = "Ошибка отправки: ${it.message}"; exportIsError = true },
                                    )
                                }
                            },
                            onDelete = { sessionPendingDelete = s },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportActionsBar(
    sessionId: Long?,
    includeIdentifiers: Boolean,
    onToggleIdentifiers: (Boolean) -> Unit,
    vm: ObdViewModel,
    onFeedback: (String, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    vm.exportSessionReport(sessionId, "TXT", includeIdentifiers, share = false) { res ->
                        res.fold(
                            onSuccess = { onFeedback("Результат сохранён: ${it.absolutePath}", false) },
                            onFailure = { onFeedback("Ошибка сохранения: ${it.message}", true) },
                        )
                    }
                },
            ) { Text("Сохранить результат") }

            OutlinedButton(
                onClick = {
                    vm.exportSessionReport(sessionId, "PDF", includeIdentifiers, share = true) { res ->
                        res.fold(
                            onSuccess = { onFeedback("Поделиться: ${it.name}", false) },
                            onFailure = { onFeedback("Ошибка отправки: ${it.message}", true) },
                        )
                    }
                },
            ) { Text("Поделиться") }

            OutlinedButton(
                onClick = {
                    vm.exportSessionReport(sessionId, "PDF", includeIdentifiers, share = false) { res ->
                        res.fold(
                            onSuccess = { onFeedback("PDF сохранён: ${it.absolutePath}", false) },
                            onFailure = { onFeedback("Ошибка PDF: ${it.message}", true) },
                        )
                    }
                },
            ) { Text("Экспорт PDF") }

            OutlinedButton(
                onClick = {
                    vm.exportSessionReport(sessionId, "ZIP", includeIdentifiers, share = false) { res ->
                        res.fold(
                            onSuccess = { onFeedback("ZIP сохранён: ${it.absolutePath}", false) },
                            onFailure = { onFeedback("Ошибка ZIP: ${it.message}", true) },
                        )
                    }
                },
            ) { Text("Экспорт ZIP") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = includeIdentifiers, onCheckedChange = onToggleIdentifiers)
            Spacer(Modifier.padding(4.dp))
            Text(
                "Включить VIN и MAC в экспортируемый отчёт (по умолчанию скрыты)",
                color = TextDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun DtcResultsBlock(
    stored: List<DiagnosticRepository.DtcEntryInfo>,
    pending: List<DiagnosticRepository.DtcEntryInfo>,
    permanent: List<DiagnosticRepository.DtcEntryInfo>,
    errors: List<String>,
    durationMs: Long,
) {
    var selectedTab by remember { mutableStateOf(DtcCategoryTab.ALL) }

    // Top summary tiles
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ValueTile("Сохранённые", stored.size.toString(), "Mode 03", Modifier.weight(1f), if (stored.isNotEmpty()) Red else Green)
        ValueTile("Ожидающие", pending.size.toString(), "Mode 07", Modifier.weight(1f), if (pending.isNotEmpty()) Amber else Green)
        ValueTile("Постоянные", permanent.size.toString(), "Mode 0A", Modifier.weight(1f), if (permanent.isNotEmpty()) Amber else Green)
    }

    Spacer(Modifier.height(8.dp))

    // Category filter tabs
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DtcCategoryTab.entries.forEach { tab ->
            val count = when (tab) {
                DtcCategoryTab.ALL -> stored.size + pending.size + permanent.size
                DtcCategoryTab.STORED -> stored.size
                DtcCategoryTab.PENDING -> pending.size
                DtcCategoryTab.PERMANENT -> permanent.size
            }
            FilterChip(
                selected = selectedTab == tab,
                onClick = { selectedTab = tab },
                label = { Text("${tab.labelRu} ($count)") },
            )
        }
    }

    val visibleItems = when (selectedTab) {
        DtcCategoryTab.ALL -> stored + pending + permanent
        DtcCategoryTab.STORED -> stored
        DtcCategoryTab.PENDING -> pending
        DtcCategoryTab.PERMANENT -> permanent
    }

    Spacer(Modifier.height(6.dp))
    if (visibleItems.isEmpty()) {
        Text("В выбранной категории ошибок не обнаружено", color = Green, style = MaterialTheme.typography.bodyMedium)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visibleItems.forEach { d ->
                CompactDtcCard(
                    d = d,
                    color = if (d.mode == DtcModes.STORED) Red else Amber,
                )
            }
        }
    }

    if (errors.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Технические замечания протокола:", color = TextDim, style = MaterialTheme.typography.labelMedium)
        errors.forEach { Text("• $it", color = Amber, style = MaterialTheme.typography.bodySmall) }
    }
    if (durationMs > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Время опроса: $durationMs мс",
            color = TextDim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Compact DTC Card (Requirement 5):
 * Collapsed view shows ONLY code, brief Russian description, error type, and «Подробнее».
 * Expanding «Подробнее» shows full description, causes, recommendations, source, EN original, and technical details.
 */
@Composable
private fun CompactDtcCard(
    d: DiagnosticRepository.DtcEntryInfo,
    color: Color,
) {
    var showDetails by remember(d.code, d.mode) { mutableStateOf(false) }
    val modeLabel = when (d.mode) {
        DtcModes.STORED -> "Сохранённая (Mode 03)"
        DtcModes.PENDING -> "Ожидающая (Mode 07)"
        DtcModes.PERMANENT -> "Постоянная (Mode 0A)"
        else -> "Mode ${d.mode}"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(d.code, style = MaterialTheme.typography.titleLarge, color = color)
                    StatusChip(modeLabel, color)
                }
                d.ecuId?.let { ecu ->
                    Text("ЭБУ $ecu", style = MaterialTheme.typography.labelMedium, color = TextDim)
                }
            }

            Text(
                d.ru ?: d.en ?: "Нет описания в базе",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (badge, badgeColor) = when (d.translationStatus) {
                    "verified" -> "✓ RU-перевод проверен" to Green
                    "auto" -> "⚙ Справочный автоперевод" to Amber
                    else -> "EN оригинал" to TextDim
                }
                Text(badge, style = MaterialTheme.typography.labelSmall, color = badgeColor)
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Скрыть" else "Подробнее")
                }
            }

            if (showDetails) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Примечание: описание из базы данных является справочным ориентиром и не заменяет инструментальную проверку узла.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber,
                )
                d.detailRu?.takeIf { it.isNotBlank() }?.let { det ->
                    Spacer(Modifier.height(4.dp))
                    Text("Полное описание:", style = MaterialTheme.typography.labelLarge, color = TextDim)
                    Text(det, style = MaterialTheme.typography.bodyMedium)
                }
                if (!d.en.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Английский оригинал: ${d.en}", style = MaterialTheme.typography.bodyMedium, color = TextDim)
                }
                if (d.causes.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Возможные причины:", style = MaterialTheme.typography.labelLarge, color = TextDim)
                    d.causes.forEachIndexed { i, c ->
                        val mark = if (d.causeStatuses.getOrNull(i) == "en") " (англ.)" else ""
                        Text("• $c$mark", style = MaterialTheme.typography.bodyMedium, color = TextDim)
                    }
                }
                if (d.symptoms.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Возможные симптомы:", style = MaterialTheme.typography.labelLarge, color = TextDim)
                    d.symptoms.forEachIndexed { i, s ->
                        val mark = if (d.symptomStatuses.getOrNull(i) == "en") " (англ.)" else ""
                        Text("• $s$mark", style = MaterialTheme.typography.bodyMedium, color = TextDim)
                    }
                }
                if (!d.recommendationsRu.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Что рекомендуется проверить:", style = MaterialTheme.typography.labelLarge, color = TextDim)
                    Text(d.recommendationsRu, style = MaterialTheme.typography.bodyMedium, color = TextDim)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Источник описания: ${d.scopeLabel ?: "общий"} · ${d.source}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDim,
                )
                Text(
                    buildString {
                        append("Технические сведения: режим Mode %02X".format(d.mode))
                        d.ecuId?.let { append(", ЭБУ $it") }
                        d.rawResponse?.takeIf { it.isNotBlank() }?.let { append(", сырой ответ: $it") }
                        if (d.relatedCodes.isNotEmpty()) append(", связанные коды: ${d.relatedCodes.joinToString()}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextDim,
                )
            }
        }
    }
}

@Composable
private fun SavedSessionDetailPanel(
    details: DiagnosticRepository.SavedSessionDetails,
    includeIdentifiers: Boolean,
    onToggleIdentifiers: (Boolean) -> Unit,
    vm: ObdViewModel,
    onClose: () -> Unit,
    onFeedback: (String, Boolean) -> Unit,
) {
    val s = details.session
    val df = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
    Panel(title = "Просмотр сохранённого сеанса №${s.id}") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StatusChip(
                if (s.demoMode) "ДЕМО-СЕАНС" else SessionStatus.labelRu(s.status, s.dtcReadCompleted).uppercase(),
                when {
                    s.demoMode -> Amber
                    s.status == SessionStatus.COMPLETED -> Green
                    else -> Amber
                },
            )
            OutlinedButton(onClick = onClose) { Text("Закрыть просмотр") }
        }
        Spacer(Modifier.height(6.dp))
        KeyValue("Дата начала", df.format(Date(s.startedAtMs)))
        KeyValue("Последнее сохранение", df.format(Date(s.updatedAtMs.takeIf { it > 0 } ?: s.startedAtMs)))
        KeyValue("Автомобиль (в сеансе)", s.displayVehicleTitle())
        KeyValue("Источник идентификации", VehicleIdentitySource.labelRu(s.vehicleIdentitySource))
        s.profileName?.takeIf { it.isNotBlank() }?.let {
            KeyValue("Ручной профиль", "$it (справочно)")
        }
        KeyValue("Адаптер / Транспорт", "${s.adapterName} (${s.transportType ?: "BLE"})")
        KeyValue("Протокол", s.protocol ?: "не определён")
        s.endReason?.takeIf { it.isNotBlank() }?.let {
            KeyValue("Причина завершения", it)
        }

        Spacer(Modifier.height(8.dp))
        DtcResultsBlock(
            stored = details.stored,
            pending = details.pending,
            permanent = details.permanent,
            errors = emptyList(),
            durationMs = 0L,
        )

        if (details.sensors.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Последние сохранённые датчики (${details.sensors.size}):", style = MaterialTheme.typography.labelLarge)
            val tf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            details.sensors.forEach { sen ->
                val unitStr = sen.unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
                KeyValue(
                    "${sen.name} (PID %02X, ${tf.format(Date(sen.timestampMs))})".format(sen.pid),
                    "${sen.valueText}$unitStr",
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        ExportActionsBar(
            sessionId = s.id,
            includeIdentifiers = includeIdentifiers,
            onToggleIdentifiers = onToggleIdentifiers,
            vm = vm,
            onFeedback = onFeedback,
        )
    }
}

@Composable
private fun SessionHistoryRow(
    s: SessionEntity,
    dateText: String,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onExportPdf: () -> Unit,
    onExportZip: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append(dateText)
                        if (s.demoMode) append(" · [ДЕМО]")
                        if (isCurrent) append(" · [ТЕКУЩИЙ]")
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                StatusChip(
                    if (s.legacyUnverified) "Неподтверждённые (старая версия)" else SessionStatus.labelRu(s.status, s.dtcReadCompleted),
                    when {
                        s.legacyUnverified -> Amber
                        s.status == SessionStatus.COMPLETED -> Green
                        s.status == SessionStatus.ACTIVE -> Primary
                        else -> Amber
                    },
                )
            }
            Text("Автомобиль: ${s.displayVehicleTitle()}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "сохранённых ${s.storedCount}, ожидающих ${s.pendingCount}, постоянных ${s.permanentCount}",
                style = MaterialTheme.typography.bodySmall,
                color = if (s.totalDtcCount > 0) Red else TextDim,
            )
            s.endReason?.takeIf { it.isNotBlank() }?.let {
                Text("Примечание: $it", style = MaterialTheme.typography.labelSmall, color = TextDim)
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextButton(onClick = onOpen) { Text("Открыть") }
                TextButton(onClick = onExportPdf) { Text("PDF") }
                TextButton(onClick = onExportZip) { Text("ZIP") }
                TextButton(onClick = onShare) { Text("Поделиться") }
                if (!isCurrent) {
                    TextButton(onClick = onDelete) { Text("Удалить", color = Red) }
                }
            }
        }
    }
}

@Composable
private fun CrashReportCard(crash: String, onClear: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Panel(title = "Отчёт о последнем сбое") {
        val firstLine = crash.lineSequence().firstOrNull { it.startsWith("Тип:") } ?: "Неизвестная ошибка"
        Text(firstLine, style = MaterialTheme.typography.bodyMedium, color = Amber)
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Скрыть подробности" else "Показать подробности (стектрейс)")
        }
        if (expanded) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    Modifier.height(260.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                ) {
                    Text(
                        crash,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = TextDim,
                    )
                }
            }
        }
        OutlinedButton(onClick = onClear) {
            Text("Очистить отчёт")
        }
    }
}
