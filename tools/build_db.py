#!/usr/bin/env python3
"""
Builds the offline OBD2 database (assets/obd_data.db, schema v2) for the
Android app.

Sources (all license-verified, pinned to git revisions):
  * foerbsnavi/obdex        — CC0-1.0 data: generic DTCs with causes/symptoms,
                              PID definitions with formulas.
  * Wal33D/dtc-database     — MIT: manufacturer-specific DTC definitions
                              (33 makers) + generic fill.
  * OBDb/SAEJ1979           — CC-BY-SA 4.0: standard PID signal definitions
                              (v3 signalset) — extends the parameter catalog.
  * tools/data/vehicles_octavia.yaml — vehicle/ECU profile from the owner's
                              VCDS scan + widely published public facts.
  * ISO 14229 identity DIDs — public standard facts (never auto-sent).

Russian translations:
  * verified — tools/data/ru_curated.yaml (hand-checked)
  * auto     — deterministic rule engine tools/translate.py
  * none     — original English kept

Run:  python tools/build_db.py
Output: android-app/app/src/main/assets/obd_data.db + tools/import_report.md
"""
import json
import os
import re
import sqlite3
import subprocess
import sys
from datetime import date

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from translate import translate_title, translate_symptom, translate_cause  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "tools", "data")
OBDEX = os.path.join(DATA, "obdex", "data")
OBDB_SIGNALSET = os.path.join(DATA, "SAEJ1979", "signalsets", "v3", "default.json")
WAL33D_DB = os.path.join(DATA, "dtc-database", "data", "dtc_codes.db")
VEHICLES_YAML = os.path.join(DATA, "vehicles_octavia.yaml")
CURATED_YAML = os.path.join(DATA, "ru_curated.yaml")
SCHEMA_SQL = os.path.join(ROOT, "tools", "schema.sql")
OUT = os.path.join(ROOT, "android-app", "app", "src", "main", "assets", "obd_data.db")
REPORT = os.path.join(ROOT, "tools", "import_report.md")

LONG_DESC_CAP = 450
MAX_CAUSES = 6

DB_VERSION = "2.0.0"
BUILT_AT = date.today().isoformat()

VAG_MAKERS = {"VOLKSWAGEN", "AUDI"}

GENERIC_FILES = [
    "P0xxx_enriched.yaml", "P2xxx_enriched.yaml", "P3xxx_enriched.yaml",
    "B0xxx_enriched.yaml", "C0xxx_enriched.yaml",
    "U0xxx_enriched.yaml", "U3xxx_enriched.yaml",
]

MAKER_RU = {
    "GENERIC": "Общий (SAE)", "OTHER": "Прочие производители",
    "VOLKSWAGEN": "Фольксваген (VAG)", "AUDI": "Ауди (VAG)", "SKODA": "Шкода (VAG)",
    "SEAT": "Сеат (VAG)", "FORD": "Форд", "MERCURY": "Меркьюри", "LINCOLN": "Линкольн",
    "CHEVY": "Шевроле (GM)", "GM": "GM", "GMC": "GMC", "BUICK": "Бьюик",
    "CADILLAC": "Кадиллак", "SATURN": "Сатурн", "OLDSMOBILE": "Олдсмобиль",
    "PONTIAC": "Понтиак", "CHRYSLER": "Крайслер", "DODGE": "Додж", "PLYMOUTH": "Плимут",
    "JEEP": "Джип", "BMW": "БМВ", "MAZDA": "Мазда", "JAGUAR": "Ягуар", "HONDA": "Хонда",
    "ACURA": "Акура", "INFINITI": "Инфинити", "NISSAN": "Ниссан", "LEXUS": "Лексус",
    "TOYOTA": "Тойота", "SUBARU": "Субару", "KIA": "Киа", "MITSUBISHI": "Митсубиси",
    "MERCEDES": "Мерседес", "GEO": "Гео", "SUZUKI": "Сузуки",
}

# Standard SAE J2012 range categories.
CATEGORIES = [
    ("P0000", "Fuel and Air Metering", "Топливо и измерение воздуха"),
    ("P0100", "Fuel and Air Metering", "Топливо и измерение воздуха"),
    ("P0200", "Fuel and Air Metering (Injector Circuit)", "Топливо и измерение воздуха (цепь форсунок)"),
    ("P0300", "Ignition System or Misfire", "Система зажигания или пропуски зажигания"),
    ("P0400", "Auxiliary Emission Controls", "Дополнительные системы снижения выбросов"),
    ("P0500", "Vehicle Speed, Idle Control, Auxiliary Inputs", "Скорость автомобиля, холостой ход, дополнительные входы"),
    ("P0600", "Computer Output Circuit", "Выходные цепи ЭБУ"),
    ("P0700", "Transmission", "Трансмиссия"),
    ("P0800", "Transmission", "Трансмиссия"),
    ("P0900", "Transmission / Other", "Трансмиссия / прочее"),
    ("P1000", "Manufacturer-specific (Fuel/Air Metering and Emission Controls)",
     "Код производителя (топливо/воздух, системы выбросов)"),
    ("P2000", "Manufacturer-specific (Fuel/Air Metering and Emission Controls)",
     "Код производителя (топливо/воздух, системы выбросов)"),
    ("P3000", "Manufacturer-specific (Fuel/Air Metering and Emission Controls)",
     "Код производителя (топливо/воздух, системы выбросов)"),
    ("B0000", "Body (restraints, comfort, instrumentation)", "Кузов (подушки, комфорт, приборы)"),
    ("C0000", "Chassis (ABS, steering, suspension)", "Шасси (ABS, рулевое, подвеска)"),
    ("U0000", "Network / Communication", "Сеть / связь (CAN)"),
]

# ---------------------------------------------------------------------------
# PID decoders (ported from v1 build; drives the app's live decoding).
# ---------------------------------------------------------------------------
PID_RU = {
    "00": "Поддерживаемые PID [01-20]", "01": "Статус мониторов с момента очистки ошибок",
    "02": "Код ошибки в кадре фиксации (freeze frame)", "03": "Состояние топливной системы (системы 1 и 2)",
    "04": "Расчётная нагрузка двигателя", "05": "Температура охлаждающей жидкости",
    "06": "Кратковременная коррекция топлива — банк 1", "07": "Долговременная коррекция топлива — банк 1",
    "08": "Кратковременная коррекция топлива — банк 2", "09": "Долговременная коррекция топлива — банк 2",
    "0A": "Давление топлива (избыточное)", "0B": "Абсолютное давление во впускном коллекторе",
    "0C": "Обороты двигателя", "0D": "Скорость автомобиля",
    "0E": "Угол опережения зажигания (цилиндр 1)", "0F": "Температура впускного воздуха",
    "10": "Массовый расход воздуха (MAF)", "11": "Положение дроссельной заслонки",
    "12": "Состояние системы вторичного воздуха", "13": "Датчики кислорода (2 банка)",
    "14": "Датчик кислорода 1 (банк 1, датчик 1) — напряжение и коррекция",
    "15": "Датчик кислорода 2 (банк 1, датчик 2) — напряжение и коррекция",
    "16": "Датчик кислорода 3 (банк 1, датчик 3) — напряжение и коррекция",
    "17": "Датчик кислорода 4 (банк 1, датчик 4) — напряжение и коррекция",
    "18": "Датчик кислорода 5 (банк 2, датчик 1) — напряжение и коррекция",
    "19": "Датчик кислорода 6 (банк 2, датчик 2) — напряжение и коррекция",
    "1A": "Датчик кислорода 7 (банк 2, датчик 3) — напряжение и коррекция",
    "1B": "Датчик кислорода 8 (банк 2, датчик 4) — напряжение и коррекция",
    "1C": "Стандарт OBD, которому соответствует автомобиль",
    "1D": "Датчики кислорода (4 банка)", "1E": "Состояние дополнительного входа (PTO)",
    "1F": "Время работы с пуска двигателя", "20": "Поддерживаемые PID [21-40]",
    "21": "Пробег с включённой лампой MIL", "22": "Давление топлива в рампе (относительно вакуума)",
    "23": "Давление топлива в рампе (избыточное, дизель/непосредственный впрыск)",
    "24": "Датчик кислорода 1 (банк 1, датчик 1) — лямбда и напряжение",
    "25": "Датчик кислорода 2 (банк 1, датчик 2) — лямбда и напряжение",
    "26": "Датчик кислорода 3 (банк 1, датчик 3) — лямбда и напряжение",
    "27": "Датчик кислорода 4 (банк 1, датчик 4) — лямбда и напряжение",
    "28": "Датчик кислорода 5 (банк 2, датчик 1) — лямбда и напряжение",
    "29": "Датчик кислорода 6 (банк 2, датчик 2) — лямбда и напряжение",
    "2A": "Датчик кислорода 7 (банк 2, датчик 3) — лямбда и напряжение",
    "2B": "Датчик кислорода 8 (банк 2, датчик 4) — лямбда и напряжение",
    "2C": "Задание EGR (рециркуляции отработавших газов)", "2D": "Ошибка EGR",
    "2E": "Задание продувки системы EVAP", "2F": "Уровень топлива в баке",
    "30": "Число прогревов с момента очистки ошибок", "31": "Пробег с момента очистки ошибок",
    "32": "Давление паров топлива в системе EVAP", "33": "Атмосферное (барометрическое) давление",
    "34": "Датчик кислорода 1 (банк 1, датчик 1) — лямбда и ток",
    "35": "Датчик кислорода 2 (банк 1, датчик 2) — лямбда и ток",
    "36": "Датчик кислорода 3 (банк 1, датчик 3) — лямбда и ток",
    "37": "Датчик кислорода 4 (банк 1, датчик 4) — лямбда и ток",
    "38": "Датчик кислорода 5 (банк 2, датчик 1) — лямбда и ток",
    "39": "Датчик кислорода 6 (банк 2, датчик 2) — лямбда и ток",
    "3A": "Датчик кислорода 7 (банк 2, датчик 3) — лямбда и ток",
    "3B": "Датчик кислорода 8 (банк 2, датчик 4) — лямбда и ток",
    "3C": "Температура катализатора — банк 1, датчик 1", "3D": "Температура катализатора — банк 2, датчик 1",
    "3E": "Температура катализатора — банк 1, датчик 2", "3F": "Температура катализатора — банк 2, датчик 2",
    "40": "Поддерживаемые PID [41-60]", "41": "Статус мониторов в текущем поездочном цикле",
    "42": "Напряжение бортовой сети (на ЭБУ)", "43": "Абсолютное значение нагрузки",
    "44": "Заданное соотношение воздух/топливо (лямбда)", "45": "Относительное положение дроссельной заслонки",
    "46": "Температура окружающего воздуха", "47": "Абсолютное положение дроссельной заслонки B",
    "48": "Абсолютное положение дроссельной заслонки C", "49": "Положение педали акселератора D",
    "4A": "Положение педали акселератора E", "4B": "Положение педали акселератора F",
    "4C": "Задание привода дроссельной заслонки", "4D": "Время работы с включённой MIL",
    "4E": "Время с момента очистки ошибок",
    "4F": "Максимальные значения (лямбда, напряжение ДК, ток ДК, давление)",
    "50": "Максимальный расход воздуха (MAF)", "51": "Тип топлива",
    "52": "Процент этанола в топливе", "53": "Абсолютное давление паров топлива (EVAP)",
    "54": "Давление паров топлива (со знаком)",
    "55": "Кратковременная коррекция вторичных ДК — банк 1 / банк 3",
    "56": "Долговременная коррекция вторичных ДК — банк 1 / банк 3",
    "57": "Кратковременная коррекция вторичных ДК — банк 2 / банк 4",
    "58": "Долговременная коррекция вторичных ДК — банк 2 / банк 4",
    "59": "Абсолютное давление топлива в рампе", "5A": "Относительное положение педали акселератора",
    "5B": "Остаточный ресурс гибридной батареи", "5C": "Температура моторного масла",
    "5D": "Момент впрыска топлива", "5E": "Расход топлива двигателем",
    "5F": "Требования к выбросам, под которые спроектирован автомобиль",
    "60": "Поддерживаемые PID [61-80]", "61": "Запрашиваемый водителем момент двигателя",
    "62": "Фактический момент двигателя", "63": "Опорный момент двигателя",
    "64": "Момент двигателя в пяти точках", "65": "Поддержка дополнительных входов/выходов",
    "66": "Расходомеры воздуха (MAF) A и B", "67": "Температура охлаждающей жидкости (датчики 1 и 2)",
    "68": "Датчики температуры впускного воздуха", "78": "Температура отработавших газов — банк 1, датчики 1-4",
    "79": "Температура отработавших газов — банк 2, датчики 1-4", "7C": "Температура сажевого фильтра (DPF)",
    "7D": "Состояние зоны контроля NOx (NTE)", "7E": "Состояние зоны контроля твёрдых частиц (NTE)",
    "80": "Поддерживаемые PID [81-A0]", "83": "Датчики концентрации NOx",
    "84": "Температура поверхности впускного коллектора", "8E": "Момент трения двигателя (процент)",
    "9D": "Расход топлива (двигатель и автомобиль)", "9E": "Расход отработавших газов",
    "A0": "Поддерживаемые PID [A1-C0]", "A2": "Цикловая подача топлива", "A6": "Одометр",
    "M9_00": "Поддерживаемые PID Mode 09 [01-20]", "M9_01": "Число сообщений VIN",
    "M9_02": "Идентификационный номер автомобиля (VIN)",
    "M9_03": "Число сообщений идентификатора калибровки",
    "M9_04": "Идентификатор калибровки (Calibration ID)", "M9_05": "Число сообщений CVN",
    "M9_06": "Контрольное число калибровки (CVN)", "M9_07": "Число сообщений счётчиков производительности",
    "M9_08": "Счётчики производительности (бензиновые двигатели)", "M9_0A": "Имя ЭБУ (ECU name)",
    "M9_0B": "Счётчики производительности (дизельные двигатели)", "M9_0D": "Серийный номер двигателя",
    "M9_0E": "Номер одобрения по выбросам",
}

NUMERIC_DECODER = {
    "04": ("A", 100 / 255, 0), "05": ("A", 1, -40), "0B": ("A", 1, 0),
    "0C": ("AB", 0.25, 0), "0D": ("A", 1, 0), "0F": ("A", 1, -40),
    "10": ("AB", 0.01, 0), "11": ("A", 100 / 255, 0), "1F": ("AB", 1, 0),
    "21": ("AB", 1, 0), "22": ("AB", 0.079, 0), "23": ("AB", 10, 0),
    "2C": ("A", 100 / 255, 0), "2D": ("A", 100 / 128, -100), "2E": ("A", 100 / 255, 0),
    "2F": ("A", 100 / 255, 0), "30": ("A", 1, 0), "31": ("AB", 1, 0),
    "32": ("AB", 0.25, -8192), "33": ("A", 1, 0), "3C": ("AB", 0.1, -40),
    "3D": ("AB", 0.1, -40), "3E": ("AB", 0.1, -40), "3F": ("AB", 0.1, -40),
    "42": ("AB", 0.001, 0), "43": ("AB", 100 / 255, 0), "44": ("AB", 2 / 65536, 0),
    "45": ("A", 100 / 255, 0), "46": ("A", 1, -40), "47": ("A", 100 / 255, 0),
    "48": ("A", 100 / 255, 0), "49": ("A", 100 / 255, 0), "4A": ("A", 100 / 255, 0),
    "4B": ("A", 100 / 255, 0), "4C": ("A", 100 / 255, 0), "4D": ("AB", 1, 0),
    "4E": ("AB", 1, 0), "50": ("A", 10, 0), "52": ("A", 100 / 255, 0),
    "53": ("AB", 1 / 200, 0), "54": ("AB", 1, -32767), "59": ("AB", 10, 0),
    "5A": ("A", 100 / 255, 0), "5B": ("A", 100 / 255, 0), "5C": ("A", 1, -40),
    "5D": ("AB", 1 / 128, -210), "5E": ("AB", 0.05, 0), "61": ("A", 1, -125),
    "62": ("A", 1, -125), "63": ("AB", 1, 0), "8E": ("A", 1, -125),
    "9E": ("AB", 0.2, 0), "A6": ("ABCD", 0.1, 0), "A2": ("AB", 1 / 32, 0),
}

SPECIAL_DECODER = {
    "00": "bitmap", "20": "bitmap", "40": "bitmap", "60": "bitmap",
    "80": "bitmap", "A0": "bitmap", "01": "status", "41": "status",
    "02": "freeze_dtc", "03": "fuel_status", "06": "trim", "07": "trim",
    "08": "trim", "09": "trim", "12": "air_status", "13": "o2_present",
    "1D": "o2_present_4", "14": "o2_voltage", "15": "o2_voltage", "16": "o2_voltage",
    "17": "o2_voltage", "18": "o2_voltage", "19": "o2_voltage", "1A": "o2_voltage",
    "1B": "o2_voltage", "1C": "obd_std", "1E": "aux",
    "24": "o2_lambda_v", "25": "o2_lambda_v", "26": "o2_lambda_v", "27": "o2_lambda_v",
    "28": "o2_lambda_v", "29": "o2_lambda_v", "2A": "o2_lambda_v", "2B": "o2_lambda_v",
    "34": "o2_lambda_i", "35": "o2_lambda_i", "36": "o2_lambda_i", "37": "o2_lambda_i",
    "38": "o2_lambda_i", "39": "o2_lambda_i", "3A": "o2_lambda_i", "3B": "o2_lambda_i",
    "4F": "max_values", "51": "fuel_type", "55": "trim2", "56": "trim2",
    "57": "trim2", "58": "trim2", "5F": "emission_req", "64": "torque_points",
    "65": "aux_io_supported", "66": "maf_multi", "67": "ect_multi", "68": "iat_multi",
    "78": "egt", "79": "egt", "7C": "dpf_temp", "7D": "nte_status", "7E": "nte_status",
    "83": "nox_sensor", "9D": "fuel_rate_multi",
}

PID_CATEGORY = {
    "engine": {"0C", "04", "43", "61", "62", "63", "64", "8E", "0E", "1F", "5C"},
    "fuel": {"06", "07", "08", "09", "0A", "22", "23", "2C", "2D", "2E", "2F", "44", "51", "52",
             "53", "54", "55", "56", "57", "58", "59", "5D", "5E", "9D", "9E", "A2", "30", "31"},
    "air": {"0B", "0F", "10", "33", "46", "66", "67", "68", "84"},
    "o2": {"13", "14", "15", "16", "17", "18", "19", "1A", "1B", "1D", "24", "25", "26", "27",
           "28", "29", "2A", "2B", "34", "35", "36", "37", "38", "39", "3A", "3B"},
    "catalyst": {"3C", "3D", "3E", "3F", "78", "79", "7C", "7D", "7E", "83"},
    "throttle": {"11", "45", "47", "48", "49", "4A", "4B", "4C", "5A"},
    "electrics": {"42", "1E", "65"},
    "status": {"00", "01", "02", "03", "12", "1C", "20", "40", "60", "80", "A0", "21", "4D", "4E",
               "4F", "50", "5B", "5F", "A6"},
}


def pid_category(pid: str) -> str:
    for cat, pids in PID_CATEGORY.items():
        if pid in pids:
            return cat
    return "other"


def clean(s):
    return re.sub(r"\s+", " ", (s or "")).strip()


def git_rev(path):
    try:
        return subprocess.check_output(
            ["git", "-C", path, "rev-parse", "--short", "HEAD"],
            stderr=subprocess.DEVNULL, text=True,
        ).strip()
    except Exception:
        return None


# ISO 14229 identity DIDs — public standard knowledge. Support depends on the
# ECU; the app never sends UDS requests automatically.
UDS_DIDS = [
    ("F186", "Active diagnostic session", "Активная диагностическая сессия"),
    ("F187", "Vehicle manufacturer spare part number (ECU)", "Каталожный номер запчасти (производитель автомобиля)"),
    ("F188", "Vehicle manufacturer ECU hardware number", "Номер аппаратной части ЭБУ"),
    ("F189", "Vehicle manufacturer ECU software number", "Номер программного обеспечения ЭБУ"),
    ("F18A", "Vehicle manufacturer ECU software version number", "Версия программного обеспечения ЭБУ"),
    ("F18B", "System supplier identifier", "Идентификатор поставщика системы"),
    ("F18C", "ECU manufacturing date", "Дата производства ЭБУ"),
    ("F18D", "ECU serial number", "Серийный номер ЭБУ"),
    ("F18E", "System supplier ECU hardware number", "Номер оборудования ЭБУ от поставщика"),
    ("F18F", "System supplier ECU software number", "Номер ПО ЭБУ от поставщика"),
    ("F190", "Vehicle identification number (VIN)", "Идентификационный номер автомобиля (VIN)"),
    ("F191", "Vehicle manufacturer immobilizer", "Иммобилайзер"),
    ("F193", "System name", "Наименование системы"),
    ("F195", "System supplier identifier (extended)", "Идентификатор поставщика системы (расширенный)"),
    ("F196", "Repair shop code", "Код ремонтной мастерской"),
    ("F197", "Last programming date", "Дата последнего программирования"),
]


def severity_from_flags(flags):
    """Derive a justified severity only from source flags; None when unknown."""
    if flags is None:
        return None, None
    limp = flags.get("limp_mode_possible")
    mil = flags.get("mil")
    if limp:
        return ("high", "Возможен аварийный (limp) режим работы по данным источника.")
    if mil:
        return ("medium", "Горит контрольная лампа неисправностей (MIL); эксплуатация обычно возможна, но требует диагностики.")
    if mil is False:
        return ("low", "По данным источника лампа MIL не загорается.")
    return None, None


def main():
    stats = {}
    conflicts = []
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    if os.path.exists(OUT):
        os.remove(OUT)
    con = sqlite3.connect(OUT)
    con.execute("PRAGMA foreign_keys = ON")
    cur = con.cursor()
    cur.executescript(open(SCHEMA_SQL, encoding="utf-8").read())

    # ---------------- data sources ----------------
    obdex_rev = git_rev(os.path.join(DATA, "obdex"))
    wal_rev = git_rev(os.path.join(DATA, "dtc-database"))
    obdb_rev = git_rev(os.path.join(DATA, "SAEJ1979"))
    sources = [
        ("obdex", "foerbsnavi/obdex (generic DTC + PIDs)", "CC0-1.0 (данные)",
         "https://github.com/foerbsnavi/obdex", obdex_rev, "2026-11-20",
         "Общие коды с описаниями, причинами и симптомами; PID с формулами."),
        ("dtc-database", "Wal33D/dtc-database (manufacturer DTCs)", "MIT",
         "https://github.com/Wal33D/dtc-database", wal_rev, "2026-11-20",
         "Фирменные коды 33 производителей; заполнение общих кодов."),
        ("obdb-saej1979", "OBDb/SAEJ1979 (PID signals v3)", "CC-BY-SA 4.0",
         "https://github.com/OBDb/SAEJ1979", obdb_rev, "2026-11-21",
         "Стандартные PID-сигналы; атрибуция обязательна (About/Settings)."),
        ("iso-14229", "ISO 14229 identity DIDs (public standard facts)", "Общеизвестные факты стандарта",
         "https://www.iso.org/standard/85819.html", None, "2026-11-21",
         "Стандартные идентификационные DID; текст стандарта не включается."),
    ]
    veh = yaml.safe_load(open(VEHICLES_YAML, encoding="utf-8"))
    sources += [
        (s.get("id"), s.get("name"), s.get("license"), s.get("url", ""),
         s.get("revision"), s.get("fetched_at"), s.get("notes", ""))
        for s in veh["data_sources"]
    ]
    cur.executemany(
        "INSERT INTO data_sources VALUES (?,?,?,?,?,?,?)",
        [(sid, name, lic, url, rev, fetched, notes)
         for (sid, name, lic, url, rev, fetched, notes) in sources],
    )

    # ---------------- manufacturers ----------------
    wcon = sqlite3.connect(WAL33D_DB)
    makers = [r[0] for r in wcon.execute(
        "SELECT DISTINCT manufacturer FROM dtc_definitions WHERE locale='en' ORDER BY 1")]
    for m in makers:
        kind = "generic" if m == "GENERIC" else ("other" if m == "OTHER" else "oem")
        cur.execute("INSERT OR IGNORE INTO manufacturers VALUES (?,?,?,?,?)",
                    (m, m.title(), MAKER_RU.get(m), kind,
                     "dtc-database" if m != "SKODA" else "public-catalogs"))
    cur.execute("INSERT OR IGNORE INTO manufacturers VALUES ('SKODA','Skoda',?,?,NULL)",
                ("Шкода (VAG)", "oem"))

    # ---------------- protocols ----------------
    for p in veh["protocols"]:
        cur.execute("INSERT INTO protocols VALUES (?,?,?,?,?,?)",
                    (p["id"], p["name_en"], p.get("name_ru"), p.get("iso_ref"),
                     p.get("elm_atdpn"), p.get("notes")))

    # ---------------- vehicles / ECUs ----------------
    for m in veh.get("vehicle_models", []):
        cur.execute("INSERT INTO vehicle_models VALUES (?,?,?,?)",
                    (m["id"], m["manufacturer_id"], m["name_en"], m.get("name_ru")))
    for g in veh.get("vehicle_generations", []):
        cur.execute("INSERT INTO vehicle_generations VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    (g["id"], g["model_id"], g.get("code"), g.get("name_en"), g.get("name_ru"),
                     g.get("years_start"), g.get("years_end"), g.get("platform"), g.get("notes"),
                     g.get("source_id"), g.get("confidence", "unverified")))
    for e in veh.get("engines", []):
        cur.execute(
            "INSERT INTO engines VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (e["id"], e["code"], e.get("name_en"), e.get("name_ru"), e.get("displacement_l"),
             e.get("cylinders"), e.get("valvetrain"), e.get("fuel"), e.get("power_hp"),
             e.get("power_kw"), e.get("torque_nm"), e.get("years_start"), e.get("years_end"),
             e.get("source_id"), e.get("confidence", "unverified"), e.get("notes")))
    for f in veh.get("engine_fitment", []):
        cur.execute("INSERT INTO engine_fitment VALUES (?,?,?,?,?)",
                    (f["engine_id"], f["generation_id"], None, None, f.get("source_id")))
    for e in veh.get("ecus", []):
        cur.execute(
            "INSERT INTO ecus VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (e["id"], e["part_number"], e.get("part_number_display"), e["system"],
             e.get("oem_name_en"), e.get("oem_name_ru"), e.get("controller_family"),
             e.get("controller_supplier"), None, e.get("protocol_id"), e.get("diag_address"),
             e.get("notes"), e.get("source_id"), e.get("confidence", "unverified")))
    for v in veh.get("vehicle_ecus", []):
        cur.execute("INSERT INTO vehicle_ecus VALUES (?,?,?,?,?)",
                    (v["generation_id"], v["ecu_id"], v.get("engine_id"), v.get("role"),
                     v.get("source_id")))
    for a in veh.get("adapter_compatibility", []):
        cur.execute("INSERT INTO adapter_compatibility VALUES (?,?,?,?,?,?,?)",
                    (a["generation_id"], a.get("ecu_id"), a["adapter"], a.get("protocol_id"),
                     a["result"], a.get("notes"), "vcds-scan-user"))

    # ---------------- obdex generic DTCs ----------------
    n_obdex = 0
    for rel in GENERIC_FILES:
        path = os.path.join(OBDEX, "generic", rel.replace("/", os.sep))
        with open(path, encoding="utf-8") as fh:
            codes = yaml.safe_load(fh)
        for c in codes:
            code = c["code"].upper()
            title_en = clean(c.get("title", {}).get("en", ""))
            if not title_en:
                continue
            long_en = clean(c.get("description", {}).get("en", ""))
            if len(long_en) > LONG_DESC_CAP:
                long_en = long_en[:LONG_DESC_CAP].rsplit(" ", 1)[0] + "…"
            flags = c.get("flags") or {}
            sev, sev_reason = severity_from_flags(flags)
            repair = c.get("repair") or {}
            hours = repair.get("estimated_hours") or [None, None]
            cost = repair.get("estimated_cost_eur") or [None, None]
            cur.execute(
                "INSERT INTO dtc_definitions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (code, "GENERIC", "SAE J2012", title_en, long_en,
                 int(bool(flags.get("mil"))) if flags.get("mil") is not None else None,
                 int(bool(flags.get("emissions_relevant"))) if flags.get("emissions_relevant") is not None else None,
                 int(bool(flags.get("drive_cycle_required"))) if flags.get("drive_cycle_required") is not None else None,
                 int(bool(flags.get("limp_mode_possible"))) if flags.get("limp_mode_possible") is not None else None,
                 repair.get("difficulty"),
                 int(bool(repair.get("diy_possible"))) if repair.get("diy_possible") is not None else None,
                 hours[0], hours[1], cost[0], cost[1],
                 json.dumps(c.get("related_codes") or [], ensure_ascii=False),
                 json.dumps(c.get("references") or [], ensure_ascii=False),
                 str(c.get("reviewed") or "") or None,
                 sev, sev_reason, "obdex", "imported"))
            for i, cc in enumerate((c.get("common_causes") or [])[:MAX_CAUSES]):
                lab = clean(cc.get("label", {}).get("en", ""))
                if lab:
                    cur.execute("INSERT INTO dtc_probable_causes VALUES (?,?,?,?,?,?)",
                                (code, "GENERIC", "SAE J2012", i, lab, cc.get("likelihood")))
            for i, s in enumerate(c.get("symptoms") or []):
                txt = clean(s.get("en", ""))
                if txt:
                    cur.execute("INSERT INTO dtc_symptoms VALUES (?,?,?,?,?)",
                                (code, "GENERIC", "SAE J2012", i, txt))
            n_obdex += 1

    # ---------------- Wal33D ----------------
    n_wal_fill = n_wal_maker = 0
    for code, desc in wcon.execute(
            "SELECT code, description FROM dtc_definitions WHERE manufacturer='GENERIC' AND locale='en'"):
        desc = clean(desc)
        if not desc:
            continue
        row = cur.execute(
            "SELECT 1 FROM dtc_definitions WHERE code=? AND manufacturer_id='GENERIC'",
            (code,)).fetchone()
        if row:
            continue
        cur.execute(
            "INSERT INTO dtc_definitions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (code, "GENERIC", "SAE J2012", desc, None, None, None, None, None,
             None, None, None, None, None, None, None, None, None, None, None,
             "dtc-database", "imported"))
        n_wal_fill += 1
    for code, maker, desc in wcon.execute(
            "SELECT code, manufacturer, description FROM dtc_definitions "
            "WHERE manufacturer NOT IN ('GENERIC') AND locale='en'"):
        desc = clean(desc)
        if not desc:
            continue
        try:
            cur.execute(
                "INSERT INTO dtc_definitions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (code, maker, "SAE J2012 (OEM-specific)", desc, None, None, None, None, None,
                 None, None, None, None, None, None, None, None, None, None, None,
                 "dtc-database", "imported"))
            n_wal_maker += 1
        except sqlite3.IntegrityError:
            row = cur.execute(
                "SELECT title_en, source_id FROM dtc_definitions WHERE code=? AND manufacturer_id=?",
                (code, maker)).fetchone()
            if row and row[1] != "dtc-database" and row[0] != desc:
                conflicts.append((code, maker, "SAE J2012 (OEM-specific)", "dtc-database",
                                  desc, row[1], "определение из другого источника оставлено без изменений"))
    wcon.close()
    for c in conflicts:
        cur.execute("INSERT INTO dtc_conflicts VALUES (?,?,?,?,?,?,?)", c)

    # ---------------- Russian translations ----------------
    curated = yaml.safe_load(open(CURATED_YAML, encoding="utf-8"))
    generic_titles = curated.get("generic_titles", {})
    vag_titles = curated.get("vag_titles", {})
    details = curated.get("details", {})

    n_ver = n_auto = 0
    defs = cur.execute(
        "SELECT code, manufacturer_id, standard, title_en FROM dtc_definitions").fetchall()
    for code, maker, standard, title_en in defs:
        title_ru = None
        status = None
        method = None
        if maker == "GENERIC":
            if code in generic_titles:
                title_ru, status, method = generic_titles[code], "verified", "curated-2026-11-21"
            elif code in details and details[code].get("title_ru"):
                title_ru, status, method = details[code]["title_ru"], "verified", "curated-2026-11-21"
        elif maker in VAG_MAKERS and code in vag_titles:
            title_ru, status, method = vag_titles[code], "verified", "curated-2026-11-21"
        if title_ru is None:
            auto = translate_title(title_en)
            if auto:
                title_ru, status, method = auto, "auto", "rules-v1"
        if title_ru is None:
            continue
        det = rec = None
        if maker == "GENERIC" and code in details:
            det = details[code].get("detail")
            rec = details[code].get("recommendations")
        cur.execute(
            "INSERT INTO dtc_translations VALUES (?,?,?,?,?,?,?,?,?,?)",
            (code, maker, standard, "ru", title_ru, det, det.lower() if det else None,
             rec, status, method))
        if status == "verified":
            n_ver += 1
        else:
            n_auto += 1
        if maker == "GENERIC" and code in details and rec:
            # split numbered recommendations into dtc_checks rows
            steps = [s.strip() for s in re.split(r"\s*\d\)\s*", rec) if s.strip()]
            for i, s in enumerate(steps):
                cur.execute("INSERT INTO dtc_checks VALUES (?,?,?,?,?,?)",
                            (code, maker, standard, i, s, "curated"))

    # cause + symptom translations (rule-based, auto status)
    n_cause_ru = n_sym_ru = 0
    cause_rows = cur.execute(
        "SELECT code, manufacturer_id, standard, ord, text_en, likelihood FROM dtc_probable_causes").fetchall()
    for code, maker, standard, ord_, text_en, lik in cause_rows:
        ru = translate_cause(text_en)
        if ru:
            cur.execute("INSERT INTO dtc_cause_translations VALUES (?,?,?,?,?,?,?,?)",
                        (code, maker, standard, ord_, "ru", ru, "auto", "rules-v1"))
            n_cause_ru += 1
    sym_rows = cur.execute(
        "SELECT code, manufacturer_id, standard, ord, text_en FROM dtc_symptoms").fetchall()
    for code, maker, standard, ord_, text_en in sym_rows:
        ru = translate_symptom(text_en)
        if ru:
            cur.execute("INSERT INTO dtc_symptom_translations VALUES (?,?,?,?,?,?,?,?)",
                        (code, maker, standard, ord_, "ru", ru, "auto", "rules-v1"))
            n_sym_ru += 1

    # ---------------- parameters ----------------
    n_pids_v1 = 0
    for yaml_name, mode in (("mode01.yaml", 1), ("mode09.yaml", 9)):
        with open(os.path.join(OBDEX, "pids", yaml_name), encoding="utf-8") as fh:
            entries = yaml.safe_load(fh)
        for e in entries:
            pid = e["pid"].upper()
            name_en = clean(e["name"]["en"])
            ru_key = pid if mode == 1 else "M9_" + pid
            name_ru = PID_RU.get(ru_key)
            formula = e.get("formula")
            formula = clean(formula) if formula and formula != "BITMAP" else None
            decoder = weight = mult = off = None
            if mode == 1 and pid in NUMERIC_DECODER:
                w, m, o = NUMERIC_DECODER[pid]
                decoder, weight, mult, off = "numeric", w, m, o
            elif mode == 1 and pid in SPECIAL_DECODER:
                decoder = SPECIAL_DECODER[pid]
            elif mode == 9:
                decoder = {"02": "vin", "04": "calid", "06": "cvn", "0A": "ecu_name",
                           "08": "ipt", "0B": "ipt", "0D": "esn", "0E": "esn"}.get(pid, "count")
            if decoder is None:
                decoder = "unsupported"
            def_id = f"pid-{mode:02d}-{pid}"
            cur.execute(
                "INSERT INTO parameter_definitions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (def_id, "obd_mode09_pid" if mode == 9 else "obd_pid", "SAE J1979", mode, pid,
                 name_en, name_ru, e.get("unit"), e.get("bytes"), None, None, formula,
                 decoder, weight, mult, off, pid_category(pid) if mode == 1 else "vehicle_info",
                 None, "obdex", "standard"))
            if mode in (1, 9):
                cur.execute(
                    "INSERT INTO pids VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    (mode, int(pid, 16), name_en, name_ru, e.get("unit"), e.get("bytes"),
                     decoder, weight, mult, off, formula,
                     pid_category(pid) if mode == 1 else "vehicle_info"))
                n_pids_v1 += 1
            # freeze frame catalog: Mode 02 uses the same PID set (SAE J1979)
            if mode == 1 and decoder in ("numeric",):
                cur.execute(
                    "INSERT INTO parameter_definitions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (f"pid-02-{pid}", "obd_pid", "SAE J1979", 2, pid,
                     name_en + " (freeze frame)", (name_ru + " (кадр фиксации)") if name_ru else None,
                     e.get("unit"), e.get("bytes"), None, None, formula,
                     decoder, weight, mult, off, pid_category(pid),
                     "Кадр фиксации (Mode 02): тот же PID-номер, что и Mode 01.",
                     "obdex", "defined"))

    # OBDb SAEJ1979: extend the catalog with standard PIDs missing from obdex
    with open(OBDB_SIGNALSET, encoding="utf-8") as fh:
        signals_doc = json.load(fh)
    n_obdb_extra = 0
    for cmd in signals_doc["commands"]:
        # signalset maps {service: pid}; the first value is the PID itself
        pid = next(iter(cmd["cmd"].values())).upper()
        exists = cur.execute(
            "SELECT 1 FROM parameter_definitions WHERE kind='obd_pid' AND mode=1 AND pid_hex=?",
            (pid,)).fetchone()
        if exists:
            continue
        sigs = cmd.get("signals") or []
        if not sigs:
            continue
        names = []
        seen = set()
        for s in sigs[:3]:
            nm = clean(s.get("name", ""))
            if nm and nm not in seen:
                seen.add(nm)
                names.append(nm)
        name_en = " / ".join(names) or f"PID 0x{pid}"
        desc_bits = clean(sigs[0].get("description", ""))[:300] or None
        cur.execute(
            "INSERT INTO parameter_definitions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (f"pid-01-{pid}", "obd_pid", "SAE J1979", 1, pid, name_en, None, None, None,
             None, None, None, "unsupported", None, None, None, "other",
             ("Битовые сигналы: " + desc_bits) if desc_bits else "Каталожное определение из signalset OBDb/SAEJ1979.",
             "obdb-saej1979", "defined"))
        n_obdb_extra += 1

    # UDS identity DIDs (ISO 14229) — catalog only, never auto-sent
    n_dids = 0
    for did, en, ru in UDS_DIDS:
        cur.execute(
            "INSERT INTO parameter_definitions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (f"did-uds-{did}", "uds_did", "ISO 14229", None, did, en, ru, None, None,
             None, None, None, None, None, None, None, "identity",
             "Поддержка зависит от конкретного ЭБУ. Приложение не отправляет UDS-запросы автоматически; через ELM327 UDS-транспорт недоступен.",
             "iso-14229", "defined"))
        n_dids += 1

    # VAG measuring blocks: informational row about the limitation
    cur.execute(
        "INSERT INTO parameter_definitions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ("vag-mvb-info", "vag_meas_group", "VAG", None, None,
         "VAG measuring blocks (TP 2.0 transport required)",
         "Измерительные группы VAG (требуется транспорт TP 2.0)",
         None, None, None, None, None, None, None, None, None, "other",
         "Недоступны через ELM327. Соответствия «группа → параметр» не включаются без проверенного источника.",
         "public-standards", "defined"))

    # ---------------- vehicle parameter support ----------------
    gen_id = "skoda-octavia-a5-1z"
    expected = {p.upper() for p in veh.get("expected_mode01_pids", [])}
    n_support = 0
    for def_id, pid_hex, mode, kind in cur.execute(
            "SELECT id, pid_hex, mode, kind FROM parameter_definitions "
            "WHERE kind IN ('obd_pid','obd_mode09_pid')").fetchall():
        if mode == 1 and pid_hex in expected:
            cur.execute("INSERT INTO vehicle_parameter_support VALUES (?,?,?,?,?,?)",
                        (gen_id, def_id, "expected", "public-standards", None,
                         "Ожидается для законодательного OBD-II (Simos 7.1, EOBD). НЕ подтверждено измерением на автомобиле."))
        else:
            cur.execute("INSERT INTO vehicle_parameter_support VALUES (?,?,?,?,?,?)",
                        (gen_id, def_id, "unknown", None, None,
                         "Не определено: неизвестно, поддерживает ли ЭБУ этот параметр."))
        n_support += 1

    # ---------------- legacy v1 tables ----------------
    n_legacy = 0
    cur2 = con.cursor()
    legacy_rows = cur.execute(
        "SELECT code, manufacturer_id, standard, title_en, long_desc_en, source_id FROM dtc_definitions").fetchall()
    for code, maker, standard, title_en, long_en, source_id in legacy_rows:
        tr = cur2.execute(
            "SELECT title_ru, translation_status FROM dtc_translations "
            "WHERE code=? AND manufacturer_id=? AND standard=? AND lang='ru'",
            (code, maker, standard)).fetchone()
        title_ru = tr[0] if tr else None
        causes = []
        for i, text_en, lik in cur2.execute(
                "SELECT ord, text_en, likelihood FROM dtc_probable_causes "
                "WHERE code=? AND manufacturer_id=? AND standard=? ORDER BY ord",
                (code, maker, standard)).fetchall():
            ctr = cur2.execute(
                "SELECT text_ru FROM dtc_cause_translations "
                "WHERE code=? AND manufacturer_id=? AND standard=? AND ord=? AND lang='ru'",
                (code, maker, standard, i)).fetchone()
            text = ctr[0] if ctr else text_en
            entry = {"text": text}
            if lik:
                entry["likelihood"] = lik
            causes.append(entry)
        tag = "VAG" if maker in VAG_MAKERS else ""
        try:
            cur.execute("INSERT INTO dtc VALUES (?,?,?,?,?,?,?,?,?)",
                        (code, maker, tag, title_en, title_ru,
                         title_ru.lower() if title_ru else None, long_en,
                         json.dumps(causes, ensure_ascii=False) if causes else None,
                         source_id))
            n_legacy += 1
        except sqlite3.IntegrityError:
            pass

    for prefix, en, ru in CATEGORIES:
        cur.execute("INSERT INTO dtc_categories VALUES (?,?,?,?,?)", (prefix, "", en, "", ru))

    # ---------------- meta ----------------
    counts = {
        "version": DB_VERSION,
        "schema": "2",
        "built_at": BUILT_AT,
        "source_pid": "foerbsnavi/obdex (CC0-1.0) + OBDb/SAEJ1979 (CC-BY-SA 4.0)",
        "source_dtc_generic": "foerbsnavi/obdex (CC0-1.0), fill: Wal33D/dtc-database (MIT)",
        "source_dtc_manufacturer": "Wal33D/dtc-database (MIT)",
        "translation_attribution": "OBDb/SAEJ1979 данные используются по лицензии CC-BY-SA 4.0; атрибуция в разделе «О базе».",
    }
    for key, sql in [
        ("pids", "SELECT COUNT(*) FROM pids"),
        ("param_definitions", "SELECT COUNT(*) FROM parameter_definitions"),
        ("param_dids", "SELECT COUNT(*) FROM parameter_definitions WHERE kind='uds_did'"),
        ("dtc_total", "SELECT COUNT(*) FROM dtc_definitions"),
        ("dtc_generic", "SELECT COUNT(*) FROM dtc_definitions WHERE manufacturer_id='GENERIC'"),
        ("dtc_manufacturer", "SELECT COUNT(*) FROM dtc_definitions WHERE manufacturer_id!='GENERIC'"),
        ("dtc_manufacturers", "SELECT COUNT(DISTINCT manufacturer_id) FROM dtc_definitions WHERE manufacturer_id!='GENERIC'"),
        ("dtc_ru_translations", "SELECT COUNT(*) FROM dtc_translations WHERE lang='ru'"),
        ("dtc_ru_verified", "SELECT COUNT(*) FROM dtc_translations WHERE lang='ru' AND translation_status='verified'"),
        ("dtc_ru_auto", "SELECT COUNT(*) FROM dtc_translations WHERE lang='ru' AND translation_status='auto'"),
        ("dtc_causes", "SELECT COUNT(*) FROM dtc_probable_causes"),
        ("dtc_causes_ru", "SELECT COUNT(*) FROM dtc_cause_translations"),
        ("dtc_symptoms", "SELECT COUNT(*) FROM dtc_symptoms"),
        ("dtc_symptoms_ru", "SELECT COUNT(*) FROM dtc_symptom_translations"),
        ("dtc_conflicts", "SELECT COUNT(*) FROM dtc_conflicts"),
        ("manufacturers", "SELECT COUNT(*) FROM manufacturers"),
        ("vehicle_models", "SELECT COUNT(*) FROM vehicle_models"),
        ("vehicle_generations", "SELECT COUNT(*) FROM vehicle_generations"),
        ("engines", "SELECT COUNT(*) FROM engines"),
        ("ecus", "SELECT COUNT(*) FROM ecus"),
        ("protocols", "SELECT COUNT(*) FROM protocols"),
        ("param_support", "SELECT COUNT(*) FROM vehicle_parameter_support"),
        ("legacy_dtc", "SELECT COUNT(*) FROM dtc"),
    ]:
        counts[key] = str(cur.execute(sql).fetchone()[0])
    for k, v in counts.items():
        cur.execute("INSERT INTO meta VALUES (?,?)", (k, v))

    con.commit()
    con.execute("PRAGMA optimize")
    cur.execute("VACUUM")
    con.commit()
    con.execute("PRAGMA foreign_key_check")
    fk_bad = cur.fetchall()
    con.close()

    # ---------------- report ----------------
    stats = counts
    write_report(REPORT, stats, n_obdex, n_wal_fill, n_wal_maker, n_ver, n_auto,
                 n_cause_ru, n_sym_ru, n_obdb_extra, n_dids, n_support, conflicts,
                 obdex_rev, wal_rev, obdb_rev, fk_bad)

    size = os.path.getsize(OUT)
    print(f"DB written: {OUT} ({size/1024/1024:.2f} MB)")
    print(f"  dtc definitions: {counts['dtc_total']} (generic {counts['dtc_generic']}, "
          f"manufacturer {counts['dtc_manufacturer']}, {counts['dtc_manufacturers']} makers)")
    print(f"  ru translations: {counts['dtc_ru_translations']} "
          f"(verified {counts['dtc_ru_verified']}, auto {counts['dtc_ru_auto']})")
    print(f"  causes: {counts['dtc_causes']} (ru {counts['dtc_causes_ru']}), "
          f"symptoms: {counts['dtc_symptoms']} (ru {counts['dtc_symptoms_ru']})")
    print(f"  pids (legacy): {counts['pids']}, param definitions: {counts['param_definitions']} "
          f"(DIDs {counts['param_dids']})")
    print(f"  vehicles: {counts['vehicle_models']} models, {counts['vehicle_generations']} generations, "
          f"{counts['engines']} engines, {counts['ecus']} ECUs")
    print(f"  conflicts: {counts['dtc_conflicts']}, foreign_key_check: {'ok' if not fk_bad else fk_bad[:3]}")


def write_report(path, stats, n_obdex, n_wal_fill, n_wal_maker, n_ver, n_auto,
                 n_cause_ru, n_sym_ru, n_obdb_extra, n_dids, n_support, conflicts,
                 obdex_rev, wal_rev, obdb_rev, fk_bad):
    lines = [
        f"# Отчёт об импорте базы данных ({BUILT_AT})",
        "",
        "## Источники",
        f"- foerbsnavi/obdex @ {obdex_rev} — CC0-1.0 (данные): {n_obdex} общих кодов с причинами/симптомами.",
        f"- Wal33D/dtc-database @ {wal_rev} — MIT: заполнение {n_wal_fill} общих кодов, {n_wal_maker} фирменных кодов.",
        f"- OBDb/SAEJ1979 @ {obdb_rev} — CC-BY-SA 4.0: +{n_obdb_extra} каталожных PID.",
        f"- ISO 14229 identity DIDs: {n_dids} идентификационных DID (каталог, не отправляются автоматически).",
        "- Скан VCDS владельца (2026-11-20): профиль Skoda Octavia A5, BSE, 6 ЭБУ.",
        "",
        "## Итоги",
        f"- DTC всего: **{stats['dtc_total']}** (общие {stats['dtc_generic']}, фирменные {stats['dtc_manufacturer']},"
        f" производителей {stats['dtc_manufacturers']})",
        f"- Русские переводы: **{stats['dtc_ru_translations']}** — проверенных **{stats['dtc_ru_verified']}**,"
        f" автоматических (правило-ориентированных) **{stats['dtc_ru_auto']}**",
        f"- Причины: {stats['dtc_causes']} записей, из них переведено {n_cause_ru};"
        f" симптомы: {stats['dtc_symptoms']}, переведено {n_sym_ru}",
        f"- Определения параметров: {stats['param_definitions']} (PID с декодерами {stats['pids']},"
        f" DID {stats['param_dids']}); записи поддержки авто: {n_support}",
        f"- Автомобили: {stats['vehicle_models']} моделей, {stats['vehicle_generations']} поколений,"
        f" {stats['engines']} двигателей, {stats['ecus']} ЭБУ, {stats['protocols']} протоколов",
        f"- Конфликтов записей: {stats['dtc_conflicts']}",
        f"- foreign_key_check: {'ok' if not fk_bad else 'ПРОБЛЕМЫ: ' + str(fk_bad[:5])}",
        "",
        "## Статусы перевода",
        "- verified — ручные проверенные переводы (tools/data/ru_curated.yaml)",
        "- auto — детерминированный правило-ориентированный перевод (tools/translate.py), не проверен человеком",
        "- (без перевода) — сохранён оригинальный английский текст",
        "",
        "## Конфликты (текст различается между источниками)",
    ]
    for code, maker, standard, src, title, winner, reason in conflicts[:50]:
        lines.append(f"- {code} [{maker}]: dtc-database «{title}» — оставлено определение источника {winner}")
    if not conflicts:
        lines.append("- нет")
    open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
