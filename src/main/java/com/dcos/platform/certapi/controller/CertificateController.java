package com.dcos.platform.certapi.controller;

import com.dcos.platform.certapi.dto.CertificateCreateRequest;
import com.dcos.platform.certapi.dto.CertificateRequest;
import com.dcos.platform.certapi.dto.CertificateResponse;
import com.dcos.platform.certapi.service.CertificateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for certificate lifecycle management. Exposes endpoints for creating, retrieving,
 * renewing, and revoking certificates.
 */
@RestController
@RequestMapping("/api/v1/certificates")
@Tag(name = "Certificates", description = "Certificate lifecycle management endpoints")
public class CertificateController {

    private final CertificateService service;

    public CertificateController(CertificateService service) {
        this.service = service;
    }

    /**
     * Creates a new certificate.
     *
     * @param request creation parameters
     * @return 201 Created with Location header and certificate body
     */
    @Operation(summary = "Create a new certificate")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Certificate created",
                content = @Content(schema = @Schema(implementation = CertificateResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request payload"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @ApiResponse(responseCode = "409", description = "Duplicate active certificate")
    })
    @PostMapping
    public ResponseEntity<CertificateResponse> create(
            @Valid @RequestBody CertificateCreateRequest request) {
        String principalName = getPrincipalName();
        CertificateResponse created = service.create(request, principalName);
        return ResponseEntity.created(URI.create("/api/v1/certificates/" + created.getId()))
                .body(created);
    }

    @Operation(summary = "Retrieve all certificates")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of certificates"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    })
    @GetMapping
    public ResponseEntity<List<CertificateResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @Operation(summary = "Retrieve a certificate by ID")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Certificate found",
                content = @Content(schema = @Schema(implementation = CertificateResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @ApiResponse(responseCode = "404", description = "Certificate not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<CertificateResponse> getById(
            @Parameter(description = "Certificate UUID") @PathVariable UUID id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @Operation(summary = "Renew a certificate")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Certificate renewed"),
        @ApiResponse(responseCode = "400", description = "Invalid request payload"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @ApiResponse(responseCode = "404", description = "Certificate not found"),
        @ApiResponse(
                responseCode = "409",
                description = "Certificate cannot be renewed in current state")
    })
    @PostMapping("/{id}/renew")
    public ResponseEntity<CertificateResponse> renew(
            @Parameter(description = "Certificate UUID") @PathVariable UUID id,
            @Valid @RequestBody CertificateRequest request) {
        return ResponseEntity.ok(service.renew(id, request));
    }

    @Operation(summary = "Revoke a certificate")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Certificate revoked"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @ApiResponse(responseCode = "404", description = "Certificate not found"),
        @ApiResponse(responseCode = "409", description = "Certificate is already revoked")
    })
    @PostMapping("/{id}/revoke")
    public ResponseEntity<CertificateResponse> revoke(
            @Parameter(description = "Certificate UUID") @PathVariable UUID id) {
        return ResponseEntity.ok(service.revoke(id));
    }

    private String getPrincipalName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }
}
