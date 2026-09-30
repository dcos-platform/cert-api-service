package com.dcos.platform.certapi.exception;

/**
 * Thrown when a renewal request provides an expiry that is not strictly later than the current
 * expiry.
 */
public class InvalidRenewalException extends RuntimeException {

    public InvalidRenewalException(String message) {
        super(message);
    }

    public InvalidRenewalException(String message, Throwable cause) {
        super(message, cause);
    }
}
