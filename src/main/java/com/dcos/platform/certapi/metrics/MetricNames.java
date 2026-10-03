package com.dcos.platform.certapi.metrics;

/** Shared constants for metric names and tags, used by multiple production classes. */
final class MetricNames {

    // Metric names (Micrometer dotted notation)
    static final String CERT_CERTIFICATES_COUNT = "cert.certificates.count";
    static final String CERT_CERTIFICATES_ORCHESTRATION_COUNT =
            "cert.certificates.orchestration.count";
    static final String CERT_CERTIFICATES_RENEWAL_WINDOW_COUNT =
            "cert.certificates.renewal.window.count";
    static final String CERT_OUTBOX_PENDING_COUNT = "cert.outbox.pending.count";
    static final String CERT_OUTBOX_FAILED_COUNT = "cert.outbox.failed.count";
    static final String CERT_OUTBOX_OLDEST_PENDING_AGE = "cert.outbox.oldest.pending.age";
    static final String CERT_SWEEP_TRANSITIONS = "cert.sweep.transitions";
    static final String CERT_COMPLETIONS_DUPLICATES_SUPPRESSED =
            "cert.completions.duplicates.suppressed";

    // Tag keys
    static final String TAG_STATUS = "status";
    static final String TAG_TYPE = "type";
    static final String TAG_ORCHESTRATION_STATUS = "orchestration.status";
    static final String TAG_SWEEP = "sweep";

    private MetricNames() {
        // Prevent instantiation
    }
}
