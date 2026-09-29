package com.dcos.platform.certapi.event;

/**
 * Completion event published by the orchestrator to {@code certificate.lifecycle.completions}.
 * Deserialized with snake_case field mapping from the orchestrator's JSON envelope.
 *
 * <p>The {@code eventId} may be suffixed with retry markers ({@code originalId:retry:N}) on
 * redelivery. The {@code retryCount} is cumulative across the certificate's entire history and is
 * informational only — never interpret it as attempts for the current operation.
 */
public record CompletionEvent(
        String eventId, String certificateId, String status, int retryCount, String error) {}
