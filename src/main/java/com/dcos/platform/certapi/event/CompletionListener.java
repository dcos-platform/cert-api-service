package com.dcos.platform.certapi.event;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import com.dcos.platform.certapi.exception.CertificateNotFoundException;
import com.dcos.platform.certapi.logging.LoggingContext;
import com.dcos.platform.certapi.metrics.CertificateOperationMetrics;
import com.dcos.platform.certapi.repository.CertificateRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
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
    private final CertificateOperationMetrics operationMetrics;

    /**
     * Consumes a completion event from {@code certificate.lifecycle.completions}.
     *
     * <p>The certificate is resolved before the event id is claimed in the inbox. Claiming first
     * would record a completion for a certificate that is not yet visible as processed, so the
     * message would be acknowledged and every redelivery afterwards suppressed as a duplicate —
     * losing the completion permanently and silently. Resolving first means an unresolvable
     * completion leaves no inbox row, so a redelivery can still succeed.
     *
     * <p>An unknown certificate therefore raises {@link CertificateNotFoundException} rather than
     * being acknowledged. Retries are bounded by the container's retry policy, after which the
     * message is dead-lettered and observable, so this cannot loop indefinitely.
     *
     * <p>A malformed certificate id is a permanent fault that no redelivery can fix, so it is
     * logged and acknowledged instead.
     *
     * @param event the completion event from the orchestrator
     * @param correlationHeader the correlation header from the message, if present
     * @throws CertificateNotFoundException if no certificate matches the event's certificate id
     */
    @RabbitListener(
            queues = "${cert-api.rabbitmq.completions-queue:certificate.lifecycle.completions}",
            containerFactory = "completionListenerContainerFactory")
    @Transactional
    @Retryable(
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2.0),
            retryFor = {Exception.class})
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

        // Resolve the certificate before claiming the event id, so that an unresolvable completion
        // leaves no inbox row behind to suppress its own redelivery.
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
                throw new CertificateNotFoundException(certificateId);
            }

            // Claim the event id. Zero rows inserted means this event was already processed.
            if (!inboxService.recordProcessed(event.eventId(), certificateId)) {
                log.debug("Duplicate completion event: eventId={}", event.eventId());
                operationMetrics.recordDuplicateCompletionSuppressed();
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
