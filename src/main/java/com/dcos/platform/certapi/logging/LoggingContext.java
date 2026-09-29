package com.dcos.platform.certapi.logging;

import java.util.UUID;

/** Constants for structured logging and correlation ID tracing through MDC and message headers. */
public final class LoggingContext {

    public static final String CORRELATION_HEADER = "x-correlation-id";
    public static final int CORRELATION_ID_MAX_LENGTH = 36;
    public static final String CORRELATION_ID = "correlationId";
    public static final String CERTIFICATE_ID = "certificateId";
    public static final String PRINCIPAL = "principal";

    private LoggingContext() {}

    /**
     * Checks if a correlation ID string is valid.
     *
     * @param value the value to check, or null
     * @return true if the value is non-null, non-blank, and 36 characters or less
     */
    public static boolean isValidCorrelationId(String value) {
        return value != null && !value.isBlank() && value.length() <= CORRELATION_ID_MAX_LENGTH;
    }

    /**
     * Validates and returns a correlation ID. Returns the supplied value if it is a valid UUID or
     * other valid string of 36 characters or less; generates and returns a new UUID otherwise.
     *
     * @param supplied the value to validate, or null
     * @return a valid correlation ID string
     */
    public static String resolveCorrelationId(String supplied) {
        if (isValidCorrelationId(supplied)) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }
}
