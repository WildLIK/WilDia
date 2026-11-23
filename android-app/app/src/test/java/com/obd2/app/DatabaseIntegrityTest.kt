package com.obd2.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/**
 * Verifies the bundled offline database (assets/obd_data.db) is a valid,
 * non-empty SQLite file with the expected schema.
 */
class DatabaseIntegrityTest {

    private fun locateDb(): File {
        // module dir -> app/assets
        val candidates = listOf(
            File("src/main/assets/obd_data.db"),
            File("android-app/app/src/main/assets/obd_data.db"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("obd_data.db not found; run tools/build_db.py first")
    }

    @Test
    fun `database passes integrity_check`() {
        val db = locateDb()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { con ->
            con.createStatement().use { st ->
                st.executeQuery("PRAGMA integrity_check").use { rs ->
                    assertTrue("integrity_check must return ok", rs.next() && rs.getString(1) == "ok")
                }
            }
        }
    }

    @Test
    fun `schema has expected tables`() {
        val db = locateDb()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { con ->
            val expected = setOf(
                "meta", "pids", "dtc", "dtc_categories",
                "data_sources", "manufacturers", "vehicle_models", "vehicle_generations",
                "engines", "protocols", "ecus", "vehicle_ecus", "adapter_compatibility",
                "dtc_definitions", "dtc_probable_causes", "dtc_symptoms", "dtc_checks",
                "dtc_translations", "dtc_cause_translations", "dtc_symptom_translations",
                "dtc_conflicts", "parameter_definitions", "vehicle_parameter_support",
            )
            con.createStatement().use { st ->
                st.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table'",
                ).use { rs ->
                    val names = HashSet<String>()
                    while (rs.next()) names.add(rs.getString(1))
                    assertTrue("missing tables: ${expected - names}", expected.all { it in names })
                }
            }
        }
    }

    @Test
    fun `database has substantial content`() {
        val db = locateDb()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { con ->
            con.createStatement().use { st ->
                st.executeQuery("SELECT (SELECT COUNT(*) FROM pids), (SELECT COUNT(*) FROM dtc)").use { rs ->
                    rs.next()
                    val pids = rs.getInt(1)
                    val dtc = rs.getInt(2)
                    assertTrue("pids expected >= 100, got $pids", pids >= 100)
                    assertTrue("dtc expected >= 15000, got $dtc", dtc >= 15000)
                }
                // Spot-check known codes
                st.executeQuery(
                    "SELECT COUNT(*) FROM dtc WHERE code='P0300' AND scope='GENERIC'",
                ).use { rs ->
                    rs.next()
                    assertEquals(1, rs.getInt(1))
                }
                st.executeQuery(
                    "SELECT COUNT(*) FROM dtc WHERE group_tag='VAG'",
                ).use { rs ->
                    rs.next()
                    assertTrue("VAG codes expected > 300", rs.getInt(1) > 300)
                }
                // PID 0C with RU name present
                st.executeQuery(
                    "SELECT name_ru FROM pids WHERE mode=1 AND pid=12",
                ).use { rs ->
                    rs.next()
                    assertEquals("Обороты двигателя", rs.getString(1))
                }
            }
        }
    }

    // ---------------- schema v2 additions ----------------

    @Test
    fun `schema v2 meta reports version 2 and provenance`() {
        val db = locateDb()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { con ->
            val meta = HashMap<String, String>()
            con.createStatement().use { st ->
                st.executeQuery("SELECT key, value FROM meta").use { rs ->
                    while (rs.next()) meta[rs.getString(1)] = rs.getString(2)
                }
            }
            assertTrue("schema version 2 expected, got ${meta["schema"]}", meta["schema"] == "2")
            assertTrue("source_pid must mention obdex", (meta["source_pid"] ?: "").contains("obdex"))
            assertTrue(
                "attribution for OBDb/SAEJ1979 required (CC-BY-SA)",
                (meta["translation_attribution"] ?: "").contains("CC-BY-SA"),
            )
        }
    }

    @Test
    fun `translations are linked and statused`() {
        val db = locateDb()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { con ->
            con.createStatement().use { st ->
                st.executeQuery(
                    "SELECT (SELECT COUNT(*) FROM dtc_translations), " +
                        "(SELECT COUNT(*) FROM dtc_translations WHERE translation_status='verified'), " +
                        "(SELECT COUNT(*) FROM dtc_translations WHERE translation_status='auto')",
                ).use { rs ->
                    rs.next()
                    val total = rs.getInt(1)
                    val verified = rs.getInt(2)
                    val auto = rs.getInt(3)
                    assertTrue("translations expected > 5000, got $total", total > 5000)
                    assertTrue("verified expected > 200, got $verified", verified > 200)
                    assertTrue("auto expected > 5000, got $auto", auto > 5000)
                    assertTrue(
                        "verified + auto must equal total",
                        verified + auto == total,
                    )
                }
                // no orphan translations
                st.executeQuery(
                    "SELECT COUNT(*) FROM dtc_translations t WHERE NOT EXISTS (" +
                        "SELECT 1 FROM dtc_definitions d WHERE d.code=t.code " +
                        "AND d.manufacturer_id=t.manufacturer_id AND d.standard=t.standard)",
                ).use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt(1))
                }
                // P0300 must have a verified curated translation
                st.executeQuery(
                    "SELECT title_ru FROM dtc_translations WHERE code='P0300' " +
                        "AND manufacturer_id='GENERIC' AND translation_status='verified'",
                ).use { rs ->
                    rs.next()
                    assertTrue(rs.getString(1).contains("пропуск"))
                }
            }
        }
    }

    @Test
    fun `manufacturer-specific codes are separate rows`() {
        val db = locateDb()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { con ->
            con.createStatement().use { st ->
                // P0300 exists as GENERIC; a maker row for the same code must not
                // overwrite it (composite key (code, manufacturer))
                st.executeQuery(
                    "SELECT COUNT(*) FROM dtc_definitions WHERE code='P0420' AND manufacturer_id='GENERIC'",
                ).use { rs ->
                    rs.next()
                    assertEquals(1, rs.getInt(1))
                }
                st.executeQuery(
                    "SELECT COUNT(DISTINCT manufacturer_id) FROM dtc_definitions",
                ).use { rs ->
                    rs.next()
                    assertTrue("expected >= 34 manufacturers incl GENERIC", rs.getInt(1) >= 34)
                }
            }
        }
    }

    @Test
    fun `vehicle profile and parameter catalog are populated`() {
        val db = locateDb()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { con ->
            con.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM ecus WHERE system='engine'").use { rs ->
                    rs.next()
                    assertEquals(1, rs.getInt(1))
                }
                st.executeQuery(
                    "SELECT controller_family FROM ecus WHERE part_number='06A906033KD'",
                ).use { rs ->
                    rs.next()
                    assertTrue("Simos 7.1 expected", rs.getString(1).contains("Simos 7.1"))
                }
                st.executeQuery(
                    "SELECT COUNT(*) FROM vehicle_parameter_support WHERE support_level='confirmed'",
                ).use { rs ->
                    rs.next()
                    assertEquals("nothing is 'confirmed' without a real measurement", 0, rs.getInt(1))
                }
                st.executeQuery(
                    "SELECT COUNT(*) FROM parameter_definitions WHERE kind='uds_did'",
                ).use { rs ->
                    rs.next()
                    assertTrue("UDS DID catalog expected", rs.getInt(1) >= 10)
                }
                st.executeQuery(
                    "SELECT COUNT(*) FROM parameter_definitions WHERE mode=1 AND pid_hex='0C'",
                ).use { rs ->
                    rs.next()
                    assertEquals("PID 0C definition expected", 1, rs.getInt(1))
                }
            }
        }
    }
}
