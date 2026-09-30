package com.dcos.platform.certapi.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the {@code prod} logging profile emits one JSON object per line and that the correlation
 * identifier held in the MDC appears as a field of that object.
 */
@SpringBootTest
@ActiveProfiles("prod")
@ExtendWith(OutputCaptureExtension.class)
class JsonLoggingProfileTest {

    private static final Logger LOG = LoggerFactory.getLogger(JsonLoggingProfileTest.class);
    private static final String MESSAGE = "json-profile-probe";
    private static final String CORRELATION_ID = "corr-json-12345";

    /** Clears Logback state left by earlier contexts so the prod profile is applied afresh. */
    @BeforeAll
    static void resetLogging() {
        LoggingSystem.get(JsonLoggingProfileTest.class.getClassLoader()).cleanUp();
    }

    @Test
    void prodProfileEmitsJsonCarryingCorrelationId(CapturedOutput output) throws Exception {
        MDC.put("correlationId", CORRELATION_ID);
        try {
            LOG.info(MESSAGE);
        } finally {
            MDC.clear();
        }

        String line =
                output.getOut()
                        .lines()
                        .filter(l -> l.contains(MESSAGE))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("probe line not found in output"));
        JsonNode json = new ObjectMapper().readTree(line);

        assertThat(json.get("message").asText()).isEqualTo(MESSAGE);
        assertThat(json.get("level").asText()).isEqualTo("INFO");
        assertThat(json.get("logger").asText()).isEqualTo(JsonLoggingProfileTest.class.getName());
        assertThat(json.has("thread")).isTrue();
        assertThat(json.get("correlationId").asText()).isEqualTo(CORRELATION_ID);
    }
}
