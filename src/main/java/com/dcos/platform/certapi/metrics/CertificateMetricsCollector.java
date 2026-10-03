package com.dcos.platform.certapi.metrics;

import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.domain.CertificateType;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import com.dcos.platform.certapi.event.OutboxState;
import com.dcos.platform.certapi.repository.CertificateRepository;
import com.dcos.platform.certapi.repository.OrchestrationStatusCount;
import com.dcos.platform.certapi.repository.OutboxRepository;
import com.dcos.platform.certapi.repository.OutboxStateCount;
import com.dcos.platform.certapi.repository.StatusTypeCount;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Collects and exposes certificate lifecycle metrics via Micrometer. */
@Slf4j
@Component
public class CertificateMetricsCollector {

    private final CertificateRepository certificateRepository;
    private final OutboxRepository outboxRepository;
    private final MeterRegistry meterRegistry;
    private final Map<CertificateStatus, Map<CertificateType, AtomicLong>> certificateCounts =
            new EnumMap<>(CertificateStatus.class);
    private final Map<OrchestrationStatus, AtomicLong> orchestrationCounts =
            new EnumMap<>(OrchestrationStatus.class);
    private final AtomicLong renewalWindowCount = new AtomicLong(0);
    private final AtomicLong outboxPendingCount = new AtomicLong(0);
    private final AtomicLong outboxFailedCount = new AtomicLong(0);
    private final AtomicLong outboxOldestPendingEpochSeconds = new AtomicLong(0);

    /**
     * Creates a metrics collector that tracks certificate lifecycle and outbox health.
     *
     * @param certificateRepository data access for certificates
     * @param outboxRepository data access for outbox entries
     * @param meterRegistry Micrometer registry for metric registration
     */
    public CertificateMetricsCollector(
            CertificateRepository certificateRepository,
            OutboxRepository outboxRepository,
            MeterRegistry meterRegistry) {
        this.certificateRepository = certificateRepository;
        this.outboxRepository = outboxRepository;
        this.meterRegistry = meterRegistry;

        initializeMetrics();
    }

    private void initializeMetrics() {
        // Pre-create certificate count series (status x type = 3 x 4 = 12 series)
        for (CertificateStatus status : CertificateStatus.values()) {
            Map<CertificateType, AtomicLong> typeMap = new EnumMap<>(CertificateType.class);
            certificateCounts.put(status, typeMap);

            for (CertificateType type : CertificateType.values()) {
                AtomicLong value = new AtomicLong(0);
                typeMap.put(type, value);

                meterRegistry.gauge(
                        MetricNames.CERT_CERTIFICATES_COUNT,
                        Tags.of(
                                MetricNames.TAG_STATUS,
                                status.toString(),
                                MetricNames.TAG_TYPE,
                                type.toString()),
                        value,
                        v -> (double) v.get());
            }
        }

        // Pre-create orchestration status count series (4 values)
        for (OrchestrationStatus orchestrationStatus : OrchestrationStatus.values()) {
            AtomicLong value = new AtomicLong(0);
            orchestrationCounts.put(orchestrationStatus, value);

            meterRegistry.gauge(
                    MetricNames.CERT_CERTIFICATES_ORCHESTRATION_COUNT,
                    Tags.of(MetricNames.TAG_ORCHESTRATION_STATUS, orchestrationStatus.toString()),
                    value,
                    v -> (double) v.get());
        }

        // Pre-create renewal window count gauge (1 series)
        meterRegistry.gauge(
                MetricNames.CERT_CERTIFICATES_RENEWAL_WINDOW_COUNT,
                renewalWindowCount,
                v -> (double) v.get());

        // Pre-create outbox pending count gauge (1 series)
        meterRegistry.gauge(
                MetricNames.CERT_OUTBOX_PENDING_COUNT, outboxPendingCount, v -> (double) v.get());

        // Pre-create outbox failed count gauge (1 series)
        meterRegistry.gauge(
                MetricNames.CERT_OUTBOX_FAILED_COUNT, outboxFailedCount, v -> (double) v.get());

        // Pre-create outbox oldest pending age gauge (1 series) with baseUnit
        // Compute age as (now - stored epoch second), advancing between refreshes
        Gauge.builder(MetricNames.CERT_OUTBOX_OLDEST_PENDING_AGE, this::computeOutboxAge)
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    private double computeOutboxAge() {
        long oldest = outboxOldestPendingEpochSeconds.get();
        if (oldest == 0) {
            return 0;
        }
        return Math.max(0, Instant.now().getEpochSecond() - oldest);
    }

    /**
     * Refreshes all metrics by executing aggregate queries and updating gauge values. Exceptions
     * are swallowed to prevent scrape failures; last known values are retained. Public to allow
     * tests to trigger refresh directly.
     */
    @Scheduled(fixedRateString = "${cert-api.metrics.refresh-interval:60000}", initialDelay = 5000)
    public void refresh() {
        try {
            refreshCertificateStatusTypeCounts();
            refreshCertificateOrchestrationCounts();
            refreshCertificateRenewalWindowCount();
            refreshOutboxStateCounts();
            refreshOutboxOldestPendingAge();
        } catch (Exception e) {
            log.warn("Metrics refresh failed, keeping last known values", e);
        }
    }

    private void refreshCertificateStatusTypeCounts() {
        // Initialize all combinations to 0
        for (CertificateStatus status : CertificateStatus.values()) {
            for (CertificateType type : CertificateType.values()) {
                certificateCounts.get(status).get(type).set(0);
            }
        }

        // Populate from grouped query results
        for (StatusTypeCount result : certificateRepository.countGroupedByStatusAndType()) {
            certificateCounts.get(result.getStatus()).get(result.getType()).set(result.getCount());
        }
    }

    private void refreshCertificateOrchestrationCounts() {
        // Initialize all orchestration statuses to 0
        for (OrchestrationStatus status : OrchestrationStatus.values()) {
            orchestrationCounts.get(status).set(0);
        }

        // Populate from grouped query results
        for (OrchestrationStatusCount result :
                certificateRepository.countGroupedByOrchestrationStatus()) {
            orchestrationCounts.get(result.getOrchestrationStatus()).set(result.getCount());
        }
    }

    private void refreshCertificateRenewalWindowCount() {
        long count = certificateRepository.countCertificatesInRenewalWindow();
        renewalWindowCount.set(count);
    }

    private void refreshOutboxStateCounts() {
        // Initialize both pending and failed to 0, then populate from grouped query
        outboxPendingCount.set(0);
        outboxFailedCount.set(0);

        for (OutboxStateCount result : outboxRepository.countGroupedByState()) {
            if (result.getState() == OutboxState.PENDING) {
                outboxPendingCount.set(result.getCount());
            } else if (result.getState() == OutboxState.FAILED) {
                outboxFailedCount.set(result.getCount());
            }
        }
    }

    private void refreshOutboxOldestPendingAge() {
        Long oldestEpochSeconds = outboxRepository.getOldestPendingEpochSeconds();
        if (oldestEpochSeconds != null) {
            outboxOldestPendingEpochSeconds.set(oldestEpochSeconds);
        } else {
            outboxOldestPendingEpochSeconds.set(0);
        }
    }
}
