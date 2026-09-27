package com.dcos.platform.certapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Request payload for renewing a certificate")
public class RenewalRequest {

    @NotNull(message = "New expiry date must not be null")
    @Future(message = "New expiry date must be in the future")
    @Schema(
            description =
                    "Certificate expiration timestamp (must be strictly later than current expiry)",
            example = "2027-01-01T00:00:00Z")
    private Instant expiresAt;

    @Min(value = 1, message = "Renewal window must be at least 1 day")
    @Max(value = 365, message = "Renewal window must not exceed 365 days")
    @Schema(description = "Certificate renewal window in days (1-365, optional)", example = "30")
    private Integer renewalWindowDays;

    @Schema(
            description = "Correlation identifier for tracking (optional)",
            example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID correlationId;

    public RenewalRequest() {}

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Integer getRenewalWindowDays() {
        return renewalWindowDays;
    }

    public void setRenewalWindowDays(Integer renewalWindowDays) {
        this.renewalWindowDays = renewalWindowDays;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(UUID correlationId) {
        this.correlationId = correlationId;
    }
}
