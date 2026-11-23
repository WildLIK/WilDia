package com.obd2.app

import android.app.Application
import com.obd2.app.core.bt.ClassicBluetoothScanner
import com.obd2.app.core.bt.ClassicBluetoothTransport
import com.obd2.app.core.bt.SppProfileStatus
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.transport.TransportState
import com.obd2.app.core.updates.ApkCheckResult
import com.obd2.app.core.updates.ApkReleaseManifest
import com.obd2.app.core.updates.ApkUpdateManager
import com.obd2.app.core.updates.ApkVerificationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReleaseReadinessAuditTest {

    private fun ctx(): Application = RuntimeEnvironment.getApplication()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class MiniHttpServer(
        private val handler: (path: String, headers: Map<String, String>) -> Triple<Int, String, ByteArray>,
    ) : Closeable {
        private val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val baseUrl: String get() = "http://127.0.0.1:${ss.localPort}"
        @Volatile private var running = true

        init {
            thread(isDaemon = true) {
                while (running) {
                    val sock = try { ss.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) {
                        sock.use { s ->
                            val reader = s.getInputStream().bufferedReader(Charsets.UTF_8)
                            val reqLine = reader.readLine() ?: return@thread
                            val path = reqLine.split(" ").getOrNull(1) ?: "/"
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val h = reader.readLine() ?: break
                                if (h.isBlank()) break
                                val idx = h.indexOf(':')
                                if (idx > 0) {
                                    headers[h.substring(0, idx).trim().lowercase()] =
                                        h.substring(idx + 1).trim()
                                }
                            }
                            val (code, contentType, body) = handler(path, headers)
                            val reason = when (code) {
                                200 -> "OK"
                                206 -> "Partial Content"
                                416 -> "Range Not Satisfiable"
                                else -> "Error"
                            }
                            val out = s.getOutputStream()
                            val hdr = buildString {
                                append("HTTP/1.1 $code $reason\r\n")
                                append("Content-Type: $contentType\r\n")
                                append("Content-Length: ${body.size}\r\n")
                                append("Connection: close\r\n\r\n")
                            }
                            out.write(hdr.toByteArray(Charsets.US_ASCII))
                            if (body.isNotEmpty()) out.write(body)
                            out.flush()
                        }
                    }
                }
            }
        }

        override fun close() {
            running = false
            runCatching { ss.close() }
        }
    }

    // ------------------------------------------------------------------
    // 1. ApkUpdateManager: versionCode, GitHub draft guard, HTTP 416, fail-closed signing
    // ------------------------------------------------------------------

    @Test
    fun `checkForUpdate parses GitHub Releases JSON and rejects draft or prerelease`() {
        val apkBytes = "fake-apk-bytes".toByteArray()
        val sha = sha256(apkBytes)
        val draftMode = AtomicInteger(0)

        lateinit var server: MiniHttpServer
        server = MiniHttpServer { _, _ ->
            val isDraft = draftMode.get() == 1
            val json = """
                {
                  "tag_name": "v0.5.5",
                  "draft": $isDraft,
                  "prerelease": false,
                  "published_at": "2026-11-23T12:00:00Z",
                  "body": "versionCode=15\nsha256=$sha\nИсправления чтения VIN",
                  "assets": [
                    {
                      "name": "obd2-v0.5.5-debug.apk",
                      "size": ${apkBytes.size},
                      "browser_download_url": "${server.baseUrl}/debug.apk"
                    },
                    {
                      "name": "obd2-v0.5.5-release.apk",
                      "size": ${apkBytes.size},
                      "browser_download_url": "${server.baseUrl}/release.apk"
                    }
                  ]
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            Triple(200, "application/json", json)
        }

        server.use {
            val mgr = ApkUpdateManager(ctx(), allowHttpForTest = true)
            val res = mgr.checkForUpdate(
                manifestUrl = "${server.baseUrl}/release",
                currentVersionCode = 14,
                currentVersionName = "0.5.4",
            )
            assertTrue(res is ApkCheckResult.UpdateAvailable)
            val avail = res as ApkCheckResult.UpdateAvailable
            assertEquals(15, avail.manifest.versionCode)
            assertTrue(avail.manifest.apkUrl.endsWith("/release.apk"))

            val upToDate = mgr.checkForUpdate(
                manifestUrl = "${server.baseUrl}/release",
                currentVersionCode = 15,
                currentVersionName = "0.5.5",
            )
            assertTrue(upToDate is ApkCheckResult.UpToDate)

            draftMode.set(1)
            val draftRes = mgr.checkForUpdate(
                manifestUrl = "${server.baseUrl}/release",
                currentVersionCode = 14,
                currentVersionName = "0.5.4",
            )
            assertTrue(draftRes is ApkCheckResult.Error)
        }
    }

    @Test
    fun `checkForUpdate returns clear error when network server is unreachable`() {
        val mgr = ApkUpdateManager(ctx(), allowHttpForTest = true)
        val res = mgr.checkForUpdate(
            manifestUrl = "http://127.0.0.1:1/unreachable",
            currentVersionCode = 15,
            currentVersionName = "0.5.5",
        )
        assertTrue(res is ApkCheckResult.Error)
    }

    @Test
    fun `downloadAndVerify recovers from oversized stale part file and HTTP 416`() {
        val payload = "corrupted-or-test-apk-payload".toByteArray()
        val sha = sha256(payload)

        val server = MiniHttpServer { _, headers ->
            if (headers.containsKey("range")) {
                Triple(416, "application/octet-stream", ByteArray(0))
            } else {
                Triple(200, "application/octet-stream", payload)
            }
        }

        server.use {
            val mgr = ApkUpdateManager(ctx(), allowHttpForTest = true)
            val part = mgr.partialFileFor(99)
            part.writeBytes(ByteArray(payload.size + 100))

            val manifest = ApkReleaseManifest(
                versionCode = 99,
                versionName = "0.9.9",
                apkUrl = "${server.baseUrl}/apk",
                sizeBytes = payload.size.toLong(),
                sha256 = sha,
            )
            val res = mgr.downloadAndVerify(manifest, wifiOnly = false, currentVersionCode = 15)
            assertTrue(res is ApkVerificationResult.Rejected)
            assertTrue((res as ApkVerificationResult.Rejected).reason.contains("корректным Android APK"))
            assertFalse("Stale .part must be cleaned up", part.exists())
        }
    }

    @Test
    fun `verifyApkArchive fails closed when expected signer is set and archive has no signature`() {
        val mgr = ApkUpdateManager(ctx(), allowHttpForTest = true)
        val dummyFile = File(ctx().filesDir, "apk_updates/unsigned_test.apk").apply {
            parentFile?.mkdirs()
            writeText("not-a-signed-apk")
        }
        val manifest = ApkReleaseManifest(
            versionCode = 99,
            versionName = "0.9.9",
            apkUrl = "https://example.com/a.apk",
            sizeBytes = dummyFile.length(),
            sha256 = sha256(dummyFile.readBytes()),
        )
        val res = mgr.verifyApkArchive(
            apkFile = dummyFile,
            manifest = manifest,
            currentVersionCode = 15,
            expectedSignerSha256Override = "deadbeef",
        )
        assertTrue(res is ApkVerificationResult.Rejected)
        assertFalse("Rejected APK must be deleted", dummyFile.exists())
    }

    // ------------------------------------------------------------------
    // 2. ClassicBluetoothTransport: hung RFCOMM connect times out cleanly
    // ------------------------------------------------------------------

    @Test
    fun `ClassicBluetoothTransport times out hung RFCOMM connect instead of hanging forever`() {
        runBlocking {
            val hungConnector = object : ClassicBluetoothTransport.ClassicSocketConnector {
                override suspend fun probeProfile(mac: String) = ClassicBluetoothScanner.SdpProbeResult(
                    status = SppProfileStatus.CONFIRMED,
                    deviceType = 1,
                    bondState = 12,
                    uuids = listOf("00001101-0000-1000-8000-00805f9b34fb"),
                    fromCache = false,
                )

                override fun cancelDiscovery() {}

                override fun connect(
                    mac: String,
                    method: ClassicBluetoothTransport.RfcommAttemptMethod,
                ): ClassicBluetoothTransport.ClassicSocketStream {
                    Thread.sleep(600)
                    throw java.io.IOException("should have timed out earlier")
                }
            }

            val transport = ClassicBluetoothTransport(
                context = ctx(),
                mac = "00:11:22:33:44:55",
                deviceName = "Android-Vlink",
                log = RawLog(),
                reconnectAttempts = 0,
                connectorOverride = hungConnector,
                connectTimeoutMs = 100L,
            )
            transport.connect()
            assertTrue(transport.state.value is TransportState.Failed)
            assertFalse(transport.isConnected())
        }
    }

    // ------------------------------------------------------------------
    // 3. v0.5.5 (15) -> v0.5.6 (16) upgrade flow & install intent
    // ------------------------------------------------------------------

    @Test
    fun `v055 to v056 GitHub Releases upgrade checks versionCode, SHA-256, downgrade protection, and install intent`() {
        val apkBytes = "fake-release-v056-payload".toByteArray()
        val goodSha = sha256(apkBytes)
        lateinit var server: MiniHttpServer
        server = MiniHttpServer { path, _ ->
            when (path) {
                "/latest" -> {
                    val json = """
                        {
                          "tag_name": "v0.5.6",
                          "draft": false,
                          "prerelease": false,
                          "published_at": "2026-11-23T18:00:00Z",
                          "body": "Wildia v0.5.6",
                          "assets": [
                            {
                              "name": "release-manifest.json",
                              "size": 256,
                              "browser_download_url": "${server.baseUrl}/release-manifest.json"
                            }
                          ]
                        }
                    """.trimIndent().toByteArray(Charsets.UTF_8)
                    Triple(200, "application/json", json)
                }
                "/release-manifest.json" -> {
                    val manifest = """
                        {
                          "versionCode": 16,
                          "versionName": "0.5.6",
                          "changelog": "Wildia branding & adaptive icons",
                          "apkUrl": "${server.baseUrl}/obd2-v0.5.6-release.apk",
                          "sizeBytes": ${apkBytes.size},
                          "sha256": "$goodSha",
                          "minAndroidSdk": 26
                        }
                    """.trimIndent().toByteArray(Charsets.UTF_8)
                    Triple(200, "application/json", manifest)
                }
                else -> Triple(200, "application/vnd.android.package-archive", apkBytes)
            }
        }

        server.use {
            val mgr = ApkUpdateManager(ctx(), allowHttpForTest = true)

            // 1. From v0.5.5 (15) -> detects v0.5.6 (16)
            val check = mgr.checkForUpdate(
                manifestUrl = "${server.baseUrl}/latest",
                currentVersionCode = 15,
                currentVersionName = "0.5.5",
            )
            assertTrue(check is ApkCheckResult.UpdateAvailable)
            val m = (check as ApkCheckResult.UpdateAvailable).manifest
            assertEquals(16, m.versionCode)
            assertEquals("0.5.6", m.versionName)

            // 2. Already on v0.5.6 (16) -> UpToDate, and downgrade (15 <= 16) is rejected
            assertTrue(
                mgr.checkForUpdate(
                    manifestUrl = "${server.baseUrl}/latest",
                    currentVersionCode = 16,
                    currentVersionName = "0.5.6",
                ) is ApkCheckResult.UpToDate,
            )
            val downgrade = mgr.downloadAndVerify(
                manifest = m.copy(versionCode = 15, versionName = "0.5.5"),
                wifiOnly = false,
                currentVersionCode = 16,
            )
            assertTrue(downgrade is ApkVerificationResult.Rejected)

            // 3. Corrupted SHA-256 is rejected
            val badShaRes = mgr.downloadAndVerify(
                manifest = m.copy(sha256 = "0".repeat(64)),
                wifiOnly = false,
                currentVersionCode = 15,
            )
            assertTrue(badShaRes is ApkVerificationResult.Rejected)

            // 4. Install intent uses FileProvider & ACTION_VIEW
            val fakeApk = File(ctx().filesDir, "apk_updates/obd2-v0.5.6-release.apk").apply {
                parentFile?.mkdirs()
                writeBytes(apkBytes)
            }
            fun clearFileProviderCache() {
                runCatching {
                    val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
                    f.isAccessible = true
                    (f.get(null) as? MutableMap<*, *>)?.clear()
                }
            }
            clearFileProviderCache()
            try {
                val intent = mgr.buildInstallIntent(fakeApk)
                assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
                assertEquals("application/vnd.android.package-archive", intent.type)
                assertTrue((intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
            } finally {
                fakeApk.delete()
                clearFileProviderCache()
            }
        }
    }
}
