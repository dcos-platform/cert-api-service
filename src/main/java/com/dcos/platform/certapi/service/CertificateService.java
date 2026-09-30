package com.dcos.platform.certapi.service;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.domain.CertificateType;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import com.dcos.platform.certapi.dto.CertificateCreateRequest;
import com.dcos.platform.certapi.dto.CertificateResponse;
import com.dcos.platform.certapi.dto.OutboxEventResponse;
import com.dcos.platform.certapi.dto.PageResponse;
import com.dcos.platform.certapi.dto.RenewalRequest;
import com.dcos.platform.certapi.dto.RevocationRequest;
import com.dcos.platform.certapi.event.EventType;
import com.dcos.platform.certapi.event.OutboxEnqueueService;
import com.dcos.platform.certapi.exception.CertificateNotFoundException;
import com.dcos.platform.certapi.exception.CertificateStateException;
import com.dcos.platform.certapi.exception.InvalidRenewalException;
import com.dcos.platform.certapi.logging.LoggingContext;
import com.dcos.platform.certapi.repository.CertificateRepository;
import com.dcos.platform.certapi.repository.CertificateSpecifications;
import com.dcos.platform.certapi.repository.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates certificate operations: creation, retrieval, renewal, and revocation. Handles state
 * validation, serial number generation, principal capture, and event publication.
 */
@Service
public class CertificateService {

    private static final int SERIAL_GENERATION_MAX_RETRIES = 10;

    private final CertificateRepository repository;
    private final OutboxEnqueueService outboxEnqueueService;
    private final OutboxRepository outboxRepository;
    private final SerialNumberGenerator serialGenerator;

    public CertificateService(
            CertificateRepository repository,
            OutboxEnqueueService outboxEnqueueService,
            OutboxRepository outboxRepository,
            SerialNumberGenerator serialGenerator) {
        this.repository = repository;
        this.outboxEnqueueService = outboxEnqueueService;
        this.outboxRepository = outboxRepository;
        this.serialGenerator = serialGenerator;
    }

    /**
     * Creates a new certificate. The service generates a serial number, derives a common name from
     * the subject if one is not explicitly provided, records the requesting principal, and persists
     * the certificate with ACTIVE status and PENDING orchestration status.
     *
     * @param request creation parameters
     * @param principalName the authenticated principal requesting the certificate (e.g., username)
     * @return the persisted certificate
     * @throws DataIntegrityViolationException if a collision occurs (handled by exception handler)
     * @throws IllegalArgumentException if common name cannot be determined
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CertificateResponse create(CertificateCreateRequest request, String principalName) {
        String commonName = CommonNameExtractor.extract(request.commonName(), request.subject());

        Certificate cert = new Certificate();
        cert.setSubject(request.subject());
        cert.setCommonName(commonName);
        cert.setType(CertificateType.valueOf(request.type().toUpperCase()));
        cert.setStatus(CertificateStatus.ACTIVE);
        cert.setIssuedAt(Instant.now());
        cert.setExpiresAt(request.expiresAt());
        cert.setIssuedBy(request.issuedBy());
        cert.setRequestedBy(principalName);
        cert.setRenewalWindowDays(
                request.renewalWindowDays() != null ? request.renewalWindowDays() : 30);
        if (request.correlationId() != null) {
            cert.setCorrelationId(request.correlationId());
        }

        // Generate serial number with collision retry
        cert.setSerialNumber(generateSerialWithRetry());

        Certificate saved = repository.save(cert);
        try (MDC.MDCCloseable ignored =
                        MDC.putCloseable(LoggingContext.CERTIFICATE_ID, saved.getId().toString());
                MDC.MDCCloseable ignored2 =
                        MDC.putCloseable(LoggingContext.PRINCIPAL, principalName)) {
            outboxEnqueueService.enqueue(saved, EventType.CREATED);
        }
        return CertificateResponse.from(saved);
    }

    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Transactional(readOnly = true)
    public CertificateResponse getById(UUID id) {
        return CertificateResponse.from(findOrThrow(id));
    }

    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<CertificateResponse> getAll() {
        return repository.findAll().stream()
                .map(CertificateResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * Retrieves certificates with filtering, paging, and sorting.
     *
     * @param status filter by certificate status, or null
     * @param type filter by certificate type, or null
     * @param commonName filter by common name (case-insensitive substring), or null
     * @param issuedBy filter by issuing authority, or null
     * @param orchestrationStatus filter by orchestration status, or null
     * @param expiringBefore filter by expiry instant, or null
     * @param page page number (0-indexed)
     * @param size page size
     * @param sort Spring Sort object for ordering
     * @return a PageResponse containing the filtered certificates
     */
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Transactional(readOnly = true)
    public PageResponse<CertificateResponse> search(
            CertificateStatus status,
            CertificateType type,
            String commonName,
            String issuedBy,
            OrchestrationStatus orchestrationStatus,
            Instant expiringBefore,
            int page,
            int size,
            Sort sort) {
        Specification<Certificate> spec =
                Specification.where(CertificateSpecifications.hasStatus(status))
                        .and(CertificateSpecifications.hasType(type))
                        .and(CertificateSpecifications.hasCommonNameContaining(commonName))
                        .and(CertificateSpecifications.hasIssuedBy(issuedBy))
                        .and(CertificateSpecifications.hasOrchestrationStatus(orchestrationStatus))
                        .and(CertificateSpecifications.expiringBefore(expiringBefore));

        PageRequest pageRequest = PageRequest.of(page, size, sort);
        Page<Certificate> result = repository.findAll(spec, pageRequest);

        List<CertificateResponse> content =
                result.getContent().stream()
                        .map(CertificateResponse::from)
                        .collect(Collectors.toList());

        return new PageResponse<>(
                content,
                page,
                size,
                result.getTotalElements(),
                result.getTotalPages(),
                result.isFirst(),
                result.isLast());
    }

    /**
     * Retrieves active certificates expiring within a given number of days.
     *
     * @param withinDays number of days to look ahead (1-365)
     * @param page page number (0-indexed)
     * @param size page size
     * @return a PageResponse containing the expiring certificates
     */
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Transactional(readOnly = true)
    public PageResponse<CertificateResponse> findExpiring(int withinDays, int page, int size) {
        Instant expiringBefore = Instant.now().plusSeconds((long) withinDays * 86400);

        Specification<Certificate> spec =
                Specification.where(CertificateSpecifications.hasStatus(CertificateStatus.ACTIVE))
                        .and(CertificateSpecifications.expiringBefore(expiringBefore));

        Sort sort = Sort.by(Sort.Order.asc("expiresAt"));
        PageRequest pageRequest = PageRequest.of(page, size, sort);
        Page<Certificate> result = repository.findAll(spec, pageRequest);

        List<CertificateResponse> content =
                result.getContent().stream()
                        .map(CertificateResponse::from)
                        .collect(Collectors.toList());

        return new PageResponse<>(
                content,
                page,
                size,
                result.getTotalElements(),
                result.getTotalPages(),
                result.isFirst(),
                result.isLast());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CertificateResponse renew(UUID id, RenewalRequest request) {
        Certificate cert = findOrThrow(id);
        try {
            CertificateStateMachine.validateRenewTransition(cert.getStatus());
        } catch (IllegalStateException e) {
            throw new CertificateStateException(id, e.getMessage());
        }

        if (!request.getExpiresAt().isAfter(cert.getExpiresAt())) {
            throw new InvalidRenewalException(
                    "New expiry must be strictly later than current expiry");
        }

        cert.setIssuedAt(Instant.now());
        cert.setExpiresAt(request.getExpiresAt());
        if (request.getRenewalWindowDays() != null) {
            cert.setRenewalWindowDays(request.getRenewalWindowDays());
        }
        if (request.getCorrelationId() != null) {
            cert.setCorrelationId(request.getCorrelationId());
        }
        cert.setStatus(CertificateStatus.ACTIVE);
        cert.setOrchestrationStatus(OrchestrationStatus.PENDING);
        cert.setRenewalCount(cert.getRenewalCount() + 1);
        cert.setLastError(null);

        Certificate saved = repository.save(cert);
        outboxEnqueueService.enqueue(saved, EventType.RENEWED);
        return CertificateResponse.from(saved);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CertificateResponse revoke(UUID id, RevocationRequest request) {
        Certificate cert = findOrThrow(id);
        try {
            CertificateStateMachine.validateRevokeTransition(cert.getStatus());
        } catch (IllegalStateException e) {
            throw new CertificateStateException(id, e.getMessage());
        }

        cert.setStatus(CertificateStatus.REVOKED);
        cert.setRevokedAt(Instant.now());
        cert.setRevocationReason(request.getReason());
        cert.setRevocationComment(request.getComment());

        Certificate saved = repository.save(cert);
        outboxEnqueueService.enqueue(saved, EventType.REVOKED);
        return CertificateResponse.from(saved);
    }

    /**
     * Retrieves the event history for a certificate, paged and ordered by creation time descending
     * (most recent first).
     *
     * @param id the certificate id
     * @param page zero-indexed page number
     * @param size page size
     * @return a PageResponse containing the event history
     * @throws CertificateNotFoundException if the certificate does not exist
     */
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Transactional(readOnly = true)
    public PageResponse<OutboxEventResponse> getEventHistory(UUID id, int page, int size) {
        findOrThrow(id);

        PageRequest pageRequest = PageRequest.of(page, size);
        var result = outboxRepository.findByAggregateIdOrderByCreatedAtDesc(id, pageRequest);

        List<OutboxEventResponse> content =
                result.getContent().stream()
                        .map(
                                row ->
                                        new OutboxEventResponse(
                                                row.getEventId(),
                                                row.getEventType(),
                                                row.getState().name(),
                                                row.getAttemptCount(),
                                                row.getCreatedAt(),
                                                row.getSentAt()))
                        .collect(Collectors.toList());

        return new PageResponse<>(
                content,
                page,
                size,
                result.getTotalElements(),
                result.getTotalPages(),
                result.isFirst(),
                result.isLast());
    }

    private Certificate findOrThrow(UUID id) {
        return repository.findById(id).orElseThrow(() -> new CertificateNotFoundException(id));
    }

    /**
     * Generates a serial number with collision retry. On a uniqueness constraint violation, retries
     * with a fresh random value. Fails if the retry limit is exceeded.
     *
     * @return a generated serial number
     * @throws IllegalStateException if retries are exhausted
     */
    private String generateSerialWithRetry() {
        for (int attempt = 0; attempt < SERIAL_GENERATION_MAX_RETRIES; attempt++) {
            String serialNumber = serialGenerator.generate();
            // Check if it exists
            if (!repository.existsBySerialNumber(serialNumber)) {
                return serialNumber;
            }
        }
        throw new IllegalStateException(
                "Unable to generate unique serial number after "
                        + SERIAL_GENERATION_MAX_RETRIES
                        + " attempts");
    }
}
