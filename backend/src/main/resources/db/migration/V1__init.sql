-- MelkCRM backend schema. Portable between PostgreSQL 13+ and H2 (PostgreSQL mode, tests).
-- Timestamps: BIGINT epoch milliseconds UTC. JSON payloads: TEXT (validated by the application).

CREATE TABLE agencies (
    id              VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    settings_json   TEXT NOT NULL,
    matching_json   TEXT NOT NULL,
    created_at      BIGINT NOT NULL
);

CREATE TABLE users (
    id              VARCHAR(64) PRIMARY KEY,
    agency_id       VARCHAR(64) NOT NULL REFERENCES agencies(id),
    login           VARCHAR(120) NOT NULL UNIQUE,
    display_name    VARCHAR(200) NOT NULL,
    phone           VARCHAR(20),
    email           VARCHAR(200),
    role            VARCHAR(20) NOT NULL,
    team_id         VARCHAR(64),
    password_hash   VARCHAR(100) NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    failed_logins   INT NOT NULL DEFAULT 0,
    locked_until    BIGINT,
    token_epoch     INT NOT NULL DEFAULT 0,
    created_at      BIGINT NOT NULL,
    deactivated_at  BIGINT
);
CREATE INDEX idx_users_agency ON users(agency_id);

CREATE TABLE refresh_tokens (
    id              VARCHAR(64) PRIMARY KEY,
    user_id         VARCHAR(64) NOT NULL REFERENCES users(id),
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    family_id       VARCHAR(64) NOT NULL,
    expires_at      BIGINT NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      BIGINT NOT NULL
);
CREATE INDEX idx_refresh_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_family ON refresh_tokens(family_id);

CREATE SEQUENCE record_seq START WITH 1;

CREATE TABLE records (
    agency_id           VARCHAR(64) NOT NULL REFERENCES agencies(id),
    entity_type         VARCHAR(40) NOT NULL,
    id                  VARCHAR(64) NOT NULL,
    version             BIGINT NOT NULL,
    assigned_agent_id   VARCHAR(64),
    team_id             VARCHAR(64),
    updated_at          BIGINT NOT NULL,
    deleted             BOOLEAN NOT NULL DEFAULT FALSE,
    data_json           TEXT NOT NULL,
    confidential_json   TEXT,
    server_seq          BIGINT NOT NULL,
    updated_by          VARCHAR(64),
    PRIMARY KEY (agency_id, entity_type, id)
);
CREATE INDEX idx_records_seq ON records(agency_id, server_seq);
CREATE INDEX idx_records_agent ON records(agency_id, assigned_agent_id);

CREATE TABLE applied_changes (
    change_id       VARCHAR(64) PRIMARY KEY,
    agency_id       VARCHAR(64) NOT NULL,
    user_id         VARCHAR(64) NOT NULL,
    result_json     TEXT NOT NULL,
    created_at      BIGINT NOT NULL
);

CREATE TABLE audit_events (
    id              VARCHAR(64) PRIMARY KEY,
    agency_id       VARCHAR(64) NOT NULL,
    actor_user_id   VARCHAR(64),
    entity_type     VARCHAR(40) NOT NULL,
    entity_id       VARCHAR(64) NOT NULL,
    action          VARCHAR(30) NOT NULL,
    changes_json    TEXT,
    note            VARCHAR(500),
    at              BIGINT NOT NULL
);
CREATE INDEX idx_audit_entity ON audit_events(agency_id, entity_type, entity_id);
CREATE INDEX idx_audit_at ON audit_events(agency_id, at);

CREATE TABLE images (
    id              VARCHAR(64) PRIMARY KEY,
    agency_id       VARCHAR(64) NOT NULL,
    property_id     VARCHAR(64) NOT NULL,
    content_type    VARCHAR(40) NOT NULL,
    size_bytes      BIGINT NOT NULL,
    storage_key     VARCHAR(200) NOT NULL,
    uploaded_by     VARCHAR(64) NOT NULL,
    created_at      BIGINT NOT NULL
);
CREATE INDEX idx_images_property ON images(agency_id, property_id);
