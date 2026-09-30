package com.dcos.platform.certapi.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Tests for correlation ID validation and generation. */
class CorrelationValidationTest {

    @Test
    void acceptsValidUuid() {
        String uuid = UUID.randomUUID().toString();
        assertThat(LoggingContext.resolveCorrelationId(uuid)).isEqualTo(uuid);
    }

    @Test
    void acceptsShortStrings() {
        String shortValue = "short-id";
        assertThat(LoggingContext.resolveCorrelationId(shortValue)).isEqualTo(shortValue);
    }

    @Test
    void acceptsMaxLengthString() {
        String maxLength = "x".repeat(LoggingContext.CORRELATION_ID_MAX_LENGTH);
        assertThat(LoggingContext.resolveCorrelationId(maxLength)).isEqualTo(maxLength);
    }

    @Test
    void generatesIdForOversizedString() {
        String oversized = "x".repeat(LoggingContext.CORRELATION_ID_MAX_LENGTH + 1);
        String result = LoggingContext.resolveCorrelationId(oversized);
        assertThat(result).isNotEqualTo(oversized);
        assertThat(result).hasSizeLessThanOrEqualTo(LoggingContext.CORRELATION_ID_MAX_LENGTH);
    }

    @Test
    void generatesIdForNull() {
        String result = LoggingContext.resolveCorrelationId(null);
        assertThat(result).isNotNull();
        assertThat(result).hasSizeLessThanOrEqualTo(LoggingContext.CORRELATION_ID_MAX_LENGTH);
    }

    @Test
    void generatesIdForBlankString() {
        String result = LoggingContext.resolveCorrelationId("   ");
        assertThat(result).isNotNull();
        assertThat(result).isNotBlank();
        assertThat(result).hasSizeLessThanOrEqualTo(LoggingContext.CORRELATION_ID_MAX_LENGTH);
    }
}
