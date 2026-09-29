package com.dcos.platform.certapi.event;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import com.dcos.platform.certapi.repository.CertificateRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Consumes completion events from the orchestrator and updates certificate orchestration state. */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompletionListener {

    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";

    private final CertificateRepository certificateRepository;
    private final CompletionInboxService inboxService;

    /**
     * Consumes a completion event from {@code certificate.lifecycle.completions}. Inserts an inbox
     * record for idempotency; a primary-key collision signals a duplicate. Ignores unknown
     * certificate ids (logs and acknowledges) to avoid infinite retry loops.
     *
     * @param event the completion event from the orchestrator
     */
    @RabbitListener(
            queues = "${cert-api.rabbitmq.completions-queue:certificate.lifecycle.completions}",
            containerFactory = "completionListenerContainerFactory")
    @Transactional
    public void consume(CompletionEvent event) {
        log.info(
                "Completion event received: eventId={}, certificateId={}, status={}",
                event.eventId(),
                event.certificateId(),
                event.status());

        // Validate certificate UUID format before inserting inbox record
        UUID certificateId;
        try {
            certificateId = UUID.fromString(event.certificateId());
        } catch (IllegalArgumentException e) {
            log.warn(
                    "Invalid certificate UUID in completion: certificateId={}",
                    event.certificateId());
            return;
        }

        // Insert inbox record to mark this event as processed. A primary-key collision means
        // the event has already been processed (duplicate delivery).
        if (!inboxService.recordProcessed(event.eventId(), certificateId)) {
            log.debug("Duplicate completion event: eventId={}", event.eventId());
            return;
        }

        Optional<Certificate> optionalCert = certificateRepository.findById(certificateId);
        if (optionalCert.isEmpty()) {
            log.warn(
                    "Certificate not found for completion: certificateId={}, eventId={}",
                    event.certificateId(),
                    event.eventId());
            return;
        }

        Certificate cert = optionalCert.get();
        if (STATUS_COMPLETED.equals(event.status())) {
            cert.setOrchestrationStatus(OrchestrationStatus.COMPLETED);
            cert.setLastError(null);
        } else if (STATUS_FAILED.equals(event.status())) {
            cert.setOrchestrationStatus(OrchestrationStatus.FAILED);
            cert.setLastError(event.error());
        }

        certificateRepository.save(cert);
        log.info(
                "Certificate orchestration status updated: certificateId={}, status={}",
                event.certificateId(),
                event.status());
    }
}
