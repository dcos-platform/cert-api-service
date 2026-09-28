package com.dcos.platform.certapi.dto;

import java.time.Instant;

/**
 * Response DTO for event history endpoint. Contains event id, type, state, attempts, created and
 * sent timestamps.
 */
public record OutboxEventResponse(
        String eventId,
        String eventType,
        String state,
        int attempts,
        Instant createdAt,
        Instant sentAt) {}
