package com.dcos.platform.certapi.exception;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class InvalidRenewalExceptionTest {

    @Test
    void constructorWithMessageCreatesException() {
        String message = "New expiry must be strictly later than current expiry";

        InvalidRenewalException ex = new InvalidRenewalException(message);

        assertThat(ex).hasMessage(message).hasNoCause();
    }

    @Test
    void constructorWithMessageAndCauseCreatesException() {
        String message = "New expiry must be strictly later than current expiry";
        Throwable cause = new IllegalArgumentException("Invalid date");

        InvalidRenewalException ex = new InvalidRenewalException(message, cause);

        assertThat(ex)
                .hasMessage(message)
                .hasCause(cause)
                .hasRootCause(new IllegalArgumentException("Invalid date"));
    }
}
