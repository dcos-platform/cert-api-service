package com.dcos.platform.certapi.event;

import static org.assertj.core.api.Assertions.*;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.domain.CertificateType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for event envelope serialization. Verifies that event envelopes are serialized to
 * snake_case JSON with ISO-8601 timestamps, matching the wire contract expected by the
 * orchestrator.
 */
class EventEnvelopeSerializationTest {

    private ObjectMapper amqpMapper;

    @BeforeEach
    void setUp() {
        amqpMapper = new ObjectMapper();
        amqpMapper.registerModule(new JavaTimeModule());
        amqpMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        amqpMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Test
    void createdEventIsSerializedCorrectly() throws Exception {
        Certificate cert = createTestCertificate();
        CertificateEventPayload payload = CertificateEventPayload.from(cert);
        CertificateEventEnvelope envelope =
                new CertificateEventEnvelope(
                        cert.getId().toString(), cert.getId().toString(), Instant.now(), payload);

        String json = amqpMapper.writeValueAsString(envelope);
        JsonNode node = amqpMapper.readTree(json);

        // Verify all top-level keys are present
        assertThat(node.has("event_id")).isTrue();
        assertThat(node.has("certificate_id")).isTrue();
        assertThat(node.has("occurred_at")).isTrue();
        assertThat(node.has("payload")).isTrue();

        // Verify no camelCase keys
        assertThat(json).doesNotContain("eventId");
        assertThat(json).doesNotContain("certificateId");
        assertThat(json).doesNotContain("occurredAt");

        // Verify timestamp is ISO-8601 string, not numeric
        assertThat(node.get("occurred_at").isTextual()).isTrue();

        // Verify payload is an object, not a string
        assertThat(node.get("payload").isObject()).isTrue();

        // Verify payload contains snake_case keys
        JsonNode payloadNode = node.get("payload");
        assertThat(payloadNode.has("source")).isTrue();
        assertThat(payloadNode.has("schema_version")).isTrue();
        assertThat(payloadNode.has("certificate_id")).isTrue();
    }

    @Test
    void renewedEventIsSerializedCorrectly() throws Exception {
        Certificate cert = createTestCertificate();
        CertificateEventPayload payload = CertificateEventPayload.from(cert);
        CertificateEventEnvelope envelope =
                new CertificateEventEnvelope(
                        UUID.randomUUID().toString(),
                        cert.getId().toString(),
                        Instant.now(),
                        payload);

        String json = amqpMapper.writeValueAsString(envelope);
        JsonNode node = amqpMapper.readTree(json);

        // Verify structure
        assertThat(node.has("event_id")).isTrue();
        assertThat(node.get("occurred_at").isTextual()).isTrue();

        // Verify payload is object
        assertThat(node.get("payload").isObject()).isTrue();
    }

    @Test
    void revokedEventIsSerializedCorrectly() throws Exception {
        Certificate cert = createTestCertificate();
        cert.setStatus(CertificateStatus.REVOKED);
        CertificateEventPayload payload = CertificateEventPayload.from(cert);
        CertificateEventEnvelope envelope =
                new CertificateEventEnvelope(
                        UUID.randomUUID().toString(),
                        cert.getId().toString(),
                        Instant.now(),
                        payload);

        String json = amqpMapper.writeValueAsString(envelope);
        JsonNode node = amqpMapper.readTree(json);

        assertThat(node.has("event_id")).isTrue();
        assertThat(node.get("payload").isObject()).isTrue();
    }

    @Test
    void expiredEventIsSerializedCorrectly() throws Exception {
        Certificate cert = createTestCertificate();
        cert.setStatus(CertificateStatus.EXPIRED);
        CertificateEventPayload payload = CertificateEventPayload.from(cert);
        CertificateEventEnvelope envelope =
                new CertificateEventEnvelope(
                        UUID.randomUUID().toString(),
                        cert.getId().toString(),
                        Instant.now(),
                        payload);

        String json = amqpMapper.writeValueAsString(envelope);
        JsonNode node = amqpMapper.readTree(json);

        assertThat(node.has("event_id")).isTrue();
        assertThat(node.get("payload").isObject()).isTrue();
    }

    @Test
    void timestampIsIso8601Format() throws Exception {
        Certificate cert = createTestCertificate();
        Instant now = Instant.now();
        CertificateEventPayload payload = CertificateEventPayload.from(cert);
        CertificateEventEnvelope envelope =
                new CertificateEventEnvelope(
                        UUID.randomUUID().toString(), cert.getId().toString(), now, payload);

        String json = amqpMapper.writeValueAsString(envelope);

        // Verify ISO-8601 format (e.g., 2026-09-27T12:34:56.789Z or with nanosecond precision)
        assertThat(json).matches(".*\"occurred_at\"\\s*:\\s*\"\\d{4}-\\d{2}-\\d{2}T[^\"]+Z\".*");
    }

    @Test
    void nonAsciiSubjectRoundTripsCorrectlyThroughUtf8() throws Exception {
        Certificate cert = createTestCertificate();
        String accentedSubject = "CN=café-sécurisé,OU=测试,O=DCOS";
        cert.setSubject(accentedSubject);
        cert.setCommonName("café-sécurisé");

        CertificateEventPayload payload = CertificateEventPayload.from(cert);
        CertificateEventEnvelope envelope =
                new CertificateEventEnvelope(
                        UUID.randomUUID().toString(),
                        cert.getId().toString(),
                        Instant.now(),
                        payload);

        String json = amqpMapper.writeValueAsString(envelope);
        byte[] serialized = json.getBytes(StandardCharsets.UTF_8);
        String deserialized = new String(serialized, StandardCharsets.UTF_8);
        JsonNode node = amqpMapper.readTree(deserialized);

        assertThat(deserialized).isEqualTo(json);
        assertThat(node.has("payload")).isTrue();
        assertThat(node.get("payload").has("subject")).isTrue();
        assertThat(node.get("payload").get("subject").asText()).isEqualTo(accentedSubject);
    }

    private Certificate createTestCertificate() {
        Certificate cert = new Certificate();
        cert.setId(UUID.randomUUID());
        cert.setSerialNumber("DCOS-2026-ABCDEF123456");
        cert.setSubject("CN=test.example.com,OU=test,O=DCOS");
        cert.setCommonName("test.example.com");
        cert.setType(CertificateType.TLS);
        cert.setStatus(CertificateStatus.ACTIVE);
        cert.setIssuedAt(Instant.now());
        cert.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        cert.setIssuedBy("Internal CA");
        return cert;
    }
}
