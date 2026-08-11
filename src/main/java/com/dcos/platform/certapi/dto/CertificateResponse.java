package com.dcos.platform.certapi.dto;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Certificate metadata response")
public class CertificateResponse {

    @Schema(description = "Unique certificate identifier")
    private UUID id;

    @Schema(description = "Certificate subject")
    private String subject;

    @Schema(description = "Certificate type")
    private String type;

    @Schema(description = "Current lifecycle status")
    private CertificateStatus status;

    @Schema(description = "Timestamp when the certificate was issued")
    private Instant issuedAt;

    @Schema(description = "Timestamp when the certificate expires")
    private Instant expiresAt;

    @Schema(description = "Name of the issuing authority")
    private String issuedBy;

    public CertificateResponse() {
    }

    public static CertificateResponse from(Certificate cert) {
        CertificateResponse r = new CertificateResponse();
        r.id = cert.getId();
        r.subject = cert.getSubject();
        r.type = cert.getType();
        r.status = cert.getStatus();
        r.issuedAt = cert.getIssuedAt();
        r.expiresAt = cert.getExpiresAt();
        r.issuedBy = cert.getIssuedBy();
        return r;
    }

    public UUID getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public String getType() {
        return type;
    }

    public CertificateStatus getStatus() {
        return status;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getIssuedBy() {
        return issuedBy;
    }
}
