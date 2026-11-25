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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2.app.BuildConfig
import com.obd2.app.core.updates.ApkCheckResult
import com.obd2.app.core.updates.ApkVerificationResult
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.Red
import com.obd2.app.ui.theme.TextDim

@Composable
fun AppUpdatesScreen(vm: ObdViewModel) {
    val ctx = LocalContext.current
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()
    val checkResult by vm.apkCheckResult.collectAsStateWithLifecycle()
    val downloadProgress by vm.apkDownloadProgress.collectAsStateWithLifecycle()
    val verifyResult by vm.apkVerifyResult.collectAsStateWithLifecycle()
    val backupNotice by vm.backupNotice.collectAsStateWithLifecycle()
    val lastBackupFile by vm.lastBackupFile.collectAsStateWithLifecycle()
    var canInstallPackages by remember { mutableStateOf(vm.canRequestPackageInstalls()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canInstallPackages = vm.canRequestPackageInstalls()
                vm.refreshPersistedApkUpdate()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(title = "Обновления приложения (GitHub Releases)") {
            KeyValue("Текущая версия", "${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            KeyValue(
                "Источник обновлений",
                BuildConfig.APK_UPDATE_MANIFEST_URL.ifBlank {
                    BuildConfig.UPDATE_GITHUB_REPO.takeIf { it.isNotBlank() }?.let { "GitHub Releases: $it" }
                        ?: "не задан в сборке"
                },
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { vm.checkApkUpdates() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Проверить сейчас")
            }

            when (val r = checkResult) {
                is ApkCheckResult.UpToDate -> {
                    Text(
                        "Установлена актуальная версия (${r.currentVersionName}).",
                        color = Green,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is ApkCheckResult.NotConfigured -> {
                    Text(r.reason, color = Amber, style = MaterialTheme.typography.bodySmall)
                }
                is ApkCheckResult.Error -> {
                    Text(r.reason, color = Red, style = MaterialTheme.typography.bodySmall)
                }
                is ApkCheckResult.UpdateAvailable -> {
                    val m = r.manifest
                    Spacer(Modifier.height(6.dp))
                    KeyValue("Доступна версия", "${m.versionName} (code ${m.versionCode})")
                    if (m.sizeBytes > 0) {
                        KeyValue("Размер", "${"%.2f".format(m.sizeBytes / (1024.0 * 1024.0))} МБ")
                    }
                    if (m.changelog.isNotBlank()) {
                        Text("Изменения:\n${m.changelog}", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { vm.downloadAndVerifyApk(m) },
                        enabled = downloadProgress == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Скачать обновление")
                    }
                }
                null -> {}
            }

            downloadProgress?.let { p ->
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Загружено: ${p.bytesDownloaded / 1024} КБ" + if (p.totalBytes > 0) " / ${p.totalBytes / 1024} КБ" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                )
            }

            when (val v = verifyResult) {
                is ApkVerificationResult.Rejected -> {
                    Spacer(Modifier.height(6.dp))
                    Text(v.reason, color = Red, style = MaterialTheme.typography.bodySmall)
                }
                is ApkVerificationResult.ReadyToInstall -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "APK проверен (SHA-256 и подпись совпадают). Нажмите для запуска штатного установщика Android:",
                        color = Green,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!canInstallPackages) {
                        Text(
                            "Android требует разрешить установку приложений из этого источника для Wildia.",
                            color = Amber,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = { ctx.startActivity(vm.buildUnknownSourcesIntent()) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Разрешить установку из этого источника")
                        }
                    } else {
                        Button(
                            onClick = { ctx.startActivity(vm.buildApkInstallIntent(v.apkFile)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Установить обновление ${v.manifest.versionName}")
                        }
                    }
                }
                null -> {}
            }
        }

        Panel(title = "Параметры проверки") {
            AppToggle("Автоматическая проверка обновлений", settings.autoCheckAppUpdates) { v ->
                vm.updateSettings { it.setAutoCheckAppUpdates(v) }
            }
            AppToggle("Уведомления о новой версии", settings.notifyAppUpdates) { v ->
                vm.updateSettings { it.setNotifyAppUpdates(v) }
            }
            AppToggle("Скачивание только через Wi-Fi", settings.wifiOnlyUpdates) { v ->
                vm.updateSettings { it.setWifiOnlyUpdates(v) }
            }
            KeyValue("Периодичность проверки", "раз в ${settings.appUpdateIntervalHours} ч")
        }

        Panel(title = "Подпись и перенос данных") {
            Text(
                if (BuildConfig.DEBUG) {
                    "Тестовая (debug) сборка: обновление поверх возможно только сборками с тем же debug-ключом. Для перехода на release-версию сначала создайте резервную копию ниже."
                } else {
                    "Установлена подписанная release-сборка Wildia. Последующие release-обновления с тем же ключом устанавливаются поверх с сохранением истории и профилей."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextDim,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { vm.exportUserBackup() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Создать резервную копию данных (Room + профили)")
            }
            lastBackupFile?.let { f ->
                OutlinedButton(
                    onClick = { vm.verifyAndRestoreBackup(f) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Проверить и восстановить: ${f.name}")
                }
            }
            backupNotice?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodySmall, color = Green)
            }
        }
    }
}

@Composable
private fun AppToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
