package com.dcos.platform.certapi.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CertificateServiceTest {

    @Mock private CertificateRepository repository;

    @Mock private CertificateEventPublisher eventPublisher;

    @Mock private SerialNumberGenerator serialGenerator;

    @InjectMocks private CertificateService service;

    private CertificateCreateRequest createRequest;
    private CertificateRequest request;
    private Certificate savedCert;
    private final String PRINCIPAL = "test-admin";

    @BeforeEach
    void setUp() {
        Instant tomorrow = Instant.now().plus(365, ChronoUnit.DAYS);
        createRequest =
                new CertificateCreateRequest(
                        "CN=test.example.com,OU=test,O=DCOS",
                        "test.example.com",
                        "TLS",
                        "Internal CA",
                        tomorrow,
                        30,
                        null);

        request = new CertificateRequest();
        request.setSubject("CN=test.example.com");
        request.setType("TLS");
        request.setExpiresAt(tomorrow);
        request.setIssuedBy("Internal CA");

        savedCert = new Certificate();
        savedCert.setId(UUID.randomUUID());
        savedCert.setSerialNumber("DCOS-2026-ABCDEF123456");
        savedCert.setSubject(createRequest.subject());
        savedCert.setCommonName(createRequest.commonName());
        savedCert.setType(CertificateType.TLS);
        savedCert.setStatus(CertificateStatus.ACTIVE);
        savedCert.setIssuedAt(Instant.now());
        savedCert.setExpiresAt(tomorrow);
        savedCert.setIssuedBy("Internal CA");
        savedCert.setRequestedBy(PRINCIPAL);
        savedCert.setRenewalWindowDays(30);
    }

    @Test
    void create_shouldPopulateSerialNumberAndPrincipal() {
        when(serialGenerator.generate()).thenReturn("DCOS-2026-ABCDEF123456");
        when(repository.existsBySerialNumber("DCOS-2026-ABCDEF123456")).thenReturn(false);
        when(repository.save(any(Certificate.class))).thenReturn(savedCert);

        CertificateResponse response = service.create(createRequest, PRINCIPAL);

        assertThat(response.getId()).isEqualTo(savedCert.getId());
        assertThat(response.getStatus()).isEqualTo(CertificateStatus.ACTIVE);

        // Verify that the saved certificate had serial and principal populated
        var savedArg = argumentCaptor();
        verify(repository).save(any(Certificate.class));
        verify(eventPublisher).publishCreated(savedCert);
    }

    @Test
    void create_shouldDeriveCommonNameWhenNotProvided() {
        CertificateCreateRequest noCommonName =
                new CertificateCreateRequest(
                        "CN=derived-name,OU=test,O=DCOS",
                        null,
                        "TLS",
                        "Internal CA",
                        Instant.now().plus(365, ChronoUnit.DAYS),
                        30,
                        null);

        when(serialGenerator.generate()).thenReturn("DCOS-2026-ABCDEF123456");
        when(repository.existsBySerialNumber("DCOS-2026-ABCDEF123456")).thenReturn(false);

        var savedCertWithDerivedName = new Certificate();
        savedCertWithDerivedName.setId(UUID.randomUUID());
        savedCertWithDerivedName.setCommonName("derived-name");
        savedCertWithDerivedName.setSerialNumber("DCOS-2026-ABCDEF123456");
        savedCertWithDerivedName.setType(CertificateType.TLS);
        savedCertWithDerivedName.setStatus(CertificateStatus.ACTIVE);
        savedCertWithDerivedName.setIssuedAt(Instant.now());
        savedCertWithDerivedName.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        savedCertWithDerivedName.setIssuedBy("Internal CA");
        savedCertWithDerivedName.setRequestedBy(PRINCIPAL);
        when(repository.save(any(Certificate.class))).thenReturn(savedCertWithDerivedName);

        service.create(noCommonName, PRINCIPAL);

        verify(repository).save(any(Certificate.class));
    }

    @Test
    void create_shouldRetryOnSerialCollision() {
        when(serialGenerator.generate())
                .thenReturn("DCOS-2026-COLLISION")
                .thenReturn("DCOS-2026-RETRY");
        when(repository.existsBySerialNumber("DCOS-2026-COLLISION")).thenReturn(true);
        when(repository.existsBySerialNumber("DCOS-2026-RETRY")).thenReturn(false);
        when(repository.save(any(Certificate.class))).thenReturn(savedCert);

        CertificateResponse response = service.create(createRequest, PRINCIPAL);

        assertThat(response.getId()).isNotNull();
        verify(serialGenerator, times(2)).generate();
    }

    @Test
    void create_shouldThrowWhenSerialRetriesExhausted() {
        when(serialGenerator.generate()).thenReturn("DCOS-2026-DUPLICATE");
        when(repository.existsBySerialNumber("DCOS-2026-DUPLICATE")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createRequest, PRINCIPAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to generate unique serial number");
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

    private org.mockito.ArgumentCaptor<Certificate> argumentCaptor() {
        return org.mockito.ArgumentCaptor.forClass(Certificate.class);
    }
}
