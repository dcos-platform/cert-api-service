-- Add correlation_id column to outbox table for tracing published messages.
-- Existing rows may leave this null; the relay will only use it for new rows.
ALTER TABLE dcos_certificates.outbox ADD COLUMN correlation_id VARCHAR(36);
