package com.dcos.platform.certapi.service;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import com.dcos.platform.certapi.metrics.CertificateOperationMetrics;
import com.dcos.platform.certapi.metrics.SweepKind;
import com.dcos.platform.certapi.repository.CertificateRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled component that sweeps certificates stuck in orchestration pending state for longer than
 * the configured timeout. Moves stale pending certificates to FAILED, recording an explicit reason.
 * This only touches orchestration_status and last_error; the certificate's own status is never
 * modified.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrchestrationTimeoutSweep {

    private static final int BATCH_SIZE = 100;
    private static final String ERROR_ORCHESTRATION_TIMEOUT = "orchestration timed out";

    private final CertificateRepository repository;
    private final CertificateOperationMetrics operationMetrics;

    @Value("${cert-api.orchestration.pending-timeout:PT2M}")
    private String pendingTimeoutConfig;

    @Scheduled(fixedDelayString = "${cert-api.orchestration.sweep-interval-ms:60000}")
    @Transactional
    public void sweep() {
        log.debug("Starting orchestration timeout sweep");

        // Parse the duration from the config (e.g., PT2M for 2 minutes)
        Duration timeout = Duration.parse(pendingTimeoutConfig);
        Instant cutoff = Instant.now().minus(timeout);

        int totalProcessed = 0;
        boolean hasMore = true;

        while (hasMore) {
            Pageable batch = PageRequest.of(0, BATCH_SIZE);
            List<Certificate> stalePending =
                    repository.findByOrchestrationStatusAndUpdatedAtBefore(
                            OrchestrationStatus.PENDING, cutoff, batch);

            if (stalePending.isEmpty()) {
                hasMore = false;
            } else {
                for (Certificate cert : stalePending) {
                    cert.setOrchestrationStatus(OrchestrationStatus.FAILED);
                    cert.setLastError(ERROR_ORCHESTRATION_TIMEOUT);
                    repository.save(cert);
                    totalProcessed++;
                }
            }
        }

        operationMetrics.recordSweepTransitions(SweepKind.ORCHESTRATION_TIMEOUT, totalProcessed);

        if (totalProcessed > 0) {
            log.info(
                    "Orchestration timeout sweep completed: {} certificates moved to FAILED",
                    totalProcessed);
        } else {
            log.debug("Orchestration timeout sweep completed: no stale orchestrations");
        }
    }
}
