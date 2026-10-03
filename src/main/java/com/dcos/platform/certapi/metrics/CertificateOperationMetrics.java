package com.dcos.platform.certapi.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Metrics for certificate lifecycle operations: sweeps and completion events. */
@Component
public class CertificateOperationMetrics {

    private final Map<SweepKind, Counter> sweepCounters;
    private final Counter duplicateCompletionsCounter;

    /**
     * Creates operation metrics with pre-registered counters for all sweep kinds and duplicate
     * completions.
     *
     * @param meterRegistry Micrometer registry for metric registration
     */
    public CertificateOperationMetrics(MeterRegistry meterRegistry) {
        this.sweepCounters = new EnumMap<>(SweepKind.class);

        // Pre-register counters for each sweep kind
        for (SweepKind kind : SweepKind.values()) {
            this.sweepCounters.put(
                    kind,
                    Counter.builder(MetricNames.CERT_SWEEP_TRANSITIONS)
                            .tag(MetricNames.TAG_SWEEP, kind.tagValue())
                            .register(meterRegistry));
        }

        // Pre-register duplicate completions counter
        this.duplicateCompletionsCounter =
                Counter.builder(MetricNames.CERT_COMPLETIONS_DUPLICATES_SUPPRESSED)
                        .register(meterRegistry);
    }

    /**
     * Records sweep transitions for a given sweep kind.
     *
     * @param kind the type of sweep (EXPIRY or ORCHESTRATION_TIMEOUT)
     * @param transitioned the number of certificates transitioned in this pass
     */
    public void recordSweepTransitions(SweepKind kind, long transitioned) {
        sweepCounters.get(kind).increment(transitioned);
    }

    /** Records that a duplicate completion was suppressed. */
    public void recordDuplicateCompletionSuppressed() {
        duplicateCompletionsCounter.increment();
    }
}
