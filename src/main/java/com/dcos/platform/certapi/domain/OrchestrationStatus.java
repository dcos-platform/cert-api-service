package com.dcos.platform.certapi.domain;

/**
 * Progress of the orchestrator's asynchronous processing of a certificate, tracked separately from
 * the certificate's own {@link CertificateStatus}.
 *
 * <p>{@code PROCESSING} mirrors the orchestrator's internal lifecycle state. The orchestrator only
 * ever publishes {@code COMPLETED} or {@code FAILED}, so this service does not currently assign it.
 */
public enum OrchestrationStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
