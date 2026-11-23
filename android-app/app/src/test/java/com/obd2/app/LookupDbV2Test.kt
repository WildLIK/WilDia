package com.obd2.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obd2.app.core.db.ObdLookupDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regression tests for the v2 lookup layer: RU search, rich DTC lookup with
 * translation statuses, vehicle profile, parameter catalog, and the external
 * data-package import guard.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LookupDbV2Test {

    private fun db(): ObdLookupDb = ObdLookupDb(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun metaReportsV2Stats() = runBlocking {
        val m = db().meta()
        assertTrue("schema 2.x expected, got ${m.version}", m.version.startsWith("2"))
        assertTrue("pids >= 132 expected, got ${m.pids}", m.pids >= 132)
        assertTrue("dtcTotal >= 19000 expected", m.dtcTotal >= 19000)
        assertTrue("ruVerified > 200 expected", m.ruVerified > 200)
        assertTrue("ruAuto > 5000 expected", m.ruAuto > 5000)
        assertTrue("paramDefinitions >= 200 expected", m.paramDefinitions >= 200)
        assertTrue("didDefinitions >= 10 expected", m.didDefinitions >= 10)
        assertTrue("sources string must be populated", m.sources.isNotBlank())
        assertTrue(m.sources.contains("obdex"))
    }

    @Test
    fun searchByCodeAndRuText() = runBlocking {
        val d = db()
        val byCode = d.searchDtc("P0300", 50)
        assertTrue("P0300 must be found", byCode.any { it.code == "P0300" && it.scope == "GENERIC" })
        val ruSearch = d.searchDtc("пропуски зажигания", 50)
        assertTrue(
            "RU text search must find misfire codes, got ${ruSearch.size}",
            ruSearch.isNotEmpty(),
        )
        assertTrue(ruSearch.all { it.titleRu != null || it.titleEn.isNotBlank() })
        // verified P0300 translation with curated text
        val p0300 = byCode.first { it.code == "P0300" && it.scope == "GENERIC" }
        assertEquals("verified", p0300.translationStatus)
        assertTrue(p0300.titleRu!!.contains("пропуск", ignoreCase = true))
        assertNotNull(p0300.recommendationsRu)
        assertTrue(p0300.checks.isNotEmpty())
    }

    @Test
    fun searchInsideRuDetails() = runBlocking {
        val d = db()
        // The phrase appears only in the verified P0420 Russian detail; the
        // secondary search over detail_ru_lower must surface it.
        val res = d.searchDtc("оценивает эффективность нейтрализатора", 50)
        assertTrue(res.isNotEmpty())
        assertTrue(res.any { it.code == "P0420" })
    }

    @Test
    fun lookupDtcProvidesSymptomsCausesRelated() = runBlocking {
        val d = db()
        val rows = d.lookupDtc("P0420")
        val generic = rows.first { it.scope == "GENERIC" }
        assertTrue(generic.causes.isNotEmpty())
        assertTrue(generic.symptoms.isNotEmpty())
        assertTrue(generic.relatedCodes.isNotEmpty())
        assertEquals("verified", generic.translationStatus)
        assertNotNull(generic.severity)
        // VAG-tagged rows, when present, must never replace the generic one
        assertTrue(rows.first().scope == "GENERIC")
    }

    @Test
    fun manufacturerLookupSeparatesOemMeanings() = runBlocking {
        val d = db()
        val makers = d.manufacturers()
        assertTrue(makers.any { it.id == "GENERIC" && it.codes > 9000 })
        assertTrue(makers.any { it.id == "VOLKSWAGEN" && it.codes > 400 })
        // a code that exists for VW keeps its own row (not merged with GENERIC)
        val vwRows = d.lookupDtc("P1136").filter { it.scope == "VOLKSWAGEN" }
        assertTrue("P1136 VW-specific row expected", vwRows.isNotEmpty())
        assertTrue(vwRows.all { it.groupTag == "VAG" })
    }

    @Test
    fun vehicleProfileDescribesOctaviaA5() = runBlocking {
        val p = db().vehicleProfile("skoda-octavia-a5-1z")
        assertNotNull(p)
        p!!
        assertTrue(p.title.contains("Октавия") || p.title.contains("Octavia"))
        assertEquals(6, p.ecus.size)
        val engineEcu = p.ecus.first { it.partNumber.startsWith("06A") }
        assertTrue(engineEcu.controller!!.contains("Simos 7.1"))
        assertTrue(p.supportExpected in 5..60)
        assertEquals("no measured support until field-tested", 0, p.supportConfirmed)
        assertNotNull(p.adapterNote)
        assertTrue(p.adapterNote!!.contains("не проводилась") || p.adapterNote!!.contains("unverified"))
    }

    @Test
    fun parameterCatalogIncludesDidsAndVagNote() = runBlocking {
        val catalog = db().parameterCatalog(400)
        assertTrue(catalog.any { it.kind == "uds_did" && it.pidHex == "F190" })
        assertTrue(catalog.any { it.kind == "vag_meas_group" && it.nameRu!!.contains("TP 2.0") })
        assertTrue(catalog.any { it.kind == "obd_pid" && it.pidHex == "0C" })
    }

    @Test
    fun importRejectsInvalidFile() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val tmp = java.io.File(ctx.filesDir, "not_a_db.bin")
        tmp.writeText("definitely not sqlite")
        val result = try {
            db().importDatabase(android.net.Uri.fromFile(tmp))
        } catch (e: Exception) {
            "exception: ${e.message}"
        }
        // Must reject (non-null error) or throw — never silently accept
        assertTrue("invalid file must be rejected, got $result", result != null)
        tmp.delete()
        Unit
    }
}
