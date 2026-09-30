package com.dcos.platform.certapi.event;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.Outbox;
import com.dcos.platform.certapi.logging.LoggingContext;
import com.dcos.platform.certapi.repository.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Enqueues certificate lifecycle events to the transactional outbox. Builds the event envelope,
 * serializes it with the AMQP object mapper (which uses snake_case naming), and writes the outbox
 * row. The caller's transaction encompasses both the certificate and the outbox row, so atomicity
 * is guaranteed: either both persist or neither does.
 *
 * <p>No service method may call the broker directly; all publication goes through this service.
 */
@Service
public class OutboxEnqueueService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper amqpObjectMapper;

    public OutboxEnqueueService(
            OutboxRepository outboxRepository,
            @Qualifier("amqpObjectMapper") ObjectMapper amqpObjectMapper) {
        this.outboxRepository = outboxRepository;
        this.amqpObjectMapper = amqpObjectMapper;
    }

    /**
     * Enqueues a certificate event to the outbox.
     *
     * @param cert the certificate for which the event is emitted
     * @param eventType the type of event
     */
    public void enqueue(Certificate cert, EventType eventType) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now();

        CertificateEventPayload payload = CertificateEventPayload.from(cert);
        CertificateEventEnvelope envelope =
                new CertificateEventEnvelope(
                        eventId.toString(), cert.getId().toString(), occurredAt, payload);

        String serialized;
        try {
            serialized = amqpObjectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event envelope", e);
        }

        String correlationId = MDC.get(LoggingContext.CORRELATION_ID);
        if (correlationId == null && cert.getCorrelationId() != null) {
            correlationId = cert.getCorrelationId().toString();
        }

        Outbox outbox = new Outbox();
        outbox.setEventId(eventId.toString());
        outbox.setAggregateId(cert.getId());
        outbox.setEventType(eventType.name());
        outbox.setRoutingKey(eventType.getRoutingKey());
        outbox.setPayload(serialized);
        outbox.setState(OutboxState.PENDING);
        outbox.setAttemptCount(0);
        outbox.setCreatedAt(occurredAt);
        outbox.setCorrelationId(correlationId);

        outboxRepository.save(outbox);
    }
}
