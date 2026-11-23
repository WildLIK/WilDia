package com.obd2.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.obd2.app.core.db.ObdLookupDb
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.Red
import com.obd2.app.ui.theme.TextDim
import kotlinx.coroutines.launch

@Composable
fun DtcBaseScreen(vm: ObdViewModel) {
    val uiScope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ObdLookupDb.DtcLookup>>(emptyList()) }
    var categories by remember { mutableStateOf<List<ObdLookupDb.CategoryInfo>>(emptyList()) }
    var makers by remember { mutableStateOf<List<ObdLookupDb.ManufacturerInfo>>(emptyList()) }
    var makerFilter by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    var scopeFilter by remember { mutableStateOf<String?>("GENERIC") }
    var codeGroup by remember { mutableStateOf<String?>(null) }
    var showReference by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        categories = vm.repo.lookupDb.categories()
        makers = vm.repo.lookupDb.manufacturers()
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isNotEmpty()) {
            uiScope.launch {
                results = vm.repo.lookupDb.searchDtc(q, 200)
                searched = true
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                searched = false
            },
            label = { Text("Код (P0420) или текст описания (рус/англ)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(onClick = { runSearch() }) { Text("Искать") }
            FilterChip(
                selected = scopeFilter == "GENERIC",
                onClick = { scopeFilter = if (scopeFilter == "GENERIC") null else "GENERIC" },
                label = { Text("SAE") },
            )
            FilterChip(
                selected = scopeFilter == "VAG",
                onClick = { scopeFilter = if (scopeFilter == "VAG") null else "VAG" },
                label = { Text("VAG") },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = codeGroup == null,
                onClick = { codeGroup = null },
                label = { Text("Все системы") },
            )
            FilterChip(
                selected = codeGroup == "P",
                onClick = { codeGroup = "P" },
                label = { Text("Двигатель/АКПП") },
            )
            FilterChip(
                selected = codeGroup == "B",
                onClick = { codeGroup = "B" },
                label = { Text("Кузов") },
            )
            FilterChip(
                selected = codeGroup == "C",
                onClick = { codeGroup = "C" },
                label = { Text("Шасси") },
            )
            FilterChip(
                selected = codeGroup == "U",
                onClick = { codeGroup = "U" },
                label = { Text("Сеть") },
            )
        }

        if (searched) {
            val topMakers = makers.filter { it.id !in setOf("GENERIC", "OTHER") }.take(6)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                topMakers.forEach { m ->
                    FilterChip(
                        selected = makerFilter == m.id,
                        onClick = { makerFilter = if (makerFilter == m.id) null else m.id },
                        label = { Text(m.nameRu.ifEmpty { m.nameEn }) },
                    )
                }
            }
        }

        if (!searched && results.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Panel(title = "База ошибок") {
                    Text(
                        "Введите код (например P0300) или слова из описания — на русском или английском. " +
                            "Фильтры сверху ограничивают поиск системой автомобиля.",
                        color = TextDim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { showReference = !showReference }) {
                        Text(if (showReference) "Скрыть справку" else "Справка: классификация SAE J2012")
                    }
                    if (showReference) {
                        categories.forEach { c ->
                            Text(
                                "${c.prefix}: ${c.systemRu.ifEmpty { c.systemEn }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextDim,
                            )
                        }
                    }
                }
                Text(
                    "Офлайн-база v2: общие коды SAE + фирменные коды 33 производителей, причины и " +
                        "симптомы, переводы с пометками «проверен/авто/оригинал».",
                    color = TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            val filtered = results.filter { r ->
                when (scopeFilter) {
                    "GENERIC" -> r.scope == "GENERIC"
                    "VAG" -> r.groupTag == "VAG"
                    else -> true
                } && (makerFilter == null || r.scope == makerFilter) &&
                    (codeGroup == null || r.code.uppercase().startsWith(codeGroup ?: ""))
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(filtered, key = { it.code + it.scope + it.standard }) { r ->
                    DtcBaseRow(r, onRelatedClick = { code ->
                        query = code
                        runSearch()
                    })
                }
            }
            Text("Найдено: ${filtered.size}", color = TextDim, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun severityColor(sev: String?): Color? = when (sev) {
    "high" -> Red
    "medium" -> Amber
    "low" -> Green
    else -> null
}

private fun severityRu(sev: String): String = when (sev) {
    "high" -> "серьёзность: высокая"
    "medium" -> "серьёзность: средняя"
    "low" -> "серьёзность: низкая"
    else -> ""
}

@Composable
private fun TranslationBadge(status: String?) {
    val (text, color) = when (status) {
        "verified" -> "✓ RU-перевод проверен" to Green
        "auto" -> "⚙ RU-перевод автоматический" to Amber
        else -> "EN оригинал (перевода нет)" to TextDim
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun DtcBaseRow(r: ObdLookupDb.DtcLookup, onRelatedClick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        onClick = { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(
                    r.code,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                val scopeText = when (r.scope) {
                    "GENERIC" -> "SAE (общий)"
                    else -> r.scope.lowercase().replaceFirstChar { it.uppercase() }
                }
                Text(scopeText, style = MaterialTheme.typography.labelSmall, color = TextDim)
                severityColor(r.severity)?.let { c ->
                    Text(
                        " · ${severityRu(r.severity ?: "")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c,
                    )
                }
            }
            Text(
                r.titleRu ?: r.titleEn,
                style = MaterialTheme.typography.bodyMedium,
            )
            TranslationBadge(r.translationStatus)
            if (expanded) {
                (r.detailRu ?: r.detailEn)?.let {
                    Spacer(Modifier.padding(top = 6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = TextDim)
                }
                if (r.symptoms.isNotEmpty()) {
                    Spacer(Modifier.padding(top = 6.dp))
                    Text("Возможные симптомы:", style = MaterialTheme.typography.labelLarge, color = TextDim)
                    r.symptoms.forEachIndexed { i, s ->
                        val mark = if (r.symptomStatuses.getOrNull(i) == "en") " (англ.)" else ""
                        Text("• $s$mark", style = MaterialTheme.typography.bodyMedium, color = TextDim)
                    }
                }
                if (r.causes.isNotEmpty()) {
                    Spacer(Modifier.padding(top = 6.dp))
                    Text("Возможные причины:", style = MaterialTheme.typography.labelLarge, color = TextDim)
                    r.causes.forEachIndexed { i, c ->
                        val mark = if (r.causeStatuses.getOrNull(i) == "en") " (англ.)" else ""
                        Text("• $c$mark", style = MaterialTheme.typography.bodyMedium, color = TextDim)
                    }
                }
                r.recommendationsRu?.let {
                    Spacer(Modifier.padding(top = 6.dp))
                    Text("Рекомендации по проверке (проверенный перевод):", style = MaterialTheme.typography.labelLarge, color = TextDim)
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = TextDim)
                }
                if (r.checks.isNotEmpty()) {
                    Spacer(Modifier.padding(top = 6.dp))
                    Text("Проверки:", style = MaterialTheme.typography.labelLarge, color = TextDim)
                    r.checks.forEach { c -> Text("• $c", style = MaterialTheme.typography.bodyMedium, color = TextDim) }
                }
                r.severityReason?.let {
                    Text("Степень серьёзности обоснована: $it", style = MaterialTheme.typography.labelSmall, color = TextDim)
                }
                if (r.relatedCodes.isNotEmpty()) {
                    Spacer(Modifier.padding(top = 6.dp))
                    Text("Связанные коды:", style = MaterialTheme.typography.labelLarge, color = TextDim)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        r.relatedCodes.take(8).forEach { code ->
                            AssistChip(onClick = { onRelatedClick(code) }, label = { Text(code) })
                        }
                    }
                }
                Text(
                    "Стандарт: ${r.standard} · источник: ${r.source}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDim,
                )
            }
        }
    }
}
