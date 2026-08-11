package com.dcos.platform.certapi.dto;

import com.dcos.platform.certapi.validation.ValidCertificateType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

@Schema(description = "Request payload for creating or renewing a certificate")
public class CertificateRequest {

    @NotBlank(message = "Subject must not be blank")
    @Schema(description = "Distinguished name or common name of the certificate subject", example = "CN=example.com")
    private String subject;

    @NotBlank(message = "Type must not be blank")
    @ValidCertificateType
    @Schema(description = "Certificate type", allowableValues = {"TLS", "CLIENT", "CA", "CODE_SIGNING"}, example = "TLS")
    private String type;

    @NotNull(message = "Expiry date must not be null")
    @Future(message = "Expiry date must be in the future")
    @Schema(description = "Certificate expiration timestamp (must be future)", example = "2027-01-01T00:00:00Z")
    private Instant expiresAt;

    @NotBlank(message = "IssuedBy must not be blank")
    @Schema(description = "Name of the issuing authority", example = "Internal CA")
    private String issuedBy;

    public CertificateRequest() {
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getIssuedBy() {
        return issuedBy;
    }

    public void setIssuedBy(String issuedBy) {
        this.issuedBy = issuedBy;
    }
}
