package com.dcos.platform.certapi.dto;

import com.dcos.platform.certapi.domain.RevocationReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for revoking a certificate")
public class RevocationRequest {

    @NotNull(message = "Revocation reason must not be null")
    @Schema(description = "Reason for revocation", example = "KEY_COMPROMISE")
    private RevocationReason reason;

    @Size(max = 512, message = "Revocation comment must not exceed 512 characters")
    @Schema(
            description = "Optional comment explaining the revocation (max 512 characters)",
            example = "Key was compromised during a security incident")
    private String comment;

    public RevocationRequest() {}

    public RevocationReason getReason() {
        return reason;
    }

    public void setReason(RevocationReason reason) {
        this.reason = reason;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
