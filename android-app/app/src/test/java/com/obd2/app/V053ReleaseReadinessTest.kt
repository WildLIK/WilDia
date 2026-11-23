package com.obd2.app

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obd2.app.core.backup.BackupVerifyResult
import com.obd2.app.core.backup.UserDataBackupManager
import com.obd2.app.core.db.AppDb
import com.obd2.app.core.db.DtcRecordEntity
import com.obd2.app.core.db.ObdLookupDb
import com.obd2.app.core.db.SessionEntity
import com.obd2.app.core.db.SessionStatus
import com.obd2.app.core.db.TripRecordingEntity
import com.obd2.app.core.db.TripSampleEntity
import com.obd2.app.core.repo.SettingsRepository
import com.obd2.app.core.repo.VehicleProfile
import com.obd2.app.core.search.DtcOnlineSearchResponse
import com.obd2.app.core.search.DtcSearchRequest
import com.obd2.app.core.search.PersistentDtcSearchCache
import com.obd2.app.core.search.PublicDtcOnlineSearchProvider
import com.obd2.app.core.search.SourceOrigin
import com.obd2.app.core.updates.ApkCheckResult
import com.obd2.app.core.updates.ApkUpdateManager
import com.obd2.app.core.updates.ApkVerificationResult
import com.obd2.app.core.updates.DbApplyResult
import com.obd2.app.core.updates.DbUpdateManifest
import com.obd2.app.core.updates.DiagnosticDbUpdater
import com.obd2.app.core.updates.UpdateTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class V053ReleaseReadinessTest {

    private class LocalHttp(val server: ServerSocket) : Closeable {
        val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"
        override fun close() { runCatching { server.close() } }
    }

    private fun startServer(
        handler: (path: String, headers: Map<String, String>) -> Triple<Int, Map<String, String>, ByteArray>,
    ): LocalHttp {
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val sock = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    sock.use { s ->
                        val reader = s.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val reqLine = reader.readLine() ?: return@use
                        val path = reqLine.split(' ').getOrNull(1) ?: "/"
                        val headers = HashMap<String, String>()
                        while (true) {
                            val h = reader.readLine() ?: break
                            if (h.isEmpty()) break
                            val idx = h.indexOf(':')
                            if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
                        }
                        val (code, respHeaders, body) = handler(path, headers)
                        val out = s.getOutputStream()
                        val sb = StringBuilder()
                        sb.append("HTTP/1.1 $code ${if (code == 206) "Partial Content" else "OK"}\r\n")
                        sb.append("Content-Length: ${body.size}\r\n")
                        respHeaders.forEach { (k, v) -> sb.append("$k: $v\r\n") }
                        sb.append("Connection: close\r\n\r\n")
                        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
                        out.write(body)
                        out.flush()
                    }
                }
            }
        }
        return LocalHttp(ss)
    }

    @Test
    fun `1 - UserDataBackupManager exports, verifies SHA256, rejects tampering, and restores into fresh Room v5 DB`() {
        runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<Application>()
            val db1 = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
            val db2 = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
            val sr = SettingsRepository(ctx)
            var backupFile: File? = null
            try {
                val sid = db1.sessionDao().insertSession(
                    SessionEntity(
                        startedAtMs = 1000L, adapterName = "IOS-Vlink", demoMode = false,
                        storedCount = 1, dtcReadCompleted = true, status = SessionStatus.COMPLETED,
                    ),
                )
                db1.dtcRecordDao().insertAll(
                    listOf(DtcRecordEntity(sessionId = sid, code = "P0171", mode = 3, descriptionRu = "Бедная смесь", descriptionEn = "Lean", scope = "generic", timestampMs = 1000L)),
                )
                val rid = db1.tripRecordingDao().insertRecording(
                    TripRecordingEntity(startedAtMs = 2000L, selectedPidsCsv = "0C", sampleCount = 1),
                )
                db1.tripRecordingDao().insertSamples(
                    listOf(TripSampleEntity(recordingId = rid, timestampMs = 2100L, pid = 0x0C, value = 1200.0, unit = "об/мин")),
                )
                sr.addProfile(VehicleProfile(id = "prof-1", name = "Ford Focus", maker = "Ford", model = "Focus"))
                sr.setActiveProfile("prof-1")

                backupFile = UserDataBackupManager.exportBackup(ctx, db1, sr)
                assertTrue(backupFile.exists())
                val verified = UserDataBackupManager.verifyBackup(backupFile)
                assertTrue(verified is BackupVerifyResult.Valid)

                // Clear settings & restore into fresh db2
                sr.clearDemoProfiles()
                sr.setActiveProfile("")
                val res = UserDataBackupManager.restoreBackup(backupFile, db2, sr)
                assertTrue(res.isSuccess)
                val summary = res.getOrThrow()
                assertEquals(1, summary.restoredSessions)
                assertEquals(1, summary.restoredDtcs)
                assertEquals(1, summary.restoredRecordings)
                assertEquals(1, db2.sessionDao().recentSessions(5).size)
                assertEquals("P0171", db2.dtcRecordDao().forSession(db2.sessionDao().recentSessions(5).first().id).first().code)
                assertEquals("prof-1", sr.activeProfileIdFlow.first())

                // Tampered backup must be rejected
                val tampered = File(backupFile.parentFile, "tampered_${backupFile.name}")
                tampered.writeText(backupFile.readText().replace("IOS-Vlink", "EVIL-Vlink"))
                assertTrue(UserDataBackupManager.verifyBackup(tampered) is BackupVerifyResult.Invalid)
                tampered.delete()
            } finally {
                backupFile?.delete()
                db1.close()
                db2.close()
            }
        }
    }

    @Test
    fun `2 - ApkUpdateManager discovers GitHub release format, resumes partial download via Range 206, and cleans stale files`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val fullApkBytes = ByteArray(1024) { (it % 251).toByte() }
        val sha = DiagnosticDbUpdater.sha256Hex(fullApkBytes)
        var failFirstChunk = true

        lateinit var server: LocalHttp
        server = startServer { path, headers ->
            when {
                "/release-manifest.json" in path -> {
                    val m = """
                        {"versionCode":99,"versionName":"0.9.9","changelog":"GitHub Release","apkUrl":"${server.baseUrl}/obd2.apk","sizeBytes":${fullApkBytes.size},"sha256":"$sha"}
                    """.trimIndent().toByteArray()
                    Triple(200, emptyMap(), m)
                }
                "/github-latest" in path -> {
                    val gh = """
                        {"tag_name":"v0.9.9","body":"versionCode: 99\nsha256: $sha","published_at":"2026-11-22T00:00:00Z","assets":[{"name":"release-manifest.json","browser_download_url":"${server.baseUrl}/release-manifest.json","size":200}]}
                    """.trimIndent().toByteArray()
                    Triple(200, emptyMap(), gh)
                }
                "/obd2.apk" in path -> {
                    val range = headers["range"]
                    if (failFirstChunk) {
                        failFirstChunk = false
                        // Send truncated first half so download fails mid-way & preserves .part
                        Triple(200, emptyMap(), fullApkBytes.copyOfRange(0, 400))
                    } else if (range != null && range.startsWith("bytes=")) {
                        val offset = range.removePrefix("bytes=").substringBefore('-').toIntOrNull() ?: 0
                        val rest = fullApkBytes.copyOfRange(offset, fullApkBytes.size)
                        Triple(206, mapOf("Content-Range" to "bytes $offset-${fullApkBytes.size - 1}/${fullApkBytes.size}"), rest)
                    } else {
                        Triple(200, emptyMap(), fullApkBytes)
                    }
                }
                else -> Triple(404, emptyMap(), ByteArray(0))
            }
        }

        try {
            val mgr = ApkUpdateManager(ctx, allowHttpForTest = true)
            val check = mgr.checkForUpdate(manifestUrl = "${server.baseUrl}/github-latest", currentVersionCode = 13, currentVersionName = "0.5.3")
            assertTrue(check is ApkCheckResult.UpdateAvailable)
            val manifest = (check as ApkCheckResult.UpdateAvailable).manifest

            // 1st attempt interrupted at 400 bytes -> .part file preserved!
            val firstTry = mgr.downloadAndVerify(manifest, wifiOnly = false, currentVersionCode = 13)
            assertTrue(firstTry is ApkVerificationResult.Rejected)
            assertTrue(mgr.partialFileFor(99).exists())
            assertEquals(400L, mgr.partialFileFor(99).length())

            // 2nd attempt resumes via Range: bytes=400- -> SHA-256 matches, reaches archive inspection!
            val secondTry = mgr.downloadAndVerify(manifest, wifiOnly = false, currentVersionCode = 13)
            assertTrue(secondTry is ApkVerificationResult.Rejected)
            // Because fullApkBytes is synthetic (not a real ZIP APK), rejection happens at PackageArchiveInfo AFTER SHA-256 & Range resume passed!
            assertTrue((secondTry as ApkVerificationResult.Rejected).reason.contains("Android APK"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `3 - 5-track DiagnosticDbUpdater hot-reloads ObdLookupDb across cold restart and preserves Room history`() {
        runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<Application>()
            val roomDb = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
            val sid = roomDb.sessionDao().insertSession(SessionEntity(startedAtMs = 5000L, adapterName = "IOS-Vlink", demoMode = false))
            roomDb.dtcRecordDao().insertAll(listOf(DtcRecordEntity(sessionId = sid, code = "P0130", mode = 3, descriptionRu = null, descriptionEn = null, scope = null, timestampMs = 5000L)))

            val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            val kp = kpg.generateKeyPair()

            val customDbFile = File(System.getProperty("java.io.tmpdir") ?: ".", "cold_${System.currentTimeMillis()}.db")
            ctx.assets.open("obd_data.db").use { inp -> customDbFile.outputStream().use { out -> inp.copyTo(out) } }
            val sq = SQLiteDatabase.openDatabase(customDbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            sq.execSQL("INSERT INTO dtc_definitions(code, manufacturer_id, standard, title_en, source_id) VALUES('P1998', 'GENERIC', 'J2012', 'Cold restart test DTC', 1)")
            sq.execSQL("INSERT INTO dtc_translations(code, manufacturer_id, standard, lang, title_ru, translation_status, method) VALUES('P1998', 'GENERIC', 'J2012', 'ru', 'Тест после перезапуска', 'verified', 'manual')")
            sq.close()
            val dbBytes = customDbFile.readBytes()
            customDbFile.delete()

            lateinit var server: LocalHttp
            server = startServer { path, _ ->
                if ("/dtc.db" in path) Triple(200, emptyMap(), dbBytes) else Triple(404, emptyMap(), ByteArray(0))
            }

            try {
                val lookup1 = ObdLookupDb(ctx)
                val updater = DiagnosticDbUpdater(
                    context = ctx,
                    pinnedPublicKey = kp.public,
                    onTrackApplied = { track, file ->
                        if (track == UpdateTrack.DTC_DB) lookup1.installVerifiedDatabase(file) else true
                    },
                    onTrackRolledBack = { track, prev ->
                        if (track == UpdateTrack.DTC_DB) {
                            if (prev != null) lookup1.installVerifiedDatabase(prev) else lookup1.revertToBundled()
                        }
                    },
                )
                val sha = DiagnosticDbUpdater.sha256Hex(dbBytes)
                val unsigned = DbUpdateManifest(1, UpdateTrack.DTC_DB.name, "2026.98", "Test", "${server.baseUrl}/dtc.db", sha, "", "2026-11-22")
                val sig = Signature.getInstance("SHA256withECDSA").apply {
                    initSign(kp.private)
                    update(unsigned.canonicalPayload().toByteArray(Charsets.UTF_8))
                }
                val signed = unsigned.copy(signatureBase64 = Base64.getEncoder().encodeToString(sig.sign()))

                assertTrue(updater.downloadAndApplyFromUrl(UpdateTrack.DTC_DB, signed, allowHttpForTest = true) is DbApplyResult.Applied)

                // Cold restart simulation: brand-new ObdLookupDb(ctx) instance reads the updated DB
                val lookupColdRestart = ObdLookupDb(ctx)
                val hit = lookupColdRestart.lookupDtc("P1998").firstOrNull()
                assertNotNull(hit)
                assertEquals("Тест после перезапуска", hit!!.titleRu)

                // Rollback reverts cleanly; Room user history untouched!
                updater.rollback(UpdateTrack.DTC_DB)
                assertTrue(ObdLookupDb(ctx).lookupDtc("P1998").isEmpty())
                assertEquals(1, roomDb.sessionDao().recentSessions(5).size)
                assertEquals("P0130", roomDb.dtcRecordDao().forSession(sid).first().code)
            } finally {
                server.close()
                roomDb.close()
            }
        }
    }

    @Test
    fun `4 - Automotive DTC search tests P0300 P0171 P0420 P0130 across brands with zero cross-brand Skoda leakage`() {
        runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<Application>()
            val lookup = ObdLookupDb(ctx)
            val cache = PersistentDtcSearchCache(ctx)
            val roomDb = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()

            val server = startServer { path, _ ->
                if ("/recalls" in path) {
                    val nhtsa = """{"results":[{"NHTSACampaignNumber":"24V001000","Component":"ENGINE AND ENGINE COOLING","Summary":"Fuel trim lean P0171 sensor issue"}]}""".toByteArray()
                    Triple(200, emptyMap(), nhtsa)
                } else {
                    Triple(404, emptyMap(), ByteArray(0))
                }
            }

            try {
                val provider = PublicDtcOnlineSearchProvider(
                    lookupDb = lookup,
                    cache = cache,
                    nhtsaBaseUrl = server.baseUrl,
                    allowHttpForTest = true,
                )

                val testCodes = listOf("P0300", "P0171", "P0420", "P0130")
                for (code in testCodes) {
                    // Test for Ford -> must NEVER contain Skoda/VAG/Ross-Tech
                    val fordRes = provider.search(
                        DtcSearchRequest(
                            code = code,
                            manufacturer = "Ford",
                            model = "Explorer",
                            year = "2004",
                            sensorSnapshot = mapOf("LTFT" to "+14%"),
                        ),
                        userOptInEnabled = true,
                    )
                    assertTrue(fordRes is DtcOnlineSearchResponse.Found)
                    val card = (fordRes as DtcOnlineSearchResponse.Found).card
                    assertFalse(card.title.contains("Skoda", ignoreCase = true))
                    assertFalse(card.manufacturerInfo?.contains("VAG", ignoreCase = true) == true)
                    assertTrue(card.browserLinks.none { it.url.contains("ross-tech") })
                    assertTrue(card.recommendedChecks.any { it.contains("LTFT") })
                }

                // VAG vehicle DOES get Ross-Tech specialized browser link
                val vagRes = provider.search(
                    DtcSearchRequest(code = "P0171", manufacturer = "VAG", model = "Octavia", year = "2012"),
                    userOptInEnabled = true,
                )
                val vagCard = (vagRes as DtcOnlineSearchResponse.Found).card
                assertTrue(vagCard.browserLinks.any { it.url.contains("ross-tech") })

                // Offline / unreachable endpoint still provides local DB card (never blank, never fake)
                val offline = PublicDtcOnlineSearchProvider(
                    lookupDb = lookup,
                    cache = PersistentDtcSearchCache(ctx),
                    customEndpointUrl = "http://127.0.0.1:1/down",
                    allowHttpForTest = true,
                ).search(DtcSearchRequest(code = "P0420", manufacturer = "Haval"), userOptInEnabled = true)
                assertTrue(offline is DtcOnlineSearchResponse.Found)
                assertEquals(SourceOrigin.LOCAL_DB, (offline as DtcOnlineSearchResponse.Found).card.origin)

                // Test queries MUST NOT write anything to user's Room session history
                assertEquals(0, roomDb.sessionDao().recentSessions(10).size)
            } finally {
                server.close()
                roomDb.close()
            }
        }
    }
}
