package com.dcos.platform.certapi.service;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.dto.CertificateRequest;
import com.dcos.platform.certapi.dto.CertificateResponse;
import com.dcos.platform.certapi.event.CertificateEventPublisher;
import com.dcos.platform.certapi.exception.CertificateNotFoundException;
import com.dcos.platform.certapi.exception.CertificateStateException;
import com.dcos.platform.certapi.repository.CertificateRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CertificateService {

    private final CertificateRepository repository;
    private final CertificateEventPublisher eventPublisher;

    public CertificateService(CertificateRepository repository,
                              CertificateEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CertificateResponse create(CertificateRequest request) {
        Certificate cert = new Certificate();
        cert.setSubject(request.getSubject());
        cert.setType(request.getType().toUpperCase());
        cert.setStatus(CertificateStatus.ACTIVE);
        cert.setIssuedAt(Instant.now());
        cert.setExpiresAt(request.getExpiresAt());
        cert.setIssuedBy(request.getIssuedBy());

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
        cert.setType(request.getType().toUpperCase());
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
        if (cert.getStatus() == CertificateStatus.REVOKED) {
            throw new CertificateStateException(id, "Certificate is already revoked");
        }
        cert.setStatus(CertificateStatus.REVOKED);

        Certificate saved = repository.save(cert);
        eventPublisher.publishRevoked(saved);
        return CertificateResponse.from(saved);
    }

    private Certificate findOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new CertificateNotFoundException(id));
    }
}
