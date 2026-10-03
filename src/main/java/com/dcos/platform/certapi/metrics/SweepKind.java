package com.dcos.platform.certapi.metrics;

/** Sweep types for lifecycle event counters. */
public enum SweepKind {
    EXPIRY("expiry"),
    ORCHESTRATION_TIMEOUT("orchestration.timeout");

    private final String tagValue;

    SweepKind(String tagValue) {
        this.tagValue = tagValue;
    }

    /** Returns the tag value for this sweep kind. */
    public String tagValue() {
        return tagValue;
    }
}
