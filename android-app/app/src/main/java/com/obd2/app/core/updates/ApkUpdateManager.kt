package com.obd2.app.core.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.obd2.app.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Serializable
data class ApkReleaseManifest(
    val versionCode: Int,
    val versionName: String,
    val changelog: String = "",
    val apkUrl: String,
    val sizeBytes: Long = 0L,
    val sha256: String,
    val minAndroidSdk: Int = 26,
    val releasedAt: String = "",
)

sealed interface ApkCheckResult {
    data class UpdateAvailable(val manifest: ApkReleaseManifest) : ApkCheckResult
    data class UpToDate(val currentVersionCode: Int, val currentVersionName: String) : ApkCheckResult
    data class NotConfigured(val reason: String) : ApkCheckResult
    data class Error(val reason: String) : ApkCheckResult
}

data class ApkDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

sealed interface ApkVerificationResult {
    data class ReadyToInstall(val apkFile: File, val manifest: ApkReleaseManifest) : ApkVerificationResult
    data class Rejected(val reason: String) : ApkVerificationResult
}

/**
 * Safe Android 8+ APK update checker, resumable downloader, verifier, and system installer launcher.
 *
 * Supports:
 * - Direct `ApkReleaseManifest` JSON endpoint OR GitHub Releases `/repos/<owner>/<repo>/releases/latest`
 *   (looking up `release-manifest.json` asset or `.apk` + `.sha256` assets).
 * - HTTP `Range: bytes=<offset>-` resume on partial `.part` files after network interruptions.
 * - Automatic cleanup of stale APK/part files (`cleanupStaleUpdates`).
 * - Strict SHA-256, packageName, anti-downgrade versionCode, and signing certificate SHA-256 verification.
 * - Android 8+ `canRequestPackageInstalls()` permission check & explicit Settings intent.
 */
class ApkUpdateManager(
    private val context: Context,
    private val allowHttpForTest: Boolean = false,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val updatesDir: File by lazy { File(context.filesDir, "apk_updates").apply { mkdirs() } }
    private val readyManifestFile: File get() = File(updatesDir, "ready_update_manifest.json")

    fun apkFileFor(manifest: ApkReleaseManifest): File =
        File(updatesDir, "obd2_${manifest.versionName}_${manifest.versionCode}.apk")

    fun loadPersistedReadyUpdate(
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        expectedSignerSha256Override: String? = null,
    ): ApkVerificationResult.ReadyToInstall? {
        if (!readyManifestFile.exists()) return null
        val manifest = runCatching {
            json.decodeFromString<ApkReleaseManifest>(readyManifestFile.readText(Charsets.UTF_8))
        }.getOrNull() ?: run {
            readyManifestFile.delete()
            return null
        }
        if (manifest.versionCode <= currentVersionCode) {
            readyManifestFile.delete()
            apkFileFor(manifest).delete()
            return null
        }
        val apkFile = apkFileFor(manifest)
        if (!apkFile.exists()) {
            readyManifestFile.delete()
            return null
        }
        val actualSha = runCatching { sha256File(apkFile) }.getOrNull()
        if (actualSha == null || !actualSha.equals(manifest.sha256.trim(), ignoreCase = true)) {
            readyManifestFile.delete()
            apkFile.delete()
            return null
        }
        return verifyApkArchive(
            apkFile = apkFile,
            manifest = manifest,
            currentVersionCode = currentVersionCode,
            expectedSignerSha256Override = expectedSignerSha256Override,
        ) as? ApkVerificationResult.ReadyToInstall
    }

    fun checkForUpdate(
        manifestUrl: String = BuildConfig.APK_UPDATE_MANIFEST_URL,
        githubRepo: String = BuildConfig.UPDATE_GITHUB_REPO,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        currentVersionName: String = BuildConfig.VERSION_NAME,
    ): ApkCheckResult {
        val directUrl = manifestUrl.trim()
        val repo = githubRepo.trim()
        val targetUrl = when {
            directUrl.isNotBlank() -> directUrl
            repo.isNotBlank() -> "https://api.github.com/repos/$repo/releases/latest"
            else -> return ApkCheckResult.NotConfigured(
                "Репозиторий GitHub Releases не задан в сборке (APK_UPDATE_MANIFEST_URL / UPDATE_GITHUB_REPO).",
            )
        }

        return runCatching {
            val body = httpGetText(targetUrl)
            val manifest = if (body.trimStart().startsWith("{") && "\"tag_name\"" in body && "\"assets\"" in body) {
                parseGitHubReleaseToManifest(body)
            } else {
                json.decodeFromString<ApkReleaseManifest>(body)
            }
            if (manifest.versionCode <= currentVersionCode) {
                ApkCheckResult.UpToDate(currentVersionCode, currentVersionName)
            } else if (Build.VERSION.SDK_INT < manifest.minAndroidSdk) {
                ApkCheckResult.Error("Обновление ${manifest.versionName} требует Android API ${manifest.minAndroidSdk}+.")
            } else {
                ApkCheckResult.UpdateAvailable(manifest)
            }
        }.getOrElse { e ->
            ApkCheckResult.Error("Не удалось проверить обновления: ${e.message ?: "сервер недоступен"}")
        }
    }

    private fun parseGitHubReleaseToManifest(releaseJson: String): ApkReleaseManifest {
        val obj = json.parseToJsonElement(releaseJson).jsonObject
        val isDraft = obj["draft"]?.jsonPrimitive?.content.equals("true", ignoreCase = true)
        val isPrerelease = obj["prerelease"]?.jsonPrimitive?.content.equals("true", ignoreCase = true)
        if (isDraft || isPrerelease) {
            throw IllegalStateException("Доступен только черновик или pre-release; стабильный релиз не найден")
        }
        val tagName = obj["tag_name"]?.jsonPrimitive?.content.orEmpty().removePrefix("v")
        val bodyText = obj["body"]?.jsonPrimitive?.content.orEmpty()
        val releasedAt = obj["published_at"]?.jsonPrimitive?.content.orEmpty()
        val assets = obj["assets"]?.jsonArray.orEmpty().map { it.jsonObject }

        // Prefer explicit release-manifest.json asset if published
        val manifestAsset = assets.firstOrNull {
            it["name"]?.jsonPrimitive?.content.equals("release-manifest.json", ignoreCase = true)
        }
        if (manifestAsset != null) {
            val mUrl = manifestAsset["browser_download_url"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("У release-manifest.json отсутствует URL")
            return json.decodeFromString(httpGetText(mUrl))
        }

        val apkAssets = assets.filter {
            it["name"]?.jsonPrimitive?.content?.endsWith(".apk", ignoreCase = true) == true
        }
        val apkAsset = apkAssets.firstOrNull {
            it["name"]?.jsonPrimitive?.content?.contains("debug", ignoreCase = true) == false
        } ?: apkAssets.firstOrNull()
            ?: throw IllegalStateException("В релизе GitHub ($tagName) отсутствует APK-файл")

        val apkUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("У APK-файла отсутствует ссылка загрузки")
        val sizeBytes = apkAsset["size"]?.jsonPrimitive?.longOrNull ?: 0L
        val shaAsset = assets.firstOrNull {
            it["name"]?.jsonPrimitive?.content?.endsWith(".sha256", ignoreCase = true) == true
        }
        val sha256 = shaAsset?.get("browser_download_url")?.jsonPrimitive?.content?.let {
            httpGetText(it).trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        } ?: Regex("sha256[:=]\\s*([0-9a-fA-F]{64})").find(bodyText)?.groupValues?.getOrNull(1)
            ?: throw IllegalStateException("В релизе отсутствует SHA-256 контрольная сумма APK")

        val vCode = Regex("versionCode[:=]\\s*(\\d+)").find(bodyText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: parseVersionCodeFromTag(tagName)

        return ApkReleaseManifest(
            versionCode = vCode,
            versionName = tagName.ifBlank { "unknown" },
            changelog = bodyText,
            apkUrl = apkUrl,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            releasedAt = releasedAt,
        )
    }

    private fun parseVersionCodeFromTag(tag: String): Int {
        val parts = tag.split('.').mapNotNull { it.toIntOrNull() }
        return if (parts.size >= 3) parts[0] * 10000 + parts[1] * 100 + parts[2] else 0
    }

    private fun httpGetText(urlStr: String): String {
        val parsed = URL(urlStr)
        if (!allowHttpForTest && !parsed.protocol.equals("https", ignoreCase = true)) {
            throw SecurityException("Требуется защищённое HTTPS-соединение")
        }
        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("Accept", "application/vnd.github+json, application/json, text/plain")
            setRequestProperty("User-Agent", "OBD2-Diagnostic-Scanner/${BuildConfig.VERSION_NAME}")
        }
        return try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND && parsed.host.equals("api.github.com", ignoreCase = true)) {
                throw IllegalStateException(
                    "Релиз не найден (HTTP 404). Если репозиторий приватный, встроенное обновление без токена недоступно; иначе проверьте публикацию стабильного релиза.",
                )
            }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun isOnWifi(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    fun partialFileFor(versionCode: Int): File = File(updatesDir, "update_$versionCode.apk.part")

    fun cleanupStaleUpdates(keepFileName: String? = null): Int {
        var removed = 0
        updatesDir.listFiles()?.forEach { f ->
            if (f.name != keepFileName && (f.name.endsWith(".apk") || f.name.endsWith(".part"))) {
                if (f.delete()) removed++
            }
        }
        return removed
    }

    fun downloadAndVerify(
        manifest: ApkReleaseManifest,
        wifiOnly: Boolean,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        availableSpaceOverrideBytes: Long? = null,
        expectedSignerSha256Override: String? = null,
        onProgress: (ApkDownloadProgress) -> Unit = {},
    ): ApkVerificationResult {
        if (manifest.versionCode <= currentVersionCode) {
            return ApkVerificationResult.Rejected(
                "Защита от отката: версия APK (${manifest.versionCode}) не новее установленной ($currentVersionCode).",
            )
        }
        if (wifiOnly && !isOnWifi()) {
            return ApkVerificationResult.Rejected("В настройках разрешена загрузка обновлений только через Wi-Fi.")
        }
        val parsedUrl = runCatching { URL(manifest.apkUrl) }.getOrNull()
            ?: return ApkVerificationResult.Rejected("Некорректный URL файла APK.")
        if (!allowHttpForTest && !parsedUrl.protocol.equals("https", ignoreCase = true)) {
            return ApkVerificationResult.Rejected("Загрузка APK разрешена только по HTTPS.")
        }

        val requiredSpace = if (manifest.sizeBytes > 0) manifest.sizeBytes + 4L * 1024L * 1024L else 30L * 1024L * 1024L
        val freeBytes = availableSpaceOverrideBytes ?: updatesDir.usableSpace
        if (freeBytes < requiredSpace) {
            return ApkVerificationResult.Rejected(
                "Недостаточно свободного места: доступно ${freeBytes / (1024 * 1024)} МБ, требуется ${requiredSpace / (1024 * 1024)} МБ.",
            )
        }

        val stagingFile = partialFileFor(manifest.versionCode)
        val finalFile = apkFileFor(manifest)

        // Reuse an already-downloaded and verified APK without re-downloading over the network.
        if (finalFile.exists()) {
            val existingSha = runCatching { sha256File(finalFile) }.getOrNull()
            if (existingSha != null && existingSha.equals(manifest.sha256.trim(), ignoreCase = true)) {
                val total = finalFile.length().coerceAtLeast(manifest.sizeBytes)
                onProgress(ApkDownloadProgress(total, total))
                return verifyApkArchive(finalFile, manifest, currentVersionCode, expectedSignerSha256Override)
            } else {
                finalFile.delete()
            }
        }

        val downloadErr = runCatching {
            if (stagingFile.exists() && manifest.sizeBytes > 0 && stagingFile.length() >= manifest.sizeBytes) {
                stagingFile.delete()
            }
            var retriedFresh = false
            while (true) {
                val existingOffset = if (stagingFile.exists()) stagingFile.length() else 0L
                val conn = (parsedUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 8000
                    readTimeout = 15000
                    if (existingOffset > 0L) {
                        setRequestProperty("Range", "bytes=$existingOffset-")
                    }
                }
                try {
                    val code = conn.responseCode
                    if (code == 416 && !retriedFresh && stagingFile.exists()) {
                        stagingFile.delete()
                        retriedFresh = true
                        continue
                    }
                    val resumeAccepted = code == HttpURLConnection.HTTP_PARTIAL && existingOffset > 0L
                    if (code !in 200..299 && code != HttpURLConnection.HTTP_PARTIAL) {
                        throw IllegalStateException("HTTP $code при скачивании APK")
                    }
                    val total = if (resumeAccepted) {
                        manifest.sizeBytes.takeIf { it > 0 } ?: (existingOffset + conn.contentLengthLong.coerceAtLeast(0L))
                    } else {
                        conn.contentLengthLong.takeIf { it > 0 } ?: manifest.sizeBytes
                    }
                    var downloaded = if (resumeAccepted) existingOffset else 0L
                    conn.inputStream.use { input ->
                        FileOutputStream(stagingFile, resumeAccepted).use { out ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                downloaded += n
                                onProgress(ApkDownloadProgress(downloaded, total))
                            }
                        }
                    }
                    if (manifest.sizeBytes > 0 && downloaded != manifest.sizeBytes) {
                        // Keep stagingFile intact for HTTP Range resume on retry!
                        throw IllegalStateException("Загрузка прервана: получено $downloaded из ${manifest.sizeBytes} байт")
                    }
                    break
                } finally {
                    conn.disconnect()
                }
            }
        }.exceptionOrNull()

        if (downloadErr != null) {
            return ApkVerificationResult.Rejected("Ошибка загрузки APK: ${downloadErr.message}")
        }

        val actualSha = sha256File(stagingFile)
        if (!actualSha.equals(manifest.sha256.trim(), ignoreCase = true)) {
            stagingFile.delete()
            return ApkVerificationResult.Rejected("Контрольная сумма SHA-256 APK не совпадает — файл повреждён или подменён.")
        }

        if (!stagingFile.renameTo(finalFile)) {
            stagingFile.copyTo(finalFile, overwrite = true)
            stagingFile.delete()
        }
        cleanupStaleUpdates(keepFileName = finalFile.name)

        return verifyApkArchive(finalFile, manifest, currentVersionCode, expectedSignerSha256Override)
    }

    @Suppress("DEPRECATION")
    fun verifyApkArchive(
        apkFile: File,
        manifest: ApkReleaseManifest,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        expectedSignerSha256Override: String? = null,
    ): ApkVerificationResult {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val archiveInfo: PackageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: return ApkVerificationResult.Rejected("Скачанный файл не является корректным Android APK.").also {
                apkFile.delete()
            }

        if (archiveInfo.packageName != context.packageName) {
            apkFile.delete()
            return ApkVerificationResult.Rejected(
                "Несовпадающий packageName: ${archiveInfo.packageName} (ожидался ${context.packageName}).",
            )
        }
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archiveInfo.longVersionCode.toInt() else archiveInfo.versionCode
        if (archiveCode <= currentVersionCode) {
            apkFile.delete()
            return ApkVerificationResult.Rejected("Версия внутри APK ($archiveCode) не новее текущей ($currentVersionCode).")
        }

        val expectedSigner = expectedSignerSha256Override ?: installedSignerSha256()
        val archiveSigner = extractSignerSha256(archiveInfo)
        if (expectedSigner != null && (archiveSigner == null || !expectedSigner.equals(archiveSigner, ignoreCase = true))) {
            apkFile.delete()
            return ApkVerificationResult.Rejected(
                "Подпись APK отсутствует или не совпадает с установленным приложением. Обновление поверх невозможно без резервного копирования и переустановки.",
            )
        }

        runCatching {
            readyManifestFile.writeText(
                json.encodeToString(ApkReleaseManifest.serializer(), manifest),
                Charsets.UTF_8,
            )
        }
        return ApkVerificationResult.ReadyToInstall(apkFile, manifest)
    }

    fun canRequestPackageInstalls(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun buildUnknownSourcesSettingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun buildInstallIntent(apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    @Suppress("DEPRECATION")
    private fun installedSignerSha256(): String? = runCatching {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val info = pm.getPackageInfo(context.packageName, flags)
        extractSignerSha256(info)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun extractSignerSha256(info: PackageInfo): String? {
        val certBytes = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            null
        } ?: info.signatures?.firstOrNull()?.toByteArray() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
