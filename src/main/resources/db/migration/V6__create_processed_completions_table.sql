-- Inbox table for completion idempotency.
-- The event_id is varchar(255) to accommodate retry-suffixed identifiers from the orchestrator.
-- Format: original event_id, then ":retry:" and an attempt number.
CREATE TABLE dcos_certificates.processed_completions (
    event_id VARCHAR(255) PRIMARY KEY,
    certificate_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index for finding completions by certificate.
CREATE INDEX idx_processed_completions_certificate_id ON dcos_certificates.processed_completions(certificate_id);
