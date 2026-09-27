package com.dcos.platform.certapi.service;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.domain.CertificateType;
import com.dcos.platform.certapi.dto.CertificateCreateRequest;
import com.dcos.platform.certapi.dto.CertificateRequest;
import com.dcos.platform.certapi.dto.CertificateResponse;
import com.dcos.platform.certapi.event.CertificateEventPublisher;
import com.dcos.platform.certapi.exception.CertificateNotFoundException;
import com.dcos.platform.certapi.exception.CertificateStateException;
import com.dcos.platform.certapi.repository.CertificateRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final CertificateEventPublisher eventPublisher;
    private final SerialNumberGenerator serialGenerator;

    public CertificateService(
            CertificateRepository repository,
            CertificateEventPublisher eventPublisher,
            SerialNumberGenerator serialGenerator) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
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
        eventPublisher.publishCreated(saved);
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

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CertificateResponse renew(UUID id, CertificateRequest request) {
        Certificate cert = findOrThrow(id);
        if (cert.getStatus() == CertificateStatus.REVOKED) {
            throw new CertificateStateException(id, "Cannot renew a revoked certificate");
        }
        cert.setSubject(request.getSubject());
        cert.setType(CertificateType.valueOf(request.getType().toUpperCase()));
        cert.setIssuedAt(Instant.now());
        cert.setExpiresAt(request.getExpiresAt());
        cert.setIssuedBy(request.getIssuedBy());
        cert.setStatus(CertificateStatus.ACTIVE);

        Certificate saved = repository.save(cert);
        eventPublisher.publishRenewed(saved);
        return CertificateResponse.from(saved);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CertificateResponse revoke(UUID id) {
        Certificate cert = findOrThrow(id);
        try {
            CertificateStateMachine.validateRevokeTransition(cert.getStatus());
        } catch (IllegalStateException e) {
            throw new CertificateStateException(id, e.getMessage());
        }
        cert.setStatus(CertificateStatus.REVOKED);

        Certificate saved = repository.save(cert);
        eventPublisher.publishRevoked(saved);
        return CertificateResponse.from(saved);
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
