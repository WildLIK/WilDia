#!/usr/bin/env python3
"""
Validates the built offline database (assets/obd_data.db):
  * SQLite integrity + foreign keys
  * DTC code format, duplicates, manufacturer linkage
  * orphan translations / causes / symptoms
  * vehicle/ECU/profile consistency
  * RU translation status sanity
  * search performance (code / RU text / EN text)
Prints PASS/FAIL lines and exits non-zero on failure.
Run:  python tools/validate_db.py
"""
import os
import re
import sqlite3
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB = os.path.join(ROOT, "android-app", "app", "src", "main", "assets", "obd_data.db")

CODE_RE = re.compile(r"^[PBCU][0-9A-F]{4}$")

failures = []


def check(name, cond, detail=""):
    mark = "PASS" if cond else "FAIL"
    print(f"[{mark}] {name}" + (f" — {detail}" if detail and not cond else ""))
    if not cond:
        failures.append(name)


def main():
    con = sqlite3.connect(DB)
    con.execute("PRAGMA foreign_keys = ON")
    cur = con.cursor()
    q = lambda sql, args=(): cur.execute(sql, args).fetchone()[0]

    check("integrity_check = ok", q("PRAGMA integrity_check") == "ok")
    fk = cur.execute("PRAGMA foreign_key_check").fetchall()
    check("foreign_key_check empty", not fk, str(fk[:5]))

    # schema presence
    tables = {r[0] for r in cur.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    expected = {
        "meta", "data_sources", "manufacturers", "vehicle_models", "vehicle_generations",
        "engines", "engine_fitment", "protocols", "ecus", "vehicle_ecus",
        "adapter_compatibility", "dtc_definitions", "dtc_probable_causes", "dtc_symptoms",
        "dtc_checks", "dtc_translations", "dtc_cause_translations", "dtc_symptom_translations",
        "dtc_conflicts", "parameter_definitions", "vehicle_parameter_support",
        "pids", "dtc", "dtc_categories",
    }
    check("all v2 tables present", expected <= tables, str(expected - tables))

    # DTC sanity
    total = q("SELECT COUNT(*) FROM dtc_definitions")
    bad_codes = cur.execute("SELECT COUNT(*) FROM dtc_definitions WHERE length(code)!=5").fetchone()[0]
    check("dtc code length 5", bad_codes == 0)
    bad_fmt = cur.execute(
        "SELECT COUNT(*) FROM dtc_definitions WHERE code GLOB '[PBCU][0-9A-F]*' == 0").fetchone()[0]
    check("dtc code charset P/B/C/U + hex", bad_fmt == 0)
    dup = cur.execute(
        "SELECT COUNT(*) FROM (SELECT code, manufacturer_id, standard, COUNT(*) c "
        "FROM dtc_definitions GROUP BY 1,2,3 HAVING c > 1)").fetchone()[0]
    check("no duplicate (code, manufacturer, standard)", dup == 0)
    generic = q("SELECT COUNT(*) FROM dtc_definitions WHERE manufacturer_id='GENERIC'")
    check("generic codes present", generic > 9000, str(generic))
    vag = q("SELECT COUNT(*) FROM dtc WHERE group_tag='VAG'")
    check("legacy VAG tag > 300", vag > 300, str(vag))

    # legacy table parity with definitions
    legacy = q("SELECT COUNT(*) FROM dtc")
    check("legacy dtc == dtc_definitions", legacy == total, f"{legacy} vs {total}")

    # translations
    orphan = cur.execute(
        "SELECT COUNT(*) FROM dtc_translations t WHERE NOT EXISTS ("
        "  SELECT 1 FROM dtc_definitions d WHERE d.code=t.code AND d.manufacturer_id=t.manufacturer_id"
        "  AND d.standard=t.standard)").fetchone()[0]
    check("no orphan dtc_translations", orphan == 0)
    verified = q("SELECT COUNT(*) FROM dtc_translations WHERE translation_status='verified'")
    auto = q("SELECT COUNT(*) FROM dtc_translations WHERE translation_status='auto'")
    check("verified translations exist", verified > 200, str(verified))
    check("auto translations exist", auto > 5000, str(auto))
    bad_status = q("SELECT COUNT(*) FROM dtc_translations WHERE translation_status NOT IN ('verified','auto','imported')")
    check("translation_status enum", bad_status == 0)
    # verified must not be auto-replaceable gibberish: spot checks
    spots = {
        "P0300": "пропуски",
        "P0420": "нейтрализатора",
        "P0171": "бедная",
        "U0100": "связ",
        "P0700": "трансмисси",
    }
    for code, needle in spots.items():
        t = cur.execute(
            "SELECT title_ru FROM dtc_translations WHERE code=? AND manufacturer_id='GENERIC' AND lang='ru'",
            (code,)).fetchone()
        check(f"spot RU {code}", t and needle in t[0].lower(), str(t))

    # causes/symptoms linkage
    orph_causes = cur.execute(
        "SELECT COUNT(*) FROM dtc_probable_causes c WHERE NOT EXISTS ("
        " SELECT 1 FROM dtc_definitions d WHERE d.code=c.code AND d.manufacturer_id=c.manufacturer_id"
        " AND d.standard=c.standard)").fetchone()[0]
    check("no orphan probable causes", orph_causes == 0)
    orph_syms = cur.execute(
        "SELECT COUNT(*) FROM dtc_symptoms c WHERE NOT EXISTS ("
        " SELECT 1 FROM dtc_definitions d WHERE d.code=c.code AND d.manufacturer_id=c.manufacturer_id"
        " AND d.standard=c.standard)").fetchone()[0]
    check("no orphan symptoms", orph_syms == 0)

    # parameters
    pids = q("SELECT COUNT(*) FROM pids")
    check("legacy pids == 132", pids == 132, str(pids))
    defs = q("SELECT COUNT(*) FROM parameter_definitions")
    check("parameter definitions > 150", defs > 150, str(defs))
    dids = q("SELECT COUNT(*) FROM parameter_definitions WHERE kind='uds_did'")
    check("UDS DID definitions >= 10", dids >= 10, str(dids))
    # PID formula sanity: RPM must be AB*0.25
    rpm = cur.execute(
        "SELECT weight, mult, \"offset\" FROM pids WHERE mode=1 AND pid=12").fetchone()
    check("PID 0C formula AB*0.25", rpm == ("AB", 0.25, 0), str(rpm))
    ect = cur.execute(
        "SELECT weight, mult, \"offset\" FROM pids WHERE mode=1 AND pid=5").fetchone()
    check("PID 05 formula A*1-40", ect == ("A", 1, -40), str(ect))
    ru_pids = q("SELECT COUNT(*) FROM pids WHERE name_ru IS NOT NULL")
    check("PID RU names >= 100", ru_pids >= 100, str(ru_pids))

    # vehicle profile
    gens = q("SELECT COUNT(*) FROM vehicle_generations")
    check("vehicle generations exist", gens >= 1, str(gens))
    ecus = q("SELECT COUNT(*) FROM ecus")
    check("ECU entries exist", ecus >= 5, str(ecus))
    simos = cur.execute(
        "SELECT controller_family FROM ecus WHERE part_number='06A906033KD'").fetchone()
    check("Simos 7.1 ECU present", simos and "Simos 7.1" in simos[0], str(simos))
    bse = cur.execute("SELECT code FROM engines WHERE code='BSE'").fetchone()
    check("BSE engine present", bool(bse))
    sup = q("SELECT COUNT(*) FROM vehicle_parameter_support")
    expected_sup = q("SELECT COUNT(*) FROM vehicle_parameter_support WHERE support_level='expected'")
    check("vehicle support rows present", sup >= 100, str(sup))
    check("expected support subset", 5 <= expected_sup <= 60, str(expected_sup))
    confirmed = q("SELECT COUNT(*) FROM vehicle_parameter_support WHERE support_level='confirmed'")
    check("no unconfirmed 'confirmed' rows", confirmed == 0, str(confirmed))
    adapters = q("SELECT COUNT(*) FROM adapter_compatibility WHERE result='unverified'")
    check("adapter compatibility marked unverified (honest)", adapters >= 1)

    # search performance (mirrors the app's queries)
    t0 = time.time()
    r = cur.execute(
        "SELECT code, scope FROM dtc WHERE UPPER(code)='P0300' LIMIT 10").fetchall()
    t1 = time.time()
    check("code search finds P0300", any(x[0] == "P0300" for x in r))
    check("code search < 100 ms", (t1 - t0) < 0.1, f"{(t1-t0)*1000:.1f} ms")
    t0 = time.time()
    r = cur.execute(
        "SELECT code FROM dtc WHERE description_ru_lower LIKE '%пропуски%' LIMIT 20").fetchall()
    t1 = time.time()
    check("RU text search finds results", len(r) >= 10, str(len(r)))
    check("RU text search < 300 ms", (t1 - t0) < 0.3, f"{(t1-t0)*1000:.1f} ms")
    t0 = time.time()
    r = cur.execute(
        "SELECT code FROM dtc WHERE UPPER(description) LIKE '%MISFIRE%' LIMIT 20").fetchall()
    t1 = time.time()
    check("EN text search finds results", len(r) >= 10, str(len(r)))

    # meta
    meta = dict(cur.execute("SELECT key, value FROM meta").fetchall())
    check("meta version 2.x", meta.get("version", "").startswith("2"), meta.get("version"))
    check("meta sources recorded", "obdex" in meta.get("source_pid", ""))

    con.close()
    print()
    if failures:
        print(f"FAILED: {len(failures)} checks: {failures}")
        sys.exit(1)
    print("ALL CHECKS PASSED")


if __name__ == "__main__":
    main()
