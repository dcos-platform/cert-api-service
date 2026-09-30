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
import com.dcos.platform.certapi.support.RequiresTestDatabase;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@RequiresTestDatabase
@DisplayName("CompletionListener integration tests (real DB and broker)")
class CompletionListenerIntegrationTest {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private CertificateRepository certificateRepository;
    @Autowired private ProcessedCompletionRepository processedCompletionRepository;

    private static final String COMPLETIONS_QUEUE = "certificate.lifecycle.completions";
    private static final String NONNATIVE_SUBJECT = "CN=Über Company,O=DCOS";

    @BeforeEach
    void cleanupBefore() {
        processedCompletionRepository.deleteAll();
        certificateRepository.deleteAll();
    }

    @AfterEach
    void cleanupAfter() {
        processedCompletionRepository.deleteAll();
        certificateRepository.deleteAll();
    }

    @Test
    @DisplayName("integration: completion event updates certificate status in database")
    void completionEventUpdatesCertificate() {
        Certificate cert = CertificateFixtures.active();
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);
        certificateRepository.save(cert);

        String eventId = UUID.randomUUID().toString();
        CompletionEvent event =
                new CompletionEvent(eventId, cert.getId().toString(), "COMPLETED", 0, null);

        rabbitTemplate.convertAndSend(COMPLETIONS_QUEUE, event);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            Optional<Certificate> updated =
                                    certificateRepository.findById(cert.getId());
                            assertThat(updated)
                                    .isPresent()
                                    .get()
                                    .satisfies(
                                            c -> {
                                                assertThat(c.getOrchestrationStatus())
                                                        .isEqualTo(OrchestrationStatus.COMPLETED);
                                                assertThat(c.getLastError()).isNull();
                                            });
                        });
    }

    @Test
    @DisplayName("integration: failed completion stores error message")
    void failedCompletionStoresError() {
        Certificate cert = CertificateFixtures.active();
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);
        certificateRepository.save(cert);

        String eventId = UUID.randomUUID().toString();
        String errorMsg = "signature validation failed";
        CompletionEvent event =
                new CompletionEvent(eventId, cert.getId().toString(), "FAILED", 0, errorMsg);

        rabbitTemplate.convertAndSend(COMPLETIONS_QUEUE, event);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            Optional<Certificate> updated =
                                    certificateRepository.findById(cert.getId());
                            assertThat(updated)
                                    .isPresent()
                                    .get()
                                    .satisfies(
                                            c -> {
                                                assertThat(c.getOrchestrationStatus())
                                                        .isEqualTo(OrchestrationStatus.FAILED);
                                                assertThat(c.getLastError()).isEqualTo(errorMsg);
                                            });
                        });
    }

    @Test
    @DisplayName("integration: duplicate event idempotency (primary key collision)")
    void duplicateEventIdempotency() {
        Certificate cert = CertificateFixtures.active();
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);
        certificateRepository.save(cert);

        String eventId = UUID.randomUUID().toString();
        // First event: completion
        CompletionEvent firstEvent =
                new CompletionEvent(eventId, cert.getId().toString(), "COMPLETED", 0, null);

        // Send first event
        rabbitTemplate.convertAndSend(COMPLETIONS_QUEUE, firstEvent);

        await().atMost(Duration.ofSeconds(5))
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
    @DisplayName("integration: retry-suffixed event id stored in inbox")
    void retryEventIdStoredInInbox() {
        Certificate cert = CertificateFixtures.active();
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);
        certificateRepository.save(cert);

        String originalId = UUID.randomUUID().toString();
        String retryEventId = originalId + ":retry:2";

        CompletionEvent event =
                new CompletionEvent(retryEventId, cert.getId().toString(), "COMPLETED", 2, null);

        rabbitTemplate.convertAndSend(COMPLETIONS_QUEUE, event);

        await().atMost(Duration.ofSeconds(5))
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

        String eventId = UUID.randomUUID().toString();
        CompletionEvent event =
                new CompletionEvent(eventId, cert.getId().toString(), "COMPLETED", 0, null);

        rabbitTemplate.convertAndSend(COMPLETIONS_QUEUE, event);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            Optional<Certificate> updated =
                                    certificateRepository.findById(cert.getId());
                            assertThat(updated)
                                    .isPresent()
                                    .get()
                                    .satisfies(
                                            c -> {
                                                assertThat(c.getSubject())
                                                        .isEqualTo(NONNATIVE_SUBJECT);
                                                assertThat(c.getOrchestrationStatus())
                                                        .isEqualTo(OrchestrationStatus.COMPLETED);
                                            });
                        });
    }

    @Test
    @DisplayName(
            "integration: certificate status unchanged after both completed and failed completion")
    void certificateStatusUnchangedAfterCompletions() {
        Certificate cert = CertificateFixtures.active();
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);
        certificateRepository.save(cert);

        // First: send completion event
        String firstEventId = UUID.randomUUID().toString();
        CompletionEvent firstEvent =
                new CompletionEvent(firstEventId, cert.getId().toString(), "COMPLETED", 0, null);
        rabbitTemplate.convertAndSend(COMPLETIONS_QUEUE, firstEvent);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            Optional<Certificate> updated =
                                    certificateRepository.findById(cert.getId());
                            assertThat(updated)
                                    .isPresent()
                                    .get()
                                    .satisfies(
                                            c -> {
                                                assertThat(c.getStatus())
                                                        .isEqualTo(CertificateStatus.ACTIVE);
                                                assertThat(c.getOrchestrationStatus())
                                                        .isEqualTo(OrchestrationStatus.COMPLETED);
                                                assertThat(c.getLastError()).isNull();
                                            });
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

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            Optional<Certificate> updated =
                                    certificateRepository.findById(cert.getId());
                            assertThat(updated)
                                    .isPresent()
                                    .get()
                                    .satisfies(
                                            c -> {
                                                assertThat(c.getStatus())
                                                        .isEqualTo(
                                                                CertificateStatus
                                                                        .ACTIVE); // Certificate
                                                // status NEVER
                                                // changes
                                                assertThat(c.getOrchestrationStatus())
                                                        .isEqualTo(
                                                                OrchestrationStatus
                                                                        .FAILED); // Orchestration
                                                // status changed
                                                assertThat(c.getLastError())
                                                        .isEqualTo(
                                                                "orchestration failed for other reason");
                                            });
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
