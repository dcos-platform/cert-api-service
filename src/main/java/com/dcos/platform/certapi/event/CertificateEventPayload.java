package com.dcos.platform.certapi.event;

import com.dcos.platform.certapi.domain.Certificate;
import java.util.UUID;

/**
 * Payload of a certificate lifecycle event, sent to downstream services. Contains service-specific
 * detail about the certificate and the event itself. The source identifies the service that emitted
 * it; schema_version allows consumers to detect breaking changes in the payload structure.
 */
public record CertificateEventPayload(
        String source,
        int schemaVersion,
        UUID certificateId,
        String subject,
        String type,
        String status) {

    public static final int SCHEMA_VERSION = 1;
    public static final String SOURCE = "cert-api-service";

    public static CertificateEventPayload from(Certificate cert) {
        return new CertificateEventPayload(
                SOURCE,
                SCHEMA_VERSION,
                cert.getId(),
                cert.getSubject(),
                cert.getType().name(),
                cert.getStatus().name());
    }
}
