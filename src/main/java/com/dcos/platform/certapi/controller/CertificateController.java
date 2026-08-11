package com.dcos.platform.certapi.controller;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/certificates")
@Tag(name = "Certificates", description = "Certificate lifecycle management endpoints")
public class CertificateController {

    private final CertificateService service;

    public CertificateController(CertificateService service) {
        this.service = service;
    }

    @Operation(summary = "Create a new certificate")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Certificate created",
            content = @Content(schema = @Schema(implementation = CertificateResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request payload"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    })
    @PostMapping
    public ResponseEntity<CertificateResponse> create(@Valid @RequestBody CertificateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @Operation(summary = "Retrieve all certificates")
    @ApiResponse(responseCode = "200", description = "List of certificates")
    @GetMapping
    public ResponseEntity<List<CertificateResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @Operation(summary = "Retrieve a certificate by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Certificate found",
            content = @Content(schema = @Schema(implementation = CertificateResponse.class))),
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
        @ApiResponse(responseCode = "404", description = "Certificate not found"),
        @ApiResponse(responseCode = "409", description = "Certificate cannot be renewed in current state")
    })
    @PutMapping("/{id}/renew")
    public ResponseEntity<CertificateResponse> renew(
            @Parameter(description = "Certificate UUID") @PathVariable UUID id,
            @Valid @RequestBody CertificateRequest request) {
        return ResponseEntity.ok(service.renew(id, request));
    }

    @Operation(summary = "Revoke a certificate")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Certificate revoked"),
        @ApiResponse(responseCode = "404", description = "Certificate not found"),
        @ApiResponse(responseCode = "409", description = "Certificate is already revoked")
    })
    @PatchMapping("/{id}/revoke")
    public ResponseEntity<CertificateResponse> revoke(
            @Parameter(description = "Certificate UUID") @PathVariable UUID id) {
        return ResponseEntity.ok(service.revoke(id));
    }
}
