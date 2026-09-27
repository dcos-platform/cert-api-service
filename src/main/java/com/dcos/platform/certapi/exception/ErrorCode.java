package com.dcos.platform.certapi.exception;

/**
 * Enumeration of error codes returned in RFC 7807 ProblemDetail responses. Each code is stable
 * across API versions and suitable for client-side error handling.
 */
public enum ErrorCode {
    CERT_NOT_FOUND("Certificate not found"),
    CERT_INVALID_STATE("Certificate state does not permit this operation"),
    CERT_DUPLICATE_ACTIVE("An active certificate already exists for this subject and type"),
    CERT_VALIDATION_FAILED("Request validation failed"),
    CERT_INVALID_PARAMETER("Invalid query or path parameter"),
    CERT_MALFORMED_BODY("Request body is malformed or cannot be parsed"),
    CERT_INVALID_RENEWAL("Renewal date must be strictly later than current expiration"),
    CERT_CONSTRAINT_VIOLATION("Data integrity constraint violated"),
    CERT_CONCURRENT_MODIFICATION("Certificate was concurrently modified"),
    CERT_FORBIDDEN("Operation not permitted for this user"),
    CERT_UNAUTHENTICATED("Authentication required"),
    CERT_INTERNAL_ERROR("Internal server error");

    private final String description;

    ErrorCode(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
