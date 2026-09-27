package com.dcos.platform.certapi.dto;

import com.dcos.platform.certapi.validation.ValidCertificateType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Request payload for creating a new certificate. Supplies the certificate's metadata; the service
 * populates serial number, common name (if not provided), and requesting principal.
 *
 * @param subject distinguished name or subject of the certificate (required, 1-255 chars)
 * @param commonName common name value (optional; if absent, parsed from subject CN RDN)
 * @param type certificate type (required; one of TLS, CLIENT, CA, CODE_SIGNING)
 * @param issuedBy issuing authority name (required, 1-255 chars)
 * @param expiresAt certificate expiration timestamp (required; must be in the future)
 * @param renewalWindowDays days before expiry when renewal should begin (optional, 1-365, default
 *     30)
 * @param correlationId UUID for cross-service tracing (optional)
 */
@Schema(description = "Request payload for creating a new certificate")
public record CertificateCreateRequest(
        @NotBlank(message = "Subject must not be blank")
                @Size(max = 255, message = "Subject must not exceed 255 characters")
                @Schema(
                        description = "Distinguished name or subject of the certificate",
                        example = "CN=service-alpha,OU=platform,O=DCOS")
                String subject,
        @Size(max = 255, message = "Common name must not exceed 255 characters")
                @Schema(
                        description =
                                "Certificate common name (optional; parsed from subject if absent)",
                        example = "service-alpha")
                String commonName,
        @NotBlank(message = "Type must not be blank")
                @ValidCertificateType
                @Schema(
                        description = "Certificate type",
                        allowableValues = {"TLS", "CLIENT", "CA", "CODE_SIGNING"},
                        example = "TLS")
                String type,
        @NotBlank(message = "IssuedBy must not be blank")
                @Size(max = 255, message = "IssuedBy must not exceed 255 characters")
                @Schema(
                        description = "Name of the issuing authority",
                        example = "DCOS Demo Authority")
                String issuedBy,
        @NotNull(message = "Expiry date must not be null")
                @Future(message = "Expiry date must be in the future")
                @Schema(
                        description = "Certificate expiration timestamp (must be future)",
                        example = "2027-01-01T00:00:00Z")
                Instant expiresAt,
        @Min(value = 1, message = "Renewal window must be at least 1 day")
                @Max(value = 365, message = "Renewal window must not exceed 365 days")
                @Schema(
                        description =
                                "Days before expiry when renewal should begin (optional, 1-365, default 30)",
                        example = "30")
                Integer renewalWindowDays,
        @Schema(
                        description = "UUID for cross-service tracing (optional)",
                        example = "0f6f0a1e-1f2a-4c3b-9d4e-5a6b7c8d9e0f")
                UUID correlationId) {}
