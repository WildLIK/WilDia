package com.obd2.app

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obd2.app.core.db.ObdLookupDb
import com.obd2.app.core.search.ConfidenceStatus
import com.obd2.app.core.search.DtcOnlineSearchResponse
import com.obd2.app.core.search.DtcSearchRequest
import com.obd2.app.core.search.PersistentDtcSearchCache
import com.obd2.app.core.search.PublicDtcOnlineSearchProvider
import com.obd2.app.core.updates.ApkCheckResult
import com.obd2.app.core.updates.ApkReleaseManifest
import com.obd2.app.core.updates.ApkUpdateManager
import com.obd2.app.core.updates.ApkVerificationResult
import com.obd2.app.core.updates.DbApplyResult
import com.obd2.app.core.updates.DbUpdateManifest
import com.obd2.app.core.updates.DiagnosticDbUpdater
import com.obd2.app.core.updates.UpdateTrack
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
class V052SearchAndUpdatesTest {

    private class LocalHttp(val server: ServerSocket) : Closeable {
        val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"
        override fun close() { runCatching { server.close() } }
    }

    private fun startServer(handler: (String) -> Pair<Int, ByteArray>): LocalHttp {
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val sock = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    sock.use { s ->
                        val reader = s.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val reqLine = reader.readLine() ?: return@use
                        val path = reqLine.split(' ').getOrNull(1) ?: "/"
                        while (true) {
                            val h = reader.readLine() ?: break
                            if (h.isEmpty()) break
                        }
                        val (code, body) = handler(path)
                        val out = s.getOutputStream()
                        val hdr = "HTTP/1.1 $code OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        out.write(hdr.toByteArray(Charsets.US_ASCII))
                        out.write(body)
                        out.flush()
                    }
                }
            }
        }
        return LocalHttp(ss)
    }

    @Test
    fun `1 to 12 - Online DTC search covers consent, unknown brand, multiple codes, offline, contradictions, cache, privacy, legacy`() {
        runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<Application>()
            val lookup = ObdLookupDb(ctx)
            val cache = PersistentDtcSearchCache(ctx)

            val server = startServer { path ->
                when {
                    "code=P0171" in path -> 200 to """
                        {
                          "standardMeaning": "Слишком бедная смесь (Банк 1)",
                          "causes": [
                            {"text": "Подсос воздуха во впускном тракте", "status": "MULTIPLE_INDEPENDENT_SOURCES"}
                          ],
                          "checks": ["Проверить ДМРВ и подсос дымогенератором"],
                          "sources": [
                            {"title": "SAE J2012", "url": "https://example.org/j2012/p0171", "tier": "OFFICIAL_DOCS", "summary": "бедная смесь"},
                            {"title": "Tech Note", "url": "https://example.org/note", "tier": "TECHNICAL_REFERENCE", "summary": "богатая смесь по другому бюллетеню"}
                          ]
                        }
                    """.trimIndent().toByteArray()
                    "code=P0420" in path -> 200 to """{"sources":[]}""".toByteArray()
                    else -> 404 to ByteArray(0)
                }
            }

            try {
                val provider = PublicDtcOnlineSearchProvider(
                    lookupDb = lookup,
                    cache = cache,
                    customEndpointUrl = "${server.baseUrl}/search",
                    allowHttpForTest = true,
                )

                // 2. Requires user consent
                val disabled = provider.search(DtcSearchRequest(code = "P0171"), userOptInEnabled = false)
                assertTrue(disabled is DtcOnlineSearchResponse.Disabled)

                // 11. Privacy: VIN never included in query without consent
                val reqNoVin = DtcSearchRequest(
                    code = "P0171",
                    manufacturer = null,
                    includeVinConsent = false,
                    maskedVinOnly = "TMBCA21ZXXXXXXXXX",
                )
                val q = provider.buildQueryString(reqNoVin)
                assertFalse(q.contains("TMB"))
                assertFalse(q.contains("Skoda", ignoreCase = true))

                // 3 & 9. Unknown brand + contradicting sources + 10. Saved in cache
                val found = provider.search(reqNoVin, userOptInEnabled = true)
                assertTrue(found is DtcOnlineSearchResponse.Found)
                val card = (found as DtcOnlineSearchResponse.Found).card
                assertNotNull(card.contradictionNote)
                assertTrue(card.possibleCauses.any { it.status == ConfidenceStatus.MULTIPLE_INDEPENDENT_SOURCES })

                val fromCache = provider.search(reqNoVin, userOptInEnabled = true)
                assertTrue((fromCache as DtcOnlineSearchResponse.Found).card.fromCache)

                // 4 & 5 & 12. Known maker without engine + legacy unverified flag
                val legacyReq = DtcSearchRequest(
                    code = "P0420",
                    manufacturer = "Ford",
                    engine = null,
                    relatedCodes = listOf("P0171"),
                    isLegacyUnverified = true,
                )
                val legacyRes = provider.search(legacyReq, userOptInEnabled = true)
                assertTrue(legacyRes is DtcOnlineSearchResponse.Found)
                assertTrue((legacyRes as DtcOnlineSearchResponse.Found).card.isLegacyUnverified)

                // 6 & 7. Server down / offline still returns local/cached info + browser fallback
                val offlineProvider = PublicDtcOnlineSearchProvider(
                    lookupDb = lookup,
                    cache = PersistentDtcSearchCache(ctx),
                    customEndpointUrl = "http://127.0.0.1:1/unreachable",
                    allowHttpForTest = true,
                )
                val offlineRes = offlineProvider.search(DtcSearchRequest(code = "P0300"), userOptInEnabled = true)
                assertTrue(offlineRes is DtcOnlineSearchResponse.Found)
                val offlineCard = (offlineRes as DtcOnlineSearchResponse.Found).card
                assertNotNull(offlineCard.offlineNotice)
                assertTrue(offlineCard.fallbackSearchUrl.startsWith("https://"))
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun `13 - APK update manager checks available, up-to-date, corrupt SHA, low space, downgrade, and server error`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val payload = "FAKE_APK_BYTES_FOR_HASH_CHECK".toByteArray()
        val sha = DiagnosticDbUpdater.sha256Hex(payload)

        var currentManifestJson = """
            {
              "versionCode": 99,
              "versionName": "0.9.9",
              "changelog": "Test release",
              "apkUrl": "",
              "sizeBytes": ${payload.size},
              "sha256": "$sha",
              "minAndroidSdk": 26
            }
        """.trimIndent()

        lateinit var server: LocalHttp
        server = startServer { path ->
            when {
                "/manifest" in path -> 200 to currentManifestJson.replace("\"apkUrl\": \"\"", "\"apkUrl\": \"${server.baseUrl}/app.apk\"").toByteArray()
                "/app.apk" in path -> 200 to payload
                "/truncated.apk" in path -> 200 to payload.copyOf(5)
                else -> 500 to ByteArray(0)
            }
        }
        val baseUrl = server.baseUrl

        try {
            val mgr = ApkUpdateManager(ctx, allowHttpForTest = true)

            // Update available
            val avail = mgr.checkForUpdate("$baseUrl/manifest", currentVersionCode = 12, currentVersionName = "0.5.2")
            assertTrue(avail is ApkCheckResult.UpdateAvailable)

            // Up-to-date when current >= remote
            val upToDate = mgr.checkForUpdate("$baseUrl/manifest", currentVersionCode = 99, currentVersionName = "0.9.9")
            assertTrue(upToDate is ApkCheckResult.UpToDate)

            // Server unreachable
            val err = mgr.checkForUpdate("http://127.0.0.1:1/manifest", currentVersionCode = 12, currentVersionName = "0.5.2")
            assertTrue(err is ApkCheckResult.Error)

            val manifest = (avail as ApkCheckResult.UpdateAvailable).manifest

            // Downgrade protection
            val downgrade = mgr.downloadAndVerify(manifest, wifiOnly = false, currentVersionCode = 100)
            assertTrue(downgrade is ApkVerificationResult.Rejected)

            // Low disk space
            val lowSpace = mgr.downloadAndVerify(manifest, wifiOnly = false, currentVersionCode = 12, availableSpaceOverrideBytes = 1024L)
            assertTrue(lowSpace is ApkVerificationResult.Rejected)

            // Interrupted download
            val truncated = mgr.downloadAndVerify(
                manifest.copy(apkUrl = "$baseUrl/truncated.apk"),
                wifiOnly = false,
                currentVersionCode = 12,
            )
            assertTrue(truncated is ApkVerificationResult.Rejected)

            // Corrupted SHA-256
            val badSha = mgr.downloadAndVerify(
                manifest.copy(sha256 = "0000"),
                wifiOnly = false,
                currentVersionCode = 12,
            )
            assertTrue(badSha is ApkVerificationResult.Rejected)
        } finally {
            server.close()
        }
    }

    @Test
    fun `14 - DiagnosticDbUpdater hot-reloads ObdLookupDb from local HTTP server and rolls back cleanly`() {
        runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<Application>()
            val lookup = ObdLookupDb(ctx)
            val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            val kp = kpg.generateKeyPair()

            // Copy real bundled obd_data.db and insert custom DTC P1999 to test real hot-reload
            val customDbFile = File(System.getProperty("java.io.tmpdir") ?: ".", "custom_dtc_${System.currentTimeMillis()}.db")
            ctx.assets.open("obd_data.db").use { inp -> customDbFile.outputStream().use { out -> inp.copyTo(out) } }
            val sq = SQLiteDatabase.openDatabase(customDbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            sq.execSQL("INSERT OR REPLACE INTO meta(key, value) VALUES('version', '2026.99-test')")
            sq.execSQL(
                "INSERT INTO dtc_definitions(code, manufacturer_id, standard, title_en, source_id) VALUES('P1999', 'GENERIC', 'J2012', 'Custom test DTC', 1)",
            )
            sq.execSQL(
                "INSERT INTO dtc_translations(code, manufacturer_id, standard, lang, title_ru, translation_status, method) VALUES('P1999', 'GENERIC', 'J2012', 'ru', 'Тестовый код из обновлённой БД', 'verified', 'manual')",
            )
            sq.close()
            val dbBytes = customDbFile.readBytes()
            customDbFile.delete()

            val server = startServer { path ->
                when {
                    "/dtc.db" in path -> 200 to dbBytes
                    else -> 404 to ByteArray(0)
                }
            }

            try {
                val updater = DiagnosticDbUpdater(
                    context = ctx,
                    pinnedPublicKey = kp.public,
                    onTrackApplied = { track, file ->
                        if (track == UpdateTrack.DTC_DB) lookup.installVerifiedDatabase(file) else true
                    },
                    onTrackRolledBack = { track, prev ->
                        if (track == UpdateTrack.DTC_DB) {
                            if (prev != null) lookup.installVerifiedDatabase(prev) else lookup.revertToBundled()
                        }
                    },
                )

                val sha = DiagnosticDbUpdater.sha256Hex(dbBytes)
                val unsigned = DbUpdateManifest(
                    schemaVersion = 1,
                    track = UpdateTrack.DTC_DB.name,
                    version = "2026.99-test",
                    changelog = "Added P1999",
                    downloadUrl = "${server.baseUrl}/dtc.db",
                    sha256 = sha,
                    signatureBase64 = "",
                    releasedAt = "2026-11-22",
                )
                val sig = Signature.getInstance("SHA256withECDSA").apply {
                    initSign(kp.private)
                    update(unsigned.canonicalPayload().toByteArray(Charsets.UTF_8))
                }
                val signed = unsigned.copy(signatureBase64 = Base64.getEncoder().encodeToString(sig.sign()))

                // Confirm P1999 was not in bundled DB, then hot-reload
                assertTrue(lookup.lookupDtc("P1999").isEmpty())
                val res = updater.downloadAndApplyFromUrl(UpdateTrack.DTC_DB, signed, allowHttpForTest = true)
                assertTrue(res is DbApplyResult.Applied)

                // Immediately visible in live ObdLookupDb!
                val reloaded = lookup.lookupDtc("P1999").firstOrNull()
                assertNotNull(reloaded)
                assertEquals("Тестовый код из обновлённой БД", reloaded!!.titleRu)

                // Rollback restores bundled DB (P1999 disappears)
                updater.rollback(UpdateTrack.DTC_DB)
                assertTrue(lookup.lookupDtc("P1999").isEmpty())
            } finally {
                server.close()
            }
        }
    }
}
