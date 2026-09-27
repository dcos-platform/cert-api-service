package com.dcos.platform.certapi.service;

import static org.assertj.core.api.Assertions.*;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.dto.CertificateCreateRequest;
import com.dcos.platform.certapi.dto.CertificateResponse;
import com.dcos.platform.certapi.event.CertificateEventPublisher;
import com.dcos.platform.certapi.repository.CertificateRepository;
import com.dcos.platform.certapi.support.RequiresTestDatabase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * Integration tests for certificate creation against a real PostgreSQL database. These prove that
 * the generated serial number, derived common name, and requesting principal actually persist, and
 * that the partial unique index rejects duplicates as specified.
 */
@RequiresTestDatabase
@SpringBootTest
class CertificateCreationIntegrationTest {

    @Autowired private CertificateService service;

    @Autowired private CertificateRepository repository;

    @MockBean private CertificateEventPublisher eventPublisher;

    private static final String PRINCIPAL = "integration-test-admin";
    private static final String ISSUER = "Integration Test Authority";

    @BeforeEach
    void cleanupTestData() {
        // Remove all rows created by previous tests, keeping seed data (IDs 11111111-... to
        // 55555555-...)
        repository.findAll().stream()
                .filter(
                        cert ->
                                !cert.getId().toString().startsWith("11111111")
                                        && !cert.getId().toString().startsWith("22222222")
                                        && !cert.getId().toString().startsWith("33333333")
                                        && !cert.getId().toString().startsWith("44444444")
                                        && !cert.getId().toString().startsWith("55555555"))
                .forEach(cert -> repository.delete(cert));
    }

    @AfterEach
    void cleanupAfterTest() {
        // Remove all rows created by this test, keeping seed data
        repository.findAll().stream()
                .filter(
                        cert ->
                                !cert.getId().toString().startsWith("11111111")
                                        && !cert.getId().toString().startsWith("22222222")
                                        && !cert.getId().toString().startsWith("33333333")
                                        && !cert.getId().toString().startsWith("44444444")
                                        && !cert.getId().toString().startsWith("55555555"))
                .forEach(cert -> repository.delete(cert));
    }

    private String uniqueSubject() {
        return "CN=integration-test-" + UUID.randomUUID() + ",OU=test,O=DCOS";
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void creationPersistsGeneratedSerialNumber() {
        String subject = uniqueSubject();
        CertificateCreateRequest request =
                new CertificateCreateRequest(
                        subject,
                        "integration-test",
                        "TLS",
                        ISSUER,
                        Instant.now().plus(365, ChronoUnit.DAYS),
                        30,
                        null);

        CertificateResponse created = service.create(request, PRINCIPAL);

        Optional<Certificate> persisted = repository.findById(created.id());
        assertThat(persisted).isPresent();
        Certificate cert = persisted.get();

        // Verify serial number was generated and persisted
        assertThat(cert.getSerialNumber()).isNotNull().containsPattern("DCOS-\\d{4}-[0-9A-F]{12}");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void creationPersistsDerivedCommonName() {
        String subject = uniqueSubject();
        // Extract expected CN from subject (CN=<value>,OU=test,O=DCOS)
        String expectedCN = subject.substring(subject.indexOf("CN=") + 3, subject.indexOf(",OU="));

        CertificateCreateRequest request =
                new CertificateCreateRequest(
                        subject,
                        null, // No explicit common name; should be derived from subject
                        "TLS",
                        ISSUER,
                        Instant.now().plus(365, ChronoUnit.DAYS),
                        30,
                        null);

        CertificateResponse created = service.create(request, PRINCIPAL);

        Optional<Certificate> persisted = repository.findById(created.id());
        assertThat(persisted).isPresent();
        Certificate cert = persisted.get();

        // Verify common name was derived from subject and persisted
        assertThat(cert.getCommonName()).isEqualTo(expectedCN);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void creationPersistsRequestingPrincipal() {
        String subject = uniqueSubject();
        CertificateCreateRequest request =
                new CertificateCreateRequest(
                        subject,
                        "integration-test",
                        "TLS",
                        ISSUER,
                        Instant.now().plus(365, ChronoUnit.DAYS),
                        30,
                        null);

        CertificateResponse created = service.create(request, PRINCIPAL);

        Optional<Certificate> persisted = repository.findById(created.id());
        assertThat(persisted).isPresent();
        Certificate cert = persisted.get();

        // Verify principal was captured and persisted
        assertThat(cert.getRequestedBy()).isEqualTo(PRINCIPAL);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void duplicateActiveCertificateSameSubjectAndTypeIsRejected() {
        String subject = uniqueSubject();
        CertificateCreateRequest request =
                new CertificateCreateRequest(
                        subject,
                        "duplicate-test",
                        "TLS",
                        ISSUER,
                        Instant.now().plus(365, ChronoUnit.DAYS),
                        30,
                        null);

        // Create the first certificate (succeeds)
        service.create(request, PRINCIPAL);

        // Attempt to create a second with the same subject and type (fails)
        assertThatThrownBy(() -> service.create(request, PRINCIPAL))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void duplicatePermittedWhenFirstCertificateIsRevoked() {
        String subject = uniqueSubject();
        CertificateCreateRequest request =
                new CertificateCreateRequest(
                        subject,
                        "revoked-duplicate-test",
                        "CLIENT",
                        ISSUER,
                        Instant.now().plus(365, ChronoUnit.DAYS),
                        30,
                        null);

        // Create and revoke the first certificate
        CertificateResponse first = service.create(request, PRINCIPAL);
        service.revoke(first.id());

        // Creating a second with the same subject and type should succeed (first is revoked)
        CertificateResponse second = service.create(request, PRINCIPAL);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.status()).isEqualTo(CertificateStatus.ACTIVE);

        Optional<Certificate> firstCert = repository.findById(first.id());
        assertThat(firstCert)
                .isPresent()
                .get()
                .extracting(Certificate::getStatus)
                .isEqualTo(CertificateStatus.REVOKED);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void duplicatePermittedWhenSubjectMatchesButTypeDiffers() {
        String sharedSubject = uniqueSubject();

        CertificateCreateRequest tlsRequest =
                new CertificateCreateRequest(
                        sharedSubject,
                        "type-test",
                        "TLS",
                        ISSUER,
                        Instant.now().plus(365, ChronoUnit.DAYS),
                        30,
                        null);

        CertificateCreateRequest clientRequest =
                new CertificateCreateRequest(
                        sharedSubject,
                        "type-test",
                        "CLIENT",
                        ISSUER,
                        Instant.now().plus(365, ChronoUnit.DAYS),
                        30,
                        null);

        // Create TLS certificate
        CertificateResponse tls = service.create(tlsRequest, PRINCIPAL);

        // Creating CLIENT certificate with same subject should succeed (type differs)
        CertificateResponse client = service.create(clientRequest, PRINCIPAL);

        assertThat(tls.id()).isNotEqualTo(client.id());
        assertThat(tls.status()).isEqualTo(CertificateStatus.ACTIVE);
        assertThat(client.status()).isEqualTo(CertificateStatus.ACTIVE);
    }
}
