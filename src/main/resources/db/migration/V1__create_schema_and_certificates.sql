CREATE SCHEMA IF NOT EXISTS dcos_certificates;

CREATE TABLE dcos_certificates.certificates (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- serial_number: NOT NULL + UNIQUE deferred to Story 6 (certificate creation).
    -- Nullable here to allow initial create without serial generation.
    serial_number        varchar(64),
    subject              varchar(255) NOT NULL,
    -- common_name: NOT NULL deferred to Story 6.
    -- Nullable here; Story 6 parses it from subject or accepts it from the request.
    common_name          varchar(255),
    type                 varchar(32)  NOT NULL,
    status               varchar(32)  NOT NULL,
    orchestration_status varchar(32)  NOT NULL DEFAULT 'PENDING',
    issued_by            varchar(255) NOT NULL,
    issued_at            timestamptz  NOT NULL,
    expires_at           timestamptz  NOT NULL,
    renewal_window_days  integer      NOT NULL DEFAULT 30,
    revoked_at           timestamptz,
    revocation_reason    varchar(64),
    revocation_comment   varchar(512),
    -- requested_by: NOT NULL deferred to Story 6.
    -- Nullable here; Story 6 captures the authenticated principal.
    requested_by         varchar(128),
    correlation_id       uuid,
    last_error           text,
    renewal_count        integer      NOT NULL DEFAULT 0,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    version              bigint       NOT NULL DEFAULT 0,

    CONSTRAINT uq_certificates_serial_number UNIQUE (serial_number),
    CONSTRAINT ck_certificates_type CHECK (type IN ('TLS','CLIENT','CA','CODE_SIGNING')),
    CONSTRAINT ck_certificates_status CHECK (status IN ('ACTIVE','EXPIRED','REVOKED')),
    CONSTRAINT ck_certificates_orchestration_status
        CHECK (orchestration_status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_certificates_validity CHECK (expires_at > issued_at),
    CONSTRAINT ck_certificates_renewal_window CHECK (renewal_window_days BETWEEN 1 AND 365),
    CONSTRAINT ck_certificates_revocation_reason CHECK (
        revocation_reason IS NULL OR revocation_reason IN (
            'UNSPECIFIED','KEY_COMPROMISE','CA_COMPROMISE',
            'AFFILIATION_CHANGED','SUPERSEDED','CESSATION_OF_OPERATION'))
    -- ck_certificates_revoked_consistency: deferred to Story 8 (lifecycle transitions).
    -- Story 8 adds: CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

CREATE INDEX idx_certificates_status      ON dcos_certificates.certificates (status);
CREATE INDEX idx_certificates_expires_at  ON dcos_certificates.certificates (expires_at);
CREATE INDEX idx_certificates_common_name ON dcos_certificates.certificates (common_name);
CREATE INDEX idx_certificates_type_status ON dcos_certificates.certificates (type, status);
CREATE INDEX idx_certificates_orch_status ON dcos_certificates.certificates (orchestration_status);
