-- Story 6: Restore constraints deferred from Story 5.
-- This migration adds NOT NULL requirements to columns populated by certificate creation
-- and enforces the duplicate-active-certificate constraint via a partial unique index.

-- Backfill null values defensively before tightening constraints.
-- Certificates may carry nulls if created between Story 5 and this story.
-- Placeholder values must be clearly marked for manual review if any exist.
UPDATE dcos_certificates.certificates
SET serial_number = '[BACKFILL-PLACEHOLDER] ' || id::text
WHERE serial_number IS NULL;

UPDATE dcos_certificates.certificates
SET common_name = '[BACKFILL-PLACEHOLDER] ' || subject
WHERE common_name IS NULL;

UPDATE dcos_certificates.certificates
SET requested_by = '[BACKFILL-PLACEHOLDER]'
WHERE requested_by IS NULL;

-- Alter the three columns to NOT NULL after backfill
ALTER TABLE dcos_certificates.certificates
  ALTER COLUMN serial_number SET NOT NULL;

ALTER TABLE dcos_certificates.certificates
  ALTER COLUMN common_name SET NOT NULL;

ALTER TABLE dcos_certificates.certificates
  ALTER COLUMN requested_by SET NOT NULL;

-- Create partial unique index: prevent two active certificates for the same (subject, type).
-- This is enforced at database level, not application level, preventing race conditions
-- that a check-then-insert cannot catch.
CREATE UNIQUE INDEX idx_certificates_subject_type_active
  ON dcos_certificates.certificates (subject, type)
  WHERE status = 'ACTIVE';
