package com.dcos.platform.certapi.event;

import com.dcos.platform.certapi.domain.Outbox;
import com.dcos.platform.certapi.logging.LoggingContext;
import com.dcos.platform.certapi.repository.OutboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relay that claims pending outbox rows, publishes them, and marks them sent. On failure,
 * increments attempts and records the error, leaving the row pending for retry. At a configurable
 * maximum, marks the row failed and logs at error level.
 *
 * <p>The relay is scheduled to run periodically. Tests invoke it directly since scheduling is
 * disabled under test.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final String CONTENT_TYPE = "application/json";
    private static final String SCHEMA_VERSION_HEADER = "x-schema-version";

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${cert-api.outbox.relay-batch-size:10}")
    private int batchSize;

    @Value("${cert-api.outbox.relay-max-attempts:3}")
    private int maxAttempts;

    @Value("${cert-api.rabbitmq.exchange:cert.events}")
    private String exchange;

    public OutboxRelay(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedRateString = "${cert-api.outbox.relay-interval:5000}")
    @Transactional
    public void relay() {
        List<Outbox> pending = outboxRepository.claimPending(batchSize);
        for (Outbox row : pending) {
            try {
                publishMessage(row);
                row.setState(OutboxState.SENT);
                row.setSentAt(Instant.now());
                outboxRepository.save(row);
            } catch (Exception e) {
                row.setAttemptCount(row.getAttemptCount() + 1);
                row.setLastError(e.getMessage());
                if (row.getAttemptCount() >= maxAttempts) {
                    row.setState(OutboxState.FAILED);
                    log.error(
                            "Outbox relay: event {} failed after {} attempts: {}",
                            row.getEventId(),
                            row.getAttemptCount(),
                            e.getMessage(),
                            e);
                } else {
                    log.warn(
                            "Outbox relay: event {} publish failed (attempt {}/{}), will retry: {}",
                            row.getEventId(),
                            row.getAttemptCount(),
                            maxAttempts,
                            e.getMessage());
                }
                outboxRepository.save(row);
            }
        }
    }

    /**
     * Publishes a message explicitly, bypassing the converter. The payload column holds serialized
     * JSON; passing it through convertAndSend would serialize it a second time, resulting in a JSON
     * string literal on the wire. Instead, build the message with the payload as the body directly.
     */
    private void publishMessage(Outbox row) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(CONTENT_TYPE);
        properties.setDeliveryMode(MessageProperties.DEFAULT_DELIVERY_MODE);
        properties.setMessageId(row.getEventId());
        String correlationId =
                row.getCorrelationId() != null ? row.getCorrelationId() : row.getEventId();
        properties.setHeader(LoggingContext.CORRELATION_HEADER, correlationId);
        properties.setHeader(
                SCHEMA_VERSION_HEADER, String.valueOf(CertificateEventPayload.SCHEMA_VERSION));

        Message message =
                MessageBuilder.withBody(row.getPayload().getBytes(StandardCharsets.UTF_8))
                        .andProperties(properties)
                        .build();

        rabbitTemplate.send(exchange, row.getRoutingKey(), message);
    }
}
