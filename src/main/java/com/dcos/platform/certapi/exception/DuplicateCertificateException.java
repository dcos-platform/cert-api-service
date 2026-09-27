package com.dcos.platform.certapi.exception;

/**
 * Raised when a create request targets a (subject, type) pair that already has an active
 * certificate. This is the business-level constraint; the database's partial unique index is the
 * enforcement mechanism.
 */
public class DuplicateCertificateException extends RuntimeException {

    public DuplicateCertificateException(String subject, String type) {
        super("An active certificate already exists for subject=" + subject + ", type=" + type);
    }
}
