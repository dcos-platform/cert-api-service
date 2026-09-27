package com.dcos.platform.certapi.dto;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import com.dcos.platform.certapi.domain.RevocationReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Certificate metadata response as a record. Carries all certificate state including the derived
 * daysUntilExpiry field computed at mapping time.
 */
@Schema(description = "Certificate metadata response")
public record CertificateResponse(
        @Schema(description = "Unique certificate identifier") UUID id,
        @Schema(description = "Certificate serial number") String serialNumber,
        @Schema(description = "Certificate subject") String subject,
        @Schema(description = "Certificate common name") String commonName,
        @Schema(description = "Certificate type") String type,
        @Schema(description = "Current lifecycle status") CertificateStatus status,
        @Schema(description = "Orchestration status") OrchestrationStatus orchestrationStatus,
        @Schema(description = "Name of the issuing authority") String issuedBy,
        @Schema(description = "Timestamp when the certificate was issued") Instant issuedAt,
        @Schema(description = "Timestamp when the certificate expires") Instant expiresAt,
        @Schema(description = "Days before certificate becomes eligible for renewal")
                int renewalWindowDays,
        @Schema(
                        description =
                                "Days remaining until expiry; may be negative for expired certificates")
                long daysUntilExpiry,
        @Schema(description = "Timestamp when the certificate was revoked") Instant revokedAt,
        @Schema(description = "Reason for revocation") RevocationReason revocationReason,
        @Schema(description = "Additional revocation comment") String revocationComment,
        @Schema(description = "Principal who requested this certificate") String requestedBy,
        @Schema(description = "Correlation ID for cross-service tracing") UUID correlationId,
        @Schema(description = "Count of renewal operations") int renewalCount,
        @Schema(description = "Last error from orchestration") String lastError,
        @Schema(description = "Timestamp when the certificate was created") Instant createdAt,
        @Schema(description = "Timestamp when the certificate was last updated")
                Instant updatedAt) {

    public static CertificateResponse from(Certificate cert) {
        long daysUntilExpiry = ChronoUnit.DAYS.between(Instant.now(), cert.getExpiresAt());
        return new CertificateResponse(
                cert.getId(),
                cert.getSerialNumber(),
                cert.getSubject(),
                cert.getCommonName(),
                cert.getType().name(),
                cert.getStatus(),
                cert.getOrchestrationStatus(),
                cert.getIssuedBy(),
                cert.getIssuedAt(),
                cert.getExpiresAt(),
                cert.getRenewalWindowDays(),
                daysUntilExpiry,
                cert.getRevokedAt(),
                cert.getRevocationReason(),
                cert.getRevocationComment(),
                cert.getRequestedBy(),
                cert.getCorrelationId(),
                cert.getRenewalCount(),
                cert.getLastError(),
                cert.getCreatedAt(),
                cert.getUpdatedAt());
    }
}
