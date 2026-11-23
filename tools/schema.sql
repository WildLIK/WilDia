-- Offline OBD2 database schema v2 (assets/obd_data.db).
-- Built by tools/build_db.py from open, licence-checked sources.
--
-- Design rules:
--   * Every record keeps provenance (source_id) and confidence/data_quality.
--   * Manufacturer-specific meanings are never merged: the key of a DTC
--     definition is (code, manufacturer_id, standard).
--   * Legacy tables pids / dtc / dtc_categories keep the v1 shape so the
--     existing Kotlin read layer stays compatible.
PRAGMA foreign_keys = ON;

-- ---------------------------------------------------------------------------
-- Provenance
-- ---------------------------------------------------------------------------
CREATE TABLE data_sources (
    id          TEXT PRIMARY KEY,   -- 'obdex', 'dtc-database', 'obdb-saej1979', ...
    name        TEXT NOT NULL,
    license     TEXT NOT NULL,
    url         TEXT NOT NULL,
    revision    TEXT,               -- git commit / version
    fetched_at  TEXT,
    notes       TEXT
);

-- ---------------------------------------------------------------------------
-- Manufacturers / vehicles / ECUs
-- ---------------------------------------------------------------------------
CREATE TABLE manufacturers (
    id             TEXT PRIMARY KEY,   -- 'VOLKSWAGEN', 'SKODA', 'GENERIC', 'OTHER'
    name_en        TEXT NOT NULL,
    name_ru        TEXT,
    kind           TEXT NOT NULL DEFAULT 'oem',  -- generic | oem | other
    source_id      TEXT REFERENCES data_sources(id)
);

CREATE TABLE vehicle_models (
    id              TEXT PRIMARY KEY,
    manufacturer_id TEXT NOT NULL REFERENCES manufacturers(id),
    name_en         TEXT NOT NULL,
    name_ru         TEXT
);

CREATE TABLE vehicle_generations (
    id              TEXT PRIMARY KEY,
    model_id        TEXT NOT NULL REFERENCES vehicle_models(id),
    code            TEXT,           -- 'A5 (1Z)'
    name_en         TEXT,
    name_ru         TEXT,
    years_start     INTEGER,
    years_end       INTEGER,
    platform        TEXT,
    notes           TEXT,
    source_id       TEXT REFERENCES data_sources(id),
    confidence      TEXT NOT NULL DEFAULT 'unverified'  -- user_scan|verified|likely|unverified
);

CREATE TABLE engines (
    id             TEXT PRIMARY KEY,
    code           TEXT NOT NULL,   -- 'BSE'
    name_en        TEXT,
    name_ru        TEXT,
    displacement_l REAL,
    cylinders      INTEGER,
    valvetrain     TEXT,
    fuel           TEXT,
    power_hp       INTEGER,
    power_kw       INTEGER,
    torque_nm      INTEGER,
    years_start    INTEGER,
    years_end      INTEGER,
    source_id      TEXT REFERENCES data_sources(id),
    confidence     TEXT NOT NULL DEFAULT 'unverified',
    notes          TEXT
);

CREATE TABLE engine_fitment (
    engine_id      TEXT NOT NULL REFERENCES engines(id),
    generation_id  TEXT NOT NULL REFERENCES vehicle_generations(id),
    years_start    INTEGER,
    years_end      INTEGER,
    source_id      TEXT REFERENCES data_sources(id),
    PRIMARY KEY (engine_id, generation_id)
);

CREATE TABLE protocols (
    id        TEXT PRIMARY KEY,     -- 'iso15765-4-can-11-500'
    name_en   TEXT NOT NULL,
    name_ru   TEXT,
    iso_ref   TEXT,
    elm_atdpn TEXT,                 -- ELM327 ATDPN number, if applicable
    notes     TEXT
);

CREATE TABLE ecus (
    id                  TEXT PRIMARY KEY,   -- normalised part number, e.g. '06a906033kd'
    part_number         TEXT NOT NULL,      -- '06A906033KD'
    part_number_display TEXT,
    system              TEXT NOT NULL,      -- engine|gateway|instrument|abs|comfort|parking|transmission|airbag|steering|body|other
    oem_name_en         TEXT,
    oem_name_ru         TEXT,
    controller_family   TEXT,               -- 'Siemens Simos 7.1'
    controller_supplier TEXT,
    firmware_note       TEXT,
    protocol_id         TEXT REFERENCES protocols(id),
    diag_address        TEXT,               -- NULL unless factually known
    notes               TEXT,
    source_id           TEXT REFERENCES data_sources(id),
    confidence          TEXT NOT NULL DEFAULT 'unverified'
);

CREATE TABLE vehicle_ecus (
    generation_id TEXT NOT NULL REFERENCES vehicle_generations(id),
    ecu_id        TEXT NOT NULL REFERENCES ecus(id),
    engine_id     TEXT REFERENCES engines(id),
    role          TEXT,
    source_id     TEXT REFERENCES data_sources(id),
    PRIMARY KEY (generation_id, ecu_id)
);

-- Adapter compatibility is a per-observation table: an entry is only written
-- when something was actually measured, or explicitly marked 'unverified'.
CREATE TABLE adapter_compatibility (
    generation_id TEXT NOT NULL REFERENCES vehicle_generations(id),
    ecu_id        TEXT REFERENCES ecus(id),
    adapter       TEXT NOT NULL,    -- 'Vgate iCar Pro BLE 4.0'
    protocol_id   TEXT REFERENCES protocols(id),
    result        TEXT NOT NULL,    -- verified|expected|failed|unverified
    notes         TEXT,
    source_id     TEXT REFERENCES data_sources(id),
    PRIMARY KEY (generation_id, ecu_id, adapter, protocol_id)
);

-- ---------------------------------------------------------------------------
-- DTC definitions (never merge manufacturer-specific meanings)
-- ---------------------------------------------------------------------------
CREATE TABLE dtc_definitions (
    code                  TEXT NOT NULL,
    manufacturer_id       TEXT NOT NULL REFERENCES manufacturers(id),
    standard              TEXT NOT NULL DEFAULT 'SAE J2012',
    title_en              TEXT NOT NULL,
    long_desc_en          TEXT,
    mil                   INTEGER,             -- NULL = unknown, 0/1 (obdex flags)
    emissions_relevant    INTEGER,
    drive_cycle_required  INTEGER,
    limp_mode_possible    INTEGER,
    repair_difficulty     TEXT,                -- low|medium|high
    repair_diy_possible   INTEGER,
    repair_hours_min      REAL,
    repair_hours_max      REAL,
    repair_cost_min_eur   REAL,
    repair_cost_max_eur   REAL,
    related_codes         TEXT,                -- JSON array of codes
    refs                  TEXT,                -- JSON array of references
    reviewed_date         TEXT,
    severity              TEXT,                -- low|medium|high (only when derived from facts)
    severity_reason       TEXT,
    source_id             TEXT NOT NULL REFERENCES data_sources(id),
    data_quality          TEXT NOT NULL DEFAULT 'imported', -- imported|curated
    PRIMARY KEY (code, manufacturer_id, standard)
) WITHOUT ROWID;
CREATE INDEX idx_dtc_code       ON dtc_definitions(code);
CREATE INDEX idx_dtc_maker      ON dtc_definitions(manufacturer_id);
CREATE INDEX idx_dtc_title_en   ON dtc_definitions(title_en);

CREATE TABLE dtc_probable_causes (
    code            TEXT NOT NULL,
    manufacturer_id TEXT NOT NULL,
    standard        TEXT NOT NULL DEFAULT 'SAE J2012',
    ord             INTEGER NOT NULL,
    text_en         TEXT NOT NULL,
    likelihood      TEXT,               -- high|medium|low (as given by the source)
    PRIMARY KEY (code, manufacturer_id, standard, ord),
    FOREIGN KEY (code, manufacturer_id, standard)
        REFERENCES dtc_definitions(code, manufacturer_id, standard) ON DELETE CASCADE
) WITHOUT ROWID;

CREATE TABLE dtc_symptoms (
    code            TEXT NOT NULL,
    manufacturer_id TEXT NOT NULL,
    standard        TEXT NOT NULL DEFAULT 'SAE J2012',
    ord             INTEGER NOT NULL,
    text_en         TEXT NOT NULL,
    PRIMARY KEY (code, manufacturer_id, standard, ord),
    FOREIGN KEY (code, manufacturer_id, standard)
        REFERENCES dtc_definitions(code, manufacturer_id, standard) ON DELETE CASCADE
) WITHOUT ROWID;

-- Recommended diagnostic checks: filled only from curated/reviewed data,
-- never invented by the import pipeline.
CREATE TABLE dtc_checks (
    code            TEXT NOT NULL,
    manufacturer_id TEXT NOT NULL,
    standard        TEXT NOT NULL DEFAULT 'SAE J2012',
    ord             INTEGER NOT NULL,
    text_ru         TEXT NOT NULL,
    origin          TEXT NOT NULL,      -- curated
    PRIMARY KEY (code, manufacturer_id, standard, ord),
    FOREIGN KEY (code, manufacturer_id, standard)
        REFERENCES dtc_definitions(code, manufacturer_id, standard) ON DELETE CASCADE
) WITHOUT ROWID;

-- Russian translations live separately from definitions so the same text can
-- be upgraded without touching imported facts.
CREATE TABLE dtc_translations (
    code               TEXT NOT NULL,
    manufacturer_id    TEXT NOT NULL,
    standard           TEXT NOT NULL DEFAULT 'SAE J2012',
    lang               TEXT NOT NULL DEFAULT 'ru',
    title_ru           TEXT,
    detail_ru          TEXT,
    detail_ru_lower    TEXT,            -- casefolded for Cyrillic-insensitive search
    recommendations_ru TEXT,
    translation_status TEXT NOT NULL,   -- verified | auto | imported
    method             TEXT NOT NULL,   -- 'curated-...' | 'rules-v1' | source id
    PRIMARY KEY (code, manufacturer_id, standard, lang),
    FOREIGN KEY (code, manufacturer_id, standard)
        REFERENCES dtc_definitions(code, manufacturer_id, standard) ON DELETE CASCADE
) WITHOUT ROWID;
CREATE INDEX idx_dtc_tr_title ON dtc_translations(title_ru);


CREATE TABLE dtc_cause_translations (
    code               TEXT NOT NULL,
    manufacturer_id    TEXT NOT NULL,
    standard           TEXT NOT NULL DEFAULT 'SAE J2012',
    ord                INTEGER NOT NULL,
    lang               TEXT NOT NULL DEFAULT 'ru',
    text_ru            TEXT NOT NULL,
    translation_status TEXT NOT NULL,
    method             TEXT NOT NULL,
    PRIMARY KEY (code, manufacturer_id, standard, ord, lang)
) WITHOUT ROWID;

CREATE TABLE dtc_symptom_translations (
    code               TEXT NOT NULL,
    manufacturer_id    TEXT NOT NULL,
    standard           TEXT NOT NULL DEFAULT 'SAE J2012',
    ord                INTEGER NOT NULL,
    lang               TEXT NOT NULL DEFAULT 'ru',
    text_ru            TEXT NOT NULL,
    translation_status TEXT NOT NULL,
    method             TEXT NOT NULL,
    PRIMARY KEY (code, manufacturer_id, standard, ord, lang)
) WITHOUT ROWID;

-- Same (code, manufacturer) defined differently by different sources is kept
-- here for the import report instead of silently overwriting data.
CREATE TABLE dtc_conflicts (
    code            TEXT NOT NULL,
    manufacturer_id TEXT NOT NULL,
    standard        TEXT NOT NULL,
    source_id       TEXT NOT NULL,
    title           TEXT NOT NULL,
    resolved_with   TEXT NOT NULL,      -- source id whose text is kept
    reason          TEXT
);

-- ---------------------------------------------------------------------------
-- Diagnostic parameters
-- ---------------------------------------------------------------------------
-- Catalog of parameter definitions. kind:
--   obd_pid        standard SAE J1979 Mode 01/02 PID
--   obd_mode09_pid SAE J1979 Mode 09 info PID
--   uds_did        ISO 14229 data identifier (never auto-sent by the app)
--   vag_meas_group informational row(s) about VAG measuring blocks
CREATE TABLE parameter_definitions (
    id          TEXT PRIMARY KEY,       -- 'pid-01-0C', 'did-uds-F190'
    kind        TEXT NOT NULL,
    scope       TEXT NOT NULL,          -- 'SAE J1979' | 'ISO 14229' | 'VAG'
    mode        INTEGER,                -- 1, 2 or 9 for OBD PIDs
    pid_hex     TEXT,                   -- '0C'
    name_en     TEXT NOT NULL,
    name_ru     TEXT,
    unit        TEXT,
    bytes       INTEGER,
    min_value   REAL,
    max_value   REAL,
    formula     TEXT,
    decoder     TEXT,                   -- numeric|bitmap|... (see tools/build_db.py)
    weight      TEXT,
    mult        REAL,
    "offset"    REAL,
    category    TEXT,
    notes       TEXT,
    source_id   TEXT REFERENCES data_sources(id),
    status      TEXT NOT NULL DEFAULT 'standard'  -- standard|defined|unsupported_decoder
);
CREATE INDEX idx_param_kind ON parameter_definitions(kind, mode, pid_hex);
CREATE INDEX idx_param_name ON parameter_definitions(name_en);
CREATE INDEX idx_param_scope ON parameter_definitions(scope);

-- Per-vehicle support of a parameter. Nothing is 'confirmed' unless it was
-- actually measured on the car; 'expected' means documented/typical for the
-- controller family, 'unknown' means no data.
CREATE TABLE vehicle_parameter_support (
    generation_id TEXT NOT NULL REFERENCES vehicle_generations(id),
    definition_id TEXT NOT NULL REFERENCES parameter_definitions(id),
    support_level TEXT NOT NULL,        -- confirmed|expected|theoretical|unknown|not_supported
    source_id     TEXT REFERENCES data_sources(id),
    measured_at   TEXT,
    notes         TEXT,
    PRIMARY KEY (generation_id, definition_id)
);
CREATE INDEX idx_support_level ON vehicle_parameter_support(support_level);

-- ---------------------------------------------------------------------------
-- Legacy v1 tables (kept for the existing Kotlin read layer)
-- ---------------------------------------------------------------------------
CREATE TABLE pids (
    mode INTEGER NOT NULL,
    pid  INTEGER NOT NULL,
    name_en TEXT NOT NULL,
    name_ru TEXT,
    unit TEXT,
    bytes INTEGER,
    decoder TEXT NOT NULL,
    weight TEXT,
    mult REAL,
    "offset" REAL,
    formula TEXT,
    category TEXT,
    PRIMARY KEY(mode, pid)
);

CREATE TABLE dtc (
    code TEXT NOT NULL,
    scope TEXT NOT NULL,                -- manufacturer id (GENERIC, VOLKSWAGEN, ...)
    group_tag TEXT,
    description TEXT NOT NULL,
    description_ru TEXT,
    description_ru_lower TEXT,          -- casefolded for Cyrillic-insensitive search
    long_description TEXT,
    causes TEXT,                        -- JSON [{text,likelihood}] (RU text when translated)
    source TEXT NOT NULL,
    PRIMARY KEY(code, scope)
) WITHOUT ROWID;
CREATE INDEX idx_dtc_code_v1 ON dtc(code);


CREATE TABLE dtc_categories (
    prefix TEXT PRIMARY KEY,
    system_en TEXT,
    system_ru TEXT,
    description_en TEXT,
    description_ru TEXT
);

CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
