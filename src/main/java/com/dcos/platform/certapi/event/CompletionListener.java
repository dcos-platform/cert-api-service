package com.dcos.platform.certapi.event;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import com.dcos.platform.certapi.logging.LoggingContext;
import com.dcos.platform.certapi.repository.CertificateRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
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
     * @param correlationHeader the correlation header from the message, if present
     */
    @RabbitListener(
            queues = "${cert-api.rabbitmq.completions-queue:certificate.lifecycle.completions}",
            containerFactory = "completionListenerContainerFactory")
    @Transactional
    public void consume(
            CompletionEvent event,
            @Header(name = LoggingContext.CORRELATION_HEADER, required = false)
                    String correlationHeader) {
        // Validate certificate UUID format early
        UUID certificateId;
        try {
            certificateId = UUID.fromString(event.certificateId());
        } catch (IllegalArgumentException e) {
            log.warn(
                    "Invalid certificate UUID in completion: certificateId={}",
                    event.certificateId());
            return;
        }

        // Insert inbox record to mark this event as processed first. A primary-key collision means
        // the event has already been processed (duplicate delivery).
        if (!inboxService.recordProcessed(event.eventId(), certificateId)) {
            log.debug("Duplicate completion event: eventId={}", event.eventId());
            return;
        }

        // Now fetch the certificate and set up correlation context
        Optional<Certificate> optionalCert = certificateRepository.findById(certificateId);
        String contextCorrelationId = resolveCorrelationId(correlationHeader, optionalCert);

        try {
            MDC.put(LoggingContext.CORRELATION_ID, contextCorrelationId);

            log.info(
                    "Completion event received: eventId={}, certificateId={}, status={}",
                    event.eventId(),
                    event.certificateId(),
                    event.status());

            if (optionalCert.isEmpty()) {
                log.warn(
                        "Certificate not found for completion: certificateId={}, eventId={}",
                        event.certificateId(),
                        event.eventId());
                return;
            }

            Certificate cert = optionalCert.get();
            applyCompletionStatus(cert, event);

            certificateRepository.save(cert);
            log.info(
                    "Certificate orchestration status updated: certificateId={}, status={}",
                    event.certificateId(),
                    event.status());
        } finally {
            MDC.clear();
        }
    }

    private String resolveCorrelationId(String header, Optional<Certificate> certificate) {
        // Prefer header if provided and valid
        if (LoggingContext.isValidCorrelationId(header)) {
            return header;
        }
        // Fall back to certificate's correlation ID if present
        if (certificate.isPresent() && certificate.get().getCorrelationId() != null) {
            return certificate.get().getCorrelationId().toString();
        }
        // Generate new UUID if neither available
        return UUID.randomUUID().toString();
    }

    private void applyCompletionStatus(Certificate cert, CompletionEvent event) {
        String status = event.status();
        if (status == null) {
            log.warn("Completion event has null status");
            return;
        }

        String lowerStatus = status.toLowerCase();
        if (STATUS_COMPLETED.equals(lowerStatus)) {
            cert.setOrchestrationStatus(OrchestrationStatus.COMPLETED);
            cert.setLastError(null);
        } else if (STATUS_FAILED.equals(lowerStatus)) {
            cert.setOrchestrationStatus(OrchestrationStatus.FAILED);
            cert.setLastError(event.error());
        } else {
            log.warn("Unrecognised completion status: status={}", status);
        }
    }
}
