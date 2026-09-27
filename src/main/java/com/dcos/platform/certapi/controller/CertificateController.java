package com.dcos.platform.certapi.controller;

import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.domain.CertificateType;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import com.dcos.platform.certapi.dto.CertificateCreateRequest;
import com.dcos.platform.certapi.dto.CertificateResponse;
import com.dcos.platform.certapi.dto.OutboxEventResponse;
import com.dcos.platform.certapi.dto.PageResponse;
import com.dcos.platform.certapi.dto.RenewalRequest;
import com.dcos.platform.certapi.dto.RevocationRequest;
import com.dcos.platform.certapi.service.CertificateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for certificate lifecycle management. Exposes endpoints for creating, retrieving,
 * renewing, and revoking certificates.
 */
@RestController
@RequestMapping("/api/v1/certificates")
@Tag(name = "Certificates", description = "Certificate lifecycle management endpoints")
@Validated
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
        return ResponseEntity.created(URI.create("/api/v1/certificates/" + created.id()))
                .body(created);
    }

    /**
     * Lists certificates with optional filtering by status, type, common name, issuer,
     * orchestration status, and expiry date. Results are paginated and sorted according to
     * parameters.
     *
     * @param status filter by certificate status (optional)
     * @param type filter by certificate type (optional)
     * @param commonName filter by common name substring, case-insensitive (optional)
     * @param issuedBy filter by issuing authority (optional)
     * @param orchestrationStatus filter by orchestration status (optional)
     * @param expiringBefore filter certificates expiring before this instant (optional)
     * @param page zero-indexed page number
     * @param size page size (1-100)
     * @param sort sort order specification
     * @return paginated and filtered certificate list
     */
    @Operation(summary = "List certificates with filtering, paging, and sorting")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Paginated list of certificates",
                content = @Content(schema = @Schema(implementation = PageResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid page parameters or filters"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    })
    @GetMapping
    public ResponseEntity<PageResponse<CertificateResponse>> search(
            @Parameter(description = "Filter by certificate status") @RequestParam(required = false)
                    CertificateStatus status,
            @Parameter(description = "Filter by certificate type") @RequestParam(required = false)
                    CertificateType type,
            @Parameter(description = "Filter by common name (case-insensitive substring)")
                    @RequestParam(required = false)
                    String commonName,
            @Parameter(description = "Filter by issuing authority") @RequestParam(required = false)
                    String issuedBy,
            @Parameter(description = "Filter by orchestration status")
                    @RequestParam(required = false)
                    OrchestrationStatus orchestrationStatus,
            @Parameter(description = "Filter by expiry date (ISO-8601 instant)")
                    @RequestParam(required = false)
                    Instant expiringBefore,
            @Parameter(description = "Page number (0-indexed)")
                    @RequestParam(defaultValue = "0")
                    @Min(0)
                    int page,
            @Parameter(description = "Page size (1-100)")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int size,
            @Parameter(description = "Sort order (e.g., 'createdAt,desc')")
                    @RequestParam(defaultValue = "createdAt,desc")
                    String sort) {
        Sort sortOrder = parseSort(sort);
        PageResponse<CertificateResponse> result =
                service.search(
                        status,
                        type,
                        commonName,
                        issuedBy,
                        orchestrationStatus,
                        expiringBefore,
                        page,
                        size,
                        sortOrder);
        return ResponseEntity.ok(result);
    }

    /**
     * Lists certificates expiring within a specified number of days. Results are paginated.
     *
     * @param withinDays number of days to look ahead (1-365, default 30)
     * @param page zero-indexed page number
     * @param size page size (1-100)
     * @return paginated list of certificates expiring within the window
     */
    @Operation(summary = "List certificates expiring within a number of days")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Paginated list of expiring certificates",
                content = @Content(schema = @Schema(implementation = PageResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid day range or page parameters"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    })
    @GetMapping("/expiring")
    public ResponseEntity<PageResponse<CertificateResponse>> expiring(
            @Parameter(description = "Days until expiry (1-365)")
                    @RequestParam(defaultValue = "30")
                    @Min(1)
                    @Max(365)
                    int withinDays,
            @Parameter(description = "Page number (0-indexed)")
                    @RequestParam(defaultValue = "0")
                    @Min(0)
                    int page,
            @Parameter(description = "Page size (1-100)")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int size) {
        PageResponse<CertificateResponse> result = service.findExpiring(withinDays, page, size);
        return ResponseEntity.ok(result);
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
            @Valid @RequestBody RenewalRequest request) {
        return ResponseEntity.ok(service.renew(id, request));
    }

    @Operation(summary = "Revoke a certificate")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Certificate revoked"),
        @ApiResponse(responseCode = "400", description = "Invalid request payload"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @ApiResponse(responseCode = "404", description = "Certificate not found"),
        @ApiResponse(responseCode = "409", description = "Certificate is already revoked")
    })
    @PostMapping("/{id}/revoke")
    public ResponseEntity<CertificateResponse> revoke(
            @Parameter(description = "Certificate UUID") @PathVariable UUID id,
            @Valid @RequestBody RevocationRequest request) {
        return ResponseEntity.ok(service.revoke(id, request));
    }

    @Operation(summary = "Get event history for a certificate")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Paginated list of events",
                content = @Content(schema = @Schema(implementation = PageResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @ApiResponse(responseCode = "404", description = "Certificate not found")
    })
    @GetMapping("/{id}/events")
    public ResponseEntity<PageResponse<OutboxEventResponse>> getEventHistory(
            @Parameter(description = "Certificate UUID") @PathVariable UUID id,
            @Parameter(description = "Page number (0-indexed)")
                    @RequestParam(defaultValue = "0")
                    @Min(0)
                    int page,
            @Parameter(description = "Page size (1-100)")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int size) {
        PageResponse<OutboxEventResponse> result = service.getEventHistory(id, page, size);
        return ResponseEntity.ok(result);
    }

    private String getPrincipalName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }

    private Sort parseSort(String sortParam) {
        String[] parts = sortParam.split(",");
        String field = parts[0].trim();
        String direction = parts.length > 1 ? parts[1].trim() : "asc";

        SortableField sortableField = SortableField.fromFieldName(field);

        Sort.Direction sortDirection =
                "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(new Sort.Order(sortDirection, sortableField.fieldName()));
    }
}
