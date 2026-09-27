package com.dcos.platform.certapi.event;

import java.time.Instant;

/**
 * Envelope of a certificate lifecycle event, sent on the message wire. Holds the event id,
 * certificate id, occurred_at timestamp, and the payload object. This envelope is validated by
 * downstream consumers (e.g., the Python orchestrator) against their schema, so the structure and
 * field names are non-negotiable and must match their Pydantic model.
 */
public record CertificateEventEnvelope(
        String eventId,
        String certificateId,
        Instant occurredAt,
        CertificateEventPayload payload) {}
