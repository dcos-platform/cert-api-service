package com.dcos.platform.certapi.event;

import static org.assertj.core.api.Assertions.*;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.Outbox;
import com.dcos.platform.certapi.repository.CertificateRepository;
import com.dcos.platform.certapi.repository.OutboxRepository;
import com.dcos.platform.certapi.support.CertificateFixtures;
import com.dcos.platform.certapi.support.RequiresTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for the transactional outbox pattern. Verifies that: (1) a rolled-back
 * transaction leaves no outbox row, and (2) a committed transaction writes both certificate and
 * outbox row in the same transaction.
 */
@RequiresTestDatabase
@SpringBootTest
class OutboxIntegrationTest {

    @Autowired private CertificateRepository certificateRepository;

    @Autowired private OutboxRepository outboxRepository;

    @Autowired private OutboxEnqueueService enqueueService;

    @Autowired
    @Qualifier("amqpObjectMapper")
    private ObjectMapper amqpObjectMapper;

    @AfterEach
    void cleanup() {
        // Remove all outbox rows created by tests
        outboxRepository.deleteAll();
        // Remove all non-seed certificates
        certificateRepository.findAll().stream()
                .filter(
                        cert ->
                                !cert.getId().toString().startsWith("11111111")
                                        && !cert.getId().toString().startsWith("22222222")
                                        && !cert.getId().toString().startsWith("33333333")
                                        && !cert.getId().toString().startsWith("44444444")
                                        && !cert.getId().toString().startsWith("55555555"))
                .forEach(certificateRepository::delete);
    }

    @Test
    @Transactional
    void creationWritesOutboxRowInSameTransaction() {
        Certificate cert = CertificateFixtures.active();

        Certificate saved = certificateRepository.save(cert);
        enqueueService.enqueue(saved, EventType.CREATED);

        List<Outbox> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);

        Outbox row = rows.get(0);
        assertThat(row.getEventId()).isNotNull();
        assertThat(row.getAggregateId()).isEqualTo(saved.getId());
        assertThat(row.getEventType()).isEqualTo(EventType.CREATED.name());
        assertThat(row.getRoutingKey()).isEqualTo(EventType.CREATED.getRoutingKey());
        assertThat(row.getState()).isEqualTo(OutboxState.PENDING);
        assertThat(row.getAttemptCount()).isEqualTo(0);
        assertThat(row.getPayload()).isNotNull();
    }

    @Test
    @Transactional
    void rollbackLeavesNoOutboxRow() {
        try {
            createAndRollback();
        } catch (Exception e) {
            // Expected
        }
        TestTransaction.flagForRollback();
        TestTransaction.end();

        TestTransaction.start();
        List<Outbox> rows = outboxRepository.findAll();
        assertThat(rows).isEmpty();
    }

    void createAndRollback() {
        Certificate cert = CertificateFixtures.active();
        Certificate saved = certificateRepository.save(cert);
        enqueueService.enqueue(saved, EventType.RENEWED);

        throw new RuntimeException("Rollback test");
    }

    @Test
    void payloadIsValidJson() throws Exception {
        Certificate cert = CertificateFixtures.expired();

        Certificate saved = certificateRepository.save(cert);
        enqueueService.enqueue(saved, EventType.EXPIRED);

        List<Outbox> rows = outboxRepository.findAll();
        Outbox row = rows.get(0);

        // Payload should be valid JSON (can be parsed)
        assertThat(row.getPayload()).isNotNull().isNotEmpty();
        amqpObjectMapper.readTree(row.getPayload());
    }

    @Test
    void multipleEventsAreIsolated() {
        Certificate cert1 = CertificateFixtures.active();
        Certificate saved1 = certificateRepository.save(cert1);
        enqueueService.enqueue(saved1, EventType.CREATED);

        Certificate cert2 = CertificateFixtures.revoked();
        Certificate saved2 = certificateRepository.save(cert2);
        enqueueService.enqueue(saved2, EventType.REVOKED);

        List<Outbox> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(2);

        assertThat(rows)
                .extracting(Outbox::getAggregateId)
                .containsExactlyInAnyOrder(saved1.getId(), saved2.getId());
        assertThat(rows)
                .extracting(Outbox::getEventType)
                .containsExactlyInAnyOrder(EventType.CREATED.name(), EventType.REVOKED.name());
    }
}
