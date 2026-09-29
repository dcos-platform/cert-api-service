package com.dcos.platform.certapi.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test: verifies that hardcoded orchestrator-shaped snake_case JSON deserializes correctly
 * to CompletionEvent records, validating the message format between orchestrator and cert-api.
 */
@DisplayName("CompletionEvent contract tests (orchestrator wire format)")
class CompletionEventContractTest {

    private final ObjectMapper snakeCaseMapper = createSnakeCaseMapper();

    private ObjectMapper createSnakeCaseMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return mapper;
    }

    @Test
    @DisplayName("contract: orchestrator completion event deserializes correctly")
    void completionEventDeserializes() throws Exception {
        // Hardcoded orchestrator-shaped JSON in snake_case
        String orchestratorJson =
                """
            {
              "event_id": "abc-123",
              "certificate_id": "550e8400-e29b-41d4-a716-446655440000",
              "status": "completed",
              "retry_count": 0,
              "error": null
            }
            """;

        CompletionEvent event = snakeCaseMapper.readValue(orchestratorJson, CompletionEvent.class);

        assertThat(event)
                .isNotNull()
                .satisfies(
                        e -> {
                            assertThat(e.eventId()).isEqualTo("abc-123");
                            assertThat(e.certificateId())
                                    .isEqualTo("550e8400-e29b-41d4-a716-446655440000");
                            assertThat(e.status()).isEqualTo("completed");
                            assertThat(e.retryCount()).isEqualTo(0);
                            assertThat(e.error()).isNull();
                        });
    }

    @Test
    @DisplayName("contract: orchestrator failed event with error deserializes correctly")
    void failedEventWithErrorDeserializes() throws Exception {
        // Orchestrator failure event in snake_case
        String orchestratorJson =
                """
            {
              "event_id": "def-456:retry:1",
              "certificate_id": "550e8400-e29b-41d4-a716-446655440001",
              "status": "failed",
              "retry_count": 1,
              "error": "certificate validation failed: invalid DN"
            }
            """;

        CompletionEvent event = snakeCaseMapper.readValue(orchestratorJson, CompletionEvent.class);

        assertThat(event)
                .isNotNull()
                .satisfies(
                        e -> {
                            assertThat(e.eventId()).isEqualTo("def-456:retry:1");
                            assertThat(e.certificateId())
                                    .isEqualTo("550e8400-e29b-41d4-a716-446655440001");
                            assertThat(e.status()).isEqualTo("failed");
                            assertThat(e.retryCount()).isEqualTo(1);
                            assertThat(e.error())
                                    .isEqualTo("certificate validation failed: invalid DN");
                        });
    }

    @Test
    @DisplayName("contract: all snake_case fields are recognized")
    void allFieldsRecognized() throws Exception {
        // Verify that the mapper recognizes the snake_case field names
        String orchestratorJson =
                """
            {
              "event_id": "test-id",
              "certificate_id": "550e8400-e29b-41d4-a716-446655440002",
              "status": "completed",
              "retry_count": 2,
              "error": null
            }
            """;

        CompletionEvent event = snakeCaseMapper.readValue(orchestratorJson, CompletionEvent.class);

        assertThat(event.eventId()).isEqualTo("test-id");
        assertThat(event.certificateId()).isEqualTo("550e8400-e29b-41d4-a716-446655440002");
        assertThat(event.status()).isEqualTo("completed");
        assertThat(event.retryCount()).isEqualTo(2);
        assertThat(event.error()).isNull();
    }
}
