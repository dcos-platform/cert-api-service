-- Story 8: Restore the revocation consistency constraint.
-- This migration ensures that status = 'REVOKED' if and only if revoked_at IS NOT NULL.

-- Backfill defensively before adding the constraint.

-- Any revoked row without a timestamp gets one (backfill with now() for safety).
-- This handles existing revoked certificates that never had the timestamp set.
UPDATE dcos_certificates.certificates
SET revoked_at = now()
WHERE status = 'REVOKED' AND revoked_at IS NULL;

-- Any non-revoked row carrying a timestamp has it cleared.
-- This handles any stale or malformed data.
UPDATE dcos_certificates.certificates
SET revoked_at = NULL
WHERE status != 'REVOKED' AND revoked_at IS NOT NULL;

-- Now enforce the constraint: status is REVOKED exactly when revoked_at is present.
ALTER TABLE dcos_certificates.certificates
  ADD CONSTRAINT ck_certificates_revoked_consistency
    CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL));
