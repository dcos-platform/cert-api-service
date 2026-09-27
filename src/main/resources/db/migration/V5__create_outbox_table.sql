-- Outbox table for the transactional outbox pattern.
-- Events are written here in the same transaction as the aggregate, then a relay claims and publishes them.
-- The payload column stores the serialized JSON as a string (not a JSONB object) to avoid double-encoding
-- when the relay publishes it.
CREATE TABLE dcos_certificates.outbox (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL UNIQUE,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    routing_key VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP
);

-- Partial index over pending rows for efficient claiming.
CREATE INDEX idx_outbox_pending ON dcos_certificates.outbox(id)
    WHERE state = 'PENDING';

-- Index on aggregate_id for history queries.
CREATE INDEX idx_outbox_aggregate_id ON dcos_certificates.outbox(aggregate_id);
