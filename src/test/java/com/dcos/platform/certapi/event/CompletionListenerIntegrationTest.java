package com.dcos.platform.certapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import com.dcos.platform.certapi.domain.ProcessedCompletion;
import com.dcos.platform.certapi.repository.CertificateRepository;
import com.dcos.platform.certapi.repository.ProcessedCompletionRepository;
import com.dcos.platform.certapi.support.CertificateFixtures;
import com.dcos.platform.certapi.support.ListenerTestSupport;
import com.dcos.platform.certapi.support.RequiresTestDatabase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@RequiresTestDatabase
@DisplayName("CompletionListener integration tests (real DB and broker)")
class CompletionListenerIntegrationTest {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;
    @Autowired private RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired private CertificateRepository certificateRepository;
    @Autowired private ProcessedCompletionRepository processedCompletionRepository;

    private final List<UUID> createdCertificateIds = new ArrayList<>();

    /**
     * Generous ceiling for a message to be consumed and its effect to land in the database.
     *
     * <p>Awaitility returns as soon as the condition holds, so a healthy run is unaffected by the
     * size of this value; it only bounds how long a genuine failure takes to report. It is
     * deliberately well beyond any plausible delivery latency so that a failure here means the
     * message was lost rather than merely slow.
     */
    private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(30);

    /** Slower than the default poll, because these conditions also query the broker. */
    private static final Duration DIAGNOSTIC_POLL_INTERVAL = Duration.ofMillis(500);

    private static final String COMPLETIONS_QUEUE = "certificate.lifecycle.completions";
    private static final String DLQ = "cert.events.dlq";
    private static final String NONNATIVE_SUBJECT = "CN=Über Company,O=DCOS";

    /**
     * Resets shared state only while no consumer is attached, then reattaches.
     *
     * <p>The listener runs against the same queue and the same database for every test in this
     * class. Purging or deleting underneath a live consumer races whatever it is delivering, which
     * is what made these tests fail intermittently in different places from run to run.
     */
    @BeforeEach
    void setupTest() {
        createdCertificateIds.clear();
        ListenerTestSupport.stopListenersAndAwaitNoConsumer(
                listenerRegistry, rabbitAdmin, COMPLETIONS_QUEUE);
        purgeAndReset();
        ListenerTestSupport.startListenersAndAwaitConsumer(
                listenerRegistry, rabbitAdmin, COMPLETIONS_QUEUE);
    }

    @AfterEach
    void teardownTest() {
        ListenerTestSupport.stopListenersAndAwaitNoConsumer(
                listenerRegistry, rabbitAdmin, COMPLETIONS_QUEUE);
        purgeAndReset();
    }

    private void purgeAndReset() {
        rabbitAdmin.purgeQueue(COMPLETIONS_QUEUE);
        rabbitAdmin.purgeQueue(DLQ);
        processedCompletionRepository.deleteAll();
        createdCertificateIds.forEach(certificateRepository::deleteById);
    }

    /**
     * Waits for the completion carrying {@code eventId} to be consumed, and for the certificate to
     * satisfy the given expectations.
     *
     * <p>Asserted in two stages, because certificate state alone cannot distinguish a message that
     * was never consumed from one that was consumed and had no effect — and a completion could in
     * principle be applied by some other delivery. The inbox row establishes that this specific
     * event was processed. When it is absent, the broker's own message and consumer counts say
     * whether the message is still queued, was dead-lettered, or was never routed at all.
     *
     * @param eventId the completion event id that must appear in the inbox
     * @param certificateId the certificate the completion applies to
     * @param expectations assertions to apply to the certificate once it is found
     */
    private void awaitCompletionApplied(
            String eventId, UUID certificateId, Consumer<Certificate> expectations) {
        await().atMost(MESSAGE_TIMEOUT)
                .pollInterval(DIAGNOSTIC_POLL_INTERVAL)
                .untilAsserted(
                        () -> {
                            assertThat(processedCompletionRepository.findById(eventId))
                                    .as(
                                            "inbox row for eventId=%s; broker: %s",
                                            eventId,
                                            ListenerTestSupport.brokerState(
                                                    rabbitAdmin, COMPLETIONS_QUEUE, DLQ))
                                    .isPresent();

                            assertThat(certificateRepository.findById(certificateId))
                                    .as("certificate %s after completion", certificateId)
                                    .isPresent()
                                    .get()
                                    .satisfies(expectations);
                        });
    }

    @Test
    @DisplayName("integration: completion event updates certificate status in database")
    void completionEventUpdatesCertificate() {
        Certificate cert = CertificateFixtures.active();
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);
        certificateRepository.save(cert);
        createdCertificateIds.add(cert.getId());

        String eventId = UUID.randomUUID().toString();
        CompletionEvent event =
                new CompletionEvent(eventId, cert.getId().toString(), "COMPLETED", 0, null);

        rabbitTemplate.convertAndSend(COMPLETIONS_QUEUE, event);

        awaitCompletionApplied(
                eventId,
                cert.getId(),
                c -> {
                    assertThat(c.getOrchestrationStatus()).isEqualTo(OrchestrationStatus.COMPLETED);
                    assertThat(c.getLastError()).isNull();
                });
    }

    @Test
    @DisplayName("integration: failed completion stores error message")
    void failedCompletionStoresError() {
        Certificate cert = CertificateFixtures.active();
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);
        certificateRepository.save(cert);
        createdCertificateIds.add(cert.getId());

        String eventId = UUID.randomUUID().toString();
        String errorMsg = "signature validation failed";
        CompletionEvent event =
                new CompletionEvent(eventId, cert.getId().toString(), "FAILED", 0, errorMsg);

        rabbitTemplate.convertAndSend(COMPLETIONS_QUEUE, event);

        awaitCompletionApplied(
                eventId,
                cert.getId(),
                c -> {
                    assertThat(c.getOrchestrationStatus()).isEqualTo(OrchestrationStatus.FAILED);
                    assertThat(c.getLastError()).isEqualTo(errorMsg);
                });
    }

    @Test
    @DisplayName("integration: duplicate event idempotency (primary key collision)")
    void duplicateEventIdempotency() {
        Certificate cert = CertificateFixtures.active();
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);
        certificateRepository.save(cert);
        createdCertificateIds.add(cert.getId());

        String eventId = UUID.randomUUID().toString();
        // First event: completion
        CompletionEvent firstEvent =
                new CompletionEvent(eventId, cert.getId().toString(), "COMPLETED", 0, null);

        // Send first event
        rabbitTemplate.convertAndSend(COMPLETIONS_QUEUE, firstEvent);

        await().atMost(MESSAGE_TIMEOUT)
                .untilAsserted(
                        () -> {
                            Optional<ProcessedCompletion> processed =
                                    processedCompletionRepository.findById(eventId);
                            assertThat(processed).isPresent();
                        });

        Certificate afterFirst = certificateRepository.findById(cert.getId()).get();
        assertThat(afterFirst.getOrchestrationStatus()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(afterFirst.getLastError()).isNull();

        // Send duplicate with different status (failed with error). Without idempotency, this
        // would change the status to FAILED. With proper dedup, the duplicate is ignored.
        CompletionEvent duplicateEvent =
                new CompletionEvent(
                        eventId,
                        cert.getId().toString(),
                        "FAILED",
                        0,
                        "this should be ignored due to idempotency");

        rabbitTemplate.convertAndSend(COMPLETIONS_QUEUE, duplicateEvent);

        // Wait a bit to ensure duplicate is processed
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Certificate should still be COMPLETED because duplicate was ignored, not reprocessed
        Certificate afterSecond = certificateRepository.findById(cert.getId()).get();
        assertThat(afterSecond.getOrchestrationStatus()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(afterSecond.getLastError()).isNull(); // No error, proves duplicate ignored
    }

    @Test
    @DisplayName("integration: completion for an unknown certificate is dead-lettered, not dropped")
    void unknownCertificateIsDeadLettered() {
        String eventId = UUID.randomUUID().toString();
        CompletionEvent event =
                new CompletionEvent(eventId, UUID.randomUUID().toString(), "COMPLETED", 0, null);

        rabbitTemplate.convertAndSend(COMPLETIONS_QUEUE, event);

        await().atMost(MESSAGE_TIMEOUT)
                .pollInterval(DIAGNOSTIC_POLL_INTERVAL)
                .untilAsserted(
                        () ->
                                assertThat(dlqDepth())
                                        .as(
                                                "dead-lettered after bounded retries; broker: %s",
                                                ListenerTestSupport.brokerState(
                                                        rabbitAdmin, COMPLETIONS_QUEUE, DLQ))
                                        .isPositive());

        // The event id must not have been claimed, or a redelivery once the certificate exists
        // would be suppressed as a duplicate and the completion lost for good.
        assertThat(processedCompletionRepository.findById(eventId))
                .as("inbox row for an unresolved completion")
                .isEmpty();
    }

    private int dlqDepth() {
        var info = rabbitAdmin.getQueueInfo(DLQ);
        return info == null ? 0 : info.getMessageCount();
    }

    @Test
    @DisplayName("integration: retry-suffixed event id stored in inbox")
    void retryEventIdStoredInInbox() {
        Certificate cert = CertificateFixtures.active();
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);
        certificateRepository.save(cert);
        createdCertificateIds.add(cert.getId());

        String originalId = UUID.randomUUID().toString();
        String retryEventId = originalId + ":retry:2";

        CompletionEvent event =
                new CompletionEvent(retryEventId, cert.getId().toString(), "COMPLETED", 2, null);

        rabbitTemplate.convertAndSend(COMPLETIONS_QUEUE, event);

        await().atMost(MESSAGE_TIMEOUT)
                .untilAsserted(
                        () -> {
                            Optional<ProcessedCompletion> processed =
                                    processedCompletionRepository.findById(retryEventId);
                            assertThat(processed)
                                    .isPresent()
                                    .get()
                                    .satisfies(
                                            p -> {
                                                assertThat(p.getEventId()).isEqualTo(retryEventId);
                                                assertThat(p.getCertificateId())
                                                        .isEqualTo(cert.getId());
                                            });
                        });
    }

    @Test
    @DisplayName("integration: non-ASCII subject in certificate survives outbox publish")
    void nonAsciiSubjectSurvivesOutboxPublish() {
        Certificate cert = CertificateFixtures.active();
        cert.setSubject(NONNATIVE_SUBJECT);
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);
        certificateRepository.save(cert);
        createdCertificateIds.add(cert.getId());

        String eventId = UUID.randomUUID().toString();
        CompletionEvent event =
                new CompletionEvent(eventId, cert.getId().toString(), "COMPLETED", 0, null);

        rabbitTemplate.convertAndSend(COMPLETIONS_QUEUE, event);

        awaitCompletionApplied(
                eventId,
                cert.getId(),
                c -> {
                    assertThat(c.getSubject()).isEqualTo(NONNATIVE_SUBJECT);
                    assertThat(c.getOrchestrationStatus()).isEqualTo(OrchestrationStatus.COMPLETED);
                });
    }

    @Test
    @DisplayName(
            "integration: certificate status unchanged after both completed and failed completion")
    void certificateStatusUnchangedAfterCompletions() {
        Certificate cert = CertificateFixtures.active();
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);
        certificateRepository.save(cert);
        createdCertificateIds.add(cert.getId());

        // First: send completion event
        String firstEventId = UUID.randomUUID().toString();
        CompletionEvent firstEvent =
                new CompletionEvent(firstEventId, cert.getId().toString(), "COMPLETED", 0, null);
        rabbitTemplate.convertAndSend(COMPLETIONS_QUEUE, firstEvent);

        awaitCompletionApplied(
                firstEventId,
                cert.getId(),
                c -> {
                    assertThat(c.getStatus()).isEqualTo(CertificateStatus.ACTIVE);
                    assertThat(c.getOrchestrationStatus()).isEqualTo(OrchestrationStatus.COMPLETED);
                    assertThat(c.getLastError()).isNull();
                });

        // Second: send genuinely distinct failed event. This proves certificate status (ACTIVE)
        // is unchanged even after a failed completion.
        String secondEventId = UUID.randomUUID().toString();
        CompletionEvent secondEvent =
                new CompletionEvent(
                        secondEventId,
                        cert.getId().toString(),
                        "FAILED",
                        0,
                        "orchestration failed for other reason");

        rabbitTemplate.convertAndSend(COMPLETIONS_QUEUE, secondEvent);

        awaitCompletionApplied(
                secondEventId,
                cert.getId(),
                c -> {
                    // The certificate's own status never changes; only orchestration state does.
                    assertThat(c.getStatus()).isEqualTo(CertificateStatus.ACTIVE);
                    assertThat(c.getOrchestrationStatus()).isEqualTo(OrchestrationStatus.FAILED);
                    assertThat(c.getLastError()).isEqualTo("orchestration failed for other reason");
                });

        // Third: send duplicate of the first (completed) event. It should be ignored.
        CompletionEvent duplicateFirstEvent =
                new CompletionEvent(
                        firstEventId,
                        cert.getId().toString(),
                        "FAILED",
                        0,
                        "this duplicate should be ignored");

        rabbitTemplate.convertAndSend(COMPLETIONS_QUEUE, duplicateFirstEvent);

        // Wait and verify the duplicate doesn't change anything
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Certificate afterDuplicate = certificateRepository.findById(cert.getId()).get();
        assertThat(afterDuplicate.getStatus()).isEqualTo(CertificateStatus.ACTIVE);
        assertThat(afterDuplicate.getOrchestrationStatus()).isEqualTo(OrchestrationStatus.FAILED);
        assertThat(afterDuplicate.getLastError())
                .isEqualTo("orchestration failed for other reason");
    }
}
