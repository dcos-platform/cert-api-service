package com.dcos.platform.certapi.service;

import static org.assertj.core.api.Assertions.*;

import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.domain.RevocationReason;
import com.dcos.platform.certapi.dto.CertificateCreateRequest;
import com.dcos.platform.certapi.dto.RevocationRequest;
import com.dcos.platform.certapi.repository.CertificateRepository;
import com.dcos.platform.certapi.support.RequiresTestDatabase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;

@RequiresTestDatabase
@SpringBootTest
class CertificateExpirySweepIntegrationTest {

    @Autowired private CertificateService service;

    @Autowired private CertificateRepository repository;

    @Autowired private CertificateExpirySweep sweep;

    @MockBean private com.dcos.platform.certapi.event.CertificateEventPublisher eventPublisher;

    private static final String PRINCIPAL = "sweep-test-admin";
    private static final String ISSUER = "Sweep Test Authority";

    @BeforeEach
    void cleanupTestData() {
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
    void cleanup() {
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

    @Test
    @WithMockUser(roles = "ADMIN")
    void sweep_movesActiveCertificatePastExpiryToExpired() {
        Instant now = Instant.now();
        Instant futureExpiry = now.plus(1, ChronoUnit.DAYS);

        var request =
                new CertificateCreateRequest(
                        "CN=sweep-test-" + UUID.randomUUID() + ",OU=test,O=DCOS",
                        "sweep-cert",
                        "TLS",
                        ISSUER,
                        futureExpiry,
                        30,
                        null);

        var created = service.create(request, PRINCIPAL);
        UUID certId = created.id();

        var cert = repository.findById(certId).get();
        Instant pastExpiry = now.minus(1, ChronoUnit.DAYS);
        Instant earlierIssueTime = now.minus(2, ChronoUnit.DAYS);
        cert.setIssuedAt(earlierIssueTime);
        cert.setExpiresAt(pastExpiry);
        repository.save(cert);

        var beforeSweep = repository.findById(certId).get();
        assertThat(beforeSweep.getStatus()).isEqualTo(CertificateStatus.ACTIVE);
        assertThat(beforeSweep.getExpiresAt()).isBefore(now);

        sweep.sweep();

        var afterSweep = repository.findById(certId).get();
        assertThat(afterSweep.getStatus()).isEqualTo(CertificateStatus.EXPIRED);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void sweep_leavesRevokedCertificatesUntouched() {
        Instant now = Instant.now();
        Instant futureExpiry = now.plus(1, ChronoUnit.DAYS);

        var request =
                new CertificateCreateRequest(
                        "CN=revoked-expired-" + UUID.randomUUID() + ",OU=test,O=DCOS",
                        "revoked-cert",
                        "TLS",
                        ISSUER,
                        futureExpiry,
                        30,
                        null);

        var created = service.create(request, PRINCIPAL);
        UUID certId = created.id();

        var cert = repository.findById(certId).get();
        Instant pastExpiry = now.minus(1, ChronoUnit.DAYS);
        Instant earlierIssueTime = now.minus(2, ChronoUnit.DAYS);
        cert.setIssuedAt(earlierIssueTime);
        cert.setExpiresAt(pastExpiry);
        repository.save(cert);

        RevocationRequest revocationRequest = new RevocationRequest();
        revocationRequest.setReason(RevocationReason.SUPERSEDED);
        service.revoke(certId, revocationRequest);

        var beforeSweep = repository.findById(certId).get();
        assertThat(beforeSweep.getStatus()).isEqualTo(CertificateStatus.REVOKED);

        sweep.sweep();

        var afterSweep = repository.findById(certId).get();
        assertThat(afterSweep.getStatus()).isEqualTo(CertificateStatus.REVOKED);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void sweep_leavesActiveCertificatesNotPastExpiryUntouched() {
        Instant now = Instant.now();
        Instant futureExpiry = now.plus(1, ChronoUnit.DAYS);

        var request =
                new CertificateCreateRequest(
                        "CN=active-future-" + UUID.randomUUID() + ",OU=test,O=DCOS",
                        "active-cert",
                        "TLS",
                        ISSUER,
                        futureExpiry,
                        30,
                        null);

        var created = service.create(request, PRINCIPAL);
        UUID certId = created.id();

        var beforeSweep = repository.findById(certId).get();
        assertThat(beforeSweep.getStatus()).isEqualTo(CertificateStatus.ACTIVE);
        assertThat(beforeSweep.getExpiresAt()).isAfter(now);

        sweep.sweep();

        var afterSweep = repository.findById(certId).get();
        assertThat(afterSweep.getStatus()).isEqualTo(CertificateStatus.ACTIVE);
    }
}
