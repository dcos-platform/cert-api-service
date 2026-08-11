package com.dcos.platform.certapi.service;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.dto.CertificateRequest;
import com.dcos.platform.certapi.dto.CertificateResponse;
import com.dcos.platform.certapi.event.CertificateEventPublisher;
import com.dcos.platform.certapi.exception.CertificateNotFoundException;
import com.dcos.platform.certapi.exception.CertificateStateException;
import com.dcos.platform.certapi.repository.CertificateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CertificateServiceTest {

    @Mock
    private CertificateRepository repository;

    @Mock
    private CertificateEventPublisher eventPublisher;

    @InjectMocks
    private CertificateService service;

    private CertificateRequest request;
    private Certificate savedCert;

    @BeforeEach
    void setUp() {
        request = new CertificateRequest();
        request.setSubject("CN=test.example.com");
        request.setType("TLS");
        request.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        request.setIssuedBy("Internal CA");

        savedCert = new Certificate();
        savedCert.setId(UUID.randomUUID());
        savedCert.setSubject(request.getSubject());
        savedCert.setType("TLS");
        savedCert.setStatus(CertificateStatus.ACTIVE);
        savedCert.setIssuedAt(Instant.now());
        savedCert.setExpiresAt(request.getExpiresAt());
        savedCert.setIssuedBy(request.getIssuedBy());
    }

    @Test
    void create_shouldPersistAndPublishEvent() {
        when(repository.save(any(Certificate.class))).thenReturn(savedCert);

        CertificateResponse response = service.create(request);

        assertThat(response.getId()).isEqualTo(savedCert.getId());
        assertThat(response.getStatus()).isEqualTo(CertificateStatus.ACTIVE);
        verify(eventPublisher).publishCreated(savedCert);
    }

    @Test
    void getById_shouldReturnResponse_whenFound() {
        when(repository.findById(savedCert.getId())).thenReturn(Optional.of(savedCert));

        CertificateResponse response = service.getById(savedCert.getId());

        assertThat(response.getId()).isEqualTo(savedCert.getId());
        assertThat(response.getSubject()).isEqualTo(savedCert.getSubject());
    }

    @Test
    void getById_shouldThrow_whenNotFound() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(unknown))
                .isInstanceOf(CertificateNotFoundException.class);
    }

    @Test
    void getAll_shouldReturnAllCertificates() {
        when(repository.findAll()).thenReturn(List.of(savedCert));

        List<CertificateResponse> responses = service.getAll();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getId()).isEqualTo(savedCert.getId());
    }

    @Test
    void renew_shouldUpdateAndPublishEvent() {
        when(repository.findById(savedCert.getId())).thenReturn(Optional.of(savedCert));
        when(repository.save(any(Certificate.class))).thenReturn(savedCert);

        CertificateResponse response = service.renew(savedCert.getId(), request);

        assertThat(response.getStatus()).isEqualTo(CertificateStatus.ACTIVE);
        verify(eventPublisher).publishRenewed(savedCert);
    }

    @Test
    void renew_shouldThrow_whenCertificateIsRevoked() {
        savedCert.setStatus(CertificateStatus.REVOKED);
        when(repository.findById(savedCert.getId())).thenReturn(Optional.of(savedCert));

        assertThatThrownBy(() -> service.renew(savedCert.getId(), request))
                .isInstanceOf(CertificateStateException.class)
                .hasMessageContaining("Cannot renew a revoked certificate");
    }

    @Test
    void revoke_shouldSetStatusAndPublishEvent() {
        when(repository.findById(savedCert.getId())).thenReturn(Optional.of(savedCert));
        when(repository.save(any(Certificate.class))).thenAnswer(inv -> inv.getArgument(0));

        CertificateResponse response = service.revoke(savedCert.getId());

        assertThat(response.getStatus()).isEqualTo(CertificateStatus.REVOKED);
        verify(eventPublisher).publishRevoked(any(Certificate.class));
    }

    @Test
    void revoke_shouldThrow_whenAlreadyRevoked() {
        savedCert.setStatus(CertificateStatus.REVOKED);
        when(repository.findById(savedCert.getId())).thenReturn(Optional.of(savedCert));

        assertThatThrownBy(() -> service.revoke(savedCert.getId()))
                .isInstanceOf(CertificateStateException.class)
                .hasMessageContaining("already revoked");
    }
}
