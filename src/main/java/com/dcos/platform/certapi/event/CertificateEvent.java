package com.dcos.platform.certapi.event;

import java.time.Instant;
import java.util.UUID;

public class CertificateEvent {

    private String eventType;
    private UUID certificateId;
    private String subject;
    private String type;
    private String status;
    private Instant occurredAt;

    public CertificateEvent() {}

    public CertificateEvent(
            String eventType, UUID certificateId, String subject, String type, String status) {
        this.eventType = eventType;
        this.certificateId = certificateId;
        this.subject = subject;
        this.type = type;
        this.status = status;
        this.occurredAt = Instant.now();
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getCertificateId() {
        return certificateId;
    }

    public String getSubject() {
        return subject;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
