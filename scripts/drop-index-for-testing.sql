-- Temporarily drop the partial unique index to verify the duplicate-rejection test fails without it
-- This is for testing purposes only - run this before the test, then restore it afterwards

DROP INDEX IF EXISTS dcos_certificates.idx_certificates_subject_type_active;
