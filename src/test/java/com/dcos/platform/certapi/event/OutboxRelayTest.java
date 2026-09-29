package com.dcos.platform.certapi.event;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dcos.platform.certapi.domain.Outbox;
import com.dcos.platform.certapi.repository.OutboxRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock private OutboxRepository outboxRepository;
    @Mock private RabbitTemplate rabbitTemplate;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(outboxRepository, rabbitTemplate);
        ReflectionTestUtils.setField(relay, "batchSize", 10);
        ReflectionTestUtils.setField(relay, "maxAttempts", 3);
        ReflectionTestUtils.setField(relay, "exchange", "test.exchange");
    }

    @Test
    void successfulPublishTransitionsRowToSentAndSetsTimestamp() {
        Outbox row = createPendingOutbox();
        when(outboxRepository.claimPending(10)).thenReturn(Collections.singletonList(row));

        relay.relay();

        assertThat(row.getState()).isEqualTo(OutboxState.SENT);
        assertThat(row.getSentAt()).isNotNull();
        verify(outboxRepository).save(row);
        verify(rabbitTemplate).send(eq("test.exchange"), eq(row.getRoutingKey()), any());
    }

    @Test
    void failureUnderMaxAttemptsIncrementsCountAndStaysInPending() {
        Outbox row = createPendingOutbox();
        when(outboxRepository.claimPending(10)).thenReturn(Collections.singletonList(row));
        doThrow(new RuntimeException("Publish failed"))
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any());

        relay.relay();

        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getState()).isEqualTo(OutboxState.PENDING);
        assertThat(row.getLastError()).isEqualTo("Publish failed");
        verify(outboxRepository).save(row);
    }

    @Test
    void failureAtMaxAttemptsTransitionsRowToFailed() {
        Outbox row = createPendingOutbox();
        row.setAttemptCount(2);
        when(outboxRepository.claimPending(10)).thenReturn(Collections.singletonList(row));
        doThrow(new RuntimeException("Final attempt failed"))
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any());

        relay.relay();

        assertThat(row.getAttemptCount()).isEqualTo(3);
        assertThat(row.getState()).isEqualTo(OutboxState.FAILED);
        assertThat(row.getLastError()).isEqualTo("Final attempt failed");
        verify(outboxRepository).save(row);
    }

    @Test
    void claimPendingIsCalledWithConfiguredBatchSize() {
        Outbox row = createPendingOutbox();
        when(outboxRepository.claimPending(10)).thenReturn(Collections.singletonList(row));

        relay.relay();

        verify(outboxRepository).claimPending(10);
    }

    @Test
    void processesBatchOfMultipleRows() {
        Outbox row1 = createPendingOutbox();
        Outbox row2 = createPendingOutbox();
        when(outboxRepository.claimPending(10)).thenReturn(Arrays.asList(row1, row2));

        relay.relay();

        assertThat(row1.getState()).isEqualTo(OutboxState.SENT);
        assertThat(row2.getState()).isEqualTo(OutboxState.SENT);
        verify(outboxRepository, times(2)).save(any());
    }

    @Test
    void emptyBatchIsHandledGracefully() {
        when(outboxRepository.claimPending(10)).thenReturn(Collections.emptyList());

        relay.relay();

        verify(outboxRepository, never()).save(any());
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void continuesProcessingOtherRowsAfterOneFailure() {
        Outbox row1 = createPendingOutbox();
        Outbox row2 = createPendingOutbox();
        when(outboxRepository.claimPending(10)).thenReturn(Arrays.asList(row1, row2));
        doThrow(new RuntimeException("Publish failed"))
                .doNothing()
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any());

        relay.relay();

        assertThat(row1.getState()).isEqualTo(OutboxState.PENDING);
        assertThat(row1.getAttemptCount()).isEqualTo(1);
        assertThat(row2.getState()).isEqualTo(OutboxState.SENT);
        assertThat(row2.getSentAt()).isNotNull();
    }

    @Test
    void usesStoredCorrelationIdInMessageHeader() {
        String correlationId = "stored-correlation-id";
        Outbox row = createPendingOutbox();
        row.setCorrelationId(correlationId);
        when(outboxRepository.claimPending(10)).thenReturn(Collections.singletonList(row));

        relay.relay();

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate)
                .send(eq("test.exchange"), eq(row.getRoutingKey()), messageCaptor.capture());

        Message sentMessage = messageCaptor.getValue();
        MessageProperties props = sentMessage.getMessageProperties();
        assertThat((String) props.getHeader("x-correlation-id")).isEqualTo(correlationId);
        assertThat((String) props.getHeader("x-correlation-id")).isNotEqualTo(row.getEventId());
    }

    @Test
    void fallsBackToEventIdWhenCorrelationIdNull() {
        Outbox row = createPendingOutbox();
        row.setCorrelationId(null);
        when(outboxRepository.claimPending(10)).thenReturn(Collections.singletonList(row));

        relay.relay();

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate)
                .send(eq("test.exchange"), eq(row.getRoutingKey()), messageCaptor.capture());

        Message sentMessage = messageCaptor.getValue();
        MessageProperties props = sentMessage.getMessageProperties();
        assertThat((String) props.getHeader("x-correlation-id")).isEqualTo(row.getEventId());
    }

    private Outbox createPendingOutbox() {
        Outbox outbox = new Outbox();
        outbox.setId(1L);
        outbox.setEventId(UUID.randomUUID().toString());
        outbox.setAggregateId(UUID.randomUUID());
        outbox.setEventType(EventType.CREATED.name());
        outbox.setRoutingKey(EventType.CREATED.getRoutingKey());
        outbox.setPayload("{}");
        outbox.setState(OutboxState.PENDING);
        outbox.setAttemptCount(0);
        outbox.setCreatedAt(Instant.now());
        return outbox;
    }
}
